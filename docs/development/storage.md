# 数据库、文件与草稿

本文件是 [根开发约定](../../AGENTS.md) 的专题补充。修改相关功能前必须阅读；跨模块改动需同时阅读索引指向的其他专题。历史实测记录保留其日期，不构成自动发起真实生成、登录或付费调用的授权。

启动恢复的文件扫描、详情映射及旧PNG角色读取由仓库切到IO线程，不能只依赖调用方启动一个主线程协程。启动缺失文件统计一次读取图片索引，避免按生成记录逐条查库；画廊和历史Flow映射在后台执行（2026-10-09）。

## 数据库

- 当前 schema 版本 **11**。新增表（如 `prompt_favorites`、`reference_images`、`favorite_images`）用独立 `CREATE TABLE`，
  不要触碰既有表。v3 → v4 新增了 `reference_images` 表与 `generations.mode` 列（可空，见下）；
  v4 → v5 给 `generations` 加 `outputWidth/outputHeight`；v5 → v6 新增 `favorite_images` 表；
  v6 → v7 新增 `anlas_transactions` 表（Anlas 消耗流水，2026-09-18）。
  v7 → v8 给 `generations` 加可空 `charactersJson`，保存独立角色完整快照；NULL 的旧记录在详情读取时从自身 PNG 元数据恢复，`[]` 表示明确无角色。详情与复用参数共用恢复结果，不读取当前草稿补历史。
  v8 → v9 仅新增 `chat_conversations`，保存对话与图片索引，既有图片记录保持不变；思考与工具 ID 必须一起保存，见[对话专题](chat.md)。
- v9 → v10 只增加抽卡批次、逐项画师串与画师串收藏表；图片引用采用 SET NULL，清理图片保留计划。见[抽卡专题](artist-lab.md)。
- **`generations` 表绝不能重建（DROP / RENAME）。** `generated_images` 以 `ON DELETE CASCADE` 引用它，重建会连带删除用户的图片记录。
- 加列用 `ALTER TABLE ... ADD COLUMN`；删列需要 SQLite 3.35+，`minSdk 26` 不满足，所以宁可保留遗留列。
- Room 的表校验要求实体列与真实表列**完全一致**，多一列少一列都会失败。因此遗留列必须留在实体里。
- 升级必须写显式 `Migration`，不使用破坏性迁移。schema 导出到 `app/schemas`。
- 新增的字段如果是给既有表加列，实体侧声明成可空可以避开 Room 的默认值比对陷阱
  （`generations.mode` 就是这么加的：迁移里只 `ADD COLUMN` 不带默认值，再用一次
  `UPDATE` 回填 `TXT2IMG`）。
- **SQLite 的外键约束默认是关的**，应用里靠 Room 生成的实现执行 `PRAGMA foreign_keys = ON`
  才生效。因此仪器化迁移测试里要验证 `ON DELETE CASCADE`，必须自己在裸库上再执行一次
  `PRAGMA foreign_keys = ON`，否则测到的是"开关没开"而不是"级联没生效"（会误报失败）。

- v10 → v11 仅给 generations 增加可空 useCharacterCoordinates 列，不重建表。NULL的旧快照沿用此前“有角色就启用坐标”的行为；从旧PNG恢复角色时同时恢复其use_coords。草稿DTO v2存同一字段，缺失时兼容旧草稿。

## 参考图的文件存储

- 参考图与 encode-vibe 产物是**内容寻址**的：`files/references/<sha256>.png`、
  `files/vibes/<缓存键>.vibe`。同一张图被多次使用只占一份磁盘。
- 因此它们**不能随生成记录一起删**（可能被别的历史引用），只能由
  `GenerationFileStore.cleanupOrphans` 按"仍被引用的路径集合"回收；
  那个集合必须来自 `GenerationDao.allReferencePaths()` 的完整查询，漏一条就会误删。
- 不要给参考图做"按生成记录分目录"的改动：那会让重复使用同一张图时磁盘翻倍。
- 启动清理的存活集合 = 数据库引用 ∪ **草稿里挂着的参考图**（`LiveReferencePathsProvider`）。
  少了后者，用户"选好图 → 重启应用 → 生成"必然失败并报"参考图已不在本机"。
  它是 `GenerationRepository` 的必填参数，不要图省事给它默认空集合。
- **`reference_images` 行的主键是 `"<generationId>:<role>:<ordinal>"`，不是素材的 id。**
  素材（`ReferenceImage.id`）会被草稿与"复用参数"跨生成复用，而插入用的是
  `OnConflictStrategy.IGNORE` —— 拿素材 id 当主键会让第二次生成静默丢掉参考图行。
  跨生成判断"是不是同一张图"要用 `sha256`，不要用 id。

## 参数记忆

- 生成页的编辑状态由 `GenerateViewModel` 自动持久化到 `GenerationDraftPreferences`，
  启动时恢复。**给 `GenerationParams` 新增字段时，必须同步更新
  `GenerationDraftCodec` 的 DTO**，否则新字段不会被记住，而且不会有编译错误提醒。
- `GenerationDraft`（草稿，可变）与 `Generation`（历史，写入后不可变）刻意分开，
  不要为了省事合并成一个类型。
- 恢复路径必须能逐字段降级：不认识的枚举用默认值、越界数值交给
  `ModelProfile.normalize` 修正，**只有整串 JSON 无法解析时才整体回退**。
  `GenerationDraftCodecTest` 覆盖了这些降级行为，改动时别删。
