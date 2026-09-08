package com.tongxie.copilotgo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * THESIS: Reading and composing lead, not a stack of labeled cards.
 * OWN-WORLD: Neutral canvas, soft user bubbles, borderless answers, quiet controls.
 * STORY: Find a conversation, read it, compose, and inspect actual tools when needed.
 * FIRST VIEWPORT: One title/model header, generous reading area, one rounded composer.
 * FORM: The user-pinned familiar native conversation canon, with Android semantics.
 */
object AppLayout {
    val ReadingWidth = 760.dp
    val PageWidth = 840.dp
    val UserBubbleWidth = 600.dp
    val PageGutter = 20.dp
    val ComposerGutter = 12.dp
    val ControlSize = 48.dp
    val ComposerShape = RoundedCornerShape(28.dp)
    val UserBubbleShape = RoundedCornerShape(20.dp)
}

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
