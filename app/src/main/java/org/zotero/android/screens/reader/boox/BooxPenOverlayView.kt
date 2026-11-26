package org.zotero.android.screens.reader.boox

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.pen.style.StrokeStyle
import org.zotero.android.screens.reader.data.ReaderAnnotationTool
import timber.log.Timber
import kotlin.math.hypot

/**
 * Transparent view stacked over the reader WebView on Onyx (Boox) devices.
 *
 * The Onyx [TouchHelper] captures the pen at the hardware level and paints the live stroke straight
 * onto the e-ink panel (low latency). The strokes are handed to the JS reader in one call
 * (`window.addInkStrokes`), which converts them to page coordinates and saves them as ink annotations.
 *
 * - ink tool: the SDK draws the live ink and strokes are collected while you write. Once the pen has
 *   been idle for a moment, all collected strokes are sent to the reader and the panel region is
 *   refreshed once. Nothing touches the e-ink driver while the pen is down or strokes are pending.
 * - any other tool (including the eraser, which has no live ink to draw): raw drawing is off and the
 *   pen reaches the WebView normally.
 */
class BooxPenOverlayView(
    context: Context,
    private val target: View,
) : View(context) {

    private data class Pt(val x: Float, val y: Float)

    /** A collected stroke: ink to add, or (pen eraser) ink to erase along. */
    private class Op(val erase: Boolean, val points: List<Pt>)

    private val main = Handler(Looper.getMainLooper())
    private var helper: TouchHelper? = null
    private var tool: ReaderAnnotationTool? = null
    private var rawEnabled = false

    private val pending = ArrayList<Op>()
    private var sending = false
    @Volatile private var penDown = false

    private var inkSize = 2f

    /** Reads the currently selected ink size. The size can change without the screen recomposing. */
    var inkSizeProvider: (() -> Float)? = null

    /** Reads the ink tool's color (hex), whichever tool is active right now. */
    var inkColorProvider: (() -> String?)? = null

    /** Reads the currently selected eraser size (PDF units). */
    var eraserSizeProvider: (() -> Float)? = null

    @Volatile private var lastPenActiveSync = 0L

    /** Last time the pen touched the screen (writing or erasing). The panel is only refreshed when it is quiet. */
    @Volatile private var lastPenTouch = 0L

    private val clearRunnable = Runnable { clearLiveInk() }

    private fun scheduleClear(delay: Long) {
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, delay)
    }

    /** Device pixels per PDF unit as reported by the reader (null until known). */
    private var readerScale: Float? = null
    /** Cells of the view (CELL_PX squares) that strokes passed through since the last refresh. */
    private val dirtyCells = HashSet<Long>()

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) {
            penDown = true
            lastPenTouch = SystemClock.uptimeMillis()
            main.post {
                main.removeCallbacks(flushRunnable)
                syncInkSize()
            }
        }

        // Fires for every pen sample, so only check for a size change a few times per second
        override fun onPenActive(p: TouchPoint) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPenActiveSync < PEN_ACTIVE_SYNC_MS) return
            lastPenActiveSync = now
            main.post { syncInkSize() }
        }

        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {}

        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {
            penDown = false
            lastPenTouch = SystemClock.uptimeMillis()
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            val pts = list.points.map { it.toPt() }
            Log.i(TAG, "stroke received: ${pts.size} raw points")
            main.post { if (rawEnabled && pts.isNotEmpty()) enqueueStroke(pts, erase = false) }
        }

        // The pen's eraser end (or side button): erase along the stroke, in the reader
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {
            penDown = true
            lastPenTouch = SystemClock.uptimeMillis()
            main.post { main.removeCallbacks(flushRunnable) }
        }

        override fun onEndRawErasing(b: Boolean, p: TouchPoint) {
            penDown = false
            lastPenTouch = SystemClock.uptimeMillis()
        }

        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            val pts = list.points.map { it.toPt() }
            Log.i(TAG, "eraser stroke received: ${pts.size} raw points")
            // Accepted even if the tool has already switched to the eraser: the stroke started on the raw layer
            main.post { if (pts.isNotEmpty()) enqueueStroke(pts, erase = true) }
        }
    }

    private fun TouchPoint.toPt() = Pt(x, y)

    // --- Spike-only tuning, read from <external files dir>/boox.properties (see BooxPenOverlayView docs) ---
    private fun cfg(): java.util.Properties {
        val props = java.util.Properties()
        runCatching {
            val f = java.io.File(context.getExternalFilesDir(null), "boox.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
        }
        return props
    }

    private fun styleFor(name: String?): Int = when (name?.lowercase()) {
        "pencil" -> TouchHelper.STROKE_STYLE_PENCIL
        "fountain" -> TouchHelper.STROKE_STYLE_FOUNTAIN
        "neo", "neobrush" -> TouchHelper.STROKE_STYLE_NEO_BRUSH
        "charcoal" -> TouchHelper.STROKE_STYLE_CHARCOAL
        "charcoal2" -> TouchHelper.STROKE_STYLE_CHARCOAL_V2
        "dash" -> TouchHelper.STROKE_STYLE_DASH
        "marker" -> TouchHelper.STROKE_STYLE_MARKER
        else -> TouchHelper.STROKE_STYLE_SQUARE_PEN
    }

    private val flushRunnable = Runnable { flushPending() }

    private val applyRectsRunnable = Runnable { applyRects() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                ensureHelper()
            }
            scheduleApplyRects()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BooxPenExclusions.listener = { scheduleApplyRects() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /** Called when the pen's eraser end comes near the screen (true) or the tip is back (false). */
    var onPenEraserNear: ((Boolean) -> Unit)? = null
    private var eraserNear = false

    /** True while the tool was switched to the eraser by the pen's eraser end (to be restored afterwards). */
    private var penSwitched = false

    /**
     * Spike experiment (`eraserraw` in the tuning file): how the raw layer is released for the pen's eraser
     * end. `brush` (default) only turns the hardware brush off, which flashes less; `enabled` turns raw drawing
     * off completely.
     */
    private fun eraserRawMode(): String = cfg().getProperty("eraserraw") ?: "brush"

    private fun setEraserNear(near: Boolean) {
        if (eraserNear == near) return
        eraserNear = near
        Log.i(TAG, "pen eraser near = $near")
        if (near) {
            // Hand the ink that was just written to the reader before the tool changes to the eraser
            main.removeCallbacks(flushRunnable)
            flushPending()
            if (tool == ReaderAnnotationTool.ink) {
                penSwitched = true
                switchReaderTool(eraser = true)
            }
        } else if (penSwitched) {
            penSwitched = false
            switchReaderTool(eraser = false)
        }
        onPenEraserNear?.invoke(near)
    }

    /**
     * Switches the reader (and the raw layer) right away, without waiting for the view model and a
     * recomposition: a quick flip of the pen leaves very little time. The view model makes the same switch
     * afterwards, so the toolbar follows.
     */
    private fun releaseRawForEraser() {
        val h = helper ?: return
        runCatching {
            if (eraserRawMode() == "brush") {
                h.setRawDrawingRenderEnabled(false)
                h.setBrushRawDrawingEnabled(false)
            } else {
                h.setRawDrawingEnabled(false)
            }
        }.onFailure { e -> Timber.e(e, "Unable to release the raw layer") }
        rawEnabled = false
    }

    private fun switchReaderTool(eraser: Boolean) {
        val webView = target as? WebView
        if (eraser) {
            releaseRawForEraser()
            val size = (eraserSizeProvider?.invoke() ?: 10f) * BooxUtils.ERASER_SIZE_MULTIPLIER
            webView?.evaluateJavascript("setTool({ type: 'eraser', color: 'null', size: '$size' });", null)
        } else {
            val color = inkColorProvider?.invoke()
            if (color != null) {
                webView?.evaluateJavascript("setTool({ type: 'ink', color: '$color', size: '$inkSize' });", null)
            }
            applyTool()
        }
    }

    /** Called when the pen's tip comes near the page (to select ink when no tool is selected). */
    var onPenTipNear: (() -> Unit)? = null
    private var lastHoverToolType = -1

    /** The pen reports which end it is before it touches, so the tool can be switched in time. */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val type = event.getToolType(0)
        when (type) {
            MotionEvent.TOOL_TYPE_ERASER -> setEraserNear(true)
            MotionEvent.TOOL_TYPE_STYLUS -> {
                setEraserNear(false)
                if (lastHoverToolType != MotionEvent.TOOL_TYPE_STYLUS) onPenTipNear?.invoke()
            }
        }
        lastHoverToolType = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else type
        return false
    }

    /** Pen touches are swallowed here while raw drawing is on, so the WebView does not see them twice. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastPenTouch = SystemClock.uptimeMillis()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            setEraserNear(false)
        }
        if (!rawEnabled) return false
        val type = event.getToolType(0)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    fun setTool(newTool: ReaderAnnotationTool?, newInkSize: Float) {
        val sizeChanged = inkSize != newInkSize
        inkSize = newInkSize
        if (tool == newTool) {
            if (sizeChanged) {
                refreshReaderScale()
                applyStrokeWidth()
            }
            return
        }
        tool = newTool
        if (newTool != ReaderAnnotationTool.ink && pending.isNotEmpty()) {
            main.removeCallbacks(flushRunnable)
            flushPending()
        }
        applyTool()
    }

    /**
     * Matches the reader's ink width: tool size (PDF units) times the on-screen scale of the page.
     * `width` in the tuning file overrides it, `widthfactor` scales it (the SDK's pen styles don't all
     * draw the nominal width).
     */
    private fun strokeWidthPx(): Float {
        val props = cfg()
        props.getProperty("width")?.toFloatOrNull()?.let { return it }
        val scale = readerScale ?: if (width > 0) width / 600f * 1.25f else 2.5f
        val factor = props.getProperty("widthfactor")?.toFloatOrNull() ?: 1f
        return (inkSize * scale * factor).coerceIn(2f, 40f)
    }

    /** Picks up a size change made in the tool options, which doesn't go through [setTool]. */
    private fun syncInkSize() {
        val size = inkSizeProvider?.invoke() ?: return
        if (size == inkSize || !rawEnabled) return
        inkSize = size
        refreshReaderScale()
        applyStrokeWidth()
    }

    /**
     * Spike diagnostic: `cleanup=count` in the tuning file logs how many ink annotations have an invalid
     * color (left behind by an earlier bug); `cleanup=delete` also deletes them.
     */
    private fun runCleanup() {
        val mode = cfg().getProperty("cleanup") ?: return
        val webView = target as? WebView ?: return
        val delete = mode == "delete"
        webView.evaluateJavascript(
            "(function(){try{return window._view._view.findBrokenInk($delete);}catch(e){return -1;}})();"
        ) { result -> Log.i(TAG, "cleanup($mode): invalid-colour ink annotations = $result") }
    }

    /** Asks the reader how big its ink is drawn at the current zoom, and applies it to the live stroke. */
    private fun refreshReaderScale() {
        val webView = target as? WebView ?: return
        webView.evaluateJavascript(
            "(function(){try{return window._view._view.getInkScale();}catch(e){return 0;}})();"
        ) { result ->
            val scale = result?.toFloatOrNull() ?: return@evaluateJavascript
            if (scale > 0f && scale != readerScale) {
                readerScale = scale
                Log.i(TAG, "reader ink scale = $scale px per unit, width now ${strokeWidthPx()}px")
                applyStrokeWidth()
            }
        }
    }

    private fun applyStrokeWidth() {
        runCatching {
            helper?.setStrokeWidth(strokeWidthPx())
            helper?.setStrokeStyle(styleFor(cfg().getProperty("style")))
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        pending.clear()
        sending = false
        if (BooxPenExclusions.listener != null) BooxPenExclusions.listener = null
        helper?.let {
            runCatching {
                it.setRawDrawingEnabled(false)
                it.closeRawDrawing()
            }.onFailure { e -> Timber.e(e, "Unable to close Onyx raw drawing") }
        }
        helper = null
        rawEnabled = false
    }

    // region Onyx helper

    private fun ensureHelper() {
        if (helper != null || width == 0 || height == 0) return
        try {
            val h = TouchHelper.create(this, callback)
            h.setStrokeWidth(strokeWidthPx())
                .setLimitRect(Rect(0, 0, width, height), currentExcludeRects())
                .openRawDrawing()
                        h.setStrokeColor(Color.BLACK)
            helper = h
            applyTool()
        } catch (e: Throwable) {
            Timber.e(e, "Unable to start Onyx raw drawing")
        }
    }

    private fun applyTool() {
        val h = helper ?: return
        val active = tool == ReaderAnnotationTool.ink
        try {
            if (!active && penSwitched && tool == ReaderAnnotationTool.eraser && eraserRawMode() == "brush") {
                // The raw session stays open, only the hardware brush is off (see releaseRawForEraser)
                rawEnabled = false
                return
            }
            if (active) {
                refreshReaderScale()
                runCleanup()
            }
            applyStrokeWidth()
            h.setRawDrawingRenderEnabled(true)
            // Lets the system draw an eraser track while the pen's eraser end is used (like Boox Notes);
            // the ink itself is erased by the reader afterwards.
            if (active && cfg().getProperty("erasertrack") != "off") {
                h.setEraserRawDrawingEnabled(true, StrokeStyle.SOFT_ERASER)
            }
            if (active) h.setBrushRawDrawingEnabled(true)
            h.setRawDrawingEnabled(active)
            rawEnabled = active
        } catch (e: Throwable) {
            Timber.e(e, "Unable to switch Onyx raw drawing")
        }
    }

    private fun currentExcludeRects(): List<Rect> {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val limit = Rect(0, 0, width, height)
        return BooxPenExclusions.rects()
            .map { Rect(it).apply { offset(-loc[0], -loc[1]) } }
            .filter { Rect.intersects(it, limit) }
    }

    private fun scheduleApplyRects() {
        main.removeCallbacks(applyRectsRunnable)
        main.postDelayed(applyRectsRunnable, 120)
    }

    private fun applyRects() {
        val h = helper ?: return
        if (width == 0 || height == 0) return
        try {
            h.setRawDrawingEnabled(false)
            h.setLimitRect(Rect(0, 0, width, height), currentExcludeRects())
            h.setRawDrawingEnabled(rawEnabled)
        } catch (e: Throwable) {
            Timber.e(e, "Unable to update Onyx limit rects")
        }
    }

    // endregion

    // region Handing strokes to the reader

    private fun enqueueStroke(points: List<Pt>, erase: Boolean) {
        val pts = if (erase) thinEraser(points) else thin(points)
        val scale = readerScale ?: 2.5f
        val pad = if (erase) {
            ((eraserSizeProvider?.invoke() ?: 10f) * eraserFactor() * scale / 2 + 8).toInt()
        } else {
            (strokeWidthPx() / 2 + 8).toInt()
        }
        markDirty(pts, pad.toFloat())
        pending.add(Op(erase, pts))
        main.removeCallbacks(flushRunnable)
        main.postDelayed(flushRunnable, cfg().getProperty("idle")?.toLongOrNull() ?: IDLE_FLUSH_MS)
    }

    private fun eraserFactor() = cfg().getProperty("eraserfactor")?.toFloatOrNull() ?: 1f

    /** Sends everything collected so far to the reader, unless the pen is down again (then waits a bit longer). */
    private fun flushPending() {
        if (penDown) {
            main.postDelayed(flushRunnable, 300)
            return
        }
        if (pending.isEmpty() || sending) return
        val webView = target as? WebView ?: return
        val ops = pending.toList()
        pending.clear()

        // Consecutive strokes of the same kind go in one call. Ink and erasing are applied in the order
        // they were made, each group only once the previous one has been handled.
        val groups = ArrayList<List<Op>>()
        for (op in ops) {
            if (groups.isEmpty() || groups.last().first().erase != op.erase) {
                groups.add(arrayListOf(op))
            } else {
                (groups.last() as ArrayList).add(op)
            }
        }
        Log.i(TAG, "sending ${ops.size} strokes in ${groups.size} groups (${ops.sumOf { it.points.size }} points) to the reader")
        sending = true

        fun sendGroup(index: Int) {
            if (index >= groups.size) {
                sending = false
                refreshReaderScale()
                scheduleClear(SETTLE_DELAY_MS)
                return
            }
            val group = groups[index]
            val json = group.joinToString(prefix = "[", postfix = "]", separator = ",") { op ->
                op.points.joinToString(prefix = "[", postfix = "]", separator = ",") { p ->
                    "%.1f,%.1f".format(java.util.Locale.US, p.x, p.y)
                }
            }
            val js = if (group.first().erase) {
                val size = (eraserSizeProvider?.invoke() ?: 10f) * eraserFactor()
                "window.eraseInkStrokes($json, ${"%.2f".format(java.util.Locale.US, size)});"
            } else {
                // The ink style is passed explicitly: the reader's active tool may already be the eraser
                val color = inkColorProvider?.invoke()
                val size = "%.2f".format(java.util.Locale.US, inkSize)
                if (color != null) "window.addInkStrokes($json, '$color', $size);" else "window.addInkStrokes($json);"
            }
            webView.evaluateJavascript(js) {
                // Give the reader time to register the change before the next group builds on it
                main.postDelayed({ sendGroup(index + 1) }, GROUP_GAP_MS)
            }
        }
        sendGroup(0)
    }

    /** The eraser only needs the points kept reasonably dense: the reader interpolates between them. */
    private fun thinEraser(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last == null || hypot(p.x - last.x, p.y - last.y) >= ERASER_POINT_DISTANCE_PX) out.add(p)
        }
        if (out.last() !== points.last()) out.add(points.last())
        Log.i(TAG, "eraser thinned ${points.size} -> ${out.size} points")
        return out
    }

    /** Drops points closer than [MIN_POINT_DISTANCE_PX] to the previous kept one (keeps the last). */
    private fun thin(points: List<Pt>): List<Pt> {
        val minDist = cfg().getProperty("mindist")?.toFloatOrNull() ?: MIN_POINT_DISTANCE_PX
        val out = ArrayList<Pt>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last == null || hypot(p.x - last.x, p.y - last.y) >= minDist) out.add(p)
        }
        if (out.last() !== points.last()) out.add(points.last())
        val epsilon = cfg().getProperty("epsilon")?.toFloatOrNull() ?: SIMPLIFY_EPSILON_PX
        val simplified = if (epsilon > 0f && out.size > 2) simplify(out, epsilon) else out
        Log.i(TAG, "thinned ${points.size} -> ${out.size} -> ${simplified.size} points (minDist=$minDist, epsilon=$epsilon)")
        return simplified
    }

    /** Douglas-Peucker line simplification: keeps the shape within [epsilon] px with far fewer points. */
    private fun simplify(pts: List<Pt>, epsilon: Float): List<Pt> {
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxDist = 0f
            var index = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(pts[i], pts[a], pts[b])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (index != -1 && maxDist > epsilon) {
                keep[index] = true
                stack.addLast(a to index)
                stack.addLast(index to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    // endregion

    private fun cellKey(cx: Int, cy: Int): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xffffffffL)

    /** Marks the cells within [pad] px of the stroke's path, so only what was written gets refreshed. */
    private fun markDirty(pts: List<Pt>, pad: Float) {
        fun markAround(x: Float, y: Float) {
            val x0 = Math.floorDiv((x - pad).toInt(), CELL_PX)
            val x1 = Math.floorDiv((x + pad).toInt(), CELL_PX)
            val y0 = Math.floorDiv((y - pad).toInt(), CELL_PX)
            val y1 = Math.floorDiv((y + pad).toInt(), CELL_PX)
            for (cy in y0..y1) for (cx in x0..x1) dirtyCells.add(cellKey(cx, cy))
        }
        for (i in pts.indices) {
            markAround(pts[i].x, pts[i].y)
            if (i + 1 < pts.size) {
                // Long straight segments have few points, so walk along them
                val dx = pts[i + 1].x - pts[i].x
                val dy = pts[i + 1].y - pts[i].y
                val steps = (hypot(dx, dy) / (CELL_PX / 2f)).toInt()
                for (k in 1..steps) {
                    val t = k / (steps + 1f)
                    markAround(pts[i].x + dx * t, pts[i].y + dy * t)
                }
            }
        }
    }

    /** Turns the dirty cells into a few rectangles (runs of neighbouring cells in a row) and clears them. */
    private fun takeDirtyRects(): List<Rect> {
        val rows = HashMap<Int, MutableList<Int>>()
        for (key in dirtyCells) {
            val cy = (key shr 32).toInt()
            val cx = key.toInt()
            rows.getOrPut(cy) { ArrayList() }.add(cx)
        }
        dirtyCells.clear()
        val bounds = Rect(0, 0, width, height)
        val rects = ArrayList<Rect>()
        for ((cy, columns) in rows) {
            columns.sort()
            var start = columns[0]
            var prev = start
            fun close() {
                val r = Rect(start * CELL_PX, cy * CELL_PX, (prev + 1) * CELL_PX, (cy + 1) * CELL_PX)
                if (r.intersect(bounds)) rects.add(r)
            }
            for (i in 1 until columns.size) {
                if (columns[i] != prev + 1) {
                    close()
                    start = columns[i]
                }
                prev = columns[i]
            }
            close()
        }
        // Many tiny refreshes are worse than one bigger one
        if (rects.size > MAX_REFRESH_REGIONS) {
            val union = Rect(rects[0])
            rects.forEach { union.union(it) }
            return listOf(union)
        }
        return rects
    }

    /**
     * The raw-drawing mode blocks normal panel refreshes, so the reader's committed ink is not shown
     * (and the SDK's temporary ink is not wiped) until raw drawing is paused and the region repainted.
     */
    private fun clearLiveInk() {
        val h = helper ?: return
        // Never touch the panel while the pen is down, strokes are waiting, or a replay is running
        if (sending || penDown || pending.isNotEmpty() || dirtyCells.isEmpty()) return
        // Wait until the pen is quiet: not erasing, not hovering with the eraser end, and off the screen for a moment
        if (eraserNear || SystemClock.uptimeMillis() - lastPenTouch < REFRESH_QUIET_MS) {
            scheduleClear(QUIET_RECHECK_MS)
            return
        }
        val mode = cfg().getProperty("refresh")?.lowercase()
        val updateMode = when (mode) {
            "gu" -> UpdateMode.GU
            "gufast" -> UpdateMode.GU_FAST
            "du4" -> UpdateMode.DU4
            "animation" -> UpdateMode.ANIMATION
            "hw" -> UpdateMode.HAND_WRITING_REPAINT_MODE
            "gc" -> UpdateMode.GC
            "regal" -> UpdateMode.REGAL
            else -> UpdateMode.DU
        }
        val regions = takeDirtyRects()
        if (regions.isEmpty()) return
        val api = cfg().getProperty("api") ?: "invalidate"
        try {
            Log.i(TAG, "refreshing ${regions.size} regions with $updateMode via $api: $regions")
            h.setRawDrawingEnabled(false)
            target.invalidate()
            main.postDelayed({
                if (mode != "none" && !penDown) {
                    for (r in regions) {
                        runCatching {
                            if (api == "region") {
                                EpdController.refreshScreenRegion(target, r.left, r.top, r.width(), r.height(), updateMode)
                            } else {
                                EpdController.invalidate(target, r.left, r.top, r.right, r.bottom, updateMode)
                            }
                        }.onFailure { e -> Timber.e(e, "Unable to refresh e-ink region") }
                    }
                }
                main.postDelayed({
                    helper?.setRawDrawingEnabled(rawEnabled)
                }, REENABLE_DELAY_MS)
            }, REPAINT_DELAY_MS)
        } catch (e: Throwable) {
            Timber.e(e, "Unable to clear Onyx live ink")
        }
    }

    // endregion

    private companion object {
        const val TAG = "BooxPen"
        const val REFRESH_QUIET_MS = 1200L
        const val QUIET_RECHECK_MS = 400L
        const val CELL_PX = 96
        const val MAX_REFRESH_REGIONS = 10
        const val SETTLE_DELAY_MS = 400L
        const val IDLE_FLUSH_MS = 1000L
        const val GROUP_GAP_MS = 150L
        const val PEN_ACTIVE_SYNC_MS = 300L
        const val ERASER_POINT_DISTANCE_PX = 4f
        const val REPAINT_DELAY_MS = 120L
        const val REENABLE_DELAY_MS = 150L
        const val MIN_POINT_DISTANCE_PX = 1f
        const val SIMPLIFY_EPSILON_PX = 0.3f
    }
}
