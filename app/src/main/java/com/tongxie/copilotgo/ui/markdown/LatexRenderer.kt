package com.tongxie.copilotgo.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.JMathTeXException
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as TexColor
import ru.noties.jlatexmath.awt.Insets as TexInsets

internal data class LatexRequest(
    val source: String,
    val display: Boolean,
    val color: Int,
    val sizePx: Float,
    val density: Float,
    val fontScale: Float
)

internal sealed interface LatexRender {
    data class Image(val bitmap: ImageBitmap, val width: Int, val height: Int) : LatexRender
    data class Source(val reason: LatexFallback) : LatexRender
}

private data class LatexSnapshot(val request: LatexRequest, val render: LatexRender)

@Composable
internal fun rememberLatexRender(request: LatexRequest): LatexRender? {
    // Keep the old formula's pixels out of a newly edited formula while its effect restarts.
    val key = remember(request) { request }
    val snapshot by produceState<LatexSnapshot?>(null, key) {
        value = null
        var lease: LatexRenderer.Lease? = null
        try {
            withContext(Dispatchers.Default) {
                // Assign inside withContext: cancellation on dispatcher return must not leak a lease.
                lease = LatexRenderer.acquire(key)
            }
            value = LatexSnapshot(key, checkNotNull(lease).render)
            awaitCancellation()
        } finally {
            lease?.close()
        }
    }
    return snapshot?.takeIf { it.request == key }?.render
}

/**
 * TeX's shared registries are protected by one render gate. The small pool lock never encloses
 * parsing or painting, so releasing a composition does not wait for TeX work on the main thread.
 * Active images count against the same budget as cached images. Eviction never recycles bitmaps:
 * Compose/the render thread may still be using them after a composition releases its lease.
 */
private object LatexRenderer {
    private val renderGate = Mutex()
    private val poolLock = Any()
    private val images = LinkedHashMap<LatexRequest, Entry>(16, 0.75f, true)
    private var retainedPixels = 0L

    private class Entry(val image: LatexRender.Image, var readers: Int = 1) {
        val pixels = image.width.toLong() * image.height
    }

    class Lease(val render: LatexRender, private val release: (() -> Unit)? = null) {
        fun close() = release?.invoke()
    }

    suspend fun acquire(request: LatexRequest): Lease = renderGate.withLock {
        currentCoroutineContext().ensureActive()
        synchronized(poolLock) {
            images[request]?.let { entry ->
                entry.readers++
                return@withLock lease(request, entry)
            }
        }
        LatexPolicy.check(request.source)?.let {
            return@withLock Lease(LatexRender.Source(it))
        }
        if (!LatexPolicy.acceptsScale(request.sizePx, request.density, request.fontScale)) {
            return@withLock Lease(LatexRender.Source(LatexFallback.PixelLimit))
        }
        var reservation = 0L
        try {
            val style = if (request.display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
            val icon = TeXFormula(request.source).createTeXIcon(style, request.sizePx)
            val inset = (2f * request.density).toInt().coerceIn(1, 16)
            icon.insets = TexInsets(inset, inset, inset, inset)
            icon.setForeground(TexColor(request.color))
            val width = icon.iconWidth
            val height = icon.iconHeight
            if (!LatexPolicy.acceptsDimensions(width, height)) {
                return@withLock Lease(LatexRender.Source(LatexFallback.PixelLimit))
            }
            currentCoroutineContext().ensureActive()
            val pixels = width.toLong() * height
            if (!reserve(pixels)) return@withLock Lease(LatexRender.Source(LatexFallback.Capacity))
            reservation = pixels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val graphics = AndroidGraphics2D().apply { setCanvas(Canvas(bitmap)) }
            icon.paintIcon(null, graphics, 0, 0)
            currentCoroutineContext().ensureActive()
            val entry = Entry(LatexRender.Image(bitmap.asImageBitmap(), width, height))
            synchronized(poolLock) { images[request] = entry }
            reservation = 0
            lease(request, entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: JMathTeXException) {
            failedLayout(failure)
        } catch (failure: IllegalArgumentException) {
            failedLayout(failure)
        } catch (failure: IndexOutOfBoundsException) {
            failedLayout(failure)
        } catch (failure: ArithmeticException) {
            failedLayout(failure)
        } finally {
            if (reservation != 0L) synchronized(poolLock) { retainedPixels -= reservation }
        }
    }

    private fun failedLayout(failure: Exception): Lease {
        // Library messages may contain private formula text.
        Log.w("LatexRenderer", "Formula layout failed (${failure.javaClass.simpleName})")
        return Lease(LatexRender.Source(LatexFallback.RenderFailed))
    }

    private fun reserve(pixels: Long): Boolean = synchronized(poolLock) {
        val iterator = images.entries.iterator()
        while (iterator.hasNext() &&
            (retainedPixels + pixels > LatexPolicy.RETAINED_PIXELS ||
                images.size >= LatexPolicy.CACHE_ENTRIES)
        ) {
            val entry = iterator.next().value
            if (entry.readers == 0) {
                retainedPixels -= entry.pixels
                iterator.remove()
            }
        }
        if (retainedPixels + pixels > LatexPolicy.RETAINED_PIXELS ||
            images.size >= LatexPolicy.CACHE_ENTRIES
        ) return@synchronized false
        retainedPixels += pixels
        true
    }

    private fun lease(request: LatexRequest, entry: Entry): Lease = Lease(entry.image) {
        synchronized(poolLock) {
            if (images[request] === entry) entry.readers--
        }
    }
}
