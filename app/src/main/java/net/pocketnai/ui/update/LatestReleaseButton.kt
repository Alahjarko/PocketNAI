package net.pocketnai.ui.update

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import net.pocketnai.BuildConfig
import net.pocketnai.R

/** 不依赖检查接口成功，网络报错时仍能让用户通过浏览器查看与下载。 */
@Composable
fun LatestReleaseButton(uriHandler: UriHandler) {
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            uriHandler.openUri("https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest")
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, R.string.update_open_page_failed, Toast.LENGTH_SHORT).show()
        }
    }) {
        Text(stringResource(R.string.update_open_release_page))
    }
}
