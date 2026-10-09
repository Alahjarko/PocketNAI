package net.pocketnai.ui.gallery

import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GalleryTimeline
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary
import java.time.Instant
import java.time.ZoneId

internal data class GalleryMotionSlot(
    val key: String,
    val createdAt: Long,
    val image: GalleryItem?,
    val generation: Generation?,
    val reveal: Boolean,
) {
    val aspectRatio: Float get() = image?.aspectRatio ?: generation!!.params.targetSize.let {
        it.width.toFloat() / it.height
    }
    val failed: Boolean get() = image == null && generation?.status in
        setOf(GenerationStatus.FAILED, GenerationStatus.PARTIAL)
}

internal data class GalleryMotionSection(val epochDay: Long, val label: String, val slots: List<GalleryMotionSlot>)

/** Only jobs seen running animate. Historical images never replay a completion animation. */
internal class GalleryMotionSlots {
    private val watched = linkedMapOf<String, Generation>()
    private val delivered = mutableMapOf<String, MutableSet<Int>>()
    private val revealed = linkedSetOf<String>()
    private val dismissed = linkedSetOf<String>()
    private var loaded = false

    fun finish(key: String) { revealed += key; trim(revealed, 512) }
    fun dismiss(id: String) { dismissed += id; watched.remove(id); trim(dismissed, 128) }

    fun sections(
        images: List<GalleryItem>,
        summaries: List<GenerationSummary>,
        showPending: Boolean,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<GalleryMotionSection> {
        val generations = summaries.associate { it.generation.id to it.generation }
        if (summaries.isNotEmpty()) loaded = true
        if (loaded) watched.keys.retainAll(generations.keys)
        generations.values.filter { it.isActive && it.id !in dismissed }.forEach { watched[it.id] = it }
        watched.keys.toList().forEach { id -> generations[id]?.let { watched[id] = it } }
        while (watched.size > 128) watched.remove(watched.keys.first())
        delivered.keys.retainAll(watched.keys)
        val imagesByGeneration = images.groupBy { it.generationId }
        imagesByGeneration.forEach { (id, images) ->
            if (id in watched) delivered.getOrPut(id) { mutableSetOf() }.addAll(images.map { it.ordinal })
        }
        val imageCounts = summaries.associate { it.generation.id to it.imageCount }
        val slots = images.map { image ->
            val key = slotKey(image.generationId, image.ordinal)
            GalleryMotionSlot(key, watched[image.generationId]?.createdAt ?: image.createdAt, image, watched[image.generationId],
                image.generationId in watched && key !in revealed)
        }.toMutableList()
        if (showPending) watched.values.forEach { generation ->
            // A completed request survives lab cleanup for auditing. Its deleted images are
            // not pending requests. Still bridge status-first / image-later Flow delivery.
            if (generation.status == GenerationStatus.SUCCEEDED && imageCounts[generation.id] == 0) return@forEach
            val existing = delivered[generation.id].orEmpty()
            val missing = (1..generation.params.sampleCount).filter { it !in existing }
            val ordinals = if (generation.status in setOf(GenerationStatus.FAILED, GenerationStatus.PARTIAL)) {
                missing.take(1)
            } else missing
            ordinals.forEach { ordinal ->
                slots += GalleryMotionSlot(slotKey(generation.id, ordinal), generation.createdAt,
                    null, generation, true)
            }
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return slots.sortedWith(compareByDescending<GalleryMotionSlot> { it.createdAt }.thenBy { it.image?.ordinal ?: it.key.substringAfterLast("-").toInt() })
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .map { (date, cards) -> GalleryMotionSection(date.toEpochDay(), GalleryTimeline.labelFor(date, today), cards) }
            .sortedByDescending { it.epochDay }
    }

    private fun trim(set: LinkedHashSet<String>, limit: Int) { while (set.size > limit) set.remove(set.first()) }
    private fun slotKey(id: String, ordinal: Int) = "slot-$id-$ordinal"
}
