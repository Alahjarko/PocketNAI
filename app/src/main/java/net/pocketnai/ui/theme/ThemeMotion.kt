package net.pocketnai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** Keep text/icons readable while light and dark surfaces pass through their middle values. */
internal fun readableThemeColor(candidate: Color, start: Color, end: Color, background: Color): Color {
    fun contrast(ink: Color): Float {
        val a = ink.luminance(); val b = background.luminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }
    if (contrast(candidate) >= 4.5f) return candidate
    val endpoint = if (contrast(start) > contrast(end)) start else end
    if (contrast(endpoint) >= 4.5f) return endpoint
    return if (background.luminance() > 0.179f) Color.Black else Color.White
}
