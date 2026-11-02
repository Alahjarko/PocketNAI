package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.*
import net.pocketnai.ui.gallery.GalleryMotionCard
import net.pocketnai.ui.gallery.GalleryMotionSlot
import net.pocketnai.ui.motion.QueuedGenerationIndicator
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

object QueuePreviewRenderer {
    fun render() {
        listOf("task-queue", "task-queue-large-text").forEach { name ->
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false,
                fontScale = if (name.endsWith("large-text")) 1.5f else 1f))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    renderer.snapshot(name) {
                        PocketNaiTheme(ThemeMode.LIGHT, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("NovelAI · 任务画廊", style = MaterialTheme.typography.titleLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TaskCard(GenerationStatus.GENERATING, Modifier.weight(1f))
                                        TaskCard(GenerationStatus.QUEUED, Modifier.weight(1f))
                                    }
                                    Text("等待接力", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        listOf(0f, .25f, .5f).forEach { phase ->
                                            QueuedGenerationIndicator(Modifier.size(72.dp), phaseOverride = phase)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }, Description.createTestDescription(QueuePreviewRenderer::class.java, name)).evaluate()
        }
    }

    @Composable
    private fun TaskCard(status: GenerationStatus, modifier: Modifier) {
        val job = Generation(status.name, 1, 1, status, "几何样本", "", GenerationParams.defaultsFor(ModelCatalog.defaultProfile()), 4)
        GalleryMotionCard(GalleryMotionSlot("slot-${status.name}-1", 1, null, job, true), null, null,
            false, false, {}, {}, {}, {}, modifier)
    }
}
