package net.pocketnai.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pocketnai.BuildConfig
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.settings.SettingsStore
import net.pocketnai.data.update.GitHubReleaseApi
import net.pocketnai.data.update.UpdateDownloader
import net.pocketnai.domain.update.UpdateEvaluator
import net.pocketnai.domain.update.UpdateRelease
import net.pocketnai.domain.update.UpdateStatus
import java.io.File

/**
 * "检查更新"的状态与动作。
 *
 * ## 什么时候查
 * - **启动后静默查一次**（[checkOnLaunch]）：失败不打扰；发现新版本且用户
 *   没有对同一个构建点过"稍后"才弹窗；
 * - **设置页手动查**（[checkManually]）：无论结果如何都给一次明确反馈 ——
 *   "已是最新"与"没查成"必须分开说（把后者说成前者会让人以为真的没问题）。
 *
 * ## 谁负责"调起安装"
 * 这里只负责把 APK **下载并校验**到本地（[UiState.readyApk]）；
 * 拉起系统安装器需要 Activity context 与 FileProvider，由界面观察
 * [UiState.readyApk] 后完成 —— ViewModel 不碰 Intent。
 */
class UpdateViewModel(
    private val api: GitHubReleaseApi,
    private val downloader: UpdateDownloader,
    private val settingsStore: SettingsStore,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
) : ViewModel() {

    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?) {
        /** 服务端没给总长度时是 null（界面回退成不确定进度条）。 */
        val percent: Int?
            get() = totalBytes?.takeIf { it > 0L }
                ?.let { ((downloadedBytes * 100) / it).coerceIn(0L, 100L).toInt() }
    }

    data class UiState(
        /** 发现的可更新版本（非空时弹对话框）。 */
        val available: UpdateRelease? = null,
        /** 手动检查进行中。 */
        val checking: Boolean = false,
        /** 手动检查"已是最新"的一次性反馈（展示在设置页那一行）。 */
        val upToDateNotice: Boolean = false,
        /** 非空表示正在下载。 */
        val download: DownloadProgress? = null,
        /** 下载并校验完成的安装包，等界面调起安装器。 */
        val readyApk: File? = null,
        /** 一次性错误（检查/下载失败、签名不一致）。 */
        val error: ErrorCode? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 启动时静默检查：失败不打扰，有更新且未被"稍后"过才弹窗。 */
    fun checkOnLaunch() {
        viewModelScope.launch {
            // 稍等片刻再查：把启动瞬间的带宽与注意力留给界面本身。
            delay(LAUNCH_CHECK_DELAY_MS)
            when (val outcome = api.latestRelease()) {
                is Outcome.Success -> {
                    val status = UpdateEvaluator.evaluate(currentVersionCode, outcome.value)
                    if (status is UpdateStatus.Available &&
                        status.release.versionCode > settingsStore.updateDismissedVersionCode()
                    ) {
                        _state.update { it.copy(available = status.release) }
                    }
                }

                // 启动检查失败保持静默：这不是用户此刻要处理的事。
                is Outcome.Failure -> Unit
            }
        }
    }

    fun checkManually() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, upToDateNotice = false, error = null) }
        viewModelScope.launch {
            when (val outcome = api.latestRelease()) {
                is Outcome.Success -> {
                    val status = UpdateEvaluator.evaluate(currentVersionCode, outcome.value)
                    _state.update {
                        when (status) {
                            is UpdateStatus.Available ->
                                it.copy(checking = false, available = status.release)

                            UpdateStatus.UpToDate ->
                                it.copy(checking = false, upToDateNotice = true)
                        }
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(checking = false, error = outcome.error.code)
                }
            }
        }
    }

    /** "稍后"：记下这个构建号，之后不再为它弹窗（出现更新的构建才重新提示）。 */
    fun dismissAvailable() {
        val release = _state.value.available ?: return
        settingsStore.setUpdateDismissedVersionCode(release.versionCode)
        _state.update { it.copy(available = null, error = null) }
    }

    fun downloadUpdate() {
        val release = _state.value.available ?: return
        if (_state.value.download != null) return
        _state.update { it.copy(download = DownloadProgress(0L, release.sizeBytes), error = null) }
        viewModelScope.launch {
            val outcome = downloader.download(release) { downloaded, total ->
                _state.update { it.copy(download = DownloadProgress(downloaded, total)) }
            }
            when (outcome) {
                is Outcome.Success -> _state.update {
                    it.copy(download = null, readyApk = outcome.value)
                }

                is Outcome.Failure -> _state.update {
                    it.copy(download = null, error = outcome.error.code)
                }
            }
        }
    }

    /**
     * 界面已经处理完 [UiState.readyApk]（调起了安装器，或引导去了授权页）。
     *
     * 连 [UiState.available] 一起清掉：安装器拉起之后这个提示就该消失，
     * 也**不记"稍后"** —— 如果用户取消了安装，下次启动重新提示才是对的。
     */
    fun onInstallHandled() {
        _state.update { it.copy(readyApk = null, available = null, download = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissUpToDateNotice() {
        _state.update { it.copy(upToDateNotice = false) }
    }

    private companion object {
        const val LAUNCH_CHECK_DELAY_MS = 3_000L
    }
}
