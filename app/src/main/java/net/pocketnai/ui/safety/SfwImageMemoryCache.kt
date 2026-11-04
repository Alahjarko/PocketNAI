package net.pocketnai.ui.safety

import android.content.Context
import coil.memory.MemoryCache

/** Keep tiny SFW display copies from being evicted by full-size/normal thumbnails.
 * The combined strong-reference budget is exactly Coil's original budget, not extra memory.
 */
internal class SfwImageMemoryCache(
    context: Context,
    private val budget: Int = MemoryCache.Builder(context).build().maxSize,
) : MemoryCache {
    private val maskBudget = minOf(2 * 1024 * 1024, budget / 4)
    private val appContext = context.applicationContext
    private var masks: MemoryCache? = null
    private var images = MemoryCache.Builder(appContext).maxSizeBytes(budget).build()

    private fun isMask(key: MemoryCache.Key) = key.key.endsWith("|${StrongSfwBlur.CACHE_KEY}")

    override val size: Int @Synchronized get() = (masks?.size ?: 0) + images.size
    override val maxSize: Int get() = budget
    override val keys: Set<MemoryCache.Key> @Synchronized get() = masks?.keys.orEmpty() + images.keys
    @Synchronized override fun get(key: MemoryCache.Key): MemoryCache.Value? =
        if (isMask(key)) masks?.get(key) else images[key]

    @Synchronized override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        if (!isMask(key)) { images[key] = value; return }
        if (masks == null) {
            // SFW-off sessions retain the entire original budget. Reserve space only when
            // the first masked image actually arrives, preserving existing normal entries.
            val previous = images
            images = MemoryCache.Builder(appContext).maxSizeBytes(budget - maskBudget).build()
            previous.keys.forEach { oldKey -> previous[oldKey]?.let { images[oldKey] = it } }
            previous.clear()
            masks = MemoryCache.Builder(appContext).maxSizeBytes(maskBudget).build()
        }
        masks!![key] = value
    }
    @Synchronized override fun remove(key: MemoryCache.Key): Boolean =
        if (isMask(key)) masks?.remove(key) ?: false else images.remove(key)
    @Synchronized override fun clear() { masks?.clear(); images.clear() }
    @Synchronized override fun trimMemory(level: Int) { masks?.trimMemory(level); images.trimMemory(level) }
}
