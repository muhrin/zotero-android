package org.zotero.android.screens.reader.boox

import android.graphics.Rect

/**
 * Window-coordinate rectangles of UI floating over the reader (currently the annotation toolbar).
 * The Onyx raw-drawing layer works on the hardware level, so pen strokes that start on such UI would
 * otherwise be captured as ink; these areas are excluded from it.
 */
object BooxPenExclusions {
    private var toolbar: Rect? = null
    var listener: (() -> Unit)? = null

    fun setToolbar(rect: Rect?) {
        if (toolbar == rect) return
        toolbar = rect
        listener?.invoke()
    }

    fun rects(): List<Rect> = listOfNotNull(toolbar)
}
