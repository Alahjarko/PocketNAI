package net.pocketnai.domain.artistlab

import kotlinx.coroutines.flow.StateFlow

data class ArtistLabProgress(val completed: Int, val total: Int, val pausing: Boolean = false)

/** 用户已确认的批次才可启动；宿主就绪后才能发请求，结束后释放后台运行资源。 */
interface ArtistLabBackgroundExecution {
    suspend fun start(progress: StateFlow<ArtistLabProgress>, pause: () -> Unit)
    fun stop()
}

class ArtistLabBackgroundUnavailable : RuntimeException("后台抽卡服务未能启动")
