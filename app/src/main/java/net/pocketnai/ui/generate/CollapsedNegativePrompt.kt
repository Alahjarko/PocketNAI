package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.ui.common.AnimatedDisclosure
import net.pocketnai.ui.common.ExpandingChevron
import net.pocketnai.ui.common.MotionTextButton
import net.pocketnai.ui.motion.rememberPocketHaptics

@Composable
internal fun CollapsedNegativePrompt(label: String, hasContent: Boolean, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberPocketHaptics()
    Column {
        MotionTextButton(
            onClick = {
                haptics.tick()
                expanded = !expanded
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label + if (hasContent) " · 已填写" else "", modifier = Modifier.weight(1f))
            ExpandingChevron(expanded = expanded)
        }
        AnimatedDisclosure(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}
