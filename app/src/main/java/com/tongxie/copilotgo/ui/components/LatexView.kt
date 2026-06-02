package com.tongxie.copilotgo.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.widget.ImageView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as TexColor
import ru.noties.jlatexmath.awt.Insets as TexInsets

/**
 * 用 JLatexMath（org.scilab.forge）+ jlatexmath-android shim 渲染 LaTeX 公式。
 *
 * 设计要点：
 * - 走 TeXFormula → TeXIcon → AndroidGraphics2D（Canvas 桥接），纯 Canvas/Bitmap,
 *   没有 TextView/WebView/AppCompat 依赖, 不会触发 v0.1.5 那种 AndroidView+Markwon ANR.
 * - ext-latex 4.6.2 自身不暴露独立 Drawable，所以直接用底层 jlatexmath-android.
 * - 解析+栅格化在 [Dispatchers.Default]（Bug 10 修复），完成后 Compose 把 Bitmap 切回 Main 渲染。
 *   旧版本在 `remember { ... }` 里同步跑，复杂公式 30-200 ms 主线程卡顿（流式过程中反复 cold-cache 解析尤甚）。
 * - 异常时降级显示原始 LaTeX 文本 + 灰色提示, 不崩.
 *
 * @param latex LaTeX 源（不含 $...$ 或 $$...$$ 包裹）
 * @param display 是否块级（display style 公式大一号）
 */
@Composable
fun LatexFormula(
    latex: String,
    display: Boolean = true,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val sizeSp = if (display) 18f else 15f
    val sizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sizeSp,
        ctx.resources.displayMetrics
    )

    val bitmap by produceState<Bitmap?>(initialValue = null, latex, textColor, sizeSp) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val style = if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
                val icon = TeXFormula(latex).createTeXIcon(style, sizePx)
                icon.insets = TexInsets(4, 4, 4, 4)
                icon.setForeground(TexColor(textColor))
                val w = icon.iconWidth.coerceAtLeast(1)
                val h = icon.iconHeight.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val g2d = AndroidGraphics2D()
                g2d.setCanvas(canvas)
                icon.paintIcon(null, g2d, 0, 0)
                bmp
            }.getOrNull()
        }
    }

    // 离开组合或公式变化时回收旧 bitmap，避免无限堆积。
    DisposableEffect(bitmap) {
        val b = bitmap
        onDispose {
            if (b != null && !b.isRecycled) {
                runCatching { b.recycle() }
            }
        }
    }

    if (bitmap != null) {
        AndroidView(
            factory = { c ->
                ImageView(c).apply {
                    adjustViewBounds = true
                    setPadding(0, 8, 0, 8)
                }
            },
            update = { iv -> iv.setImageBitmap(bitmap) },
            modifier = modifier.horizontalScroll(rememberScrollState())
        )
    } else {
        // 解析失败或尚未完成时显示原文，让用户先看到内容
        Text(
            text = if (display) "$$$latex$$" else "\$$latex\$",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontStyle = FontStyle.Italic
            ),
            modifier = modifier.padding(vertical = 2.dp)
        )
    }
}
