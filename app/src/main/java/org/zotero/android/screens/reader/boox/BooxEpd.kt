package org.zotero.android.screens.reader.boox

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import timber.log.Timber

/** E-ink refresh-mode helpers. No-ops on non-Onyx devices. */
object BooxEpd {
    private var lastEnabled: Boolean? = null

    fun setHandwritingMode(view: View, enabled: Boolean) {
        if (!BooxUtils.isOnyxDevice || lastEnabled == enabled) return
        lastEnabled = enabled
        try {
            if (enabled) {
                EpdController.setViewDefaultUpdateMode(view, UpdateMode.HAND_WRITING_REPAINT_MODE)
            } else {
                EpdController.resetViewUpdateMode(view)
            }
        } catch (e: Throwable) {
            Timber.e(e, "Unable to set EPD update mode")
        }
    }
}
