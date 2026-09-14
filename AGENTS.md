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

⚠️ **2026-09-14 补充："免费"是订阅权益，先确认订阅状态再依赖它。**
当天读 `GET /user/subscription` 得到的原始读数是
`tier 0 / active false / accountType 0 (RETAIL) / expiresAt 0` —— 按官方判据这**不是**订阅账号，
生成会正常扣费。上面那张"免费组合"表是账号所有者在订阅有效期内给出的经验值，
**不是账号的永久属性**。动手前先在设置页（或余额弹层）看订阅等级那一行：
它显示"本机指定"或"有订阅权益"时才谈得上免费。
如果读数与用户描述不一致，**不要自己判断谁对**，按上面"费用未知一律不自动发起"处理。

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

### 参考图的请求形态

- 图生图：`action` 换成 `img2img`，`parameters.image` + `parameters.strength`。
- Precise Reference：`action` 保持 `generate`，发 `director_reference_*` **五个数组**。
  数组按下标一一对应，**任何一项缺失都不能跳过**（必须补默认值），
  否则"这个角色的强度"会落到"那个角色"上，而服务端不报错。
- 黑边补齐在**导入时**做（`ImageTransform.DirectorCanvas`），缩略图因此就是提交图；
  提交时再套一次同样的 Letterbox 是恒等变换，不要"聪明地"跳过。
- Vibe Transfer：`action` 也保持 `generate`，发 `reference_*_multiple` **三个数组**，
  内容是 `encode-vibe` 的产物 base64（实测服务端收编码产物，不收原图）。
  编码缓存在 `files/vibes/<hash>.vibe`，键含模型 + 图片 sha256 + Information Extracted。
- **Vibe 与 Precise Reference 不能同时发**：服务端会拒绝
  （`cannot mix reference and director_reference at the same time`）。界面必须互斥。
  Vibe 与图生图可以同时发。

### 局部重绘（Inpaint）

**一句话：功能已经完全实现，但入口被关掉了，因为公开 API 不接受它。**
这是本仓库目前唯一一处"代码就绪、能力位为 false"的功能，改动前请先读完这一节。

**请求形态（已实现，别再重新设计）**

- `action` 用 `infill`，底图放 `parameters.image`、蒙版放 `parameters.mask`，
  强度放在**嵌套的** `parameters.img2img.strength`（OpenAPI 对它的说明是 `used by inpaint`）。
- 蒙版必须与底图**同尺寸**、**硬边（关抗锯齿）**，且是二值语义。
  约定（白色 vs 透明 = 重画区域）关在 `MaskConvention` 里，**尚未用真实生成验证**。
- 扩张用"笔刷半径加 N"实现（Minkowski 和），**不要**改成对位图做形态学卷积 ——
  那样会毁掉实时预览。橡皮不参与扩张。
- 重绘与参考条件（Precise Reference / Vibe）互斥；换底图必须作废旧蒙版。

**窘境：官方网页能做，公开 API 不做**

官方文档、官方网页都有 Inpaint（网页上还能"大图零 Anlas 重绘"），但公开 API 的行为不同。
2026-09-14 实测了三条路径，全部堵死（详见技术决策记录第十五节）：

| 试过的路径 | 结果 |
|---|---|
| `action: "infill"` + `nai-diffusion-4-5-curated` | `HTTP 400 Model nai-diffusion-4-5-curated doesn't support action infill`（响应里还跟了一段 `500 Internal Server Error`） |
| `action: "infill"` + `nai-diffusion-4-5-full` | `HTTP 400 Model nai-diffusion-4-5-full doesn't support action infill`（同样跟一段 500，看起来这个 action 在服务端的处理链本身就不完整） |
| `action: "img2img"` + 蒙版 | **请求成功、出图，但蒙版被完全忽略** |

第三条的结论是**定量**得出的，不是"看起来没生效"：蒙版只覆盖 3.55% 的像素，
而输出有 52.6% 的像素发生变化、变化区域的包围盒是整张图，且 96.7% 的变化落在蒙版外。
也就是说 `img2img` 会把蒙版字段当空气，用户会得到"整张图被重画"的结果 ——
比没有这个按钮更糟，因为它看起来是成功的。

**为什么保留实现而不是删掉**

- 编辑器（画笔/橡皮/撤销重做/清空二次确认/扩张实时预览）、蒙版渲染、请求构造、
  存储与入口都已完成，删掉等于把"接口一开放就能用"变成"要重新做一遍"；
- 但**留着入口**是有害的：用户会以为点了能生效，在未核实的模型上还可能真扣费。
  因此收敛为一个常量：`ModelCatalog` 里四个模型的 `supportsInpaint = false`，
  界面据此隐藏入口并说明原因。

**重新启用的条件（三条都要满足）**

1. 服务端开放 `infill`（或明确给出替代 action）。验证方式是先发一次请求看是否还回
   `doesn't support action infill`，**不要**靠推断；
2. 蒙版约定必须先用判别性探针确认（`PAINTED_IS_WHITE` vs `PAINTED_IS_TRANSPARENT`，
   探针方法写在 `MaskConvention` 的注释里）。这一步之前**不允许**打开入口 ——
   约定猜错的后果是"涂的区域没变、没涂的区域全变了"；
3. 由用户明确授权，且一次只发一个请求、用已知免费的最小参数
   （V4.5 Curated + Normal + steps 23 + guidance 7.0 + 单张）。

**不要做的事**

- 不要因为它"属于 Image2Img 家族、V5 也能用"就以为换个模型就能跑 ——
  两个档位都实测被拒；
- 不要在未授权时为了"验证一下"发真实请求（上一次就是这么被叫停的：
  账号余额有限，用户明确说"停"）；
- 不要删除编辑器与蒙版渲染的代码，也不要把它标记成"未实现"。

### 参考图的文件存储

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
  定价公式的入口是 `PaidAnlasFormula`，**当前实现是 `NovelAiPaidAnlasFormula`**：
  式子与免费规则是 2026-09-14 从官方网页前端 bundle 反解出来的（见技术决策记录第十六节），
  不要再按"社区库公式"或"人工读网页费用标签"的路子重做一遍。
- **订阅状态**：官方判据是 `accountType ∈ {B2B,SERVICE,SUPPORT,ADMIN} || (expiresAt > now && tier > 0)`，
  **`active` 字段完全不参与判断**；`tier` 的整数含义是 `0/1/2/3 → 无/Tablet/Scroll/Opus`。
  服务端读数可能和用户实际买到的权益不一致，因此设置页有**手动覆盖**
  （`SettingsStore.subscriptionOverride`），手动值直接取代读数。
  要改订阅判断只改 `SubscriptionStatusResolver` 一处，不要在计算器里再塞第二套。
- **附加费按功能而不是按图数计**，规则以官方前端为准：Image2Img 不额外收费（已用余额观测印证）；
  Precise Reference **每张参考图 × 每张输出图 5 Anlas**；Vibe 超出 4 张的部分每张 +2，
  另加"未编码的 vibe"每张 2 Anlas 的编码费（已编码的不再收，判定入口是
  `GenerationRepository.isVibeEncoded`，与真正编码用的是同一个缓存键）。
- **免费单张规则（以官方前端为准）**：`面积 ≤ 1024×1024 && steps ≤ 28 && tier 是 Opus && 有订阅权益`
  （V5 还要 `usage.isNegative == false`）。**没有"无底图"这一条**（图生图同样免费，已实测印证），
  **也不是整单免费**（批量只免 1 张）。
- **"订阅打 20% 折扣"指的是买 Anlas 的美元价，不是生成扣费**：官方定价页那一行叫
  Anlas Purchase Discount，前端里订阅价是原价的 ~79%（$4.79 → $3.79 等），
  而计价函数里**没有任何 0.8 系数**。给它加折扣会把实际扣费报少 20%。
  用户再提这件事时直接引技术决策记录 §16.6。
- **唯一未实测项**：官方前端总是显式发 `sm` / `sm_dyn`（这四个模型默认 false），我们的请求不发。
  参数在 `AnlasPricingContext.smeaMultiplier`（当前恒 1.0），要消除它需要用户授权做一次费用核对。
- 模型能力位：图生图四个模型都支持；**Vibe Transfer 与 Precise Reference 目前只有 V4.5**。
  界面要隐藏 V5 上的入口，`GenerationRequest.validate` 也要在本地拦一次。

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

局部重绘（Inpaint）的调研与当前状态见
[docs/PocketNAI-局部重绘功能规划书.md](docs/PocketNAI-局部重绘功能规划书.md)（§9 是实施记录）
与技术决策记录第十五节。**实现已经完成，卡在服务端不提供 `infill`**，
因此入口关闭；重新启用前必须先做蒙版约定探针，那条探针与其余 B 类项一样，
**只能由用户手动发起**。详见上面"局部重绘（Inpaint）"一节。

参考图功能（Image2Img / Vibe Transfer / Precise Reference）另有一份待核对清单，
见 [docs/PocketNAI-参考图功能规划书.md](docs/PocketNAI-参考图功能规划书.md) 第 3.4 节：
**阶段 0 的 A 类核对（官方网页版的默认值与最大张数）必须先做完**，那些数值不允许按经验猜。

低成本的协议探针技巧：`GET /ai/generate-image/suggest-tags?prompt=x&model=<模型ID>` 会校验模型 ID（无效返回 400），
可以用它零成本核对模型 ID，不必发起真实生成。该端点现在也是**标签补全**的数据来源
（见技术决策记录第八节）：不消耗 Anlas，但匹配是**包含式**而非严格前缀，建议里可能出现与输入无关的词。
