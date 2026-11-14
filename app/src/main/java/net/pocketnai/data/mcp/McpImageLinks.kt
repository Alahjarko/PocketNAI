package net.pocketnai.data.mcp

import java.security.SecureRandom

/** Per-connection, single-image viewing grants. Never use account or pairing credentials in URLs. */
internal class McpImageLinks(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Grant(val imageId: String, val expires: Long)
    private val random = SecureRandom()
    private val grants = linkedMapOf<String, Grant>()

    @Synchronized fun issue(imageId: String): String {
        val time = now()
        grants.entries.removeAll { it.value.expires <= time }
        val existing = grants.entries.firstOrNull { it.value.imageId == imageId }?.key
        val path = existing ?: PREFIX + ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        grants.remove(path)
        grants[path] = Grant(imageId, time + TTL_MILLIS)
        while (grants.size > MAX_LINKS) grants.remove(grants.keys.first())
        return path
    }

    @Synchronized fun resolve(path: String): String? {
        if (!PATH.matches(path)) return null
        val grant = grants[path] ?: return null
        if (grant.expires <= now()) { grants.remove(path); return null }
        return grant.imageId
    }

    @Synchronized fun clear() = grants.clear()

    companion object {
        const val PREFIX = "/mcp-image/"
        const val TTL_MILLIS = 30 * 60_000L
        const val MAX_LINKS = 128
        private val PATH = Regex("/mcp-image/[0-9a-f]{64}")
    }
}

data class McpImageData(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
    init {
        require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024)
        require(mimeType in setOf("image/jpeg", "image/png"))
        require(width > 0 && height > 0)
    }
}
