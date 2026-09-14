package net.pocketnai.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 生成任务表（规划书 7.1）。
 *
 * 这里刻意使用扁平的基础类型列而不是嵌套对象 + TypeConverter：
 * 数据库结构因此可以被 SQL 直接检查，迁移脚本也更直白。
 * 枚举一律以名字符串存放，映射回领域模型的工作放在仓库层。
 *
 * [deletedAt] 实现规划书 4.4 的“删除先标记、确认后再清理文件”语义：
 * 界面上撤销删除只需要把该列置空。
 */
@Entity(
    tableName = "generations",
    indices = [Index("createdAt"), Index("deletedAt")],
)
data class GenerationEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** [net.pocketnai.domain.model.GenerationStatus] 的名字。 */
    val status: String,
    val title: String,
    /** 用户输入的原文，可能含 Randomizer 语法。 */
    val promptTemplate: String,
    /**
     * [net.pocketnai.domain.model.GenerationMode] 的名字。
     *
     * 声明成可空是**故意的**：v3 之前落库的记录没有这一列，`ALTER TABLE ADD COLUMN`
     * 加出来的列也没有默认值，可空声明才能通过 Room 的 schema 校验
     * （见 AGENTS.md 中"新增字段声明成可空"的约定）。读取时按 TXT2IMG 降级。
     */
    val mode: String?,

    // ---- GenerationParams 展平 ----
    /** 本次实际提交的提示词（Randomizer 已展开）。 */
    val prompt: String,
    val negativePrompt: String,
    val modelApiId: String,
    val width: Int,
    val height: Int,
    val sampleCount: Int,
    val steps: Int,
    val guidance: Double,
    val cfgRescale: Double,
    val sampler: String,
    val noiseSchedule: String,
    val seedMode: String,
    val baseSeed: Long,

    /**
     * 质量标签档位，存 [net.pocketnai.domain.model.QualityTagsOption] 的名字。
     *
     * 声明为可空是**迁移的需要**，不是业务上的可空：v1 的表结构里没有这一列，
     * `ALTER TABLE ADD COLUMN` 加出来的列对既有行为 NULL。
     * 如果这里声明成非空并带 Room 默认值，Room 的表校验会拿实体默认值与
     * SQLite 里记录的真实默认值比较，容易在迁移校验阶段失败。
     * 读取统一走 [net.pocketnai.domain.model.QualityTagsOption.fromNameOrDefault]。
     */
    val qualityTags: String?,

    /**
     * 遗留列，自数据库 v2 起不再有业务含义。
     *
     * 保留它的原因：Room 的表校验要求实体列与真实表列**完全一致**，
     * 留下一个"表中存在但实体没有"的列会导致校验失败；而删列（DROP COLUMN）
     * 需要 SQLite 3.35+，minSdk 26 的 Android 8 设备不满足。
     * 重建表则更危险：`generated_images` 以 ON DELETE CASCADE 引用本表，
     * DROP/RENAME 会连带删除用户的图片记录。
     *
     * 因此这里保留该列并继续写入（由 [qualityTags] 派生），
     * 等未来重做一次完整的表重建迁移时再一并清除。
     */
    val qualityTagsEnabled: Boolean,

    val ucPresetIndex: Int,

    /** 生成这份参数时使用的模型配置版本，便于日后判断默认值来源。 */
    val modelConfigVersion: String,
    /** 请求体快照版本，对应 `params_version`。 */
    val requestSnapshotVersion: Int,

    val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    val correlationId: String?,

    /** 非空表示已标记删除但尚未清理文件，界面此时应隐藏该记录。 */
    val deletedAt: Long? = null,
)
