package net.pocketnai.ui.connect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.openai.ImageProvider
import net.pocketnai.ui.common.MotionButton
import net.pocketnai.ui.common.MotionOutlinedButton

/** A finite first-install introduction. Actions work from the first frame. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeScreen(onConnect: (ImageProvider) -> Unit, onExplore: () -> Unit) {
    var providerName by rememberSaveable { mutableStateOf(ImageProvider.NOVEL_AI.name) }
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        played = true
        progress.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
    }
    Surface(Modifier.fillMaxSize().testTag("welcome")) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val markSize = if (maxHeight < 680.dp) 120.dp else 192.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                WelcomeMark(progress, Modifier.size(markSize))
                Spacer(Modifier.height(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = .3f + .7f * progress.value
                        translationY = (1f - progress.value) * 12.dp.toPx()
                    }) {
                    Text("PocketNAI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("把灵感，画出来。", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("从一句描述，或一张参考图开始", style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
                }
                Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ImageProvider.entries.forEachIndexed { index, option ->
                            SegmentedButton(selected = providerName == option.name,
                                onClick = { providerName = option.name },
                                shape = SegmentedButtonDefaults.itemShape(index, ImageProvider.entries.size)) {
                                Text(option.label)
                            }
                        }
                    }
                    MotionButton(onClick = { onConnect(ImageProvider.valueOf(providerName)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("连接绘画服务") }
                    MotionOutlinedButton(onClick = onExplore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("先看看") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The app's four-corner motif opens around a small canvas; no bitmap or endless clock. */
@Composable
private fun WelcomeMark(progress: Animatable<Float, *>, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val path = remember { Path() }
    Canvas(modifier) {
        val p = progress.value
        val unit = size.minDimension
        rotate(-9f * (1f - p)) {
            drawRoundRect(surface, Offset(unit * .21f, unit * .2f), Size(unit * .62f, unit * .68f),
                CornerRadius(unit * .1f))
            drawRoundRect(paper, Offset(unit * .17f, unit * (.16f + .04f * (1f - p))),
                Size(unit * .62f, unit * .68f), CornerRadius(unit * .1f))
        }
        val inset = unit * (.25f - .13f * p)
        val length = unit * .13f
        repeat(4) { corner ->
            rotate(corner * 90f) {
                drawLine(ink, Offset(inset, inset + length), Offset(inset, inset), unit * .022f, StrokeCap.Round)
                drawLine(ink, Offset(inset, inset), Offset(inset + length, inset), unit * .022f, StrokeCap.Round)
            }
        }
        val center = Offset(unit * .48f, unit * .49f)
        val radius = unit * (.08f + .035f * p)
        path.reset()
        path.moveTo(center.x, center.y - radius)
        path.quadraticTo(center.x + radius * .22f, center.y - radius * .22f, center.x + radius, center.y)
        path.quadraticTo(center.x + radius * .22f, center.y + radius * .22f, center.x, center.y + radius)
        path.quadraticTo(center.x - radius * .22f, center.y + radius * .22f, center.x - radius, center.y)
        path.quadraticTo(center.x - radius * .22f, center.y - radius * .22f, center.x, center.y - radius)
        path.close()
        drawPath(path, ink)
        drawLine(ink.copy(alpha = .4f), Offset(unit * .33f, unit * .7f), Offset(unit * .63f, unit * .7f),
            unit * .018f, StrokeCap.Round)
    }
}
