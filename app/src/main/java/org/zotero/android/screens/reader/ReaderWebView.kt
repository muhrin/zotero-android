package org.zotero.android.screens.reader

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import org.zotero.android.R
import org.zotero.android.architecture.ui.CustomLayoutSize
import org.zotero.android.screens.reader.boox.BooxEpd
import org.zotero.android.screens.reader.boox.BooxPenLayout
import org.zotero.android.screens.reader.boox.BooxUtils
import org.zotero.android.screens.reader.data.ReaderAnnotationTool
import org.zotero.android.screens.reader.web.ReaderCustomWebView

@Composable
fun ReaderWebView(viewModel: ReaderViewModel, activeTool: ReaderAnnotationTool? = null) {
    val layoutType = CustomLayoutSize.calculateLayoutType()
    val isTablet = layoutType.isTablet()
    val textFont = MaterialTheme.typography.bodyMedium
    AndroidView(
        factory = { context ->
            val webView = ReaderCustomWebView(
                ContextThemeWrapper(context, R.style.ReaderWebViewThemeOverlay)
            )
            webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            webView.onUserGestureDetected = { viewModel.onDocumentUserGestureDetected() }
            viewModel.initOnce(isTablet = isTablet, textFont = textFont)
            viewModel.initEveryTime(webView = webView)
            if (BooxUtils.isOnyxDevice) {
                // Low-latency pen overlay on top of the WebView
                BooxPenLayout(context, webView).apply {
                    layoutParams = webView.layoutParams
                    setInkSizeProvider { viewModel.activeLineWidth }
                    setInkColorProvider { viewModel.inkColorHex() }
                    setEraserSizeProvider { viewModel.activeEraserSize }
                    setPenEraserListener { near -> viewModel.onPenEraserNear(near) }
                    setPenTipListener { viewModel.onPenTipNear() }
                }
            } else {
                webView
            }
        },
        update = { view ->
            if (view is BooxPenLayout) {
                view.setTool(activeTool, viewModel.activeLineWidth)
                BooxEpd.setHandwritingMode(view.webView, enabled = activeTool != null)
            }
        },
        onRelease = { view ->
            if (view is BooxPenLayout) {
                view.release()
                (view.webView as? ReaderCustomWebView)?.destroy()
            } else {
                (view as? ReaderCustomWebView)?.destroy()
            }
        }
    )
}