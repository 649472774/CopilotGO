package com.tongxie.copilotgo.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/** Only called by controlled fixture screens on the integration-owned acceptance devices. */
internal fun saveNativeScreenshotEvidence(
    activity: Activity,
    directory: File,
    name: String,
    composeCapture: () -> Bitmap
) {
    val metadata = JSONObject().put("beforePhysical", windowEvidence(activity))
    // Record the physical frame before any Compose node capture can affect the fixture.
    val deviceBitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ?: throw AssertionError("Could not capture the physical device for $name")
    metadata.put("afterPhysical", windowEvidence(activity))
        .put("physicalWidth", deviceBitmap.width)
        .put("physicalHeight", deviceBitmap.height)
    saveOwnedScreenshot(deviceBitmap, File(directory, "$name-device.png"))
    val file = File(directory, "$name-device.json")
    file.writeText(metadata.toString(2))
    val composeBitmap = composeCapture()
    metadata.put("afterComposeCapture", windowEvidence(activity))
        .put("composeWidth", composeBitmap.width)
        .put("composeHeight", composeBitmap.height)
    saveOwnedScreenshot(composeBitmap, File(directory, "$name.png"))
    file.writeText(metadata.toString(2))
}

private fun windowEvidence(activity: Activity): JSONObject {
    var result: JSONObject? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val decor = activity.window.decorView
        val config = activity.resources.configuration
        val insets = ViewCompat.getRootWindowInsets(decor)
        val visibleFrame = Rect()
        decor.getWindowVisibleDisplayFrame(visibleFrame)
        val composeViews = JSONArray()
        fun collect(view: View, depth: Int) {
            if (view.javaClass.name.contains("Compose")) composeViews.put(viewEvidence(view))
            if (depth < 12 && view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index), depth + 1)
            }
        }
        collect(decor, 0)
        result = JSONObject()
            .put("uptimeMillis", SystemClock.uptimeMillis())
            .put("activityClass", activity.javaClass.name)
            .put("activityIdentity", System.identityHashCode(activity))
            .put("requestedOrientation", activity.requestedOrientation)
            .put("rotation", activity.display?.rotation)
            .put("orientation", config.orientation)
            .put("screenWidthDp", config.screenWidthDp)
            .put("screenHeightDp", config.screenHeightDp)
            .put("densityDpi", config.densityDpi)
            .put("fontScale", config.fontScale)
            .put("windowBounds", activity.windowManager.currentWindowMetrics.bounds.toShortString())
            .put("windowVisibleFrame", visibleFrame.toShortString())
            .put("softInputMode", activity.window.attributes.softInputMode)
            .put("decorSoftInputMode", (decor.layoutParams as? WindowManager.LayoutParams)?.softInputMode)
            .put("windowFlags", activity.window.attributes.flags)
            .put("imeVisible", insets?.isVisible(WindowInsetsCompat.Type.ime()))
            .put("imeInsets", insets?.getInsets(WindowInsetsCompat.Type.ime())?.toString())
            .put("systemInsets", insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.toString())
            .put("decor", viewEvidence(decor))
            .put("content", activity.findViewById<View>(android.R.id.content)?.let(::viewEvidence))
            .put("focusedView", decor.findFocus()?.let(::viewEvidence))
            .put("composeViews", composeViews)
    }
    return requireNotNull(result)
}

private fun viewEvidence(view: View): JSONObject {
    val screen = IntArray(2)
    val window = IntArray(2)
    val visible = Rect()
    view.getLocationOnScreen(screen)
    view.getLocationInWindow(window)
    val globallyVisible = view.getGlobalVisibleRect(visible)
    return JSONObject()
        .put("class", view.javaClass.name)
        .put("width", view.width)
        .put("height", view.height)
        .put("screenX", screen[0]).put("screenY", screen[1])
        .put("windowX", window[0]).put("windowY", window[1])
        .put("scrollX", view.scrollX).put("scrollY", view.scrollY)
        .put("translationX", view.translationX).put("translationY", view.translationY)
        .put("shown", view.isShown)
        .put("hasWindowFocus", view.hasWindowFocus())
        .put("globallyVisible", globallyVisible)
        .put("visibleBounds", visible.toShortString())
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
