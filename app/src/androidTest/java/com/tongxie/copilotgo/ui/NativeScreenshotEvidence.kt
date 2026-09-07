package com.tongxie.copilotgo.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue

/** Only called by controlled fixture screens on the integration-owned acceptance devices. */
internal fun saveNativeScreenshotEvidence(composeBitmap: Bitmap, directory: File, name: String) {
    saveOwnedScreenshot(composeBitmap, File(directory, "$name.png"))
    val deviceBitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ?: throw AssertionError("Could not capture the physical device for $name")
    saveOwnedScreenshot(deviceBitmap, File(directory, "$name-device.png"))
}

private fun saveOwnedScreenshot(bitmap: Bitmap, file: File) {
    try {
        assertTrue("Empty screenshot: ${file.name}", bitmap.width > 0 && bitmap.height > 0)
        file.outputStream().use { output ->
            assertTrue("Could not encode screenshot: ${file.name}", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    } finally {
        bitmap.recycle()
    }
}
