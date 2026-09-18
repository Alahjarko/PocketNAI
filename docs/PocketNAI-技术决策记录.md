# PocketNAI 技术决策记录

> 状态：实现基线 v0.1
>
> 日期：2026-09-13
>
> 关联文档：[PocketNAI-规划书草案.md](./PocketNAI-规划书草案.md)

规划书第 12 节列出了 12 个尚未定稿的产品决策。为了让实现可以立即开始，这里逐条给出**当前采用的决定、理由，以及哪些属于可回退的临时选择**。

标记说明：

- **已定**：实现已完成，回退需要改代码。
- **临时**：为了不阻塞进度先按某个方向实现，改动成本低，等真实使用反馈后可以调整。
- **待定**：刻意留空，当前实现不依赖它。

---

## 一、规划书第 12 节逐条决定

### 1. 发布形态（个人自用 / 公开 APK / 开源 / 上架）

**待定**。当前脚手架按“个人自用或开源”的前提设计：

- 没有配置 release 签名（`app/build.gradle.kts` 里没有 `signingConfigs`）；
- 没有集成任何分析、崩溃上报或广告 SDK；
- 没有隐私政策页面、没有应用商店元数据。

如果最终要上架，需要额外补：签名配置、隐私政策、数据安全表单、NSFW 内容的商店合规说明。

### 2. Android 最低版本与目标设备

**临时**：`minSdk = 26`（Android 8.0）、`targetSdk = 35`、`compileSdk = 35`。

选择 26 的理由：

- Android Keystore 的 AES/GCM 与 `KeyGenParameterSpec` 从 API 23 起可用，26 有充足余量；
- `java.time` 从 API 26 起原生可用，标题与文件名的时间格式化不需要 desugaring；
- 需要为 Android 9 及以下保留 `WRITE_EXTERNAL_STORAGE` 分支（已在清单中声明 `maxSdkVersion="28"`）。

如果要支持 Android 7 或更低，`java.time` 需要开 core library desugaring，属于明确但可控的改动。

### 3. 是否使用 Jetpack Compose 与视觉方向

**已定**：使用 Compose + Material 3。视觉方向是低饱和靛蓝/紫罗兰配色，见 `ui/theme/Color.kt`。

考虑：生成类应用的界面会长时间被图片占据，因此界面本身刻意保持中性，让作品成为视觉主体，而不是让应用主题抢戏。

### 4. 生成页布局：简洁模式 + 高级抽屉，还是完整参数单页

**临时 → 已改为悬浮层（2026-09-14）**：生成表单现在是一个可上拉下滑的底部悬浮层，
常改项（提示词、负面提示词、模型、Resolution、张数、质量标签、UC 预设）直接可见，
Steps、Guidance、CFG Rescale、Sampler、Noise Schedule、Seed 仍收在“高级参数”折叠区里。

### 5. 瀑布流位置：首页还是独立 Tab

**已改为合并（2026-09-14）**：原本分成“生成 / 画廊 / 设置”三个 Tab，
现在**生成与画廊合并为同一个首页**，底部导航只剩“画廊 / 设置”。

理由：生成时最想看到的就是图片陆续出现在瀑布流里，分成两页会强迫用户来回切换。
合并后生成悬浮层一收起，瀑布流就在眼前；再加上生成开始时自动收起悬浮层，
“点生成 → 看着图出现”中间不需要任何额外操作。

### 5.1 生成按钮的位置：试了三种方案，最后放在悬浮层头部

按钮必须同时满足三个约束，这决定了它只能待在一个地方：

1. 表单滚到任何位置都不能把它滚走；
2. 悬浮层展开和收起两种状态下都要看得见；
3. 不能额外占一条底部栏的高度（用户反馈底部视觉太重）。

**方案 A：悬浮层外的全宽底部固定栏。** 满足 1、2，违反 3 —— 底部叠了
「收起态 118dp + 固定栏约 90dp + 导航栏 80dp」三层，视觉重量过大。

**方案 B：浮在画廊上的自由 FAB（Extended FAB）。** 满足 1、3，但**违反 2**。
实测发现硬伤：悬浮层展开时会完全覆盖它，也就是用户刚编辑完参数、正要生成的那一刻，
按钮消失了。`Material 3` 的 `BottomSheetScaffold` 也没有 `floatingActionButton` 插槽
（那是 M2 的 API），只能自己在内容区用 `Box` 定位，同样会被悬浮层盖住。

**方案 C（当前）：悬浮层头部右侧的圆角胶囊按钮。** 三个约束全部满足：
头部在可滚动表单之外（1），展开收起都在同一位置且不会被自己所在的层盖住（2），
不额外占用任何竖向空间（3）。圆角 + `elevation` 保留“浮起来”的观感。

实现要点：头部那行是 `Row`，左侧 `Column(weight = 1f)` 放标题 / 参数摘要 / 状态行，
右侧放按钮。摘要在放不下时会被省略，所以模型名用 `shortDisplayName`
（去掉 `NovelAI ` 前缀），让「模型 · 尺寸 · 质量标签」能完整显示在一行里。

### 5.2 状态显示：头部第三行

原本的底部固定栏顺带承担了「生成进度 / 错误摘要」的显示。固定栏去掉后，
这些信息移到头部第三行，按优先级只显示一件事：

未连接 → 生成中（`生成中 1/4`）→ 失败原因 → 提示词为空 → 如何收起去看结果

好处是收起态也能看到生成进度与失败原因；完整的服务端错误信息仍在展开后的表单里。

### 6. 后台前台服务维持生成连接

**临时**：首版不做前台服务。

生成在应用进程内进行，超时配置为连接 30s / 读 300s / 写 60s。若进程被系统回收，启动时会通过 `GenerationRepository.cleanupOnStartup()` 把卡在 `Generating` 的任务标记为失败，错误码用 `TIMEOUT_UNCERTAIN`，并明确告知“结果状态不确定”（对应规划书 9.1）。

需要前台服务时应新增一个 `ForegroundService` + 通知渠道，并更新 `AndroidManifest.xml` 与通知权限声明。

### 7. 私有历史的缓存上限、自动清理规则与手动清理入口

**临时**：只提供手动清理入口（设置页“清理孤立临时文件”），不做自动容量上限。

理由：规划书 12 尚未定稿，而“自动删除用户的历史图片”属于不可逆操作，在规则未确定前不应实现。当前已有的清理行为只有两类，都是安全的：

- 清理没有数据库记录的孤立目录与全部中间文件；
- 收尾已完成标记删除的记录。

设置页会显示当前占用字节数。

### 8. 删除采用立即删除加撤销，还是删除前确认

**已定**：两步删除（立即标记 + 5 秒撤销窗口）。

对应规划书 4.4“删除操作应提供短时间撤销机会；撤销期间先标记删除，确认后再清理文件”。实现细节：

- 数据库 `generations.deletedAt` 非空即视为已删除，所有查询都过滤该字段；
- 撤销窗口结束后由仓库执行 `purgeDeleted()` 物理清理；
- 启动时也会收尾，避免“标记已删除但进程被杀”导致孤立文件长期占用空间；
- 详情页的删除是立即彻底删除（用户在详情页已经明确表达意图，且不在列表流里）。

### 9. NSFW 内容在画廊、通知预览与最近任务缩略图中的隐私策略

**待定**。首版未实现任何特殊策略，这是**有意留空**而不是遗漏。

需要处理的位置已经明确，等策略确定后改动点很集中：

- 最近任务缩略图：给 `MainActivity` 加 `FLAG_SECURE`（会同时禁止截图，需要用户知情）；
- 通知预览：只有引入前台服务后才有通知，届时由通知渠道的可见性设置控制；
- 应用内画廊：目前直接显示原图，可选方案是提供“模糊缩略图，点击后显示”。

### 10. Prompt Chunk 与参数预设的导入导出格式

**待定**。第二层能力，首版未实现。

规划书 8.4 已明确约束：Prompt Chunk 与参数预设首版只存本地，不访问 NovelAI 的故事或用户数据同步接口。导出格式建议等实际使用后再定，避免过早固化。

### 11. 深色主题、多语言 UI 与动态颜色

**已定**：深色模式完整可用，并且提供三档手动选择。

- **跟随系统**（默认）：`values-night/themes.xml` + Compose 的 `isSystemInDarkTheme()`；
- **浅色 / 深色**：用户可在设置页固定，覆盖系统设置，存在 `SettingsStore`（键 `theme_mode`）。

已实测：系统切到 night 后应用自动变深；系统保持浅色时把设置改成“深色”，应用同样变深。

顺带修掉一个真实缺陷：`values-night/themes.xml` 里的 `window_background` 原本引用的是浅色值，
深色模式下启动会闪一下白底。现在 `values-night/colors.xml` 覆盖为深色值。

动态取色（Android 12+ 从壁纸取色）默认开启，可在 `PocketNaiTheme(dynamicColor = ...)` 关闭。

**待定**：UI 文案目前只有中文（`res/values/strings.xml`）。因为所有用户可见文案都已集中在该文件、错误文案集中在 `ui/common/ErrorMessages.kt`，新增语言只需要添加 `values-<locale>/strings.xml`。

### 12. 允许用户关闭流式预览

**已定**：设置页提供开关，且实现自动关闭策略。

`SettingsStore` 记录连续失败次数，达到阈值（当前 3 次）后自动关闭未来任务的流式预览，符合规划书 6.3“只关闭未来任务的流式预览，并提示用户当前将使用普通生成”。阈值与“流式协议”本身一样，需要协议探针用真实数据校准。

注意：流式传输本身（第二层能力）**尚未实现**，当前只实现了开关、失败计数与自动关闭策略，以及 `GenerationEvent.Intermediate` 这个接口位。首版走的是普通 ZIP 传输。

---

## 二、规划书未列出的技术选型

### 依赖注入：不使用框架，手写 `AppContainer`

应用只有一条依赖链、一个数据库、一个网络客户端。手写装配（`di/AppContainer.kt`）更容易在阅读时看清全貌，也少一层注解处理器。依赖关系复杂到需要作用域管理时再引入 Hilt。

### 网络：OkHttp + kotlinx.serialization，不引入 Retrofit

NovelAI 的接口只有三个（账户状态、生成、标签建议），其中生成接口需要把响应直接流式落盘而不是先反序列化成对象。Retrofit 的返回值模型在这里帮不上忙，反而要多一层注解。请求体用 `kotlinx.serialization` 的 `JsonObject` 手工构建（`NovelAiRequestBuilder`），这样新增/删除字段时不必改数据类，天然保留了对未知字段的兼容空间（规划书 3.2 的要求）。

工程上**不引入 OkHttp 的 HttpLoggingInterceptor**，避免有人顺手打开 BODY 级别把 Token 或 Prompt 写进日志。日志由 `RedactingHttpLogger` 承担，它只记录方法、路径、状态码与耗时。

### Token 存储：直接用 Android Keystore，不用 security-crypto

`androidx.security:security-crypto` 已停止积极维护，且此前是 alpha 版本。这里直接用 `AndroidKeyStore` 生成 AES-256/GCM 密钥、应用进程拿不到密钥材料，IV 与密文分开存进 `pocketnai_secure` 首选项（`data/security/TokenStore.kt`）。

首选项文件名必须与 `backup_rules.xml` / `data_extraction_rules.xml` 的排除项保持一致，否则 Token 会通过云备份离开设备。

密钥失效（例如用户重设锁屏）时会捕获 `GeneralSecurityException`、清空数据并回到“未连接”状态，而不是崩溃。

### 数据库：Room，扁平列而不是 TypeConverter

`GenerationEntity` 使用扁平的原始类型列（枚举存名字字符串），映射集中在 `data/local/Mappers.kt`。好处是领域模型演进不会悄悄改变数据库结构——改列必须显式改实体和迁移脚本。

schema 导出到 `app/schemas`，后续升级必须写显式迁移，不用破坏性迁移回避问题。

### 网络返回：只用 ZIP，不解析 JSON/Base64 分支

规划书 6.2 已定“ZIP 只作为网络传输容器”。实现上更进一步：如果响应 `Content-Type` 是 JSON，**直接判定失败**，而不是尝试把它当图片写进历史目录（`OkHttpNovelAiApi.generateImage`）。这样一旦官方改变返回形式，会得到明确的错误而不是一批损坏的历史记录。

### 不自动重试

OkHttp 客户端设置了 `retryOnConnectionFailure(false)`，仓库层也没有任何重试逻辑。对应规划书 9.2“T2I POST 请求默认零次自动重试”。超时与断流统一映射为 `TIMEOUT_UNCERTAIN`，界面明确说明服务端可能已经接受任务。

---

## 三、已在真实账户上核对的结论

### 3.1 接口主机已迁移（2026-09-14 实测）

这是首版上线前发现并修掉的**阻断性**问题，记录在此以免重犯。

用真实的 Persistent API Token 请求 `GET https://api.novelai.net/user/data`，得到：

```
HTTP 400
{"statusCode":400,"message":"Please refresh NovelAI.net. If using a third-party tool, update to the image URL."}
```

结论：**`api.novelai.net` 不再接受第三方工具的 Persistent API Token**，账户状态与图像生成都必须走 `image.novelai.net`。同一 Token 请求 `GET https://image.novelai.net/user/data` 返回 `HTTP 200`。

对应的实现改动：

- `NovelAI_API_BASE_URL` 收敛为唯一主机 `https://image.novelai.net`，不再保留 `api.novelai.net`，避免以后有人再往错的主机上发请求；
- 新增 `ErrorCode.CLIENT_UPDATE_REQUIRED`：NovelAI 在接口迁移时会用 400 明确要求客户端更新，如果归类成 `INVALID_PARAMS`（“请求参数无效”），排查方向会被彻底带偏。现在这类响应会直接提示“需要更新客户端”。

### 3.2 `GET /user/data` 的真实响应结构

```json
{
  "priority": { "maxPriorityActions": 0, "taskPriority": 0 },
  "subscription": {
    "tier": 0, "active": false, "accountType": 0, "expiresAt": 0, "isGracePeriod": false,
    "perks": { "...": 0 }, "trainingStepsLeft": { "...": 0 }
  },
  "information": { "emailVerified": true, "trialImagesLeft": 0, "loginMethod": "sso" }
}
```

要点：

1. `tier` **嵌在 `subscription` 下，且是整数**，不是顶层字符串。初版按顶层字符串解析，在真机上拿不到任何订阅信息。
2. 响应里**没有 Anlas 字段**，因此首版不展示额度 —— 这也与规划书 2.3「暂不计算 Anlas」一致。
3. ⚠️ **不能用 `tier == 0 && active == false` 判断“不能生成图片”。** 按量购买 Anlas 但不订阅的账户，这里同样是 `tier 0 / active false`，却能正常生成。初版据此在连接页显示红色告警，是误报（真实用户反馈）。额度不足应在真正提交生成、服务端返回 402 时由 `INSUFFICIENT_ANLAS` 如实告知。

### 3.3 已确认可用的端点

| 端点 | 结果 |
|---|---|
| `GET https://image.novelai.net/user/data` | 200，结构见上 |
| `GET https://image.novelai.net/user/subscription` | 200，`tier` 在顶层 |
| `GET https://image.novelai.net/ai/generate-image/suggest-tags?prompt=&model=` | 200，`{"tags":[{"tag":"...","count":N,"confidence":F}]}` |
| 无效 Token 请求任意上述端点 | 401 `{"statusCode":401,"message":"Unauthorized"}` |

标签建议的响应是对象数组（`{"tag": ...}`），现有解析器已兼容，无需改动。

### 3.4 四个模型 ID 已被服务端证实有效

`GET /ai/generate-image/suggest-tags` 会**校验 `model` 参数**（无效模型返回 400），因此可以零成本地核对模型 ID：

| `model` 参数 | 结果 |
|---|---|
| `nai-diffusion-4-5-curated` | 200 |
| `nai-diffusion-4-5-full` | 200 |
| `nai-diffusion-5-curated` | 200 |
| `nai-diffusion-5-full` | 200 |
| `totally-bogus-model-xyz`（对照组） | 400 |

结论：规划书 2.1 指定的四个模型 ID 全部正确，V5 模型确实可用。这也提供了一个以后可以复用的低成本协议探针：**改模型 ID 之后先用 suggest-tags 验证，不必发起真实生成。**

### 3.5 质量标签是“追加到提示词末尾的文本”，不是开关

来自用户从官方网页版截取的界面（2026-09-14）。官方 Quality Tags 下拉有三档，鼠标悬停会明确显示追加内容：

| 档位 | 追加到提示词末尾的文本 |
|---|---|
| `Standard` | `very aesthetic, masterpiece, no text` |
| `Light` | `very aesthetic, amazing quality, no text` |
| `None` | 不追加 |

**这修正了初版的理解。** 初版以为质量标签由 API 的布尔字段 `qualityToggle` 控制，并把 `QualityTagsRule.positiveSuffix` 留空占位 —— 方向是错的：官方 UI 是把文本拼进提示词再提交。

对应实现：

- `QualityTagsOption` 枚举携带 `appendedText`，`applyQualityTags()` 负责拼接（纯函数，有单元测试）；
- `NovelAiRequestBuilder` 把结果同时写进 `input` 与 `v4_prompt.caption.base_caption`；
- **`qualityToggle` 固定发 `false`** —— 质量标签既然由客户端显式加入提示词，就必须关掉服务端的自动追加，否则同一段文本可能被叠加两次。

⚠️ 最后一条是基于“官方既然在客户端拼接，服务端就不再负责”的推理，**尚未用真实生成结果反查确认**。验证方法：用 `Standard` 生成一张图，然后在详情页看该记录的提示词尾部是否只出现一次 `very aesthetic, masterpiece, no text`。

### 3.6 官方默认参数（已核对）

从官方网页版新建任务读到并固化：

- `Steps = 23`
- `Prompt Guidance = 7.0`
- 质量标签默认 `Standard`
- Resolution 采用「档位 × 横竖方」二维结构，`Normal` 档位：横 `1216 × 832`、竖 `832 × 1216`、方 `1024 × 1024`

### 3.7 仍需用真实账户核对的项目（规划书 6.3 协议探针）

以下数值与结构仍是**按公开 API 语义整理的待核对基线**，代码里以 `ModelCatalog.CONFIG_VERSION`（当前 `2026-09-14-qtag-resolution`）标记。

1. Resolution `Large` 档位的具体像素组合（当前按 Normal 的比例放大为 1024×1536 / 1536×1024 / 1536×1536）。
2. V5 的默认档位与方向（当前默认 Normal 方图 1024×1024）。
3. Sampler × Noise Schedule 组合表，特别是：
   - DPM++ 2S Ancestral 是否真的不支持 Karras；
   - Polyexponential 对哪些采样器有效；
   - DDIM 是否只支持 Native。
4. 请求体字段集合（`NovelAiRequestBuilder`）：
   - `params_version` 是否仍为 3；
   - `ucPreset` 的取值域；
   - 是否需要 `dynamic_thresholding`、`legacy`、`controlnet_strength`、`add_original_image` 等兼容字段；
   - `v4_negative_prompt` 是否确实需要 `legacy_uc`；
   - `qualityToggle` 固定为 false 是否会与客户端的提示词追加冲突（见 3.5）。
5. Prompt Guidance 与 CFG Rescale 的合法区间（当前取 0–10 与 0–1）。
6. 生成响应 ZIP 内部 entry 的命名与顺序（当前按 entry 顺序编号，不依赖名称）。
7. 流式端点尚未验证；`StreamingGenerationTransport` 接口位已留出。
8. 部分模型的可用性受订阅等级限制（例如 Full 变体可能要求更高等级订阅）。
   首次生成建议从 V4.5 Curated 开始，遇到订阅类错误会映射为 `MODEL_UNAVAILABLE`。

探针输出只能包含脱敏后的字段结构、类型、事件顺序与状态码，**不得记录 Authorization、完整 Prompt 或图片内容**。

用真实账户发起**生成**请求会消耗 Anlas，因此不应由自动化流程代跑，需由用户本人点击触发。

---

## 四、收藏提示词（规划书 8.4 的 Prompt Chunk）

规划书第二层列了「本地 Prompt Chunks / 提示词片段」，并把要求写成
「名称、内容、分组、搜索和插入」。实现时的几个取舍：

### 4.1 一个收藏夹，两种粒度，靠选中状态区分

用户要的是两种粒度都要：既能存整条配方，也能把长提示词里的片段拆出来复用。
但**不为它们做两个入口** —— 那样用户每次点收藏前都得先想清楚"这算哪种"。

提示词框右上角只有一个书签按钮：

- 有选中文字 → 存成**标签**（`PromptFavoriteKind.TAG`），名称默认就是选中内容；
- 没有选中 → 存成**整条提示词**（`PROMPT`），名称默认由 `PromptTitle` 提取。

代价是两个输入框都必须用 `TextFieldValue` 而不是普通字符串 —— 只有它带选区信息，
而这正是判断依据。负面提示词也同样处理，否则两个长得一样的框行为不一致。

### 4.2 填入默认追加，覆盖类操作收进菜单

点一行就是「追加到末尾」，「替换整条」和「删除」放在右侧 ⋮ 菜单里。

理由是这两种操作的代价不对称：追加不会毁掉用户已经写好的内容，而替换会。
所以把最省事的手势给追加，把会破坏内容的操作放在需要多点一下的位置。

### 4.3 拼接规则只允许存在一份

收藏填入与质量标签追加都调用 `PromptComposition.append`。

初版给质量标签单独写了一份拼接逻辑，再多一份收藏的拼接，就会出现两套"空字符串怎么办"
"要不要 trim"的判断 —— 最终表现为"有时开头多一个逗号"这类难查的小毛病。
现在只有一处规则，`PromptCompositionTest` 同时保护两条功能路径。

### 4.4 数据库 v2 → v3 用新增表，不触碰既有表

收藏是独立的新表 `prompt_favorites`，迁移里只有一条 `CREATE TABLE`。

这比 v1→v2 安全得多（当时要给既有表加列），但仍然必须让**列名、类型、NOT NULL、
主键与索引**与 Room 的期望完全一致，否则打开数据库时的 schema 校验会抛异常。
因此这里刻意**不建索引**：为几十到几百行数据建索引，收益很小，
却要多维护"索引名必须与 Room 生成的名字一致"这条约束。

### 4.5 字段与排序

`lastUsedAt` 是特意加的：列表按"最近用过优先"排序，常用收藏会稳定停在前几位，
这样"快速填入"才真的快。`category` 支持规划书里的「分组」，留空即归入「未分组」。

### 4.6 本轮未做

- **重命名**：仓库层一度写了 `rename`，但没有界面入口，属于死代码，已删除。
  要加的话是在 ⋮ 菜单里补一项、复用保存对话框的命名表单。
- **参数预设**：规划书 8.4 的另一半（保存模型 + 参数组合，切换模型时做兼容性校验）。
  数据模型与收藏不同（要存整套 `GenerationParams`），单独做更清楚。

---

---

## 五、记住上次的生成参数

用户提出希望重新打开应用时不用再把模型、尺寸、Steps 等重调一遍。

### 5.1 保存范围包含提示词

用户列举的是"参数"，但实现里把**正向 / 负向提示词文本也一起保存**了 ——
重新打开时最费事的其实是重打提示词，参数只是顺手调，只存参数等于没解决主要麻烦。

提示词因此会落到本地首选项文件。这与历史记录本来就会保存提示词是一致的，
Token 依旧只存在 Android Keystore 保护的那份存储里，与草稿文件无关。
如果用户希望提示词不要被记住，改动点只有 `GenerationDraft` 的两个字段与 `GenerationDraftCodec`。

### 5.2 用独立的 `GenerationDraft` 类型，不复用 `Generation`

`Generation` 是**已经提交过**的历史记录，一旦写入不可变；
草稿是还没提交、每次改动都覆盖的状态。两者字段高度重合，但共用类型会出现
"改草稿顺手把历史也改了"这类事故，所以刻意分开，用 `GenerationDraftCodec` 做转换。

### 5.3 恢复必须能逐字段降级

草稿是跨版本、跨机型的持久数据：上次保存的模型 id 可能已不存在，
数值可能因为默认值调整而越界。所以 `GenerationDraftCodec.decode` 遵循：

- **不抛异常、也不整体丢弃**。字段级损坏只影响那个字段：
  不认识的模型 id 退回默认模型，不认识的采样器 / 调度用该模型默认值，
  无效的 `ucPreset` 序号回退默认；
- 越界数值、不支持的采样器组合、非法尺寸统一交给
  `ModelProfile.normalize`（它本来就是参数合法性的唯一出口）；
- 只有整串 JSON 无法解析时才返回 `null`，让调用方回到出厂默认。

这组降级行为有 11 个单元测试覆盖，比"能存能读"的往返测试重要得多。

### 5.4 写入用防抖

拖动 Steps / Guidance 滑杆会连续产生几十次状态变化，逐次写首选项既无必要也影响手感。
`GenerateViewModel` 对状态流做 `distinctUntilChanged` + `debounce(600ms)` 后落盘。
时间取 600ms：太长会在进程被杀时丢掉最后的改动，太短则拖滑杆时频繁写盘。

### 5.5 维护提醒

`GenerationDraftCodec` 的 DTO 是**显式字段列表**。给 `GenerationParams` 新增字段时
必须同步更新它，否则新字段不会被记住（而且不会有编译错误提醒）。
```

---

## 六、双认证模式实现记录（按《双认证模式实施计划》分阶段推进）

计划书把工作切成 A–F 六个阶段。这里记录已完成的阶段与其中踩到的坑。

### 6.1 阶段 A：Access Key 派生（已完成并验证）

新增 `domain/auth/NovelAiAccessKeyDeriver`，用 Bouncy Castle 的 `Blake2bDigest` +
`Argon2BytesGenerator` 实现，依赖固定在 `gradle/libs.versions.toml`（`bcprov-jdk18on:1.78.1`，
Bouncy Castle Licence）。直接实例化原语，不注册也不替换系统 Provider。

`domain/auth` 保持纯 Kotlin：只依赖 kotlinx.coroutines、Bouncy Castle 与 `java.util.Base64`，
不引用任何 Android API，因此能在 JVM 单元测试里逐字节断言。

**测试向量是独立生成的**：逐字提取 Aedial 参考实现（`novelai_api/utils.py` 的
`argon_hash` / `get_access_key`）并用 argon2-cffi 执行，输入全部是虚构账号，
输入与预期结果固化进 `NovelAiAccessKeyDeriverTest`（13 个向量）。
参考实现走 C 实现的 argon2，本仓库走纯 Java 的 Bouncy Castle —— 两条独立路径一致才有意义。

#### 坑一：Bouncy Castle 的 `Blake2bDigest(int)` 收的是比特，不是字节

第一轮 13 个向量**全部**失败，输出形如 `1ce30000000000000000000000000000`
—— 只有前 2 个字节有值。原因是 `Blake2bDigest(16)` 里的 16 是 **16 比特**（BC 文档原文
"Basic sized constructor - size in bits"），得到 2 字节摘要，其余字节保持为零。
正确写法是 `Blake2bDigest(16 * 8)`。

排查方式值得保留：`DerivationDiagnosticTest` 把 preSalt、BLAKE2b 的 salt、Argon2 的原始输出
分三步与参考实现对账。它立刻指出"Argon2 用参考 salt 算出的结果是对的，只有 BLAKE2b 错了"，
把范围从"整个算法"缩到一行代码。这个用例已保留。

#### 坑二：前 6 个字符必须按 Unicode 码位切，不能用 `take(6)`

参考实现是 Python，`password[:6]` 数的是**码位**；Kotlin 的 `take(6)` 数的是
**UTF-16 单元**。密码前 6 个字符里出现 BMP 之外的字符（emoji 等代理对）时两者结果不同，
派生出的 Access Key 会完全不一样。

例：密码 `🔑secret123`，参考实现取到 `🔑secre`（6 个码位），`take(6)` 只会取到 `🔑secr`。

因此实现里显式按码位截取，并留下 `emoji-leading` / `emoji-inside` 两个向量专门钉住它
—— 如果哪天有人改回 `take(6)`，其余向量仍然通过，只有这两个会失败。

⚠️ **仍待确认**：官方网页版是 JS，JS 的 `substring` 按 UTF-16 单元计数。
也就是说"密码前 6 个字符含 emoji"这种账号上，参考实现与官方前端可能并不一致。
当前按计划书要求与参考实现对齐，这一项需要用户用真实账号（且密码前 6 位含非 BMP 字符）
手动验证；不影响普通密码。

### 6.2 阶段 B：登录协议层（已完成并验证）

新增 `NovelAiAuthApi` / `OkHttpNovelAiAuthApi` / `NovelAiAuthErrorMapper`，
与图片生成的 `NovelAiApi` **完全分离** —— 认证是可拆除、可降级的适配器。

- 请求：`POST ${baseUrl}/user/login`，体里**只有 `key`**，不带 `Authorization`、
  不带邮箱、不带任何设备标识；零次自动重试。
- 响应：严格解析 `accessToken`（必须存在且是非空字符串），
  读取上限 64 KiB，超限直接按协议错误处理。
- 登录专用 OkHttpClient：从基客户端派生，但 `callTimeout` 收紧到 60 秒 ——
  生成链路需要 5 分钟读取等待，登录不该继承它。

#### 为什么登录不能复用生成错误的映射器

生成接口把 `401 || 403` 都当作"凭据失效"。登录接口不一样：`401` 是"邮箱或密码不对"，
`403` 更可能是风控或需要人机验证。混用会让用户看到"Token 无效"这种完全误导的提示。
`NovelAiAuthErrorMapperTest` 里有两个用例专门断言同一种情况在两套映射下得到不同结论。

超时同理：登录用 `REQUEST_TIMEOUT`，**不复用** `TIMEOUT_UNCERTAIN` ——
后者带着"服务端可能已经接受并计费"的含义，只适用于图片生成。

#### 错误明细只保留状态码

请求体里有 Access Key，响应体里有 Access Token，而错误明细会落进数据库并显示在界面上。
因此登录失败时 `detail` 只写 `HTTP <status>`，不保存任何响应体内容
（有用例断言"响应体里的内容不会出现在 detail 里"）。

#### 坑三：Okio 的 `readByteArray(n)` 不足 n 字节会抛异常

有界读取最初写成 `source.readByteArray(MAX + 1)`，误以为它会读到 EOF。
实际它在字节数不足时抛 `EOFException`，于是**正常长度的响应全被当成网络错误**，
只有"超过上限"的用例反而通过 —— 这个失败模式很容易误判成"映射器写错了"。
正确做法是逐块 `source.read(buffer, remaining)` 直到返回 -1。

### 6.3 阶段 C：凭据存储兼容（已完成并验证）

`TokenStore` 替换为 `CredentialStore` / `KeystoreCredentialStore`，
引入 `CredentialType`（`PERSISTENT_API_TOKEN` / `ACCOUNT_SESSION`）与 `StoredCredential`。

**加密格式与存储位置一个字都没改**，这是"旧 PST 无需重新输入"的前提：

| 项目 | 值 | 是否改动 |
|---|---|---|
| 首选项文件 | `pocketnai_secure` | 未改 |
| Keystore alias | `pocketnai_token_key` | 未改 |
| 密文键 | `token_iv` / `token_ciphertext` | 未改 |
| 备份排除规则 | `res/xml/*` | 未改 |
| 新增元数据 | `credential_type` / `credential_created_at` | 新增，非敏感 |

旧安装的密文存在但没有 `credential_type`，按 `PERSISTENT_API_TOKEN` 解释；
元数据损坏时同样按 PST 降级——凭据本身能解密就继续可用，不打扰用户去重新输入。

**没有数据库迁移**：本次改造不碰 Room，`generations` 与 `prompt_favorites` 结构不变。

#### 设备验证

模拟器上原本保存着一个旧格式凭据（只有 `token_ciphertext` / `token_iv` /
`token_hint_length` / `token_hint_fingerprint` 四个键，没有类型元数据）。
升级安装后应用仍显示"已连接"，连接卡片的指纹与改造前**完全一致**（`742744f9`），
说明旧密文仍可解密、类型降级正确、Token 字节未变。

### 6.4 阶段 D：登录状态机（已完成并验证）

`ConnectViewModel` 重写为两条并存的路径，`AuthMode` 区分 PST 与账号登录。
11 个单元测试覆盖计划书列出的全部必测项（保存类型、失败不保存、登录失败不调 `/user/data`、
复验失败不保存、重复点击只发一次、登录后清空密码、切换模式清密码与错误、断开只清凭据）。

#### 提交保护必须同步置位

`submitting` 最初是在协程内部置位的。这样依赖"`viewModelScope` 会立即开始执行"这一
实现细节 —— 在 `StandardTestDispatcher` 下（以及任何不立即调度的场景）连点两次会让
两个协程都起步，重复发出登录请求。现在提交标志在**点击的同一时刻同步置位**，
保护与点击严格同刻生效，也让这条测试变得确定而不是碰运气。

#### 登录流程严格按计划书 §6.2 的顺序

```
派生 Access Key → /user/login → 用返回的 Token 调 /user/data 复验 → 才保存
```
`/user/data` 失败时**不保存**（避免把"部分可用"的响应当成已连接）、**不重发登录**、
保留邮箱方便重试但清空密码，并给出专门的 `ConnectNotice.SESSION_OBTAINED_BUT_UNVERIFIED` 提示。

敏感值清理放在 `finally`，成功、失败与协程取消都会执行。需要承认：Kotlin 的 `String`
不可变、无法主动擦除，"清空"只能解除引用并清掉界面状态，不承诺内存绝对无残留。

### 6.5 阶段 E：双入口界面（已完成并验证）

连接页用两个 FilterChip 切换 `Persistent Token`（默认）与 `账号登录 · 实验`。
账号表单含邮箱、遮挡的密码与显示切换、登录按钮，以及"登录失败？改用 Persistent Token"退路；
固定说明文案按计划书 §7.3 原文给出。

密码的生命周期处理：
- 默认遮挡；
- 应用进入后台（`ON_PAUSE`）与离开页面（`onDispose`）都会清空密码输入；
- 不放进导航参数、不写 `SavedStateHandle`、不落任何偏好设置。

**屏幕旋转会清空密码** —— 这是有意的选择（安全优先，让用户重输一次），
而不是把密码留在状态里跨配置变更存活。计划书 §7.3 要求这一点必须明确选择，这里选的是清空。

设备验证（截图核对）：1080×1920 与 1920×1080 都能正常操作且可滚动；
密码输入后显示为圆点；切到后台再回来密码框已清空而邮箱保留；
Logcat 中邮箱、密码、`accessToken` 等关键字命中 **0 处**。

### 6.6 阶段 F：按凭据类型区分失效引导（已完成）

底层仍然只产出 `ErrorCode.TOKEN_INVALID`，由界面层结合 `SessionState.credentialType`
选择文案 —— 认证类型没有耦合进通用 HTTP 错误映射器：

| 凭据类型 | 提示 |
|---|---|
| `ACCOUNT_SESSION` | 账号会话已失效，请重新登录 NovelAI 账号 |
| `PERSISTENT_API_TOKEN` | Persistent Token 无效、已被覆盖或已撤销，请重新获取 |
| 未知 | 中性的通用文案，不猜 |

`SessionState` 因此扩展为同时持有"是否已连接"与"凭据类型"。生成页的错误卡片在
凭据失效时会多出一个"去连接"入口；**不自动登录、不自动刷新、不重发生成请求**。

### 6.7 六个阶段的状态

A–F 全部完成。**账号登录仍是实验性功能**，且官方当前推荐的第三方接入方式依然是 PST ——
界面把 PST 标为默认与"稳定方式"，账号登录标为"实验"，两者都可以随时切换，PST 始终可用。

真实账号验证按计划书 §12.4 只能由用户在已安装的 APK 里手动完成；
编码助手不读取 `persistent-api-token.txt`、不代发登录请求、不代发任何消耗 Anlas 的生成请求。

### 6.8 已知不适用场景：SSO（Google 等第三方登录）账号

**结论：这类账号请使用 Persistent Token，账号登录不适用。**

依据：

- 用户的账号在 `/user/data` 里返回 `information.loginMethod = "sso"`，
  说明它是通过 Google 等第三方方式注册的，通常没有 NovelAI 密码。
  而没有密码，就没有可供本地派生的输入，整条 `邮箱 + 密码 → Access Key` 的链路无从谈起。
- 计划书 §2.2 已明确排除"Google SSO、OAuth、WebView 登录或 Cookie 导入"；
  §12.4 也写明：遇到 SSO 应记录为"账号登录不适用，PST 回退正常"，不继续逆向 SSO。
- 这不是能力不足，而是有意的边界：逆向 SSO 需要接管浏览器会话或 Cookie，
  既超出范围，也不符合计划书的安全红线。

界面上的处理（都已截图核对）：

1. 账号表单顶部**先给出提示**，而不是等用户失败一次再解释：
   "用 Google 等第三方方式登录的账号可能没有 NovelAI 密码，这类账号请改用 Persistent Token。"
2. PST 表单新增"打开 NovelAI 网页"按钮（计划书 §7.2 明确允许：只打开官方网页，
   不抓 Cookie、不代取 Token），并提示在账户设置里点 `Get Persistent API Token`。
3. 已连接卡片在 `loginMethod == "sso"` 时显示"检测到该账号使用第三方登录（SSO）"。
   `loginMethod` 只读不推断：字段缺失时为 null，绝不因为缺失就把正常密码账号当成 SSO 去误导用户
   （有用例守住这条）。

**没有做的事**：没有用真实账号尝试过登录。真实账号验证按计划书 §12.4 只能由用户手动完成，
而且对 SSO 账号而言试也是白试——缺的是密码，不是协议。

---

## 七、关于"内嵌 WebView 走 Google SSO 并读取会话"的可行性评估

**结论：不可行，且不予实现。** 这一节记录评估依据，避免以后重复论证。

### 7.1 需求

提出过一个设想：在 PocketNAI 内嵌 NovelAI 网页 → 用户在 WebView 里点 Google 登录 →
由 PocketNAI 读取 Cookie / Local Storage / 网页 Access Token。

### 7.2 阻断性依据一：Google 明文禁止这种用法

Google OAuth 2.0 政策（`developers.google.com/identity/protocols/oauth2/policies`，
2026-09-14 取回）原文：

> **Use secure browsers**
> A developer must not direct a Google OAuth 2.0 authorization request to an embedded
> user-agent under the developer's control. Embedded user-agents include, but are not
> limited to, software libraries that allow a developer to insert arbitrary scripts,
> alter the default routing of a request to the Google OAuth server, **or access session
> cookies**.

这条政策把设想的三个要点**逐条点名禁止**：受开发者控制的嵌入式 user-agent、
可注入任意脚本的容器、读取会话 Cookie。而且这不只是纸面规定——Google 长期在
WebView 里直接拒绝登录（`disallowed_useragent`），第二步"点 Google 登录"就走不通。

唯一能让它在 WebView 里跑通的办法是把 User-Agent 伪装成普通浏览器，
即**绕过 Google 的安全控制**。这不在可接受范围内。

### 7.3 阻断性依据二：与项目自己的规则冲突

- 《双认证模式实施计划》§2.2 明确排除"Google SSO、OAuth、WebView 登录或 Cookie 导入"；
- §9 安全红线与 §12.4 写明**编码助手不得**"从浏览器抓取 Cookie、Local Storage 或 Token"；
- §15 暂停条件 #4 与 #10 覆盖了"需要无法在安全范围内支持的验证"与"转发到第三方"。

当初写下这些排除项，理由不是"做不到"，而是这样做需要接管用户浏览器会话，
把凭据安全标准拉低到整个计划书都在避免的水平。现在没有任何新论据推翻它。

### 7.4 附带依据：NovelAI 服务条款

§9.1.5 "Circumvent any access or use restrictions put into place to prevent certain uses
of the Services"；§9.1.6 禁止对服务造成过度压力的自动化系统。
条款并未为这种接入方式提供许可，官方文档也仍要求第三方应用使用 Persistent API Token。

### 7.5 更关键的一点：这个需求本身不成立

提出该设想的动机是"我用 Google 账号登录，所以没有密码，账号登录走不通"。
但 PST 的存在**恰恰不依赖密码**，而且已经有实证：

- 用户提供的 Persistent API Token 对 `image.novelai.net/user/data` 返回 **HTTP 200**；
- 同一个响应的 `information.loginMethod` 为 **`sso`**；
- 该 Token 已实际用于生成图片。

也就是说：**这是一个 SSO 账号，它已经有可用的 Persistent API Token。**
"SSO 账号用不了 PST"这个前提是错的，因此整套 WebView 方案要去解决的问题并不存在。

真正可能需要的一步只是"从官网把 Token 复制过来"，而这一步已经由连接页的
"打开 NovelAI 网页"按钮覆盖（计划书 §7.2 明确允许：只打开官方页面，
不抓 Cookie、不代取 Token）。

### 7.6 如果确实想让账号登录工作

唯一可能且合规的路径是**让该账号拥有 NovelAI 密码**：官方文档没有说明 SSO 账号能否设置密码，
因此不做承诺。用户可以自己在官网账户设置里确认有没有这个选项；
若有，`邮箱 + 密码` 那条链路即可使用（且仍属实验功能）。

### 7.7 顺带：本次为实验建立的还原点

实验前先把当前版本存成了正式的版本还原点：

- 本目录原先不是 git 仓库，已 `git init`；
- 首次提交 `7c612b8`，标签 `v0.1.0-baseline`，121 个文件；
- `.gitignore` 已排除 `persistent-api-token.txt`、`local.properties`、`.tooling/`、构建产物，
  并已核对版本库中**从未**包含凭据文件；
- git 身份设为本仓库局部（`PocketNAI Dev <dev@pocketnai.local>`），未改全局配置 ——
  若要推送到远端请先改成你自己的身份。

---

## 八、标签补全（规划书 8.1 的标签建议）

需求来自用户的真实使用体验：官方网页版在输入一个词时会跳出 5 条可点的建议，
能省下大量拼写与记标签名的功夫。

### 8.1 这个功能其实只缺界面

`NovelAiApi.suggestTags()` 早已实现，当初是当**零成本的模型 ID 探针**用的
（见 3.4：`suggest-tags` 会校验 `model` 参数，无效返回 400）。它不消耗 Anlas，
所以把它接成补全不需要任何新的协议工作。本轮补的是三件事：
取"当前标签"的纯文本逻辑、防抖的请求调度、以及界面。

### 8.2 只查"光标所在的那一个标签"

请求参数 `prompt` 传的是光标所在标签的文本（如 `blue ey`），不是整条提示词。

实测（2026-09-14，MuMu 模拟器 + 真实账户）：服务端做的是**包含式匹配而非严格前缀**——
输入 `1g` 返回了 `1girl`、`gigabyte`、`mg mg`、`5pb`。因此建议里偶尔会混进与输入无关的词，
这是服务端的排序结果，客户端不做二次过滤（过滤掉只会让可选建议更少）。

### 8.3 定位与替换单独抽成纯函数

`domain/prompt/PromptTagEditing` 负责两件事：找出光标所在标签的范围，以及把建议填进去。
抽出来的理由是这个逻辑直接改用户已经写好的提示词，"改坏"的代价很高，
而它又是纯字符串处理 —— 放在 `domain` 里就能用普通 JVM 测试逐条覆盖边界
（光标停在标签中间、停在逗号上、停在逗号后的空格上、换行分隔、末尾只剩空白……）。

两条刻意的规则：

| 情况 | 行为 | 原因 |
|---|---|---|
| 标签在末尾 | 填入后补 `", "` | 可以直接接着打下一条；末尾的空白会被这个逗号顶掉 |
| 标签后面还有内容 | 原样保留，不补逗号 | 补了会变成 `", ,"` |
| 光标停在标签中间 | 整段替换 | 只换前半截会与原有后半截拼成不存在的词 |

### 8.4 失败必须静默，且建议只在"对得上"时才显示

补全是第二层能力（规划书 8.1），失败不允许影响生成，所以
`TagSuggestionSource` 的契约就写明"实现必须自己吞掉失败并返回空列表"，
`NovelAiTagSuggestionSource` 在任何失败下都返回空列表，界面也没有为它准备任何错误态。

状态里同时保存 `suggestions` 与 `suggestionQuery`（这批建议对应哪个片段）。
界面拿 `suggestionQuery` 和当前光标所在的标签比对，不一致就不显示 ——
请求在途时用户又改了字，那批建议已经过期，宁可空着也不能让用户点到一个
跟自己刚打的字对不上的建议。

界面侧还有一个"刚填入的建议不重复建议"的记录：填入后光标正停在该标签末尾，
片段恰好等于建议本身，不拦住会立刻又弹出同一批。

### 8.5 已知不适用 / 未做

1. **只作用于正向提示词**。负向提示词（Undesired Content）不触发。
   服务端这个端点只接受一个 `prompt` 参数，没有区分正负向的语义；
   在负向框里显示按正向词表来的建议，帮助有限而且容易误导。
2. **不做本地标签库**。建议全部来自服务端，因此不存在"词表过期"的维护问题，
   代价是离线时没有建议 —— 符合补全的定位。
3. **服务端匹配是包含式的**（见 8.2），会混入不相关的词；未做二次过滤。
4. **`count` / `confidence` 被丢弃**。响应里带这两个字段，当前只取 `tag` 文本，
   展示顺序沿用服务端顺序。若以后想按热度排序或显示权重，数据已经在响应里，不需要改协议。


---

## 九、参考图功能的存储与图片管线（规划书阶段 A）

《参考图功能规划书》的阶段 A 只做地基：图片几何计算、参考图的存储形状、数据库 v3 → v4。
这一段没有界面，因此这里记录的是**形状决策**——它们会决定后面三个阶段好不好写。

### 9.1 参考图独立成表，不展平进 `generations`

`generations` 的每一列都对应 `GenerationParams` 的一个字段，`Mappers` 靠这个一一对应关系读写。
参考图是数量可变、可选、带逐条参数的列表，展平进去会让父表列数随功能增长而膨胀，
也表达不了"挂了几张"。因此新增 `reference_images` 表，与 `generated_images` 同一套路子。

`Generation` 领域模型多了一个 `references` 字段，但**仓库层不 join 它**：
画廊与历史那两条查询已被瀑布流依赖，加 join 会让"一张图对应多行"的语义无处安放。
需要参考图的只有详情页与"复用参数"，按需查一次即可。

### 9.2 `reference_images` 用 `ON DELETE CASCADE`，但文件不跟着删

行会随生成记录级联删除，**文件不会**。原因是文件是内容寻址的：
`files/references/<sha256>.png`，同一张图被多次使用时只占一份磁盘，
因此一条历史被删掉时，那个文件很可能还有别的历史在引用。

回收责任因此落在启动清理上：`cleanupOrphans(knownGenerationIds, referencedRelativePaths)`，
存活集合来自 `GenerationDao.allReferencePaths()`。这里有一个必须守住的纪律 ——
**存活集合必须是完整查询结果**，漏一条就会误删用户还在用的参考图，
表现为下次打开那条历史时提示"参考图已不在本机"。

这个设计比"按生成记录分目录"多了一层间接，但避免了磁盘翻倍：
用户反复用同一张喜欢的图做起点是很自然的用法。

### 9.3 `generations.mode` 用可空列 + UPDATE 回填

新增这一列时有两个选择：带 `DEFAULT 'TXT2IMG'`，或者不带默认值再 `UPDATE` 回填。
选了后者，因为 Room 会把实体上声明的默认值与 SQLite 里记录的真实默认值做比对，
多一个 `DEFAULT` 就多一处可能对不上的地方。AGENTS.md 里"新增字段声明成可空"
这条约定就是为此写的（`qualityTags` 是同一个先例）。

配套的读取降级在 `GenerationMode.fromNameOrDefault`：读不出来的模式一律按 `TXT2IMG`。
这个方向是唯一安全的 —— 加这个功能之前的记录本来就全是纯文生图。

### 9.4 图片几何计算单独抽成纯函数

`domain/image/ImageGeometry` 是纯 Kotlin，不依赖 Android，因此可以在 JVM 测试里逐条断言。
抽出来的理由：算错的结果不会崩溃，而是**提交一个服务端拒绝的请求**
（边长不是 64 的倍数、总像素超限、图与请求里的 width/height 不一致），
在真机上只表现为一句笼统的"参数无效"。

写这块时当场抓到一个自己的 bug：`downsampleFactor` 的判据方向写反了 ——
写成"除以倍数后仍不小于上限"，会让 8000px 的图算出倍数 1，解码时直接 OOM。
正确判据是"除以倍数后**不超过**上限"。测试里留了一条专门盯这个方向的用例。

### 9.5 验证方式：抓真实数据库快照做前后对比

阶段 A 的迁移必须在**有真实历史数据**的库上验证，而不是只在空库上跑通。
做法是先 `adb exec-out run-as` 把 `pocketnai.db` 连 `-wal` / `-shm` 一起抓下来，
用 Python 的 `sqlite3` 读；装上新版本、启动应用后再抓一份对比。

2026-09-14 在 MuMu（`127.0.0.1:5559`）与 `emulator-5558` 上各验证一次：

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| `user_version` | 3 | 4 |
| `generations` / `generated_images` | 7 / 7 | 7 / 7（id、时间、标题、路径、哈希完全一致） |
| `reference_images` 表 | 不存在 | 已创建，列定义与实体一致 |
| `generations.mode` | 不存在 | 存在，7 条老记录全部回填为 `TXT2IMG` |

应用启动后无 schema 校验异常，画廊渲染正常 —— 这也顺带确认了迁移后的表结构与
Room 的期望完全一致（不一致时 Room 会在打开数据库时直接抛异常）。

---

## 十、Image2Img 的实现与真机验证（规划书阶段 B/C）

阶段 B（请求构造）与阶段 C（界面与链路）一起做完了。这一段记录两件**只有真机能发现**的事，
以及一个自己写出来的缺陷 —— 它们都不是靠读 OpenAPI 能得到的。

### 10.1 整个图生图必须换 action，光有 image 字段会被拒绝

第一版实现沿用了 `action = "generate"`，只在 `parameters` 里加了 `image` 与 `strength`。
真机上服务端返回：

```
HTTP 400: {"statusCode":400,"message":"Validation error: error validating request:
           image is not allowed for regular generations, use img2img or infill"}
```

也就是说 **`image` 字段只在 `action` 为 `img2img` / `infill` 时才被接受**。
OpenAPI 里 `action` 只是一个没有枚举约束的 string，字段说明也没写这一点 ——
规划书的待核对清单里原本没有列出这一项，是实测补上的。

修正：只要真的带上了起点图，`action` 就发 `img2img`；缺图时连 `action` 都不换
（换了 action 却没有 image，服务端一样会报参数错误）。

`strength` 仍然放在 `parameters` 顶层，服务端接受。OpenAPI 里那个嵌套的
`parameters.img2img` 对象没有发 —— 它的字段说明写的是 `used by inpaint`。

### 10.2 参考图行的主键不能用素材的 id

`insertReferences` 用的是 `OnConflictStrategy.IGNORE`（与生成记录、图片行一致，
避免 REPLACE 的"先删后插"波及子表）。第一版把 `ReferenceImage.id` 直接当主键，
于是出现了这个现象：

> 同一张起点图连续生成两次，第二次的记录里**没有参考图行**。

因为素材 id 在两次生成之间是同一个（草稿恢复、复用参数都会复用同一个对象），
第二次插入被 IGNORE 静默丢弃。表现是详情页看不到参考图、"复用参数"也带不回起点图 ——
而这恰恰是规划书 4.5 明确要求不能出现的情况。

修正：行的主键改成 `"<generationId>:<role>:<ordinal>"`。这个组合在一条生成记录内天然唯一
（`GenerationRequest.validate` 不允许重复），既是确定的也不会碰撞。
`ReferenceImage.id` 的注释里写清了它"素材身份 / 行身份"两种语义，并说明跨生成去重要用 `sha256`。

**这个缺陷只有在真机上、并且连续用同一张图生成两次才会暴露。** 单元测试当时是全绿的。

### 10.3 免费档位下的验证过程

用户提供了一个实验账号：V4.5 Curated + Normal 档位 + `steps=23` + `guidance=7` 时生图免费。
所有真机生成都严格限定在这组参数内（发请求前在界面上逐项核对过）。

验证链路与结果：

| 步骤 | 结果 |
|---|---|
| 从系统照片选择器导入 2560×1440 的测试图 | 卡片显示"源图 2560×1440 / 提交尺寸 1216×832"，说明按源图比例选中了 Normal 横图预设 |
| 改 Resolution 后重新提交 | 起点图按新尺寸重新裁切（裁剪发生在提交时，不需要重新选图） |
| 冷启动应用 | 草稿把起点图、Strength、提示词全部恢复 |
| 第一次生成（action 错误） | 400，失败记录与参考图行都如实落库，界面显示服务端原文 |
| 第二次生成（action 已修正） | 成功，出图 1216×832，`mode=IMG2IMG`，参考图行已写入 |
| 详情页 | 显示"生成方式：图生图"与起点图缩略图、2560×1440、Strength 0.7 |
| 移除参考图 | 摘要行的"图生图"标记消失 |
| 从详情页点"复用参数" | 起点图从数据库回来（草稿里当时已经没有它），摘要行重新出现"图生图" |

最后一步是判别性的：先移除本地的起点图，再从历史复用，能回来的只可能是数据库里那一行。

### 10.4 与规划书的两处偏差

1. **没有做分页**。规划书 4.1 设计的是"提示词 / 参考图"两个分页（对齐官方三 Tab）。
   当前只有 Image2Img 一个面板、且只有一张图，做分页会让用户多点一次才能看到唯一的面板。
   改成表单里的一张卡片，等 Vibe 与 Precise Reference 落地、真的出现多个面板时再引入分页。
2. **不做"任意合法尺寸"**。阶段 A 里写过一个 `nearestLegalSize`（按源图比例算出 64 倍数的
   任意尺寸），本轮删掉了：图生图的输出尺寸改为**对应到官方预设**（Normal 档位的三组像素）。
   理由是尺寸直接关系到计费与观感，自己算一个尺寸等于引入一个未知量；
   而官方预设是确定的那些值。保留的 `ImageGeometry` 只负责裁切与黑边。

---

## 十一、余额与费用预估（《余额与 Anlas 费用系统实施规划》阶段 1–5）

按那份规划实现了余额子系统、费用四态计算器与界面。这里只记录**实现时做的判断**
和**真机上观测到的事实**，不重复规划本身。

### 11.1 三层错误语义继续分开

新增了第三个错误映射器 `AccountReadErrorMapper`。三者的失败含义完全不同：
登录失败要引导重新输入；生成失败可能已经计费（措辞必须谨慎）；
余额读取失败只是辅助信息没读到。特别是**余额查询超时映射为 `REQUEST_TIMEOUT`**，
不是 `TIMEOUT_UNCERTAIN` —— 后者那句"服务端可能已经接受任务并计费"用在一个只读查询上会平白吓人。

### 11.2 费用四态：算不出来是一等公民

`GenerationCostEstimate` 有 `Free` / `UsesV5Allowance` / `EstimatedAnlas` / `Unknown` 四态。
未经官方网页费用标签校准的付费组合一律返回 `Unknown`（界面显示"费用待确认"），
不给猜测数字。定价公式抽成 `PaidAnlasFormula` 策略，默认实现是
`UncalibratedPaidAnlasFormula`（`supports()` 恒为 false），
这样"还没有公式"是代码里一个明确的状态，而不是抛异常或返回 0。

### 11.3 免费判定放在"订阅等级是否已知"之前

规划 §5.6 的判定顺序把 `Unknown(UNKNOWN_SUBSCRIPTION_TIER)` 放在免费判定之前。
实现时调整了顺序，理由是：**实测确认的那条免费规则只依赖参数，不依赖订阅等级**。
如果先判等级，余额还没读回来时按钮会先显示"费用待确认"、读完再跳成"免费" ——
用户会以为费用变了。现在这个组合从启动起就是稳定的"免费"。

### 11.4 参考图的固定附加费

账号所有者确认的规则：**每加一张参考图多加 5 Anlas，与模型无关**，
因此它是一条与基础生成费用正交的加价。实现要点：

- 基础费用未知时**不给总数**（只报那 5 点会让用户以为总共只要 5）；
- 基础费用已确定时（免费 / V5 额度覆盖 / 已校准公式）相加后返回 `EstimatedAnlas`；
- 常量集中在 `AnlasCostCalculator.REFERENCE_IMAGE_SURCHARGE_ANLAS`，改一处即可。

### 11.5 ⚠️ 真机观测与"免费"的说法不一致

免费档位（V4.5 Curated + Normal 1216×832 + Steps 23 + Guidance 7.0 + 单张）下做了观测：

| 时间 | 事件 | 订阅池 | 购买池 | 总额 |
|---|---|---|---|---|
| 13:32 | 用户官网截图 | — | — | 407 |
| 14:42 | 图生图（带 1 张起点图）生成成功 | 407 | 0 | 407 |
| 14:44 | 强制刷新（生成后约 2 分钟） | 407 | 0 | 407 |

也就是说 **2 分钟后的强制刷新仍然没有看到任何扣减**，界面如实显示"暂未观察到余额变化"。

两种解释都还成立，需要继续观察才能定论：

1. 这个账号（或它的当前促销状态）对 V4.5 Curated 的**所有**生成都不计费，包括带参考图的；
2. 服务端余额更新比 2 分钟更慢（规划的 §3.2 已经列出"服务端更新可能存在延迟"这一条）。

代码按账号所有者给出的规则实现（带参考图 +5），因此按钮上显示"预计 5"。
如果后续观测确认这个账号确实不计费，需要把实测免费规则的形状条件放宽到允许基础图，
并去掉附加费 —— 改动只涉及 [AnlasCostCalculator] 的两处判定。

### 11.6 余额字段映射仍待与网页对照

`fixedTrainingStepsLeft` → 订阅 Anlas、`purchasedTrainingSteps` → 购买 Anlas，
是按官方说明解析的（规划 §2.2 明确要求先按这个含义解析、再用网页余额人工对照）。
本次观测到的是**订阅池 407 / 购买池 0**，而账号所有者描述为"买了积分"。
两种可能：订阅池在计费语义上就是"额度池"，或字段映射需要修正。
界面刻意不写"Training Steps"这类历史命名，只写"订阅 Anlas / 购买 Anlas"，
因此即使映射需要修正，改动也只在文案与解析处。

### 11.7 订阅等级的整数映射

`0/1/2/3 → 无订阅/Tablet/Scroll/Opus` 是社区与官方响应示例一致的解读，
但**尚未与网页订阅名称人工对照**（规划阶段 0 第 4 项）。未知整数一律返回 `Unknown`，
不冒充 Opus 也不冒充无订阅 —— 猜错会让计价走进错误的分支。
`active = false` 时按无订阅处理，但**绝不据此判断"不能生成"**（按量购买 Anlas 的账户就是这种情况）。

---

## 十二、Precise Reference 与费用规则更正

### 12.1 费用规则更正（账号所有者，2026-09-14）

上一轮把"每张参考图 +5"理解成所有参考图通用的附加费，**这是错的**。更正为：

| 功能 | 费用 | 依据 |
|---|---|---|
| Image2Img | **免费**（不额外收费） | 账号所有者更正 + 余额观测印证 |
| Precise Reference | **每张 +5 Anlas** | 账号所有者更正 + 余额观测印证 |
| Vibe Transfer | 未知 | 未确认，一律显示"费用待确认" |

因此附加费按**生成类型**而不是按"参考图张数"计：图生图也带图，但它不加价。
常量 `PRECISE_REFERENCE_SURCHARGE_ANLAS` 是唯一入口。

**余额观测（真机）**：

| 时间 | 事件 | 订阅池 |
|---|---|---|
| 14:42 | Image2Img（带 1 张起点图）成功 | 407 → 407（无变化） |
| 15:06 | Precise Reference ×1 成功 | **407 → 402（正好 5）** |

一条免费、一条 5 Anlas，两条规则同时得到印证。这也说明余额观测这条链路本身是准的 ——
它能分辨"没花钱"和"花了 5"。

### 12.2 模型能力：图生图全模型可用，参考条件是 V4.5 专属

账号所有者确认：

- **Image2Img：V4.5 与 V5 都能用**；
- **Precise Reference 与 Vibe Transfer：目前只有 V4.5 能用**。

因此 `ModelProfile` 上分成三个能力位（`supportsImg2Img` / `supportsVibeTransfer` /
`supportsDirectorReference`），界面据此**隐藏** V5 上的 Precise Reference 入口并说明原因，
`GenerationRequest.validate` 也在本地拦一次 —— 与其让用户提交后收到服务端拒绝，不如当场说清楚。

### 12.3 Precise Reference 的请求形态

用的是官方 `director_reference_*` 五个数组，`action` 保持 `generate`
（与图生图不同：那个要换 `img2img`，因为 `image` 字段只在那个 action 下被接受）。
数组之间**按下标一一对应**，所以任何一项缺失都不能"跳过"，必须补齐 ——
跳过会让"这个角色的强度"落到"那个角色"上，而服务端不会报错，只表现为"参数好像没生效"。
`caption.base_caption` 用 `character` 或 `character&style`。

黑边补齐在**导入时**完成（[ImageTransform.DirectorCanvas]）：画布尺寸取决于源图方向，
而方向要解码后才知道，所以这个决策属于处理管线。导入结果因此就是提交图，
界面缩略图上能直接看到黑边。提交时再套一次同样的 Letterbox 是恒等变换，
"导入补过就跳过"那种优化反而容易在将来出错。

### 12.4 真机验证抓到的两个缺陷

1. **导入时没有补齐黑边**：重构导入路径时把变换去掉了（当时只有图生图，变换确实该留到提交时做），
   结果 Precise Reference 存的是原图。表现是卡片上写"补齐为 1080 × 1920"——就是源图尺寸。
   修正为"导入时可传变换"，图生图仍传 null（尺寸可变，留到提交时裁切）。
2. **草稿里的参考图会被启动清理删掉**：清理只按数据库里的 `reference_images` 判活，
   而用户刚选好图、还没生成时，那几张图只存在于草稿里，没有任何数据库记录。
   于是"选图 → 重启应用 → 生成"必然失败，报"参考图已不在本机"。
   修正为引入 [LiveReferencePathsProvider]：存活集合 = 数据库引用 ∪ 草稿引用。
   它被做成 `GenerationRepository` 的**必填**参数而不是可选参数 ——
   漏掉它是一个静默的数据丢失，不该靠调用方记得传。

---

## 十三、Vibe Transfer（规划书阶段 D）

### 13.1 请求形态：用复数那一组字段

`reference_image_multiple` / `reference_strength_multiple` /
`reference_information_extracted_multiple`（官方同时提供了单数与复数两套，
网页最多 4 张、用的就是复数那套，我们也统一走复数，避免"一张用单数、两张用复数"两条代码路径）。

### 13.2 编码层是可摘除的 —— 实测确认传的是编码产物

`reference_image_multiple` 到底收原始图片还是 `encode-vibe` 的产物，OpenAPI 没有说明。
按官方行为实现为**先编码再发送**，并把这一层做成可摘除的（`GenerationRepository.vibeBase64For`）。

真机验证结果：**服务端接受编码后的 `.vibe` base64**，一次带 Vibe 的生成成功。
因此这一层保留。如果将来发现服务端改收原图，把该函数换成一次 `referenceEncoder.encodeBase64` 即可，
请求构造与界面都不用动。

缓存按 `模型 + 图片 sha256 + Information Extracted` 取哈希命名（`files/vibes/<hash>.vibe`），
实测第二次用同一张图时没有再次编码（缓存命中），缓存路径也写回了参考图行。
这三个输入都进缓存键，是因为 `encode-vibe` 的请求体里带着 `model` 与 `information_extracted`。

### 13.3 ⚠️ Vibe 与 Precise Reference 不能混用（服务端明确拒绝）

原以为参考条件类功能可以叠加，实测被服务端否定：

```
HTTP 400: Validation error: error validating request:
          cannot mix reference and director_reference at the same time, got 1 refs, 1 director refs
```

因此界面上这两者**互斥**（挂上其中一类会清空另一类），与图生图 ⟷ Precise Reference 的互斥一致。
Vibe 与**图生图**不冲突：实测两者同时发送时请求通过（前者是条件、后者是起点，字段互不重叠）。

这条规则 OpenAPI 里没有任何提示，只有真机能发现。

### 13.4 encode-vibe 很可能收费 2 Anlas（观测到一次，未重复验证）

时间线（都在免费档位参数下）：

| 事件 | 余额 | 说明 |
|---|---|---|
| 加入 Vibe 后第一次生成 | 402 → **400** | 该次生成被服务端以 400 拒绝（Vibe 与 Precise Reference 混用），但在此之前 `encode-vibe` 已成功返回并写出 48 KB 的 `.vibe` |
| 移除 Precise Reference 后再生成（Vibe 缓存命中） | 400 → 400 | 没有再次编码 |

生成请求本身被校验拒绝、不该计费，因此那 2 Anlas 最可能来自 `encode-vibe`。
**只观测到一次**，且存在"服务端延迟更新"的干扰，因此不写成结论。

对实现的影响：编码发生在**按下生成之后**，不是选中图片时。所以即使用户不生成，
也不会因为"选了一张图"而被扣费 —— 这一点无论 §13.4 的结论如何都是对的。

### 13.5 当前状态

- 界面：Vibe Transfer 面板（最多 4 张、逐张 Strength 与 Information Extracted、可从相册/历史选图）；
- 与图生图可同时使用，与 Precise Reference 互斥；
- 仅 V4.5 可用（能力位与 Precise Reference 相同）；
- 费用：价格未确认，只要挂了 Vibe，按钮上就是"费用待确认"；
- 两个滑块的默认值（0.6 / 1.0）与模型 ID 一样属于**待核对**项，集中在 `NovelAiRequestBuilder`。

---

## 十四、官方文档核对（局部重绘调研的副产品）

为调研 Inpaint 而通读官方文档站（`docs.novelai.net/en/image/*`），顺手核对了三件之前只能靠推断或口述的事。
**文档是权威来源，这三条从此不再是"待核对"。**

### 14.1 Precise Reference 的 +5 得到官方证实

原文：*"Using Precise Reference will apply an additional cost of 5 Anlas to each image generation.
This extra cost scales with the number of references you use."*

这确认了两件事：**每张 5 Anlas**，且**按参考图张数累加**。我们的实现
（`PRECISE_REFERENCE_SURCHARGE_ANLAS` = 5，按张相乘）与官方一致，
余额观测到的 407 → 402 也与它对得上。

### 14.2 Precise Reference 有三种取用方式，我们少了"纯画风"

官方有 **Character Reference / Style Reference / Character & Style Reference** 三种。
我们只提供了后两种（`character` / `character&style`），**缺纯画风**。

界面与枚举都要补一个值。请求里它多半仍然写在 `caption.base_caption` 上，
但纯画风对应的字符串（`style`？`character&style` 之外的哪个？）**需要核对** ——
OpenAPI 只给出了 `character` / `character&style` 两个取值。

### 14.3 Vibe 的 Strength 有一条官方经验值

原文：*"generally, the strengths of all your vibes should add up to 1.0 or less for good results.
You can use the Normalize Reference Strengths toggle to do this automatically when using V4 or higher models."*

因此第一版缺的不只是提示文案：官方那侧有一个**归一化开关**。
建议补一个"归一化"按钮（把当前几张的 Strength 等比例缩放到合计 1.0），
并在面板上写明这条经验值。

另外 "Information Extracted" 官方建议**用默认值**，并说明 V4 以上先丢的是高频信息（纹理）：
降低它保留更多构图、更少风格。

### 14.4 仍未解决的

官方文档是散文式的产品说明，**不写数值默认值**。因此这些仍是待核对项：
Inpaint 的 Strength/Noise 初值、Vibe 两个滑块的初值、img2img 的 Strength/Noise 初值、
CFG Rescale 的可用区间。它们只能从官方网页的界面上读出来。

**2026-09-14 下午更新**：Inpaint 的强度初值已由官方前端反解确认（**1.0**，见第十七节），
不再属于待核对项；其余各项仍待核对。

---

## 十五、局部重绘（Inpaint）实现记录

按《局部重绘功能规划书》实现了阶段 0–E。真机验证把一个关键假设推翻了，因此**功能现状是"界面完整、链路待最后一步验证"**。

> ⚠️ 本章记录的是 2026-09-14 当时的状态与三次探针过程；真正的根因与最终验证
> 在第十七、十八节（换用 `-inpainting` 模型 ID 后链路已跑通、功能已开放）。


### 15.1 已确认：`action` 用 `infill`

服务端在报错里明确把它当作一个 action 名字使用：

```
Model nai-diffusion-4-5-curated doesn't support action infill
```

这句话同时确认了另一件更重要的事，见 15.2。

### 15.2 ⚠️ 推翻的假设：Curated 档位不支持局部重绘

账号所有者原先的判断是"局部重绘属于 Image2Img 家族，所以四个模型都能用"。
真机实测**不成立**：同样的请求换成 Curated 模型后，服务端直接拒绝：

```
HTTP 400: {"statusCode":400,"message":"Model nai-diffusion-4-5-curated doesn't support action infill"}
{"statusCode":500,"message":"Internal Server Error"}
```

（响应体里还跟了一段 500，服务端在拒绝之后的内部处理似乎也不干净，但不影响结论。）

于是 `ImageModel` 增加了 `tier`（CURATED / FULL），`supportsInpaint` 按档位判定：
**只有 Full 支持**。界面在 Curated 上会直白说明"Curated 档位不支持服务端的 infill 操作，请切换到 Full 模型"，
而不是把入口藏起来让用户猜。

**仍未验证的一点**：Full 是否真的支持。这一条无法免费验证 —— 账号的免费组合只有
V4.5 Curated + Normal + 23 + 7，换 Full 就会真实扣费，因此只能由用户触发。

### 15.3 仍未确认：蒙版约定

因为生成一直没成功，规划书 §3 的 B1 探针（蒙版只涂左半边 + 完全不同的提示词，一次生成判定
"白色=重画区域"还是"透明=重画区域"）**尚未执行**。代码里已经把约定关进
`MaskConvention` 这一个枚举，探针出结果后改一行。

在那之前按更常见的 `PAINTED_IS_WHITE` 实现。

### 15.4 已实现且已验证的部分

| 部分 | 状态 |
|---|---|
| 蒙版几何（视图↔位图换算、笔刷半径换算、扩张=半径偏移） | ✅ 12 个单元测试 |
| 请求构造（`action=infill`、`image`+`mask`、嵌套 `img2img.strength`） | ✅ 14 个单元测试 |
| 蒙版渲染落盘（硬边、内容寻址、约定可翻转） | ✅ |
| 全屏蒙版编辑器 | ✅ 截图验证：画笔/橡皮、笔刷 27px、撤销重做、清空二次确认、**扩张 15px 实时预览**（蓝带明显变粗） |
| 三个入口 | ✅ 画廊长按菜单（截图确认）、详情页按钮、参考图卡片（底图就绪后出现） |
| WYSIWYG | ✅ 底图进入重绘时按输出尺寸裁切，编辑器显示的就是提交图 |
| 费用 | ✅ `GenerationKind.INPAINT` 按图生图家族算：免费组合下按钮显示"免费"，不额外收费 |

### 15.5 扩张为什么不做形态学运算

`effectiveRadius(stroke, dilation)`：圆头笔刷画出的形状是"笔画 ∪ 半径 r 的圆"（Minkowski 和），
把半径整体加 N 就等价于把整个蒙版膨胀 N 像素。因此**不需要对位图做卷积**，
扩张滑块的实时预览只需"换个半径重放笔画"。
橡皮不参与扩张 —— 把橡皮也放大等于缩小涂抹区域，与"扩张"的字面意思相反。

### 15.6 顺带完成的两处补缺（官方文档刚确认过的缺口）

1. **Precise Reference 补上"纯画风"**（官方有 Character / Style / Character & Style 三种）。
   ⚠️ `"style"` 这个 API 取值是**推断**的（OpenAPI 只给了另两个），真机核对后可能需要改一个字符串。
2. **Vibe 面板加"参考强度合计"提示与"归一化到 1.0"按钮**（官方经验值 + 网页端的
   Normalize Reference Strengths 开关），只在合计超过 1.0 时可点。

### 15.7 教训

"属于某个功能家族"是一条**产品层面的推断**，不能直接当成协议事实使用。
这次是服务端的报错把推断纠正回来的。与 Image2Img 的 `action` 那次（`image is not allowed for
regular generations`）是同一类问题：**接口的能力边界只能实测，不能从功能分类推导**。

### 15.8 补充：用定量证据排除了"img2img + 蒙版"这条替代路径

账号所有者反馈"官方网页上 Curated 也能局部重绘"，与 15.2 的服务端拒绝相矛盾。
于是试了第二条路：把蒙版挂到 `action: "img2img"` 上（Curated 的请求就这样发出去了）。

结果：**请求成功，但蒙版被完全忽略**。用 JDK 的 ImageIO 对底图 / 蒙版 / 出图做逐像素比对：

| 指标 | 数值 |
|---|---|
| 蒙版里标记为"重画区域"的像素 | 35 871（占 3.55%） |
| 出图相对底图变化的像素 | 532 030（占 **52.6%**） |
| 变化区域的包围盒 | x[0..1215] y[0..831] —— **整张图** |
| 变化中落在蒙版内的比例 | 3.3% |
| 变化中落在蒙版外的比例 | **96.7%** |

也就是说服务端做了一次普通的 `img2img`（strength 0.7 全图重画），蒙版字段对它没有语义。

**结论收敛为**：

1. 只有 `action: "infill"` 会真正读取蒙版；
2. Curated 档位被 `infill` 拒绝（服务端原话）；
3. 因此**Curated 无法通过公开 API 做局部重绘**。官方网页能做到，但网页用的是后端接口，
   公开 API 的能力边界不等于网页的能力边界 —— 这与"Image2Img 家族"那类推断一样，
   属于只能实测、不能从功能分类推导的东西。

代码据此收敛：`supportsInpaint = (tier == FULL)`，请求只用 `infill`（保留 `img2img` + 蒙版
这条死路会误导后来者，故在 `actionFor` 的注释里写明了实测证据）。
界面在 Curated 上直说"公开 API 只在 Full 档位接受 infill（官方网页对 Curated 可用，但 API 会拒绝）"。

**决定**（账号所有者）：官方网页上是用 Curated 测的，但既然公开 API 只有 Full 走得通，
就**只支持 Full**，不再为 Curated 找别的路。

**仍未验证**：Full 档位是否真的能跑通 `infill`、以及蒙版约定（白/透明）。
两者都需要一次真实生成，只能由用户触发。

### 15.9 结论：公开 API 目前不提供局部重绘，入口已关闭

> ⚠️ **本节结论已被第十七、十八节推翻**：真正的原因是请求用了常规模型 ID
> （应换用专用的 `-inpainting` 变体），不是服务端不提供该能力；重绘已实测可用、
> 入口已开放。本节留作历史记录。

在 15.8 之后又测了一次 **V4.5 Full**，服务端回的是同一句话：

```
HTTP 400: {"statusCode":400,"message":"Model nai-diffusion-4-5-full doesn't support action infill"}
{"statusCode":500,"message":"Internal Server Error"}
```

（两次都跟着一段 500，看起来这个 action 在服务端的处理链本身就不完整。）

因此三种可能全部收敛为一条结论：

| 路径 | 结果 |
|---|---|
| V4.5 Curated + `infill` | 服务端拒绝（`doesn't support action infill`） |
| V4.5 Full + `infill` | 服务端拒绝（同一句） |
| 任意模型 + `img2img` + 蒙版 | 请求成功但**蒙版被完全忽略**（逐像素比对：变化中 96.7% 落在蒙版外） |
| V5 | **未测**（账号余额有限，用户叫停） |

**决定（2026-09-14）**：局部重绘的入口**对四个模型一律关闭**，界面说明"公开 API 暂不可用：
服务端拒绝了 infill 操作，官方网页可以但那属于网页后端的能力"。

理由不只是"现在不能跑"：留着入口，用户会以为点了能生效（在未核实的 V5 上还可能真扣费）。
一个必然失败或可能悄悄花钱的按钮，比暂时没有这个按钮更糟。

代码层面保留全部实现（编辑器、蒙版渲染、请求构造、存储），只是把能力位改成常量 false：
服务端哪天开放这个 action，改 `ModelCatalog` 一行即可启用，其余不用动。

### 15.10 关于测试消耗的说明

被叫停前一共发过三次局部重绘请求，**全部是被服务端拒绝的**（两次 400、一次 img2img 成功但不计费），
因此没有产出图片、也没有扣费记录；余额维持在最后一次读到的 400 Anlas。
被中断的那次 V5 测试**没有发出请求**（数据库里没有对应记录）。

后续如果还要验证，必须：由用户明确授权、使用已知免费的最小参数（Normal + steps 23 + guidance 7 + 单张），
并且一次只发一个请求。

---

## 十六、计费矩阵的确定与订阅状态（2026-09-14，用户要求"重新确认好计费矩阵"）

### 16.1 问题的转折点

《余额与 Anlas 费用系统实施规划》§10 的校准流程假设"官方没有公开报价接口，
只能人工读网页费用标签"。用户指出"按理来说这个应该是可以读取账号订阅状态的才对"，
于是做了一件之前没做过的调查：**直接读官方网页前端**。

结论是这个假设不成立 —— 计价公式、免费规则、附加费规则**全都在客户端 JS 里**，
而且比任何人工校准都精确。§10 的人工矩阵因此没有再执行，改为以反解结果为准。

### 16.2 反解方法与证据链

| 步骤 | 做法 | 结果 |
|---|---|---|
| 1 | 拉取官方文档 sitemap（`docs.novelai.net/sitemap-0.xml`） | 确认官方文档只说规则、不给公式 |
| 2 | 下载 `novelai.net/image` 页面的 40 个 JS chunk | 前端的计价函数就在 `pages/image` 的共享 chunk 里 |
| 3 | 用公式特征值定位（`15.266497014243718`、`65536`、`1048576`） | 找到计价函数本体 |
| 4 | 解析导出的辅助函数（`H_0`、`GIT`、`DkU`、`ax`、`t1`） | 得到附加费规则与免费规则 |
| 5 | 用两个可当场手算的样本交叉验证 | **1024×1024 @28 → 20**、**832×1216 @23 → 17**，后者正好等于社区长期流传的"默认一张约 17 Anlas" |

反解出的关键片段（原样，作为日后复核的凭证）：

```js
// 基础费用（V4.5 与 V5 家族走这条；V1–V3 另有指数式与查表）
raw = ceil(2951823174884865e-21 * area + 5753298233447344e-22 * area * steps) * smeaFactor
if (family === v5) raw *= 1.5
perImage = max(ceil(raw * strength), 2)
if (perImage > 140) return -3          // 不给数字
total = perImage * billableSamples

// 免费单张：注意没有"无底图"这一条，也不是整单免费
freeEligible = !characterRef && area <= 1048576 && steps <= 28
freeSample   = freeEligible && tier >= 3 && hasSubscription && !(v5 && usage.isNegative)
billable     = n_samples - (freeSample ? 1 : 0)

// 附加费
preciseReference += 5 * 张数 * n_samples
vibes            += max(0, 张数 - 4) * 2
vibeEncoding     += 2 * 需编码张数        // 已编码过的 0

// 是否算订阅（完全没用 active 字段）
hasSubscription = accountType ∈ {B2B, SERVICE, SUPPORT, ADMIN}
               || (expiresAt > now && tier > 0)
```

### 16.3 三条被官方文档误导过的地方

| 官方文档的说法 | 前端的真实规则 | 本仓库此前的处理 |
|---|---|---|
| 免费条件是"不带任何底图" | 判据里**没有底图这一条** | 早期实测 407 → 407 已印证，但没有写进规则 |
| "至少 Normal 尺寸" | 只有面积**上界** 1024²，比 Normal 小的图也免费 | 曾经要求 `resolutionTier == NORMAL`（偏严格） |
| 一次生成多张时"只有一张免费" | 前端就是 `n_samples -= 1`，**只免一张** | 曾经判定"批量一律不免费"（偏严格） |

三处都改为与前端的真实规则一致。前两条把免费区间放宽到**更符合官方行为**，
第三条把"批量不免费"改成"批量免一张"。

### 16.4 订阅状态：`active` 字段完全不参与判断

官方前端判断"这个账号算不算订阅"用的是：

```js
accountType ∈ {B2B, SERVICE, SUPPORT, ADMIN} || (expiresAt > now && tier > 0)
```

`active` **一次都没出现**。此前我们把 `active == false` 直接读成"无订阅"，
那会把"已取消但仍在付费周期内"的账号误判成未订阅（官方文档明确说权益保留到周期结束）。
另外，此前用"账户带回 V5 使用额度"当作订阅旁证，那只是 V5 Opus 的功能，额度用尽/
字段缺失时就会失真 —— 现在被官方判据取代。

`accountType` 的整数含义也是反解出来的：`RETAIL=0, B2B=1, SERVICE=2, SUPPORT=3, ADMIN=4`。

**本机实测账号的读数是 `tier 0 / active false / accountType 0 / expiresAt 0`，
按官方判据就是"没有订阅"。** 工具能读到，读出来就是"没有" —— 这一点如实告诉用户，
并且给出手动兜底（§16.5）。

### 16.5 订阅等级的手动指定（用户提出的兜底）

设置页新增"订阅等级"：自动读取（默认）/ 无订阅 / Tablet / Scroll / Opus。

- 手动值**直接取代**服务端读数（连 `subscribed` 一起取代），因为用户是唯一能核对
  "我到底有没有买"的人；
- 持久化在 `pocketnai_settings`（`subscription_override`），重启后不会退回另一种算法；
- 余额弹层里同时显示**服务端原始读数**（`tier` / `accountType` / `expiresAt`），
  报价不对时用户能一眼看出是"服务端说我没订阅"还是"我手动指定错了"。

### 16.6 关于"订阅打 20% 折扣"：官方原文指的是**买** Anlas，不是生成扣费

用户提出的需求原文是"带有订阅的还要有生成扣费 20% 的积分优惠"。查证结果：

- 官方定价页那一行叫 **Anlas Purchase Discount**，说明文字是
  `20% off our on-demand Anlas pricing.`；
- 前端里 `yI(n) = round(2 + n/1111 - 0.01, 2)`，`tT(n)` 是原价：
  2000 Anlas 原价 $4.79、订阅价 $3.79；5000 原价 $8.19、订阅价 $6.49；
  10000 原价 $13.99、订阅价 $10.99 —— **都是美元价的 ~79%，即充值时的折扣**；
- 充值弹层自带说明：`*The discounted Anlas pricing does not apply to accounts with
  canceled or non-renewing subscriptions.`；
- **计价函数里没有任何 0.8 系数**，唯一的减免就是上面那张免费单张。

因此**没有**在生成费用上加 8 折：那会把实际扣费报少 20%。设置页里用一句话把这个区别写清楚，
免得以后再被同一句话说动。

### 16.7 反解带来的唯一未实测项

官方前端**总是显式发送** `sm` / `sm_dyn`（这四个模型的默认值都是 false，
V1–V3 才是 autoSmea），而我们的请求**不发这两个字段**。
服务端在字段缺失时是否等于 false，我们没实测过。

代码里的处理：`AnlasPricingContext.smeaMultiplier` 由调用方按"我们实际发出的字段"给出，
当前恒为 1.0；注释里写明这是唯一未实测项。要消除它有两种办法，都需要用户授权：
读一次网页端同一参数的费用标签，或发一次最小的付费生成做余额反查（会花 Anlas）。

### 16.8 代码落点

| 变更 | 文件 |
|---|---|
| 付费公式（含附加费） | `domain/billing/NovelAiPaidAnlasFormula.kt` |
| 免费单张规则与四态判定 | `domain/billing/AnlasCostCalculator.kt` |
| 订阅状态与手动覆盖 | `domain/billing/SubscriptionStatus.kt`、`SubscriptionTier.kt` |
| `accountType` 解析 | `data/network/SubscriptionBalanceParser.kt` |
| 手动覆盖持久化 | `data/settings/SettingsStore.kt` |
| 上下文组装（含"Vibe 是否已编码"） | `ui/generate/GenerateViewModel.kt`、`GenerationRepository.isVibeEncoded` |
| 设置页与余额弹层 | `ui/settings/SettingsScreen.kt`、`ui/billing/BalanceDialog.kt` |

样本值与规则都有单元测试：`NovelAiPaidAnlasFormulaTest`（含 15 条手算样本）、
`AnlasCostCalculatorTest`（免费/额度/附加费/订阅解析）。

### 16.9 遗留：账号实际订阅状态与观测不一致

本机账号 2026-09-14 的读数是"无订阅"，但账号所有者此前描述过"V4.5 Curated 免费"、
并观测到过 `usage`（V5 额度）存在。两种可能：token 换了账号，或订阅已到期
（官方新政策：订阅结束后 Subscription Anlas 归零并转为 Paid Anlas，与本次读到的
`订阅池 0 / 购买池 7538` 形状一致）。

**这不需要在代码里"解决"** —— 工具如实读、如实显示，用户可以在设置页手动纠正。
但它是所有"报价和官网不一样"问题的第一嫌疑，排查时先看余额弹层里的原始读数。

---

## 十七、局部重绘的真正原因：重绘要换专用的 `-inpainting` 模型 ID（2026-09-14 下午）

### 17.1 第十五节的结论是错的，错在模型 ID

第十五节把"两次 `doesn't support action infill`"读成了"公开 API 不提供局部重绘"。
同一件事换一个角度看：**服务端拒绝的是"常规模型 ID + infill"这个组合**，
而不是 infill 本身。用第十六节反解计价矩阵的同一个方法（下载官方网页
`novelai.net/image` 的 43 个 JS chunk 直接读），找到了网页端重绘的真实请求形态：

- 网页端做局部重绘时调用 `generateInfill`，它的 `model` 字段先经过映射函数
  （bundle 里的 `tM:()=>u`）——**常规模型 ID 换成对应的 `-inpainting` 变体**；
- 然后与图生图共用同一条分发链，POST 到同一个
  `https://image.novelai.net/ai/generate-image`，`action: "infill"`。

也就是说我们此前的请求等于"拿 `nai-diffusion-4-5-curated` 去问它能不能 infill"，
服务端如实回答"这个模型不行"。**答案一直是对的，是我们问错了模型。**

### 17.2 模型映射（官方前端原样照搬）

| 界面选的模型 | 重绘时实际发出的 `model` |
|---|---|
| `nai-diffusion-4-5-curated` | `nai-diffusion-4-5-curated-inpainting` |
| `nai-diffusion-4-5-full` | `nai-diffusion-4-5-full-inpainting` |
| `nai-diffusion-5-curated` | **`nai-diffusion-4-5-curated-inpainting`**（官方前端就是这样回落的） |
| `nai-diffusion-5-full` | `nai-diffusion-5-full-inpainting` |

代码落点：`ImageModel.inpaintingApiModelId`（枚举上一列，构造请求时按模式取用）。

### 17.3 零成本交叉验证：suggest-tags 实测每个 ID

`GET /ai/generate-image/suggest-tags?prompt=x&model=<ID>` 不需要鉴权、不消耗 Anlas，
且会校验模型 ID（无效返回 400）。实测（2026-09-14）：

| 模型 ID | 结果 |
|---|---|
| `nai-diffusion-4-5-curated-inpainting` | 200 ✅ |
| `nai-diffusion-4-5-full-inpainting` | 200 ✅ |
| `nai-diffusion-5-full-inpainting` | 200 ✅ |
| `nai-diffusion-5-curated-inpainting` | **400 ❌（不存在）** |
| `nai-diffusion-4-curated-inpainting` / `4-full-inpainting` / `3-inpainting` | 200 ✅ |

`5-curated-inpainting` 不存在这件事与前端映射（V5 Curated 回落到
4-5-curated-inpainting）**严丝合缝**：前端作者显然也知道它不存在。
这是"反解结果可信"的强证据，不是巧合能解释的。

### 17.4 官方 infill 请求的完整字段集合（与我们实现的差异）

网页端发出的 infill 请求体（`{input, model, action, parameters, use_new_shared_trial}`），
其中 `parameters` 在常规字段之外：

| 字段 | 官方行为 | 我们此前 | 现在 |
|---|---|---|---|
| `model`（顶层） | `-inpainting` 变体 | 常规模型 ID ❌ | 变体 ✅ |
| `image` / `mask` | 底图 / 蒙版 base64 | 同 ✅ | 同 |
| `add_original_image` | 恒发 `false` | 不发 | 照发 `false` |
| `sm` / `sm_dyn` | 带底图时恒发 `false` | 不发 | 照发（仅重绘路径） |
| `extra_noise_seed` | 带底图且未显式设置时发 `seed - 1` | 不发 | 照发（仅重绘路径） |
| 嵌套 `img2img` | **仅当** `inpaintImg2ImgStrength ≠ 1` 时发 `{strength, color_correct: true}` | 恒发 `{strength: 0.7}` | 按官方规则 |
| 顶层 `strength` | 重绘请求里没有 | 没有 ✅ | 没有 |
| 重绘强度默认值 | **1.0**（滑块初值；计价函数也读它） | 沿用图生图的 0.7 | 1.0 |
| `use_new_shared_trial` | 恒发 `true` | 不发 | **仍不发**（语义不明，与试用账户有关；文生图不带它也能跑） |

注意嵌套对象里 `color_correct: true` 与图生图顶层的 `color_correct: false` **相反**，
两处都是官方前端的原样行为，不是笔误。图生图路径保持原样不动
（字节不变，有测试钉死），上述"恒发字段"只落在重绘分支。

### 17.5 蒙版形态：从"赌约定"变成"有官方实现背书"

官方前端的蒙版管线（全部在客户端完成，bundle 里可读）：

1. 编辑器导出蒙版 PNG（涂抹处为亮色、未涂抹为透明）；
2. 最近邻缩到 **1/8 分辨率**（隐空间对齐：8 是 VAE 下采样倍数），按 155 阈值二值化；
3. 提交前再拉伸回**生成尺寸**并合成到**黑色背景**上 —— 线上的蒙版就是
   "与底图同尺寸、不透明、白=涂抹、黑=保留"的 PNG；
4. 生成后客户端合成链 `replaceTransparent(→黑) → dilate → ×8 → blur → alphaMatchRed`
   把返回图贴回原图，同样以"白=重画区域"工作。

因此 `MaskConvention.CURRENT = PAINTED_IS_WHITE` 不再是"SD 系常见约定"的猜测，
而是有官方实现背书。我们的蒙版（同尺寸、硬边二值、白涂抹/黑背景、无抗锯齿）
与该形态一致；官方的 1/8 量化只是对齐隐空间下采样的优化，不改变语义，
我们没有照搬（我们的蒙版本来就是硬边二值，服务端自己也会做同样的下采样）。

B1 判别性探针仍然保留为上线前的最后一步（前端证据 ≠ 服务端语义），
但它现在只负责"确认"，不再负责"二选一"。

### 17.6 计价的相应修正

官方计价函数里：`strength = mask ? (inpaintImg2ImgStrength ?? 1) : (image ? strength : 1)`。
即**重绘的费用乘数是重绘强度而不是图生图强度**。代码落点：
进入重绘时底图的默认强度改为 1.0（`ModelProfile.defaultInpaintStrength`），
费用上下文按模式取对应默认值（`GenerateViewModel.pricingContextOf`）。
由于乘数始终跟随"实际发出去的强度值"，其余计价逻辑不需要动。

### 17.7 现状与重新启用的条件

`supportsInpaint` 仍为 `false`：**换了模型 ID 之后服务端是否接受 infill 尚未实测**，
而实测是一次真实生成（可能扣费），按纪律只能由用户手动发起。

与第十五节相比，重新启用从"等服务端开放"变成"等一次用户授权的验证"：

1. 用户手动触发一次重绘生成（建议直接按 B1 探针形态：蒙版只涂左半边 +
   与底图完全不同的提示词，最小参数：V4.5 Curated + Normal + steps 23 + guidance 7.0 + 单张）；
2. 出图且左半边变 → `infill` 通了且 `PAINTED_IS_WHITE` 正确，一次请求同时判定两件事；
   仍回 `doesn't support action infill` → 专用模型 ID 也不接受公开调用，入口继续关闭；
3. 成功则把 `ModelCatalog.supportsInpaint` 改成 `true`，其余代码都已就绪。

蒙版内容与计费观测（B4）随这一次生成顺带完成（对比生成前后余额即可）。

### 17.8 反解方法备注（日后复核用）

- 素材：`novelai.net/image` 的 43 个 JS chunk（构建 `3102745-production`），
  本次留档在 `.tooling/webbundle/`；
- 定位路径：`infill` 全文搜 → `generateInfill` 实现（chunk 2952）→
  分发函数 `K`（同 chunk）→ 端点常量 `ImageBackendUrl+"/ai/generate-image"`（_app chunk）→
  模型映射 `tM:()=>u` 与模型枚举（_app chunk）→ 能力位 switch（含
  `img2imgInpainting` / `charRefInpainting`，_app chunk）→
  计价函数的 mask 分支（chunk 1601，§16 的同一个函数）；
- 能力位表（前端视角）：V4.5 家族（含两个 `-inpainting` 变体）
  `inpainting=T, img2imgInpainting=T, characterReferences=T, charRefInpainting=T`；
  V5 家族 `charRef=F`（与我们"Vibe/PR 只有 V4.5"的既有结论一致）；
- 顺带发现：官方有个 `POST /ai/generate-image/request-price` 端点（前端计价是本地的，
  该端点用途未查），日后若要做服务端报价核对可以从它入手。

---

## 十八、局部重绘真机验证通过（2026-09-14 晚，用户授权的一次生成）

### 18.1 探针设计

用户授权一次真实生成，并在应用内备好前置状态（底图已挂、蒙版已涂、V4.5 Curated）。
探针按 §17.7 的建议形态执行，一个请求同时判定两件事：

| 项 | 值 |
|---|---|
| 模型 | `nai-diffusion-4-5-curated`（线上发 `nai-diffusion-4-5-curated-inpainting`） |
| action | `infill` |
| 尺寸 / 步数 / Guidance / 张数 | 832×1216（Normal）/ 23 / 7.0 / 1 |
| 强度 | 1.0（官方默认 → 未发嵌套 `img2img` 对象） |
| 蒙版 | 用户涂抹的底部条带，覆盖 **3.91%** 像素，横跨左右两半 |
| 提示词 | 换成与底图完全不同的 `cyberpunk city street at night, neon signs, rain, no humans` |
| 账号 | Opus 订阅生效中，预估 **免费** |

这个蒙版形状比"涂左半边"更狠：两种约定分别预测"3.91% 的区域变"与
"其余 96.09% 的区域变"，一眼可分。

### 18.2 结果：两个问题一次判完

**服务端接受了请求并出图**（状态 SUCCEEDED，无 400）。对底图 / 蒙版 / 出图
做逐像素比对（任通道差值 >10 记为"变化"）：

| 指标 | 数值 | 对照：上午 img2img+蒙版那次 |
|---|---|---|
| 蒙版覆盖 | 3.91% | 3.55% |
| 蒙版内像素变化率 | **98.9%** | （蒙版内几乎没变） |
| 蒙版外像素变化率 | **0.74%** | 52.6% 全图变 |
| 变化总量占全图 | 4.58% | 52.6% |
| 变化落在蒙版内 / 外 | **84.6% / 15.4%** | 3.3% / 96.7% |

蒙版外那 0.74% 是边缘羽化级别的零散点，与上午"整图重画"是两个数量级的差别。
出图目视：蒙版条带（鞋的位置）变成霓虹灯牌，人物其余部分与底图一致。

**结论 1：`infill` + 专用 `-inpainting` 模型 ID 在公开 API 上走通。**
上午的 `doesn't support action infill` 确认是模型 ID 用错，不是功能缺失。

**结论 2：蒙版约定是 `PAINTED_IS_WHITE`（白=重画、黑=保留）。**
若约定相反，变化的应是那 96%。`MaskConvention.CURRENT` 从"有前端背书"升级为"实测确认"。

**结论 3（B4 计费）：这次重绘没有扣费**（余额刷新前后都是 400 Anlas），
与本地计算器按官方规则给出的"免费"一致 —— Opus 免费单张对重绘生效
（面积 ≤ 1024²、steps ≤ 28、单张、有订阅权益；强度乘数 1.0）。

### 18.3 随之固化的实现事实

- 请求里实际发出的字段组合（含 `add_original_image=false`、`sm=false`、
  `sm_dyn=false`、`extra_noise_seed=seed-1`、无嵌套 `img2img`）被服务端接受，
  这就是重绘路径的已验证基线；
- 历史记录正确：`generations.mode = INPAINT`，`reference_images` 同时落
  IMG2IMG（strength 1.0）与 INPAINT_MASK 两行；
- 底图导入时的 Cover 裁切保证编辑器坐标与提交像素一一对应（本次探针的
  蒙版位置与出图变化区域吻合，没有错位）。

### 18.4 代码状态

`ModelCatalog.supportsInpaint = true`（四个模型全部开放；V5 Curated 的重绘
按官方前端映射回落到 `nai-diffusion-4-5-curated-inpainting`）。
局部重绘从"代码就绪、能力位 false"转为**正式可用**。
蒙版 1/8 量化那一步官方优化我们没有照搬（我们的蒙版本来就是硬边二值，
服务端会自己做隐空间下采样）—— 本次出图边缘没有可见问题，维持现状。

### 18.5 跟进修复：移除底图后残留的蒙版导致 400（用户实测踩中）

验证通过后用户第一次正常使用就踩中一个状态管理 bug：在重绘状态下点掉底图（X），
再切到 V4.5 Full 想普通生图，结果收到
`HTTP 400: Model nai-diffusion-4-5-full doesn't support action infill`。

**根因是三层判定各说各话**：

1. `onRemoveReference()` 只清底图、不清蒙版 → 状态里只剩蒙版；
2. 模式判定只看"有没有蒙版" → 仍停在 INPAINT；
3. 请求构造里 action 看模式（INPAINT 就发 infill）、model 却看载荷是否齐备
   （缺底图 → 用常规模型 ID）→ 发出"常规模型 ID + infill"的自相矛盾组合，
   服务端如实 400。而 `GenerationRequest.validate` 当时只查蒙版、不查底图，
   全程没有任何一道闸拦住它。

**修复（四层，纵深防御）**：

| 层 | 改动 |
|---|---|
| 交互 | `onRemoveReference()` 连坐作废蒙版（与笔画/扩张量一起清空）—— 底图没了，重绘模式即结束 |
| 校验 | `GenerationRequest.validate` 新增 `MissingInpaintBase`：INPAINT 必须有底图 |
| 构造 | `action` 与 `model` 改为**同一个判定**（载荷齐备才走 infill/-inpainting ID）；残缺请求只会退化成普通生成，不可能再发出自相矛盾的组合 |
| 状态 | `mode` 判定改为"蒙版与底图同时在场才算 INPAINT"；草稿恢复时丢掉缺底图的残留蒙版（自愈旧版本留下的残缺草稿）；摘要行的"重绘"标记跟随 mode |

有回归测试钉住用户这次的精确场景（`有蒙版没底图时绝不能发出 常规模型ID加infill 的自相矛盾请求`）。
真机复现验证：旧草稿冷启动后蒙版被自愈丢弃，摘要行不再挂"重绘"，普通生图恢复可用。

### 18.6 蒙版对齐隐空间网格（8×8），消除"边缘不明材质"（2026-09-14 晚）

用户实测：以 `black pantyhose` 出底图、涂腿部、`white pantyhose` 重绘，
结果在蒙版边界生成了一圈白色蕾丝状的不明材质。逐像素核对：蒙版内 99.3%
重画、蒙版外 0.97% —— 蒙版语义没问题，问题出在**边界圈的过渡**。

根因：服务端在隐空间（分辨率的 1/8，即 8×8 像素一格）解释蒙版。
我们此前提交任意精度的全分辨率硬边蒙版，边界会切穿隐空间格，
经服务端下采样后产生"半涂半不涂"的边缘格，模型就在那圈里发明过渡材质。
官方前端的做法是把蒙版先量化到 1/8 再拉伸回来（每个格子都是确定的纯黑/纯白）。

修复：`MaskImageProcessor.render` 落盘前把蒙版最近邻缩到 1/8 再放回
（`snapToLatentGrid`），与官方管线等价。输入本来就是硬边二值，
最近邻缩放保持二值，无需阈值化。编辑器的实时预览保持平滑（差距最多半格 4px），
编辑器提示语补了一句"蒙版边缘会按 8px 网格对齐"。

真机验证（不消耗 Anlas）：在 emulator-5558 上开编辑器画一笔 → 完成 →
拉出落盘蒙版逐格校验：只有纯黑/纯白两种颜色、全部 15808 个 8×8 格均匀一致。

注意：网格对齐消除的是"边界模糊"这一类瑕疵；蒙版圈得紧贴内容边缘时
模型仍会在边界发明过渡（这是重绘的固有行为，官方文档的建议是把蒙版扩张一点）。
扩张滑块（0–24px）就是干这个的。

---

## 十九、图片元数据导入（2026-09-14 晚，用户要求）

### 19.1 需求与结论

用户要求"做一个读取图片 metadata 的功能"：在 Image2Image 选图时，如果这张图是 NovelAI
生成的，就把里面的提示词与参数导入编辑区。**不联网、不需要订阅、不消耗 Anlas** ——
纯本地解析。

**结论：可以做，而且我们自己的生成图天然就是样本。**
`GET /ai/generate-image` 返回的 PNG 里，服务端**已经写好了完整的参数元数据**，
落盘时没有被破坏（`files/generations/<id>/0001.png`）。因此"从历史选择"这条路径
导入的正是自家生成的图，元数据完整。

### 19.2 真实文件长什么样（本机实测，不是推测）

拉一张真实生成图，PNG 结构是：

```text
IHDR → IDAT × N → tEXt(Comment) → tEXt(Title) → tEXt(Description)
     → tEXt(Software) → tEXt(Source) → tEXt(Generation_time) → pHYs → IEND
```

| 关键字 | 值示例 | 说明 |
|---|---|---|
| `Software` | `NovelAI` | **唯一的"这是不是 NovelAI 图"判据** |
| `Source` | `NovelAI Diffusion V5 DB276663` | 模型名 + 模型哈希 |
| `Description` | 提示词全文 | 与 `Comment.prompt` 逐字节相同（已比对） |
| `Comment` | 5 KB 的 JSON | 全部参数 |
| `Generation_time` | `1.3046269710175693` | ⚠️ **下划线**；官方 WebP 样本里写的是 `Generation time` |
| `Title` | `AI generated image` | 无信息量 |

`Comment` JSON 的关键字段（真实值）：

```json
{"prompt": "...", "steps": 23, "width": 832, "height": 1216, "scale": 7.0,
 "cfg_rescale": 0.0, "seed": 495204733, "n_samples": 1, "sampler": "k_euler_ancestral",
 "noise_schedule": "karras", "sm": false, "sm_dyn": false, "uc": "",
 "v4_prompt": {"caption": {"base_caption": "...", "char_captions": []},
               "use_coords": null, "use_order": null, "legacy_uc": null},
 "v4_negative_prompt": {"caption": {"base_caption": "", "char_captions": []}},
 "reference_strength_multiple": [], "director_reference_strengths": null,
 "model_name": "NovelAI Diffusion V5", "model_hash": "DB276663", "version": 1}
```

两代接口的差别：**V5 的 Comment 里有 `model_name`/`model_hash`，V4.5 的没有** ——
所以模型识别不能只靠 Comment，必须以 `Source` 为准。

### 19.3 官方前端怎么做的（反解，2026-09-14）

官方 `image2image` 上传后的那个面板（用户提供的截图）由 `pages/image` 的 chunk 实现，
关键语义全部反解出来并对齐：

| 项 | 官方行为 | 我们的实现 |
|---|---|---|
| 判定"有元数据" | `Software` 含 `NovelAI` 且 `Comment` 可解析 | 同（`Comment` 坏了也认，只是没有参数） |
| 提示词来源 | `Description`（勾 Actual Prompt 时用 `actual_prompts.prompt.base_caption`） | 同（`Description` → `Comment.prompt` → v4 caption 回退） |
| 参数来源 | `Comment` 经**白名单**过滤：19 个键（`scale/seed/steps/strength/noise/sampler/sm/sm_dyn/uc/dynamic_thresholding/cfg_rescale/noise_schedule/legacy_v3_extend/width/height/skip_cfg_above_sigma/extra_passthrough_testing/v4_prompt/v4_negative_prompt`） | 我们只取自己认识的字段，同样是白名单；`prompt`/`n_samples` 官方都不取 |
| 质量标签 | 按 `\|` 切块，要求**每一块**都以候选后缀结尾才剥离，并设对应预设；都不匹配则 `qualityPresetId="none"` | 同（用我们自己的后缀表：Standard / Light） |
| Clean Imports | `.replace(/[[\]{}]/g,"").replace(/,(?=[^ ])/g,", ").replace(/ ,/g,",")` | 逐字符照抄 |
| 模型 | `Source` 的哈希查表；V5 只有 `657484A5`/`0ADF9AB7` 是 Full，其余 V5 一律 Curated | 同（表已用本机数据库交叉验证） |
| 尺寸 | 按预设匹配 | 同；**不是预设就明确说"不改"，不做"最接近"** |
| Seed / Clean Imports | 默认不勾 | 同 |

### 19.4 模型哈希表（已用本机数据验证）

`Source` 里的人话部分**分不出 Curated 与 Full**（两者名字相同），只有哈希不同。
反解出的表与本机 11 张生成图的 `Source` × 数据库 `modelApiId` **完全一致**：

| 数据库 modelApiId | Source 哈希 |
|---|---|
| `nai-diffusion-4-5-full` | `4BDE2A90`、`1229B44F`（+ `B9F340FD`、`F3D95188`） |
| `nai-diffusion-4-5-curated` | `C02D4F98`、`5AB81C7C`、`B5A2A797` |
| `nai-diffusion-5-full` | `0ADF9AB7`（+ `657484A5`） |
| `nai-diffusion-5-curated` | 其余 V5 哈希（官方 default 分支） |

认不出的 `Source`（V3 / SDXL / 更早）**保持当前模型不变**并显示原文 ——
绝不静默映射成 V4.5，那会让用户以为参数对上了。

### 19.5 三条守住的边界

1. **必须从用户选择的原始文件读，不能从归一化后的参考图读。**
   `ReferenceImageProcessor` 会解码再重新编码 PNG，`tEXt` 全部丢失。
   因此 `onReferencePicked` 里先 `metadataInspector.inspect(source)`，再
   `referenceImporter.import(source)`。顺序反了功能就永远不生效（且不会报错）。
2. **只导入明确认识的字段。** 解析器不认识的值一律留空并记录原文，
   由规划器逐项降级成"跳过 + 说明"，绝不反射进请求。
3. **Characters 只报告数量，不导入。** 多角色提示词还没实现，把角色词拼进基础
   提示词会丢掉角色的独立反向词、位置坐标、数组顺序与 `use_coords` ——
   那比不支持更糟。官方面板里的 `Characters` / `Append` 我们也**不显示**。

### 19.6 实现

| 层 | 文件 | 职责 |
|---|---|---|
| 纯 Kotlin | `domain/metadata/PngTextChunks.kt` | 流式读 `tEXt`/`iTXt`/`zTXt`，带全套限额（单块 1 MiB、解压 1 MiB、总量 2 MiB、chunk 长度上限 64 MiB） |
| 纯 Kotlin | `domain/metadata/NovelAiMetadataParser.kt` | 关键字归一（`Generation_time` ≡ `Generation time`）→ 领域模型；`NovelAiModelHashes` 模型表 |
| 纯 Kotlin | `domain/metadata/MetadataImportPlanner.kt` | 逐项降级 + 质量标签去重 + Clean Imports + `MetadataImportNote` 清单 |
| Android | `data/image/AndroidImageMetadataInspector.kt` | `ReferenceSource` → 流；magic 识别 JPEG/WebP 并如实报"格式不支持" |
| UI | `ui/generate/MetadataImportDialog.kt` | 勾选项 + 将改动清单 + 逐条说明 |
| 接线 | `GenerateViewModel.onReferencePicked` / `confirmMetadataImport` | 探查在归一化之前；导入在一个 `_state.update` 里原子应用 |

## 19.7 真机验证（2026-09-14 晚）

在 MuMu 上走了一遍真实流程：图生图 → 从历史选择 → 选一张自家生成的 V5 图 →
弹出"这张图带 NovelAI 元数据"（模型、耗时、五个勾选项）→ 导入。

结果：提示词变成元数据里的提示词、质量标签被剥离并设为 Standard、尺寸/Steps/Guidance/
采样器按元数据设置、参考图正常挂上、**没有自动生成**。

**过程中抓到一个真 bug**：一开始把 `promptTemplate` 设成了元数据里的**原始**提示词
（含质量标签），而 `params.prompt` 是剥掉标签的版本 —— 编辑器显示的是 `promptTemplate`，
于是界面看起来"标签还在"，而生成时又会追加一次，等于**标签翻倍**。
修法：模板与提交值保持一致（勾了"实际提示词"时官方网页也是把展开值写进输入框）。
这条只有真机跑一遍才会发现，纯单测覆盖不到"编辑器绑定的是哪个字段"。

### 19.8 没做的事（明确的边界）

- **JPEG / WebP**：只识别格式并说明"首版仅支持 NovelAI 原始 PNG"。相册里的照片
  大多是 JPEG，直接被判为不支持 —— 而不是含糊地说"这张图没有元数据"。
- **Vibe / Precise Reference 的原始素材**：不在图片里，无法恢复，只提示"检测到用过"。
  绝不建一条空引用冒充恢复。
- **图生图 / 重绘的原始底图**：同上。
- **从画廊详情页直接导入**：目前入口在选图流程里（官方也是这样）。
- **多角色（Characters / Append）**：等多角色提示词功能落地后再开。

---

## 二十、自定义分辨率（2026-09-14 深夜，用户要求）

### 20.1 要解决的问题

用户要自定义分辨率，并指出官网的怪现象：**`1920×1080` 能生成，但滑块划不到那个位置**。
调研结论（用户提供的分析 + 我们独立核对官方前端）：

- 官方画布边长步长是 **64**（前端 `$d`：四个模型都返回 64），`1080 ÷ 64 = 16.875` 不合法；
- 输入框失焦与提交前各归一化一次，取**最近的** 64 倍数（`sD`：`floor` 与 `ceil` 谁近取谁）。
  `1080` 距 `1024` 差 56、距 `1088` 差 8 → 选 `1088`；
- 官方**不会**在生成后裁回 1080：网页版最终给你的就是 `1920×1088`；
- 面积上限 **3,145,728 = 3 × 1024²**（`Dk`：宽高存在、`steps ≤ 50`、面积不超上限）。

所以"能生成却划不到"不是 bug，而是"合法画布是 64 的倍数"这件事在 UI 上的表现。

### 20.2 我们的方案：精确最终尺寸

**默认走"精确最终尺寸"**：画布**向上**对齐到 64 的倍数，收到图后**居中裁切**到用户输入的尺寸。

```text
输入 1920×1080 → 生成 1920×1088 → 本地上下各裁 4 px → 最终 1920×1080
只多生成 0.74% 的面积，而且裁切不重采样（缩放才会引入一次重采样）
```

同时保留"仿官方"模式（不裁切、取最近倍数）：两种行为都有明确说明，
因为总有人要的是"和官网一模一样"。

**为什么精确模式必须向上取整而不是取最近**：用户要 `1050×1050` 时最近值是 `1024×1024`，
比目标**小** —— 根本没有像素可裁。这是必须与官方分道扬镳的唯一一处。

### 20.3 一个尺寸不能再承担三种含义

以前 `width × height` 同时是"界面输入 / 请求参数 / 最终文件尺寸"，预设尺寸下三者恰好相同，
所以看不出问题。自定义分辨率把这个巧合打破了：

| 场景 | 用哪个尺寸 |
|---|---|
| 界面输入框 | 用户目标（`CustomResolution.width/height`） |
| NovelAI 请求、计费、底图预处理 | **画布**（`params.size`，64 对齐） |
| 最终文件、瀑布流、相册导出 | **最终尺寸**（`params.outputSize`，可空） |

`params.size` 是唯一进入请求构造的尺寸；`outputSize` 只在需要裁切时非空。
`resolutionPlan` 由 `ResolutionPlanner` 从两者推出，**居中裁切规则只定义一处**
（`centeredCrop`），奇数差值固定多给右下 1 px —— 规则确定，同 Seed 重现才不漂移。

### 20.4 落盘前裁切，并且不能丢元数据

裁切发生在 `ZipImageExtractor` 之后、`fileStore.commitImages` 之前（`OutputImageProcessor`）：
历史文件、数据库宽高、缩略图与相册导出都以落盘那一刻为准，之后再改就要重新保证三者一致。

- **不裁切时一个字节都不动**：预设尺寸的历史完全不受影响，SHA-256 与体积保持原样；
- **裁切时必须把 NovelAI 元数据写回**：`Bitmap.compress(PNG)` 会丢掉原图的 `tEXt`，
  不写回去的话，"元数据导入"会在用户裁过一次之后**静默失效**（这是两个功能之间的接缝，
  也正是这次特别注意的地方）；
- 写回的是**白名单**（`Software` / `Source` / `Description` / `Comment` / `Generation_time` / `Title`），
  未知工具的块不带走；
- 另加一条自己的块 `pocketnai_output=output=1920x1080;generation=1920x1088;crop=0,4`。
  **绝不篡改 NovelAI 的尺寸字段** —— 那是模型生成时的画布，改掉它同 Seed 就复现不出来；
- 裁切后重算 `byteSize` / `sha256` / `width` / `height`（数据库里记的就是这四个值）。

### 20.5 尺寸约束分成"官方"与"本地"两套

`ModelCatalog` 从 `min 512 / max 1536 / 面积 1536²` 改为：

| 约束 | 值 | 来源 |
|---|---|---|
| 步长 | 64 | 官方 |
| 面积上限 | 3,145,728 | 官方 |
| 边长下限 | 256 | **我们的护栏** |
| 边长上限 | 2048 | **我们的护栏** |

官方只按面积约束、理论上允许极端长条；但超大画布在解码 / 缩略图 / GPU 纹理上都可能出问题，
而这类问题表现为"某张图打不开"，很难归因。**两套限制在注释里分开写清楚**，
不把本地限制伪装成 NovelAI 的限制。

### 20.6 一起改到的地方

- **数据库 schema 5**：`generations` 加 `outputWidth` / `outputHeight`（可空、不重建表）；
- **草稿**：`CustomResolution` + `outputWidth/Height` 一起持久化，否则重开应用尺寸就丢了；
- **元数据导入**：尺寸不在预设里但合法时**按自定义尺寸接住**
  （原图就是这个画布，取整就复现不出来），并给出对应说明；
  连合法都算不上时才跳过尺寸；
- **图生图**：自定义模式下**不**用底图比例覆盖用户尺寸（底图会被 Cover 到那个画布）；
- **复用历史参数**：尺寸不在预设里就自动回到自定义模式；
- **计费**：公式读 `params.size`，因此天然按画布算 —— `1920×1088` 面积 2,088,960
  超过 1024² 的免费门槛，Opus 也不免费（本次不涉及公式改动）。

### 20.7 验证方式（没有花 Anlas）

自定义尺寸的**真实生成**会产生费用，因此没有自动发起。改为在设备上做两件事：

1. **仪器化测试**（新增 `app/src/androidTest`，8 个用例全绿）：
   - `OutputImageProcessorTest`：造一张 1920×1088 的三色标记图 → 裁 `(0,4,1920,1080)` →
     断言输出尺寸、**第一行正是原图第 4 行**（裁对位置）、哈希/体积与文件自洽、
     白名单元数据保留、未知块被丢弃、`pocketnai_output` 可解析；
     以及"不裁切时字节不变"与"裁切范围越界时保留原图"；
   - `MigrationTest`：用 `MigrationTestHelper` 从 **schema 4** 建库、插一条生成记录与一张图片，
     跑 `MIGRATION_4_5` 后校验数据仍在、新列为 NULL、表结构与 Room 期望一致；
   - `ResolutionSelectorTest`（Compose UI）：断言四行尺寸说明的文案，
     包括"上下各裁 4 px"与"不裁切，最终文件就是这个尺寸"两种模式。
2. **视觉**：真机截图需要在应用里连上账号；本次测试机的应用数据被测试运行清掉了
   （见 20.8），所以界面改用上面的 Compose UI 测试验证。

### 20.8 一个必须记住的操作陷阱

**`:app:connectedDebugAndroidTest` 默认会在跑完后卸载被测应用**
（AGP 的 `android.injected.androidTest.leaveApksInstalledAfterRun`，默认 false），
而卸载 = 清空应用数据：本机保存的 NovelAI 凭据（Keystore 加密）与全部生成历史一起消失。
2026-09-14 就是这样把测试机上的账号与历史清掉的（事后才反应过来）。

已在 `gradle.properties` 里把它设为 `true`，并在 AGENTS.md 里写明：
跑仪器化测试前先确认这台设备的本地数据是否可以丢。

### 20.9 界面：Custom 放进下拉，而不是旁边的独立按钮

第一版把 Custom 做成档位下拉右边的一个独立 chip，装机后用户反馈两点：
"选 custom 的时候那几个尺寸都变灰"和"这个 custom 在最右边条状的按钮很没有辨识度"。
截图一看，那个 chip 在一行里被挤成了**没有文字的空药丸** —— 既看不出来也点不准。

改为：**Custom 就是下拉里的一项**（`Normal` / `Large` / `Custom`），选中它时
档位下拉显示 `Custom`、三个横竖方按钮整体变灰（禁用态）。这也正是官方网页版的做法：
官方尺寸列表的最后一项就是 `{name:"Custom", width:0, height:0, category:"Custom"}`。

实现上用一个文件内的 `sealed interface ResolutionSizeOption { Tier / Custom }` 作为下拉的选项类型，
**不给 `ResolutionTier` 加 `CUSTOM`**：档位是"固定组合 × 三个方向"，自定义是任意宽高，
塞进枚举会制造一个没有尺寸的假档位，还要在每个 `when` 里处理它。

### 20.10 没做的事

- **真实的自定义尺寸生成**：会产生费用（`1920×1088` V4.5 约 34 Anlas），留给你在界面上手动验收；
- **锁定宽高比**：只做了"交换宽高"，没有做锁定 16:9 这类比例锁；
- **JPEG / WebP 输出**：最终文件仍然是 PNG（与现有链路一致）。

---

## 二十一、随机 Seed 不随机的 bug（2026-09-14 深夜，用户报告）

### 21.1 症状与证据

用户报告："随机种子不再随机，而是一直使用同一个种子了。"

先取证再改代码。同一个 /user/subscription 读出的是账号状态、数据库里记的是本地参数，
两边都不足以说明"服务端到底收到了什么"，**图片自己的元数据才是权威**：

| 来源 | 读数 |
|---|---|
| 生成图的 PNG 元数据（服务端写入） | `"seed": 0` |
| 本地数据库那一行 | `seedMode = RANDOM`、`baseSeed = 63722125` |

也就是说：我们**抽了一个随机 seed、写进了历史、显示在界面上**，而请求体发的是
另一个值（原始参数里的默认 0）。用户按历史里那个数字去复现，永远复现不出来；
反过来，同一提示词反复生成会得到一模一样的结果。

### 21.2 成因

`GenerationRepository.generate()` 里：

```kotlin
val seed = when (normalized.seedMode) { ... }        // 随机模式下抽一个新的
val effectiveParams = normalized.copy(baseSeed = seed)   // 只用于落库
...
api.generateImage(payload = NovelAiRequestBuilder.build(profile, request, …))  // ← 用的是原始 request
```

`request.params.baseSeed` 在随机模式下一直是默认值（或在草稿里长期不变的值），
于是每次都把同一个 seed 发出去。请求体与历史记录**必须来自同一份参数**，
而这里恰好是两份。

### 21.3 修法

```kotlin
val effectiveParams = normalized.withResolvedSeed(random)
...
payload = NovelAiRequestBuilder.build(profile, request.copy(params = effectiveParams), encodedImages)
```

- 把"这次用哪个 seed"抽成一个纯函数 `GenerationParams.withResolvedSeed`（可单测）；
- 落库与请求体**共用** `effectiveParams`，从结构上消除"两份参数"的可能；
- 顺手把 `generated_images.seed` 填上：单张时我们本来就知道这个值
  （随机模式下就是我们自己抽的那个），以前一直留空说"需要解析 PNG 才能知道"。
  批量时服务端会派生每张图的 seed，那个值我们不知道，仍然留空。

### 21.4 为什么以前没被发现

每次生成的提示词都不一样，所以"seed 固定"这件事不会显形 —— 只有**同一提示词生成两次**
才会看到两张一模一样的图（或者去读图片元数据）。历史里显示的随机 seed 还起了误导作用：
它看起来完全正常。

### 21.5 补的测试

这个 bug 能溜进来，根因是 `GenerationRepository` **一个测试都没有**
（它依赖 Room 与 `GenerationFileStore`，后者要 Context，JVM 单测里没有）。
现在补了 `app/src/androidTest/.../GenerationSeedAndPayloadTest`，
用假的 `NovelAiApi`（不联网）+ 内存 Room + 真实文件存储，断言：

1. 随机模式发出去的 seed **不是 0**，且**历史记录里的值与之相等**；
2. 连续两次随机生成发出去的 seed **不同**；
3. 固定模式发出去的就是那个固定值；
4. 自定义分辨率下请求体发的是**画布**（1920×1088），最终尺寸只进数据库。

第 1 条在修复前必然失败 —— 这正是它存在的意义。

### 21.6 顺带记一条坑

仪器化测试里读 Room 的 Flow 要用 `first()`，**不能写 `toList().first()`**：
Room 的 Flow 永不结束，`toList()` 会一直等下去，表现为"测试跑到一半卡死"。

## 22. 提示词权重高亮（2026-09-15）

### 22.1 需求

编辑框里把"被加了权重的标签"用底色圈出来：权重 < 1 绿色、> 1 红色，圆角矩形，
像荧光笔划的底纹；**改底色不改字色** —— 反色文字会让提示词本身变难读，
而提示词是用户要逐字检查的东西。

### 22.2 认识哪些写法

`PromptWeightScanner`（纯 Kotlin，`domain/prompt`）：

| 写法 | 权重 |
|---|---|
| `{tag}` / `{{tag}}` | 1.05 / 1.05²，每层 ×1.05 |
| `[tag]` / `[[tag]]` | 1/1.05 / (1/1.05)²，每层 ÷1.05 |
| `0.9::tag ::` | 0.9，字面值 |

- 权重计算**复用 `EmphasisSyntax.strengthOf`**，不另写一套：两个入口各算一遍，
  迟早会给出不同的数；
- 按**顶层逗号**切分片段（`{a, b}` 内部的逗号不切），扫出来的区间**含语法符号本身**
  （`{}`、`0.9::`），底纹因此能把整个片段圈住；
- 与 1.0 的差小于 1e-9 的片段不产生高亮：`{[tag]}` 在浮点下会是
  0.9999999999999998 或 1.0000000000000002，直接比大小会画出一层无意义的底色。

### 22.3 渲染：为什么最后是"自己搭输入框"

底纹要画在文字**下面**，就必须拿到文字的排版结果。Material 的 `OutlinedTextField`
既不暴露内部的 `TextLayoutResult`，也不允许替换内部的输入框。

先试了官方为此留的入口 `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`，
**结果是文字被画了两遍、边框消失**：那个 `container` 参数不是"输入框的内容"，
而是**边框图层**，`DecorationBox` 自己会放置输入框。这条路依赖 Material 的内部约定，
而它的参数表在版本之间已经改过（1.3.2 与后续版本的 DecorationBox 参数就不同），
不值得继续赌。

最终做法（`ui/common/WeightHighlightedTextField`）：

- 边框、圆角、标签（外部标签）都在本文件里画，不依赖任何 Material 内边距常量；
- 底纹 Canvas 与 `BasicTextField` 放进**同一个 `Box`**，Canvas 用 `matchParentSize`，
  于是两者严格同尺寸同原点，坐标直接来自输入框自己回调的 `onTextLayout`；
- 输入框加 `fillMaxWidth()`，保证"排版宽度 == Box 宽度"，换行位置才对得上；
- 没有加权片段时**连排版都不复算**（不保存 layout）。

代价是标签变成外部标签（在框上方），放弃了 Material 的浮动标签动画。
这与本应用已有的带标签控件一致（`DropdownSelector`：张数 / 质量标签 / 模型都是这样），
对"总是有内容的提示词框"来说也更稳定。

**已知限制**：输入框内部滚动时底纹会错位。目前两个提示词框都是"随内容长高、
由外层 `verticalScroll` 滚动"，没有内部滚动，所以不构成问题；
若将来给它们加上固定高度与 `maxLines`，这里必须一并处理滚动偏移。

### 22.4 数字权重这一条要说清楚

`0.9::tag ::` 是**用户要求的语法**。`EmphasisSyntax` 里原本写着一句
"刻意不做数字权重，因为那不是 NovelAI 的语法"，而这一点**至今未经核对**：

- 因此这次只做"把它画出来"，**不替用户改写提示词**，界面上也**不提供插入这种写法的按钮**；
- 如果服务端不认这种写法，`0.9::ningen mame ::` 会被当成普通文字，
  用户会得到一张与预期不符的图 —— 这属于"用户自己选择使用"的风险；
- 要确认只需要在官方网页版粘一段进去看效果，属于**用户手动发起**的核对项，
  不得由自动化流程代发真实生成请求。

### 22.5 测试与验证

- `PromptWeightScannerTest`（17 项，JVM）：两种语法、嵌套层数、区间覆盖语法符号、
  顶层逗号、数字前缀优先于包裹语法、权重恰为 1 不高亮、未闭合括号、随机选项语法不误判；
- 真机截图验证：`{silver hair}` / `{{blue eyes}}` / `1.3::smile::` 红底，
  `[simple background]`（跨 3 行折行）/ `0.9::ningen mame ::` 绿底，圆角贴合、随换行切分。

### 22.6 深色模式下的黑字（2026-09-16 修复）

用户实测：深色主题下提示词框里是"深色底上的黑字"，几乎没法读；浅色主题下一切正常。
§22.5 的截图验证当初只在浅色主题做过，这个缺口没被发现。

**根因：裸 `BasicTextField` 不会把未指定的文字颜色解析成主题色。**
`LocalTextStyle` 在 Material 3 里的默认值是 `TextStyle.Default`（颜色 `Unspecified`），
而 foundation 的 `CoreTextField` / `TextFieldDelegate` 不做内容色兜底 ——
已核对 1.7.6 的 aar 字节码：*整个 foundation 里没有任何 `ContentColor` 引用*
（它也依赖不到 material3，模块层次上不允许）。于是 `Unspecified` 一路到文字渲染层，
按**黑色**画出来；浅色主题下黑色恰好是对的，所以只有深色主题暴露。
Material 的 `OutlinedTextField` 之所以正常，是因为它内部显式把
`focused/unfocusedTextColor`（默认 `onSurface`）merge 进 `textStyle` ——
自己搭输入框就得自己补这一步。

修法（一行）：`textStyle = LocalTextStyle.current.copy(color = textColor)`，
其中 `textColor` 取 `colorScheme.onSurface`，禁用态按 Material 惯例
`copy(alpha = 0.38f)`。光标颜色此前已显式设为 `primary`，边框与标签也一直用主题色，
因此**只有文字**要补。

真机验证（2026-09-16）：同一段全语法提示词（`{}` / `[]` / `{{}}` / `0.9::` / `1.3::`），
深色与浅色两种主题各截图一次 —— 文字分别为浅色 / 深色，红绿底纹在两种背景下都清晰。

## 23. 画廊检索与收藏（2026-09-15）

### 23.1 收藏为什么单开一张表

`favorite_images(imageId PRIMARY KEY, createdAt)`，外键指向 `generated_images(id)`
并带 `ON DELETE CASCADE`。理由：

- **不碰既有表**。加列要动 `generations` / `generated_images`（那两张被级联外键拴着），
  而收藏是纯增量功能，没有理由让它们承担迁移风险；
- 外键带级联 ⇒ 图片被物理删除时收藏行自动消失，不需要在启动清理里再补一条
  "删掉指向已不存在图片的收藏"；
- 删除生成记录走"先标记 `deletedAt`、确认后才 purge"，标记期间图片行仍在、收藏也仍在，
  **撤销删除后收藏自然还在** —— 这正是用户期望的语义；
- 主键是**图片 id** 而不是生成记录 id：瀑布流与详情页都是"一张图"，
  一批四张里只收藏其中一张是正常需求。

schema 5 → 6 只有 `CREATE TABLE`，不触碰任何既有表。真机上验证过：迁移后
`user_version = 6`、`favorite_images` 存在且 `pragma foreign_key_list` 报告 `CASCADE`、历史完好。

### 23.2 筛选为什么在内存里做

`GallerySearch.matches(item, filter)` 是**纯函数**，在 ViewModel 里用 `combine` 套一层过滤。
画廊本来就要把全部卡片取回内存（瀑布流是这样实现的），在这个列表上过滤：

- 能直接写 **JVM 单测**，不需要仪器化测试也不需要真数据库；
- 换成一条带可选参数的大 SQL（`(:query = '' OR prompt LIKE ...)`），
  只能靠跑起来才知道对不对，而历史是用户不可再生的数据，值得用更稳的方式写。

代价是每次筛选要点过全部卡片。个人使用的量级（几千条）下字符串比较可以忽略。

**关键词规则**：大小写不敏感；`_` 与空格**互相等同**（NovelAI 标签写 `silver_hair`，
用户手打会写 "silver hair"，不归一化就永远搜不到）；空格分隔的多个词是**全部命中**（AND）
而不是当成一句必须原样出现的短语。

### 23.3 两个坑

**（1）历史选图对话框不能和画廊共用 ViewModel 实例。**
两者默认的 `ViewModelStoreOwner` 相同（同一个 NavBackStackEntry），
共用实例就意味着画廊的筛选条件会作用到"从历史选图"上 —— 而那个对话框
**没有任何筛选控件**，用户只会看到"我的图少了一大半"却找不到原因。
它现在用 `viewModel(key = "history-image-picker", ...)` 拿自己的实例。

**（2）`connectedDebugAndroidTest` 不会清空应用数据，但裸读数据库会漏掉 WAL。**
排查"收藏没落库"时用 `adb exec-out run-as ... cat databases/pocketnai.db` 读到的是**主库文件**，
而 Room 默认启用 WAL —— 刚写入的行还在 `pocketnai.db-wal` 里，主库里查不到。
本次就因此误判了一次"收藏没有持久化"。要查最近写入必须把 `-wal`（必要时 `-shm`）
一起取回来放在同一目录下再打开，或者让应用自己把结果读出来。

---

## 二十四、流式中间预览（2026-09-16）

### 24.1 协议探针：一次真实生成同时完成探针与验证

规划书 §6.3 要求"开发前先做协议探针"。用户授权用免费组合直接测
（2026-09-16：模拟器账号读数 **Opus（读取自 NovelAI）**、余额 248 → 生成后仍 248，不扣费），
于是探针与功能验证合并成一次：请求参数与普通生成完全相同，只多了
`parameters.stream = "sse"`（OpenAPI 的 `image.StreamingType` 枚举，另一个取值是官方网页在用的 `msgpack`）。

| 项 | 实测结果 |
|---|---|
| 端点 | `POST /ai/generate-image-stream`（无鉴权探测返回 401 而非 404，确认路由存在） |
| 事件名 | SSE 的 `event:` 字段给类型：`intermediate` / `final`（error 帧未出现） |
| 事件负载 | JSON 键：`event_type, gen_id, image, samp_ix, sigma, step_ix`；final 帧没有 sigma / step_ix |
| 帧数 | steps=23 → **23 个 intermediate + 1 个 final**，与采样步数一一对应 |
| 时长 | 同参数约 3–12 秒（随采样波动） |
| **中间图格式** | **JPEG**（magic `ffd8ffe0`，8–16 KB/帧）—— 不是 PNG |
| final 图格式 | PNG（与 ZIP 路径一致，进历史前按 PNG 校验） |
| 进度 | 服务端只给 `step_ix`，**不给总步数**；百分比由客户端用请求里的 steps 自己算 |

### 24.2 实现链路与三个设计决定

`SseFrameReader`（逐行分帧）→ `GenerationStreamParser`（帧 → 事件）→
`OkHttpNovelAiApi.generateImageStream`（Flow，事件 + Completed/Failed 两种收尾）→
`GenerationRepository` 的流式分支（与 ZIP 分支产物统一成 `ExtractedImage`，之后的裁切/落盘/记账完全共用）→
`GenerationPreviewStore`（生成页写入、画廊占位卡读取）→ `GeneratingCard` 预览 + 进度。

1. **中间图宽容解码**：接受 PNG / JPEG / WebP / GIF 魔数，并剥掉可能的
   `data:image/...;base64,` 前缀。第一版只认 PNG（沿用 final 的校验），
   结果**预览完全不出现**——中间图被逐帧丢弃，而 final 正常，链路看起来"没坏"。
   这是本次唯一一个只有真机能发现的错误。
2. **失败不自动降级到 ZIP**（规划书 §6.3 原文："流式请求失败后不自动发起第二次生成"）。
   流式失败照常记为一次生成失败，并计入设置层的失败计数；连续 3 次失败自动关闭流式预览
   （错误文案会说明"下次生成将使用普通方式"）。用户可在设置页重新打开。
3. **诊断日志只记结构**：DEBUG 构建下每帧输出"事件名 + data 长度 + JSON 顶层键名 +
   图片 magic"，不含任何字段值。这套日志就是本次探针的取证方式，也是未来协议变化时的第一现场。

### 24.3 真机验证（模拟器 + 免费组合）

- 生成期间：占位卡显示中间图（画面逐渐长出来，早期帧是模糊色块），左下角
  "生成中 N%" 随 `step_ix` 更新（实测 9%）；
- 生成结束：预览目录整个清掉，占位卡被正式图替换，历史落盘/落库正常；
- 余额 248 → 248，**不扣费**；三次测试共消耗 0 Anlas；
- 预览文件在 `cache/previews/<generationId>/0001.png`（同序号覆盖），
  启动清理（`clearAllPreviews`）兜底进程被回收时的残留。

### 24.4 决定：默认关闭（2026-09-16 晚）

用户装到手机上体验后判断"跟网页版很不一样"，决定不启用，但**保留实现**（改一个默认值即可恢复）。

差异的来源（反解 webbundle 与本次实测对照）：

| | 官方网页 | 本实现 |
|---|---|---|
| 流类型 | **MessagePack**（`parameters.stream = "msgpack"`） | SSE（`"sse"`，有 OpenAPI 文档） |
| 中间图 | 网页设置里的 `rawIntermediates` **默认关闭**，即默认显示的是**处理过的**中间图 | 服务端给的**原始采样帧**（JPEG） |
| 显示 | 固定画布区域、按图片比例平滑过渡 | 占位卡是 1:1 方形，竖图被 `Crop` 裁切放大 |

因此本次的观感差距属于**预期之中**，不是实现缺陷。收尾动作：

- `SettingsStore.streamingPreviewEnabled` 默认值改为 `false`（设置页可手动打开，标注"实验性"）；
- 若将来重启这个功能，先解决上面表格里的后两行：**按图片比例显示**与**过渡/平滑**，
  其次再考虑是否值得换 MessagePack 流。

## 二十五、提示词输入框删除时跳行（2026-09-16 晚，用户报告）

### 25.1 现象

用户用键盘删除长提示词时，"输入框有概率跳行、跳到不同的行"，例如删掉最后一行时
光标跳到中间某行。提示词越长越容易遇到。

### 25.2 取证（模拟器 + 临时日志：只记偏移与长度，不记内容）

在 `onValueChange`、`UiState.promptTemplate`、`WeightHighlightedTextField` 三处插桩后，
用 30 连发退格复现，抓到关键三行：

```
ime sel=206..206 len=463          ← 输入框删到 463 字符，光标停在 206（正是删除点）
PnaiSync: RESET old=463 new=464   ← 同步 effect 带着**过期的 464** 执行，判定"外部改了文本"
pass sel=464..464 len=464         ← 输入框被整体重置：文本回滚一个字，光标被丢到文末
```

### 25.3 根因：镜像的刷新粒度与逐键输入不匹配

`GenerateSheet.rememberSyncedField` 原本用
`LaunchedEffect(text) { if (输入框.text != text) 整体重置 }` 把 ViewModel 的文本镜像回输入框。
问题在 effect 的执行时机：

- 每次按键都会把文本推给 ViewModel，于是 ViewModel 的文本**成了输入框自己的回声**；
- 而 `LaunchedEffect` 的 body 是派发执行的、**晚一拍**，它捕获的又是启动它那次组合的
  `text`。连续快速退格时（实测每键 ~13 ms，一帧 ~16 ms），body 拿到的是上一拍的文本；
- 再与"输入框此刻的文本"比较必然不等 → 判定为外部写入 → 整个 `TextFieldValue` 被换掉：
  文本回滚 + 光标落到那份旧文本的末尾。用户看到的就是"光标跳到别的行"。

即**删除逐键发生，而镜像一帧才刷新一次**，中间那一拍的空档就是 bug。

### 25.4 修复：文本代数（`UiState.textRevision`）

编辑框的文本与光标**归编辑框自己所有**，外面只在"整体替换"时插手一次：

- `UiState.textRevision` 只由整体替换的写入者 +1：复用历史参数、导入元数据、
  收藏夹填充（新增 `GenerateViewModel.replacePromptText`）；
- 打字路径（`onPromptChange` / `onNegativePromptChange`）**绝不**碰它；
- 输入框改为 `remember(revision) { mutableStateOf(TextFieldValue(text, 文末)) }`：
  代数不变则文本与光标全程留在输入框手里，代数一变就带着新文本重建。
  **没有 LaunchedEffect，也就没有竞态**（重建是同步的，与文本在同一次 UiState 更新里）。

### 25.5 顺带修掉一个连带 bug：导入的负面提示词被静默丢弃

`confirmMetadataImport` 只写了 `params.negativePrompt`，没写 `negativeTemplate`；
而提交时 `startGeneration` 会用模板重新解析一遍并覆盖 `params` —— 于是导入的 UC
既不在输入框里显示（输入框读的是模板），也不会进入请求。现在两处都写。

## 二十六、展开悬浮层时"先到顶、再跳回"（2026-09-16 晚，用户报告）

### 26.1 现象

把生成悬浮层拉起、向下滚一段再收起，下一次拉起时会先显示最靠前的位置（提示词区域），
然后才跳到上一次拉到的位置。

### 26.2 根因：滚动位置要等展开动画落定才生效

`GenerateSheet` 原来把滚动修饰符按 `expanded` **条件挂载**：

```kotlin
.then(if (expanded) Modifier.verticalScroll(scrollState) else Modifier)
```

而 `expanded` 取自 `SheetState.currentValue`，它**要等动画落定才翻转**（动画期间变的是
`targetValue`）。于是整个展开动画期间滚动修饰符都没挂上，内容按顶部排版；动画结束
`currentValue` 变成 `Expanded` 的那一帧才挂上，内容瞬间跳到 `scrollState` 的位置 ——
就是用户看到的"先到顶、再跳一下"。

模拟器取证（手指停在半途截图）：把表单滚到底部 → 从头部拖起并**停在中途**，
可见的表单区域显示的是"费用 / 提示词 / 正向提示词框"，而滚动位置其实在底部的参考图区块。

### 26.3 修复：始终挂载，只禁用用户滚动

```kotlin
// 顶部内边距放在滚动**外面**，其余三边留在里面
.padding(top = 16.dp)
.verticalScroll(scrollState, enabled = expanded)
.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
```

- `enabled = false` 时手势照样交给悬浮层 —— "收起时手指在表单上往上拖要能拉起悬浮层"
  这条约束不变；
- 但滚动位置一直参与排版：展开的第一帧就已经停在用户上次的位置上，没有跳变。
- **顶部内边距必须放在滚动外面**：收起时 peek 只到头部，而 peek 比头部高出的那一小条
  （本机 ≈15 dp）会露出表单顶端 —— 内边距若留在滚动里面、跟着内容滚走，露出的就是
  "滚动到一半、被截断的一行"（改完只挂载滚动后实测截图确认过）。放到外面后那一小条
  永远是空白，收起态外观与改动前一致。
- **不要改回条件挂载**（AGENTS.md"界面结构"一节已同步这两条）。


## 二十七、自动发布与应用内检查更新（2026-09-18）

### 27.1 背景

应用的 APK 此前靠人工"构建 → 拷到桌面 → 发给用户"，每次都要重复一遍。
用户要求：提交时顺手编译并发 Release，同时在应用里加自动检测更新。

### 27.2 分发形态：公开仓库 + 每次提交一个 Release

- **仓库必须公开**：应用内的"检查更新"是对 `GET /repos/{owner}/{repo}/releases/latest`
  的**匿名**请求。私有仓库的 Release 接口需要授权，而把 Token 打进 APK 不可接受 ——
  于是"自动更新"与"公开仓库"是绑定的：这是功能前提，不是偏好。
- **每次 push 到 master 就发一个 Release**（tag `build-<github.run_number>`），
  而不是滚动同一个 tag：tag 数字天然就是构建号，应用解析 tag 即可，也不必删 tag 重发；
  workflow 只保留最近 3 个 Release，列表不会无限增长。
- 固定分享链接 `releases/latest/download/PocketNAI.apk` 永远指向最新构建 ——
  把链接发给用户即可，不需要他们找版本。

### 27.3 签名：CI 必须沿用本机的 debug keystore

用户手机上已装的包是用本机 `~/.android/debug.keystore` 签的（2026-09-18 用
apksigner 与本机 keytool 的证书指纹比对确认，SHA-256 一致）。
GitHub runner 每次自动生成的 debug keystore 是随机的 —— 若用它签名，新包在既有安装上
会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装（丢历史与凭据）。
因此 workflow 把本机 keystore 的 base64 从 Secret 还原到 `~/.android/debug.keystore`，
再用 AGP 默认的 debug 签名。以后若要换正式签名密钥，前提是先解决存量安装的数据迁移。

### 27.4 版本号同源

`versionCode = github.run_number`（CI 用环境变量注入，本地构建回退 `1`）。
**Release tag 里的数字与 APK 的 versionCode 同源**，应用内比较只用这一个数字，
不引入"版本名解析"这一层（字符串比较迟早会遇到 `0.10 < 0.9`）。

### 27.5 应用内更新的取舍

- **不比较版本名**，判据是构建号大小；
- **启动时静默检查**（延迟几秒，失败不打扰），设置页可手动检查并明确区分
  "已是最新"与"没查成" —— 把后者说成前者是最糟的失败模式；
- **"稍后"持久化**（`SettingsStore.updateDismissedVersionCode`）：同一个构建提示过之后
  不再骚扰，出现更新的构建才再弹；
- **下载后做签名校验**：与当前安装的签名不一致时丢弃并明确报错。Android 安装器本来
  就会拒绝签名不一致的覆盖安装，但系统只给"应用未安装"这类含糊提示；提前校验是为了
  给出可理解的原因。旧系统上读不到签名（只有 v2 签名的包）时放行 —— 由系统安装器兜底，
  不假装校验通过；
- **不做自动下载**：与"费用/资源类动作一律由用户发起"同一原则，下载必须在对话框里点；
- 更新包落 `cache/updates/`（不是用户数据，不进 files/、不参与历史清理），
  FileProvider **只**暴露这一条路径；
- 安装前检查 `canRequestPackageInstalls()`：无授权先跳系统设置页，回来再点一次即可
  （已下载的文件会被复用，不会重新下一遍）。

### 27.6 待核对

- 实体机（MuMu 之外）上"安装未知应用"授权的跳转与返回行为；
- GitHub 匿名 API 限流（60 次/小时/IP）：正常使用一次启动最多查一次，不会触及。

### 27.7 补充：本地发布路径（2026-09-18，用户反馈"云端好慢"）

云端每次构建要在队列里等 1–5 分钟，日常迭代改用**本地一键发布**：
`scripts/publish-release.ps1`（或双击仓库根目录的"发布新版本.bat"）——
跑单测 → 构建 APK → 建 Release 上传 → 清理旧版本，一条命令完成。
两条路径**构建号同源**（都取"已有 Release 里最大编号 + 1"），不会撞号；
云端 workflow 保留为手动触发的备份。

当晚端到端实测（模拟器）：应用从 build-1 检出 build-5 → 下载 22 MB →
签名校验通过 → 引导授予"安装未知应用" → 覆盖安装成功，历史数据完好
（设置页从 0.1.0/build 1 变为 0.1.5/build 5）。

踩坑记录（三条都已写进 AGENTS.md 的硬性约定）：
- **不能无条件给 `debug.signingConfig` 赋值**（包括赋 `null`）：会清掉 AGP 预置的
  默认 debug 签名，构建"成功"但产物变成 `app-debug-unsigned.apk`；
- **`~/.android/debug.keystore` 在 GitHub runner 上不可靠**：文件放对了、
  sha256 与本地一致，但 AGP 没采用它（构建用了自动生成的新密钥，被应用内
  签名校验拦下）——云端必须用 `PNAI_KEYSTORE_PATH` 显式指定；
- **Windows 下 pwsh 读取外部程序输出默认按系统 ANSI 解码**：`git log` 的中文
  在 Release 说明里变成乱码（应用弹窗直接可见）；脚本里固定 UTF-8 输出编码，
  并把说明改经 `--notes-file` 传给 gh。


