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
