package net.pocketnai.ui.generate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import net.pocketnai.domain.model.ReferenceImage

/** Keep a removed thumbnail only for its exit; it cannot receive actions or affect the draft. */
@Composable
internal fun AnimatedReferenceList(references: List<ReferenceImage>, content: @Composable (ReferenceImage) -> Unit) {
    val retained = remember { mutableStateListOf<ReferenceImage>().also { it.addAll(references) } }
    val initialIds = remember { references.map { it.id }.toSet() }
    LaunchedEffect(references) {
        references.forEach { next ->
            val index = retained.indexOfFirst { it.id == next.id }
            if (index < 0) retained.add(next) else if (retained[index] != next) retained[index] = next
        }
    }
    // New items must participate in this frame's layout, before the empty picker starts
    // shrinking. Waiting for the effect would briefly clamp the surrounding scroll range.
    val departing = retained.filter { old -> references.none { it.id == old.id } }
    (references + departing).forEach { old -> key(old.id) {
        val current = references.firstOrNull { it.id == old.id }
        val visibility = remember { MutableTransitionState(old.id in initialIds) }
        visibility.targetState = current != null
        LaunchedEffect(visibility.isIdle, visibility.currentState, current != null) {
            if (visibility.isIdle && !visibility.currentState && current == null) retained.removeAll { it.id == old.id }
        }
        val modifier = if (current != null) Modifier else Modifier.clearAndSetSemantics { }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } } }
        AnimatedVisibility(visibility, modifier,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(200))) { content(current ?: old) }
    } }
}
