package net.pocketnai.data.files

import java.io.File

object MediaFormat {
    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "jpeg", "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }
}
