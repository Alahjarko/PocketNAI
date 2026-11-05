package net.pocketnai.domain.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.model.GenerationParams

data class ChatImageQuote(val label: String, val anlas: Long? = null, val allowance: Boolean = false) {
    companion object {
        fun from(estimate: GenerationCostEstimate): ChatImageQuote = when (estimate) {
            is GenerationCostEstimate.Free -> ChatImageQuote("当前报价免费", 0)
            is GenerationCostEstimate.UsesV5Allowance -> ChatImageQuote("使用 V5 额度", allowance = true)
            is GenerationCostEstimate.EstimatedAnlas -> ChatImageQuote("预计 ${estimate.batchTotal} Anlas", estimate.batchTotal)
            else -> ChatImageQuote("费用待确认，以服务端为准")
        }
    }
}

data class ChatPlannedImage(val params: GenerationParams, val quote: ChatImageQuote)
data class ChatImagePlan(val items: List<ChatPlannedImage>, val completed: Int = 0, val changed: Boolean = false) {
    val summary: String get() = buildList {
        add("本次 ${items.size} 张")
        if (items.all { it.quote.anlas != null }) add("合计预计 ${items.sumOf { it.quote.anlas!! }} Anlas")
        else {
            val known = items.mapNotNull { it.quote.anlas }.sum()
            if (known > 0) add("已知费用 $known Anlas")
            if (items.any { it.quote.allowance }) add("含 V5 额度，余额变化后重新确认")
            if (items.any { it.quote.anlas == null && !it.quote.allowance }) add("含待确认费用，不能确定总价")
        }
    }.joinToString(" · ")
}

data class ChatBatchResult(val images: List<ChatImage>, val status: String, val message: String, val requested: Int)

/** One instance per user message. Permission is attached to frozen parameters, never to model prose. */
class ChatImageBatch(private val images: ChatImageGenerator, private val pause: suspend () -> Unit = { delay(1000) }) {
    var attempted = 0
        private set
    private var blocked = false
    val remaining get() = if (blocked) 0 else ImageChatTools.MAX_IMAGES - attempted

    suspend fun execute(
        params: List<GenerationParams>, autoSingle: Boolean,
        confirm: suspend (ChatImagePlan) -> Boolean,
        active: () -> Boolean,
        onProgress: (Int, Int, ChatImageQuote) -> Unit,
        onImage: (ChatImage) -> Unit,
        checkpoint: suspend (List<ChatImage>) -> Unit,
    ): ChatBatchResult {
        if (params.isEmpty() || params.size > remaining) throw ChatFailure("本条消息剩余可生成 $remaining 张，请调整计划或发送新消息")
        val account = images.authorizationKey()
        var plan = params.map { ChatPlannedImage(it, images.quote(it)) }
        val output = mutableListOf<ChatImage>()
        fun finish(status: String, message: String): ChatBatchResult {
            if (status != "succeeded") blocked = true
            return ChatBatchResult(output.toList(), status, message, params.size)
        }
        if (!(autoSingle && params.size == 1 && attempted == 0) && !confirm(ChatImagePlan(plan)))
            return finish("cancelled", "用户取消计划，未发送图片请求")
        for (index in params.indices) {
            if (index > 0 || attempted > 0) pause()
            if (!active()) return finish("cancelled", "已停止后续图片；已完成 ${output.size}/${params.size} 张")
            if (images.authorizationKey() != account) return finish("cancelled", "账号或图片服务已改变，后续图片未发送")
            try {
                val latest = images.quote(params[index])
                if (latest != plan[index].quote) {
                    val remainingPlan = params.drop(index).map { ChatPlannedImage(it, images.quote(it)) }
                    if (!confirm(ChatImagePlan(remainingPlan, output.size, changed = true)))
                        return finish("cancelled", "费用变化后用户取消，后续图片未发送")
                    plan = plan.take(index) + remainingPlan
                    // Confirmation is asynchronous: recheck the account and current price again.
                    if (!active() || images.authorizationKey() != account)
                        return finish("cancelled", "授权条件已改变，后续图片未发送")
                    if (images.quote(params[index]) != plan[index].quote)
                        return finish("cancelled", "费用再次变化，请发送新消息重新确认")
                }
                attempted++ // A failed or uncertain request consumes its allowance too.
                onProgress(index + 1, params.size, plan[index].quote)
                val approvedQuote = plan[index].quote
                val current = images.generate(params[index], account, beforeSend = {
                    // The shared generation gate can wait behind another task after this loop's check.
                    if (!active()) throw ChatFailure("用户已停止后续图片，本张未发送")
                    if (images.authorizationKey() != account) throw ChatFailure("账号已改变，本张未发送")
                    if (images.quote(params[index]) != approvedQuote) throw ChatFailure("等待发送期间费用变化，本张未发送；请重新确认计划")
                }) { image ->
                    if (output.none { it.imageId == image.imageId }) { output += image; onImage(image) }
                }
                if (current.isEmpty()) throw ChatFailure("未收到最终图片，未自动重试")
                current.forEach { image -> if (output.none { it.imageId == image.imageId }) { output += image; onImage(image) } }
                checkpoint(output.toList())
            } catch (e: CancellationException) { blocked = true; throw e }
            catch (e: ChatFailure) { checkpoint(output.toList()); return finish("failed", "${e.userMessage}；后续图片已停止，已完成 ${output.size}/${params.size} 张") }
            catch (_: Exception) { checkpoint(output.toList()); return finish("failed", "图片任务失败，后续图片已停止；未自动重试") }
        }
        return finish("succeeded", "已生成 ${output.size} 张图片")
    }
}
