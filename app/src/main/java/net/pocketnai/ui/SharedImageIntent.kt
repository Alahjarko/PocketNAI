package net.pocketnai.ui

import android.content.Intent
import android.content.ClipData
import android.content.Context
import android.net.Uri
import androidx.core.content.IntentCompat

/** 只接收用户分享的单张图片 content URI，不接收外部提供的任意本地路径。 */
internal fun sharedImageUri(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
    val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    return uri?.takeIf { it.scheme == "content" }
}

/** 新建主入口 Intent，只转交已校验的单张图片与读取权限，不继承外部任务标记。 */
internal fun sharedImageMainIntent(context: Context, incoming: Intent): Intent? {
    val uri = sharedImageUri(incoming) ?: return null
    return Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = incoming.type
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("shared image", uri)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}
