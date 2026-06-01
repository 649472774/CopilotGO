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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 * - remember(latex, color, sizeSp) 缓存 Bitmap, 避免每帧 parse + 渲染.
 * - 用 AndroidView+ImageView 承载 Bitmap, recomposition 廉价.
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

    val bitmap = remember(latex, textColor, sizeSp) {
        runCatching {
            val sizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sizeSp,
                ctx.resources.displayMetrics
            )
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
        // 解析失败时显示原文，让用户看到出问题的 LaTeX，方便排查
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
