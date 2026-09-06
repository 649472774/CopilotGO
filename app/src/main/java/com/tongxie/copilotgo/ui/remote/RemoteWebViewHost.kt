package com.tongxie.copilotgo.ui.remote

import android.content.Context
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

internal fun remoteImeOverlap(rootHeight: Int, hostBottom: Int, imeBottom: Int): Int =
    if (rootHeight <= 0 || imeBottom <= 0) 0
    else (hostBottom - (rootHeight - imeBottom)).coerceAtLeast(0)

/**
 * The native listener receives the target insets, not every animation frame.
 * Resize Chromium once, without JS viewport/scroll overrides or a second IME inset.
 */
internal class RemoteWebViewHost(context: Context) : FrameLayout(context) {
    private var imeBottom = 0
    private val position = IntArray(2)
    private val rootPosition = IntArray(2)

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            imeBottom = ViewCompat.getRootWindowInsets(this)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
                ?: insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyImeInset()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            this,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat = insets
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyImeInset()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyImeInset()
    }

    private fun applyImeInset() {
        getLocationInWindow(position)
        rootView.getLocationInWindow(rootPosition)
        val bottom = position[1] - rootPosition[1] + height
        val inset = remoteImeOverlap(rootView.height, bottom, imeBottom).coerceAtMost(height)
        if (paddingBottom != inset) setPadding(0, 0, 0, inset)
    }
}
