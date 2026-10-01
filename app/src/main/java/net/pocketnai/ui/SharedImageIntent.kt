package net.pocketnai.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** 只接收用户分享的单张图片 content URI，不接收外部提供的任意本地路径。 */
internal fun sharedImageUri(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
    val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    return uri?.takeIf { it.scheme == "content" }
}
