# PocketNAI 开发约定

面向在本仓库工作的编码助手。产品范围见 [docs/PocketNAI-规划书草案.md](docs/PocketNAI-规划书草案.md)，技术取舍见 [docs/PocketNAI-技术决策记录.md](docs/PocketNAI-技术决策记录.md)。

## 每次改完代码后的固定动作

**不要停在"编译通过"或"问用户要不要装"。每次完成一项改动，按顺序做完全部四步：**

1. `./gradlew :app:testDebugUnitTest` —— 单元测试必须全绿；
2. `./gradlew :app:assembleDebug` —— 产出 APK；
3. **直接安装到模拟器**（用户明确要求，无需再问）：
   ```bash
   adb -s 127.0.0.1:5559 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   多开实例时 `emulator-5558` 也一并安装；
4. 需要看界面时用 `adb shell screencap` 截图确认，而不是只靠读代码推断。

用户的本机环境（勿假设为默认值）：

- Android SDK：`C:\Users\13911\AppData\Local\Android\Sdk`
- Gradle 发行版：`.tooling/gradle-8.11.1/bin/gradle`（本机没装全局 gradle，`gradlew` 也可用）
- 模拟器：MuMu，`adb connect 127.0.0.1:5559`（另有 `emulator-5558`）
- Git Bash 下调用 adb 必须 `export MSYS_NO_PATHCONV=1`，否则 `/sdcard/...` 会被改写成 Windows 路径

## 硬性约束（改动时不得违反）

### 生成成本（优先级最高，先于其它所有便利性考虑）

用户提供了一个**实验账号**：在同时满足下列四个条件时，V4.5 Curated 的生图是**免费**的。

| 项 | 必须是 |
|---|---|
| 模型 | `nai-diffusion-4-5-curated` |
| Resolution 档位 | `Normal`（横 1216×832 / 竖 832×1216 / 方 1024×1024） |
| `steps` | 23 |
| Prompt Guidance（请求里的 `scale`） | 7.0 |

**任何一项偏离都会产生真实 Anlas 消耗。** 因此：

- 用这个账号做验证时，必须逐项确认这四个值；不得顺手改参数，不得批量或循环跑图；
- **费用未知或确定要花钱的调用一律不得自动发起**，只能由用户在界面上手动触发：
  其它三个模型、`Large` 档位、Img2Img / Vibe Transfer / Precise Reference、
  `/ai/encode-vibe`、`/ai/upscale`、`/ai/augment-image`、`/ai/generate-image-stream`；
- 单元测试与任何自动化流程一律使用 MockWebServer 或假实现，**永不触网**；
- 免费的那一次生成只用于验证"链路是否通"，不用于探索参数效果。

### 安全

- **Token 绝不进入日志、数据库、图片元数据、剪贴板或崩溃信息。** 日志只允许记录请求方法、路径、状态码与耗时。
- 不引入 OkHttp 的 `HttpLoggingInterceptor`（防止有人顺手开到 BODY 级别）。网络日志走 `RedactingHttpLogger`。
- Prompt 在日志里只记录长度与哈希指纹。
- `persistent-api-token.txt`、`local.properties` 已被 `.gitignore` 排除，不要把它们加进版本库。
- Token 所在首选项文件固定叫 `pocketnai_secure`，必须与 `res/xml/backup_rules.xml`、`data_extraction_rules.xml` 的排除项保持一致。

### 数据库

- 当前 schema 版本 **4**。新增表（如 `prompt_favorites`、`reference_images`）用独立 `CREATE TABLE`，
  不要触碰既有表。v3 → v4 新增了 `reference_images` 表与 `generations.mode` 列（可空，见下）。
- **`generations` 表绝不能重建（DROP / RENAME）。** `generated_images` 以 `ON DELETE CASCADE` 引用它，重建会连带删除用户的图片记录。
- 加列用 `ALTER TABLE ... ADD COLUMN`；删列需要 SQLite 3.35+，`minSdk 26` 不满足，所以宁可保留遗留列。
- Room 的表校验要求实体列与真实表列**完全一致**，多一列少一列都会失败。因此遗留列必须留在实体里。
- 升级必须写显式 `Migration`，不使用破坏性迁移。schema 导出到 `app/schemas`。
- 新增的字段如果是给既有表加列，实体侧声明成可空可以避开 Room 的默认值比对陷阱
  （`generations.mode` 就是这么加的：迁移里只 `ADD COLUMN` 不带默认值，再用一次
  `UPDATE` 回填 `TXT2IMG`）。

### 参考图的文件存储

- 参考图与 encode-vibe 产物是**内容寻址**的：`files/references/<sha256>.png`、
  `files/vibes/<缓存键>.vibe`。同一张图被多次使用只占一份磁盘。
- 因此它们**不能随生成记录一起删**（可能被别的历史引用），只能由
  `GenerationFileStore.cleanupOrphans` 按"仍被引用的路径集合"回收；
  那个集合必须来自 `GenerationDao.allReferencePaths()` 的完整查询，漏一条就会误删。
- 不要给参考图做"按生成记录分目录"的改动：那会让重复使用同一张图时磁盘翻倍。
- **`reference_images` 行的主键是 `"<generationId>:<role>:<ordinal>"`，不是素材的 id。**
  素材（`ReferenceImage.id`）会被草稿与"复用参数"跨生成复用，而插入用的是
  `OnConflictStrategy.IGNORE` —— 拿素材 id 当主键会让第二次生成静默丢掉参考图行。
  跨生成判断"是不是同一张图"要用 `sha256`，不要用 id。

### 网络

- **所有请求走 `https://image.novelai.net`。** `api.novelai.net` 已拒绝第三方 Persistent API Token（返回 400 要求改用 image URL）。
- T2I 请求**零次自动重试**；超时/断流映射为 `TIMEOUT_UNCERTAIN`，必须让用户知情而不是自动重来。
- 生成必须由用户明确点击触发。**自动化流程绝不能代发真实生成请求**——那会消耗用户的 Anlas。
- **图生图要换 `action`**：`image` 字段只在 `action = "img2img"`（或 `infill`）时被接受，
  沿用 `generate` 会得到 400 `image is not allowed for regular generations`。
  `strength` 放在 `parameters` 顶层；嵌套的 `parameters.img2img` 是 inpaint 用的，不要发。
- 默认值未经核对的字段（`noise`、`extra_noise_seed`、`add_original_image`、`color_correct`）
  **一律不发**，让服务端用它自己的默认值，比猜一个更接近官方行为。
- 余额走 `GET /user/subscription`（只读），错误映射用 `AccountReadErrorMapper`：
  **余额超时是 `REQUEST_TIMEOUT`，不是 `TIMEOUT_UNCERTAIN`**（后者那句"可能已计费"只适用于生成）。

### 余额与费用

- 余额是服务端事实，费用是本地推算，**两者严格分开**：余额只在内存缓存（5 分钟），
  不写 Room、不写 SharedPreferences —— 否则会把上一个账号的余额显示给下一个账号。
- 余额读取失败**不得阻止生成**，也不得把界面上的余额清零；能不能生成始终由服务端 402 决定。
- 费用计算器只能返回四态之一（免费 / V5 额度 / 预计 Anlas / 待确认）。
  **未用官方网页费用标签校准的付费组合必须返回"待确认"，不允许猜数字。**
  定价公式的入口是 `PaidAnlasFormula`，默认实现是恒不支持校准的占位实现。
- 每张参考图有固定附加费（`AnlasCostCalculator.REFERENCE_IMAGE_SURCHARGE_ANLAS`）；
  基础费用未知时**不要只报附加费**，那会让用户以为总共只要 5。
- 实测免费规则只覆盖被观测过的参数（V4.5 Curated + Normal + Steps ≤ 28 + Guidance 7.0 + 至多一张起点图）；
  **不要**把它放宽到整个 V4.5 家族或 V5，也不要放宽到任意 Guidance。

### 质量标签

- 质量标签是**追加到提示词末尾的文本**（`very aesthetic, masterpiece, no text` 等），由客户端拼接，不是 API 开关。
- 因此 `NovelAiRequestBuilder` 把 `qualityToggle` 固定发 `false`，避免服务端重复追加。

### 提示词拼接

- 任何"把一段文本接进提示词"的需求都必须调用 `PromptComposition.append`，
  **不要另写一份拼接逻辑**。曾经质量标签与收藏片段各有一份实现，
  差异最终会变成"有时开头多一个逗号"这类难查的小毛病。
- 收藏提示词只有一个入口（提示词框右上角的书签）：有选中存标签，没选中存整条。
  因此两个输入框都必须用 `TextFieldValue` 跟踪，不能退回普通字符串——
  选区信息是这条判断的唯一依据。

### 凭据与认证（双认证模式）

- **绝不允许**把密码、Access Key、PST 或 Access Token 写进日志、数据库、SharedPreferences
  （`pocketnai_secure` 里的 Keystore 密文除外）、图片元数据、剪贴板、异常 detail 或测试代码。
  也不要读 `persistent-api-token.txt`，不要代发真实登录或真实生成请求。
- **不得改动的兼容性常量**：首选项文件名 `pocketnai_secure`、Keystore alias
  `pocketnai_token_key`、密文键 `token_iv` / `token_ciphertext`。改任何一个都会让旧安装
  已保存的 PST 无法读取，必须同步 `res/xml` 的备份排除规则。
- 登录错误与生成错误是**两套映射器**，不要合并：生成接口的 403 是"凭据失效"，
  登录接口的 403 是"风控/需要额外验证"。超时同理（`REQUEST_TIMEOUT` vs `TIMEOUT_UNCERTAIN`）。
  两边都有用例钉住这个差异。
- Access Key 派生的前 6 个字符必须按 **Unicode 码位**切，不能用 `take(6)`（按 UTF-16 单元）。
  `emoji-leading` / `emoji-inside` 两个向量专门守这条。
- 改派生相关代码前先看 `DerivationDiagnosticTest`：它把 preSalt / BLAKE2b salt / Argon2 输出
  分三步与参考实现对账，能立刻定位是哪一步跑偏。
- 生成链路只认 `credentialStore.load()?.token`，不应该知道邮箱、密码或派生算法的存在。

### 参数记忆

- 生成页的编辑状态由 `GenerateViewModel` 自动持久化到 `GenerationDraftPreferences`，
  启动时恢复。**给 `GenerationParams` 新增字段时，必须同步更新
  `GenerationDraftCodec` 的 DTO**，否则新字段不会被记住，而且不会有编译错误提醒。
- `GenerationDraft`（草稿，可变）与 `Generation`（历史，写入后不可变）刻意分开，
  不要为了省事合并成一个类型。
- 恢复路径必须能逐字段降级：不认识的枚举用默认值、越界数值交给
  `ModelProfile.normalize` 修正，**只有整串 JSON 无法解析时才整体回退**。
  `GenerationDraftCodecTest` 覆盖了这些降级行为，改动时别删。


## 分层约定

`core/` 与 `domain/` 是纯 Kotlin，**不得引入 Android API**。请求构造、提示词解析、参数校验、ZIP 校验这些最容易出错的逻辑都放在这里，好处是能在 JVM 单元测试里直接断言，不需要模拟器。

新增这类逻辑时，同步补单元测试；测试用中文方法名描述行为，断言用 Truth。

## 界面结构（改动前先读）

首页 = 瀑布流画廊 + 生成悬浮层，两者**共用同一个 `GenerateViewModel`**：

- 该 ViewModel 在 `PocketNaiApp` 这一层创建（activity 作用域），再传给 `HomeScreen`。
  **不要把它下移到 HomeScreen 或悬浮层内部**：Tab 会来回切换，
  `saveState`/`restoreState` 下 NavBackStackEntry 的 ViewModelStore 不保证保留，
  用户输入的提示词会丢。
- **生成按钮（`GenerateButton`）放在悬浮层头部右侧，不要移回底部栏或改成自由 FAB。**
  三种方案都试过：全宽底部栏让底部过重（用户反馈）；浮在画廊上的 Free FAB 会被
  展开的悬浮层完全盖住，导致用户刚编辑完参数就找不到生成入口；头部方案同时满足
  「不被滚走、展开收起都可见、不占额外高度」。详见技术决策记录 5.1。
- 状态显示（未连接 / 生成中 / 失败 / 提示词为空 / 拖动提示）在头部第三行，
  由 `SheetStatusLine` 按优先级择一显示，收起态可见。
- 悬浮层的内部滚动只在展开时挂载（见 `GenerateSheet` 的 `expanded` 参数），
  否则手指在表单上往上拖只会滚内容、永远拉不开悬浮层。

在模拟器上用 `adb shell input swipe` 验证拖拽时，**手势要慢且距离长**（例如
`input swipe 540 1320 540 200 900`）。太快太短的 swipe 不会触发 Compose 的拖拽识别，
会让人误判为“悬浮层拉不开”。

用 `adb shell input text` 输入带空格的文本时，**空格要写成 `%s`**，并且整串要用引号包住：
`input text '1girl,%ssilver%shair'`。直接写空格只会输入第一个词，
看起来就像应用把用户输入截断了 —— 会白费一轮排查。

长按选中文本可以用 `input swipe x y x y 900`（同一点、长时长）。

截图验证时注意：MuMu 的 `screencap` 偶尔会合成到上一帧，出现并不存在的 UI 状态。
怀疑时连拍两张比对字节数，相同则画面稳定。

模拟器分辨率会在 1080x1920 与 1920x1080 之间变化，`input tap` 前先确认截图尺寸，
不要复用上一次的坐标。

## 待核对清单

不要把"按公开 API 语义整理的值"当成官方默认值。当前仍待核对的项集中在
[docs/PocketNAI-技术决策记录.md](docs/PocketNAI-技术决策记录.md) 第 3.7 节，
改到相关代码时先看一遍。

参考图功能（Image2Img / Vibe Transfer / Precise Reference）另有一份待核对清单，
见 [docs/PocketNAI-参考图功能规划书.md](docs/PocketNAI-参考图功能规划书.md) 第 3.4 节：
**阶段 0 的 A 类核对（官方网页版的默认值与最大张数）必须先做完**，那些数值不允许按经验猜。

低成本的协议探针技巧：`GET /ai/generate-image/suggest-tags?prompt=x&model=<模型ID>` 会校验模型 ID（无效返回 400），
可以用它零成本核对模型 ID，不必发起真实生成。该端点现在也是**标签补全**的数据来源
（见技术决策记录第八节）：不消耗 Anlas，但匹配是**包含式**而非严格前缀，建议里可能出现与输入无关的词。
