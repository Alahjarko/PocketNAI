package net.pocketnai.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import net.pocketnai.R
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.theme.readableThemeColor
import net.pocketnai.ui.theme.LocalThemeReveal

@Composable
internal fun ColorModeCard(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("颜色模式", style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val largeText = LocalDensity.current.fontScale >= 1.3f && maxWidth < 400.dp
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (largeText) {
                        ModeChoice(ThemeMode.SYSTEM, mode, onSelect, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ModeChoice(ThemeMode.LIGHT, mode, onSelect, Modifier.weight(1f))
                            ModeChoice(ThemeMode.DARK, mode, onSelect, Modifier.weight(1f))
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeMode.entries.forEach { option ->
                            ModeChoice(option, mode, onSelect, Modifier.weight(if (option == ThemeMode.SYSTEM) 1.4f else 1f))
                        }
                    }
                }
            }
        }
    }
}

/** Motion is read by drawing/layers/color producers, never by text measurement. */
@Composable
private fun ModeChoice(option: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier) {
    val (label, icon) = when (option) {
        ThemeMode.SYSTEM -> R.string.theme_system to Icons.Outlined.BrightnessAuto
        ThemeMode.LIGHT -> R.string.theme_light to Icons.Outlined.LightMode
        ThemeMode.DARK -> R.string.theme_dark to Icons.Outlined.DarkMode
    }
    val selected = current == option
    val reveal = LocalThemeReveal.current
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        val target = if (selected) 1f else 0f
        if (progress.value != target) progress.animateTo(target, spring(dampingRatio = 1f, stiffness = 650f))
    }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressScale = animateFloatAsState(if (pressed) 0.975f else 1f,
        spring(dampingRatio = 1f, stiffness = 900f), label = "mode-press")
    val colors = MaterialTheme.colorScheme
    val restFill = colors.surfaceContainerHigh
    val activeFill = colors.primary
    val restInk = colors.onSurfaceVariant
    val activeInk = colors.onPrimary
    fun fill() = lerp(restFill, activeFill, progress.value)
    fun ink() = readableThemeColor(lerp(restInk, activeInk, progress.value), restInk, activeInk, fill())
    val painter = rememberVectorPainter(icon)
    Row(modifier
        .onGloballyPositioned { reveal?.register(option, it.boundsInRoot().center) }
        .graphicsLayer {
            scaleX = pressScale.value; scaleY = pressScale.value
            shape = RoundedCornerShape(12.dp + 12.dp * progress.value)
            clip = true
        }
        .drawBehind { drawRect(fill()) }
        .selectable(selected = selected, role = Role.RadioButton, interactionSource = interactions,
            indication = ripple(), onClick = {
                if (!selected || reveal?.hasPendingChange() == true) {
                    if (reveal == null) onSelect(option)
                    else reveal.select(option) { onSelect(option) }
                }
            })
        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        // Fixed icon slots prevent text shifting, including during quick reversals and presses.
        Canvas(Modifier.size(18.dp)) {
            with(painter) { draw(size, alpha = 0.65f + 0.35f * progress.value, colorFilter = ColorFilter.tint(ink())) }
        }
        BasicText(stringResource(label), style = MaterialTheme.typography.labelLarge,
            color = { ink() }, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
