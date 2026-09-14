package net.pocketnai.data.settings

import android.content.Context
import net.pocketnai.domain.model.GenerationDraft

/**
 * 生成草稿的本地存储。
 *
 * 只保存"上一次用的参数与提示词"，用于下次打开时恢复，不参与任何网络请求。
 * 单独用一个首选项文件，与 [SettingsStore]（行为开关）分开，便于各自演进。
 *
 * 提示词会因此落到本地磁盘 —— 这与历史记录本来就会保存提示词是一致的；
 * Token 依旧只存在 Android Keystore 保护的那份存储里，与本类无关。
 */
class GenerationDraftPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取上次的草稿；从未保存过或数据损坏时返回 null，由调用方用出厂默认。 */
    fun load(): GenerationDraft? = GenerationDraftCodec.decode(prefs.getString(KEY_DRAFT, null))

    fun save(draft: GenerationDraft) {
        prefs.edit().putString(KEY_DRAFT, GenerationDraftCodec.encode(draft)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DRAFT).apply()
    }

    private companion object {
        const val PREFS_NAME = "pocketnai_draft"
        const val KEY_DRAFT = "last_generation_draft"
    }
}
