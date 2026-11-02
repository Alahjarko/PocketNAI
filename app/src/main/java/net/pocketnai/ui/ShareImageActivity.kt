package net.pocketnai.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** 无界面的分享接收器：图片交给主任务后立即结束，不保留第二份应用界面。 */
class ShareImageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            sharedImageMainIntent(this, intent)?.let { startActivity(it) }
        } catch (_: SecurityException) {
            Toast.makeText(this, "无法读取分享图片，请从相册重新分享", Toast.LENGTH_SHORT).show()
        } finally {
            // Theme.NoDisplay 必须在 onResume 前结束；这里不能等待图片读取或后台任务。
            finish()
        }
    }
}
