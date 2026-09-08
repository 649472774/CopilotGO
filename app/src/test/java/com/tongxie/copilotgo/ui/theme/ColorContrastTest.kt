package com.tongxie.copilotgo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {
    @Test fun staticLightAndDarkTextRolesReachFourPointFiveToOne() {
        for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val roles = listOf(
                Triple("primary", scheme.primary, scheme.onPrimary),
                Triple("primary container", scheme.primaryContainer, scheme.onPrimaryContainer),
                Triple("secondary container", scheme.secondaryContainer, scheme.onSecondaryContainer),
                Triple("surface", scheme.surface, scheme.onSurface),
                Triple("secondary text", scheme.surface, scheme.onSurfaceVariant),
                Triple("composer placeholder", scheme.surfaceContainer, scheme.onSurfaceVariant),
                Triple("composer text", scheme.surfaceContainer, scheme.onSurface),
                Triple("user bubble", scheme.secondaryContainer, scheme.onSecondaryContainer),
                Triple("surface container", scheme.surfaceContainerHigh, scheme.onSurface),
                Triple("error", scheme.errorContainer, scheme.onErrorContainer)
            )
            roles.forEach { (role, background, foreground) ->
                val ratio = contrast(background, foreground)
                assertTrue("$name $role contrast was $ratio", ratio >= 4.5)
            }
        }
    }

    @Test fun conversationSurfacesRemainNeutralAndControlsStayDistinct() {
        for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            listOf(
                scheme.background, scheme.surface, scheme.secondaryContainer,
                scheme.surfaceContainer, scheme.primary
            ).forEach { color ->
                val channels = listOf(color.red, color.green, color.blue)
                assertTrue("$name conversation chrome must remain neutral", channels.max() - channels.min() < 0.035f)
            }
            assertTrue("$name secondary icons contrast", contrast(scheme.surfaceContainer, scheme.onSurfaceVariant) >= 3.0)
            assertTrue("$name field outline contrast", contrast(scheme.surface, scheme.outline) >= 3.0)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val one = first.luminance().toDouble()
        val two = second.luminance().toDouble()
        return (maxOf(one, two) + 0.05) / (minOf(one, two) + 0.05)
    }
}
