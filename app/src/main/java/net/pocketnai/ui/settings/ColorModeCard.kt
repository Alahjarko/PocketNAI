package net.pocketnai.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.model.ThemeAccent
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.theme.LocalThemeReveal
import net.pocketnai.ui.theme.accentSwatchColors
import net.pocketnai.ui.theme.readableThemeColor

@Composable
internal fun ColorModeCard(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    accent: ThemeAccent = ThemeAccent.DEFAULT,
    onSelectAccent: ((ThemeAccent) -> Unit)? = null,
) {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveAccent = if (!supportsDynamic && accent == ThemeAccent.DYNAMIC) ThemeAccent.INDIGO else accent
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("颜色模式", style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val largeText = LocalDensity.current.fontScale >= 1.3f && maxWidth < 400.dp
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (largeText) {
                        ModeChoice(ThemeMode.SYSTEM, mode, onSelect, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ModeChoice(ThemeMode.LIGHT, mode, onSelect, Modifier.weight(1f))
                            ModeChoice(ThemeMode.DARK, mode, onSelect, Modifier.weight(1f))
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { option ->
                            ModeChoice(option, mode, onSelect, Modifier.weight(if (option == ThemeMode.SYSTEM) 1.4f else 1f))
                        }
                    }
                }
            }
            if (onSelectAccent != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("主题色", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = effectiveAccent.displayLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val options = remember(supportsDynamic) {
                    if (supportsDynamic) ThemeAccent.entries else ThemeAccent.entries.filter { it != ThemeAccent.DYNAMIC }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(options, key = { it.name }) { option ->
                        AccentSwatch(
                            option = option,
                            selected = effectiveAccent == option,
                            darkTheme = darkTheme,
                            onSelect = { onSelectAccent(option) },
                        )
                    }
                }
            }
        }
    }
}

private fun ThemeAccent.displayLabel(): String = when (this) {
    ThemeAccent.DYNAMIC -> "壁纸取色"
    ThemeAccent.INDIGO -> "靛蓝"
    ThemeAccent.ROSE -> "樱粉"
    ThemeAccent.SAGE -> "鼠尾草"
    ThemeAccent.AMBER -> "暖琥珀"
    ThemeAccent.OCEAN -> "雾蓝"
    ThemeAccent.GRAPHITE -> "石墨"
}

@Composable
private fun AccentSwatch(
    option: ThemeAccent,
    selected: Boolean,
    darkTheme: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val reveal = LocalThemeReveal.current
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "accent-press",
    )
    val (primary, container) = remember(option, context) {
        if (option == ThemeAccent.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dynamic = dynamicLightColorScheme(context)
            dynamic.primary to dynamic.primaryContainer
        } else {
            accentSwatchColors(option)
        }
    }
    val ringColor = MaterialTheme.colorScheme.primary
    val label = option.displayLabel()
    Box(
        modifier = Modifier
            .size(44.dp)
            .onGloballyPositioned { reveal?.register(option, it.boundsInRoot().center) }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (selected) Modifier.border(2.dp, ringColor, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape),
            )
            .padding(3.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "主题色 $label" }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = interactions,
                indication = ripple(),
                onClick = {
                    if (!selected || reveal?.hasPendingChange() == true) {
                        haptics.tick()
                        if (reveal == null) onSelect()
                        else reveal.selectPalette(option, darkTheme to option, onSelect)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(color = primary, topLeft = Offset.Zero, size = Size(size.width, size.height * 0.56f))
            drawRect(color = container, topLeft = Offset(0f, size.height * 0.56f), size = Size(size.width, size.height * 0.44f))
        }
        if (option == ThemeAccent.DYNAMIC && !selected) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(120)) + scaleIn(
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f),
                initialScale = 0.65f,
            ),
            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.75f),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(14.dp),
                    )
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
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
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
    val restFill = colors.surfaceContainerHighest
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
                    haptics.tick()
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
