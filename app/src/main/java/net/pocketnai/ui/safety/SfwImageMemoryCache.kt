package net.pocketnai.ui.safety

import android.content.Context
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

private fun MemoryCache.Key.isMask() = key.endsWith("|${StrongSfwBlur.CACHE_KEY}")

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

    override val size: Int @Synchronized get() = (masks?.size ?: 0) + images.size
    override val maxSize: Int get() = budget
    override val keys: Set<MemoryCache.Key> @Synchronized get() = masks?.keys.orEmpty() + images.keys
    @Synchronized override fun get(key: MemoryCache.Key): MemoryCache.Value? =
        if (key.isMask()) masks?.get(key) else images[key]

    @Synchronized override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        if (!key.isMask()) { images[key] = value; return }
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
        if (key.isMask()) masks?.remove(key) ?: false else images.remove(key)
    @Synchronized override fun clear() { masks?.clear(); images.clear() }
    @Synchronized override fun trimMemory(level: Int) { masks?.trimMemory(level); images.trimMemory(level) }
}

/** 宽窄网格同时首次请求同一遮罩时，后到者等前一次写入 Coil 内存缓存再读取。 */
internal class SfwMaskRequestInterceptor : Interceptor {
    private val pending = ConcurrentHashMap<MemoryCache.Key, CompletableDeferred<Unit>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = request.memoryCacheKey
        if (key == null || !key.isMask()) return chain.proceed(request)
        val completed = CompletableDeferred<Unit>()
        while (true) {
            val previous = pending.putIfAbsent(key, completed) ?: break
            previous.await()
        }
        return try {
            chain.proceed(request)
        } finally {
            // 即便首个 painter 离场取消，其他可见卡片也能接手。
            pending.remove(key, completed)
            completed.complete(Unit)
        }
    }
}
