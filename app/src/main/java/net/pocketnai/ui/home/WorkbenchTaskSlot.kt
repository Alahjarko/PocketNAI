package net.pocketnai.ui.home

import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary

internal data class WorkbenchTaskSlot(val generation: Generation?, val ordinal: Int) {
    val key = generation?.let { keyOf(it.id, ordinal) } ?: SUBMITTING
    val queued: Boolean get() = generation?.status == GenerationStatus.QUEUED

    companion object {
        const val SUBMITTING = "workbench-submitting"
        fun keyOf(generationId: String, ordinal: Int) = "task-$generationId-$ordinal"
    }
}

/** 任务不受历史筛选或分页限制。按实际已落盘的序号扣除，部分成功时序号可以有洞。 */
internal fun workbenchTaskSlots(
    summaries: List<GenerationSummary>,
    inFlight: Boolean,
    visibleImages: List<GalleryItem> = emptyList(),
): List<WorkbenchTaskSlot> {
    val active = summaries.filter { it.generation.isActive }
    if (active.isEmpty()) return if (inFlight) listOf(WorkbenchTaskSlot(null, 1)) else emptyList()
    val activeIds = active.mapTo(mutableSetOf()) { it.generation.id }
    val visible = visibleImages.asSequence().filter { it.generationId in activeIds }
        .groupBy({ it.generationId }, { it.ordinal })
    return active.sortedWith(compareBy<GenerationSummary>(
        { it.generation.status == GenerationStatus.QUEUED }, { it.generation.createdAt }, { it.generation.id },
    )).flatMap { (generation, _, saved) ->
        (1..generation.params.sampleCount).filter { it !in saved && it !in visible[generation.id].orEmpty() }.map { WorkbenchTaskSlot(generation, it) }
    }
}
