package org.zotero.android.screens.reader.boox

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

/** Helpers for Onyx (Boox) e-ink devices. Everything is a no-op on other devices. */
object BooxUtils {

    /** Eraser radius scale on e-ink, to compensate for the sparse pen samples the reader receives. */
    const val ERASER_SIZE_MULTIPLIER = 2.5f

    val isOnyxDevice: Boolean by lazy {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()
        listOf(m, b).any { it.contains("onyx") || it.contains("boox") }
    }

    fun init(application: Application) {
        if (!isOnyxDevice) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (e: Throwable) {
                Timber.e(e, "Unable to add hidden API exemptions")
            }
        }
    }
}
