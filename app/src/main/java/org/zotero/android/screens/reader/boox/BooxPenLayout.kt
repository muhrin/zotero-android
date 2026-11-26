package org.zotero.android.screens.reader.boox

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.zotero.android.screens.reader.data.ReaderAnnotationTool

/** Hosts the reader WebView together with the Onyx low-latency pen overlay. */
class BooxPenLayout(context: Context, val webView: View) : FrameLayout(context) {
    private val overlay = BooxPenOverlayView(context, webView)

    init {
        addView(
            webView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        addView(
            overlay,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    fun setInkSizeProvider(provider: () -> Float) {
        overlay.inkSizeProvider = provider
    }

    fun setEraserSizeProvider(provider: () -> Float) {
        overlay.eraserSizeProvider = provider
    }

    fun setPenEraserListener(listener: (Boolean) -> Unit) {
        overlay.onPenEraserNear = listener
    }

    fun setInkColorProvider(provider: () -> String?) {
        overlay.inkColorProvider = provider
    }

    fun setPenTipListener(listener: () -> Unit) {
        overlay.onPenTipNear = listener
    }

    fun setTool(tool: ReaderAnnotationTool?, inkSize: Float) = overlay.setTool(tool, inkSize)

    fun release() = overlay.release()
}
