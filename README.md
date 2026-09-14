# PocketNAI

面向 Android 的 NovelAI 图像生成客户端。首版专注于手机端的文生图（T2I）闭环：提示词输入 → 参数设置 → 生成预览 → 本地历史瀑布流。

使用你自己的 NovelAI **Persistent API Token** 直连 NovelAI，不经过任何第三方中转服务器，也不要求提供 NovelAI 邮箱或密码。

> 规划基线见 [docs/PocketNAI-规划书草案.md](docs/PocketNAI-规划书草案.md)，实现取舍见 [docs/PocketNAI-技术决策记录.md](docs/PocketNAI-技术决策记录.md)，
> 参考图（Image2Img / Vibe Transfer / Precise Reference）的分阶段计划见 [docs/PocketNAI-参考图功能规划书.md](docs/PocketNAI-参考图功能规划书.md)。

---

## 两种连接方式

连接页提供两个入口，默认选中 **Persistent Token**（官方当前推荐的第三方接入方式）。

| | Persistent Token | 账号登录（实验） |
|---|---|---|
| 用户提供 | 一个 Token | 邮箱 + 密码 |
| 凭据处理 | 直接验证后加密保存 | 密码**只在本机**用于派生 Access Key，密码本身不保存、不上传 |
| 落盘内容 | Token | 短期 Access Token |
| 失效引导 | 去网页重新生成 Token | 重新登录 |

账号登录的链路：`邮箱 + 密码 → BLAKE2b-128 做盐 → Argon2id 派生 64 字节 → URL-safe Base64 取前 64 字符 → POST /user/login → 用返回的 Token 调 /user/data 复验 → 才保存`。
派生与协议行为由 13 个固定向量（与独立参考实现逐字节对齐）和 22 个 MockWebServer 契约测试守住。

安全边界：

- 密码与 Access Key **都不持久化**，只活在一次登录过程中；密码框在应用进入后台或离开页面时清空。
- Token 只以 Android Keystore 的 AES/GCM 密文落盘；存储的文件名、别名与密文键与旧版本完全一致，
  因此旧安装的 PST 升级后无需重新输入。
- 登录失败不自动重试、不自动刷新；登录超时不被描述成"可能已计费"。
- 账号登录是实验功能：NovelAI 官方仍要求第三方应用使用 Persistent Token，
  遇到验证码、SSO 或协议变化时请改用 PST。PST 始终可用。

**用 Google 等第三方方式注册的账号请直接用 Persistent Token。** 这类账号通常没有 NovelAI 密码，
而账号登录需要密码才能在本机派生凭据。PocketNAI **不支持也不打算支持** Google SSO：
接管浏览器会话或导入 Cookie 既超出范围，也违反本项目的安全底线。
PST 表单里有一个"打开 NovelAI 网页"按钮可以直达官网账户设置。

## 界面结构

底部导航只有 **画廊 / 设置** 两项。生成与画廊已合并到同一个首页：

```
┌───────────────────────────────┐
│  瀑布流画廊（图片陆续出现）        │
│                               │
├───────────────────────────────┤
│  生成设置          [ ✦ 生成 ]   │  ← 可上拉展开 / 下滑收起的悬浮层
│  V4.5 Curated · 832×1216 · …  │     头部（含按钮）不随表单滚动
│  下滑收起，看生成结果             │
├───────────────────────────────┤
│       画廊        设置         │
└───────────────────────────────┘
```

几处刻意的设计：

- **生成按钮在悬浮层的头部右侧**，圆角 + 阴影，形态接近官方网页版。头部在可滚动表单之外，所以表单滚到哪儿按钮都停在原地，展开和收起两种状态也都看得见。
- **按钮为什么不做成浮在画廊上的自由 FAB**：实测过，悬浮层展开时会把它完全盖住 —— 也就是用户刚编辑完参数、正要生成的那一刻按钮消失。放在头部可以同时满足「永远可见」和「不占额外高度」。
- **头部第三行是状态行**，按优先级只显示一件事：未连接 → 生成中（含进度）→ 失败原因 → 提示词为空 → 如何收起去看结果。收起态也能看到这些信息。
- **一点生成，悬浮层自动收起**，因为此刻用户想看的是图片陆续出现，而不是参数表单。
- **参数摘要常驻在收起态**，不展开也知道当前会用哪个模型、什么尺寸、什么质量标签。
- 悬浮层永远不会被完全收掉（`skipHiddenState`），不会因为一次误拖弄丢生成入口。
- 底部只有「悬浮层收起态 + 导航栏」两层，不再有额外的操作栏。

## 收藏提示词

提示词框右上角的书签按钮是唯一的收藏入口，**存成什么由有没有选中文字决定**：

| 操作 | 结果 |
|---|---|
| 选中一段文字 → 点书签 | 存成**标签**（如 `silver hair`），名称默认就是选中内容 |
| 不选中 → 点书签 | 存成**整条提示词**，名称默认从提示词提取 |

`强化 / 弱化` 旁边的「收藏夹」按钮打开列表：可以搜索、在「提示词 / 标签」两个分页间切换、
按分组浏览。点「填入」把内容**追加到末尾**（逗号分隔，不会产生重复逗号），
右侧 ⋮ 菜单里是「替换整条」和「删除」—— 会覆盖已写内容或不可撤销的操作都收进菜单，
不给它最省事的手势。

其他约定：

- 收藏内容只存本地，不访问 NovelAI 的任何用户数据接口（规划书 8.4）。
- 内容完全相同的收藏不会重复写入，避免反复点收藏堆出一串一样的条目。
- 列表按**最近使用时间**排序，常用的几条会稳定停在前几位。
- 收藏填入与质量标签追加共用同一套拼接规则（`PromptComposition`），
  所以「空提示词不产生开头逗号」这类边界行为在两条路径上完全一致。

## 参数记忆

生成页的编辑状态会自动保存，重新打开应用不用再把模型、尺寸、Steps、Guidance 等重调一遍。

- **保存范围**：模型、Resolution 档位与方向、张数、Steps、Guidance、CFG Rescale、
  Sampler、Noise Schedule、Seed 模式与数值、质量标签档位、Undesired Content 预设，
  以及**正向 / 负向提示词文本**。
- **写入时机**：状态停止变化约 0.6 秒后落盘。拖动滑杆会连续产生几十次状态变化，
  逐次写盘既无必要也会卡顿。
- **恢复时的容错**：草稿是跨版本的持久数据，旧版本保存的模型 id 可能已经不存在。
  这种情况下**只退化受影响的字段**，其余设置照旧保留；越界数值会被夹取回合法区间，
  无效的采样器组合与非法尺寸会换成该模型的默认值。只有整串数据无法解析时才回到出厂默认。
- 草稿与历史记录刻意分成两个类型（`GenerationDraft` vs `Generation`）：
  前者每次改动都覆盖，后者一旦写入就不可变，避免"改草稿顺手改了历史"。

## 标签补全

在正向提示词里输入一个词时，输入框下方会出现最多 5 条标签建议，点一下即可填入 ——
与官方网页版"打字时跳建议"是同一个体验。

- **只针对光标所在的那一个标签**。分隔符是逗号与换行；光标停在标签中间时整个标签都算数，
  填入时整段替换，不会和原有的后半截拼成一个不存在的词。
- **标签在末尾时自动补一个 `", "`**，可以直接接着打下一条；标签后面本来就有内容时保持原样，不会出现 `", ,"`。
- **停手约 0.35 秒才发请求**，少于两个字符不给建议（一个字母能匹配到的标签太多，没有参考价值）。
- **补全失败完全静默**：只是没有建议可点，不弹错误、不影响生成。这个端点不消耗 Anlas。
- 建议只在确实对应当前光标所在标签时才显示。请求在途时又改了字，那批建议会作废，而不是硬塞给你。

范围说明：目前只作用于**正向提示词**，负向提示词与详情页不触发；建议来自 NovelAI 服务端的标签表，
不做本地标签库，因此也不需要维护一份必然会过时的词表。

## 参考图 · 图生图

生成表单顶部的「参考图 · 图生图」卡片可以挂一张起点图：模型会在这张图的基础上重新生成。

- **两个选图入口**：系统照片选择器（不需要任何存储权限）与**从历史选择**（拿自己刚生成的图继续改，
  这是手机端最常用的用法）。
- **尺寸跟着源图走**：选定后 Resolution 自动切到与源图比例对应的官方预设，卡片里写明"提交尺寸 1216 × 832"。
  用户之后仍可改 Resolution —— 起点图会在**提交时**按新尺寸重新裁切，所以改尺寸不需要重新选图。
- **Strength** 控制改动幅度：越小越接近源图，越大越接近纯文字生成的结果。
- **可追溯**：历史的详情页会显示"生成方式：图生图"与起点图缩略图、源图尺寸、Strength；
  「复用参数」会把起点图一起带回编辑区（只带回 Strength 却没有图，那个参数没有意义）。
- **参考图会记住**：草稿只存本地文件索引（几百字节），重开应用后起点图仍在。
- 取图失败（图片损坏、格式不支持、体积过大、文件已被清理）都有各自的提示，且不影响已经写好的提示词与参数。

计费说明：图生图走的是同一个生成端点，因此计费方式与纯文字生成不同，额度不足时服务端会拒绝。
应用不做费用预估（沿用首版规划书 2.3 的结论）。

## 余额与费用预估

生成按钮上直接显示这次生成要花多少，头部第三行显示当前余额（点击看明细）。

**费用有四种状态，刻意不是一个数字：**

| 状态 | 按钮上 | 说明 | 什么时候出现 |
|---|---|---|---|
| 免费 | `免费` | 预计不消耗 Anlas | 官方免费条件成立，或实测确认的组合 |
| V5 额度 | `0 Anlas` | 将消耗 V5 免费额度 | V5 + Opus + 额度可用 |
| 预计 Anlas | `预计 17` | 能算出确定金额 | 基础费用确定（含每张参考图 +5） |
| 待确认 | `费用待确认` | 当前组合的费用以 NovelAI 为准 | 定价未校准、等级未知、参数不支持 |

**为什么会有"待确认"**：NovelAI 没有公开的报价接口，本地公式必须先用官方网页的费用标签校准。
未校准的组合一律不给数字 —— 给一个看起来精确、实际可能错的数字，比不给更糟。

**余额是服务端事实，费用是本地推算，两者严格分开：**

- 余额来自只读接口 `GET /user/subscription`，分订阅池与购买池，另有独立的 **V5 免费额度**
  （它不是 Anlas，不与余额相加）；
- 余额只在内存中缓存 5 分钟，**不落库**：避免上一个账号的余额显示给下一个账号；
- 读取失败只影响余额区域，不清零、不覆盖生成错误、**不阻止生成**；
- 本地预估余额不足时只弹一次非阻断确认，最终能不能生成仍由服务端 402 决定；
- 生成后自动刷新一次，给出"**本次观察到的余额变化**"—— 这是观察值不是账单
  （同账户可能在别处消费、可能充值、服务端更新可能有延迟）。

## 当前进度

已完成首版第一层能力的完整实现，可以编译、可以跑单元测试、可以在真实设备上验证生成流程。

| 能力 | 状态 |
|---|---|
| Token 导入 / 验证 / 替换 / 删除 | ✅ 已实现 |
| 账号登录（邮箱 + 密码 → 本地派生 Access Key，实验性） | ✅ 已实现 |
| Token 的 Android Keystore 加密存储与日志脱敏 | ✅ 已实现 |
| 四个模型（V4.5 / V5 × Curated / Full） | ✅ 已实现 |
| T2I 生成（ZIP 传输 → 逐张 PNG 校验落盘） | ✅ 已实现 |
| 正向 / 负面提示词、模型、尺寸、张数 | ✅ 已实现 |
| Resolution 选择（档位 Normal/Large × 横竖方，显示实际像素） | ✅ 已实现 |
| 质量标签三档（Standard / Light / None，追加到提示词末尾） | ✅ 已实现 |
| Steps / Guidance / CFG Rescale / Sampler / Noise Schedule | ✅ 已实现 |
| 随机与固定 Seed | ✅ 已实现 |
| Undesired Content 预设 | ✅ 已实现 |
| 生成中 / 成功 / 失败状态与错误映射 | ✅ 已实现 |
| 本地历史（Room + 私有目录 + 瀑布流画廊） | ✅ 已实现 |
| 记住上次的参数与提示词，重开不用重调 | ✅ 已实现 |
| 收藏提示词 / 标签（本地 Prompt Chunks，可搜索与分组） | ✅ 已实现 |
| 生成 / 画廊合并为首页，生成为可上下拖动的悬浮层 | ✅ 已实现 |
| 深色 / 浅色主题，可跟随系统或手动固定 | ✅ 已实现 |
| 长按保存到相册 / 删除 / 复制提示词 | ✅ 已实现 |
| 详情页复用参数 | ✅ 已实现 |
| 删除的撤销窗口 | ✅ 已实现 |
| 启动恢复（孤立文件清理、卡住任务收尾） | ✅ 已实现 |
| 强调语法便捷编辑 `{}` / `[]` | ✅ 已实现 |
| Prompt Randomizer（本地展开并冻结快照） | ✅ 已实现（PocketNAI 自有语法，见下） |
| 流式中间预览 | ⛔ 未实现（第二层，接口位已留出，开关与自动关闭策略已就绪） |
| NovelAI 标签建议（输入时补全，点击填入） | ✅ 已实现 |
| Image2Img 图生图（单张起点图 + Strength） | ✅ 已实现 |
| 账户余额（订阅 / 购买 Anlas 分池 + V5 免费额度） | ✅ 已实现 |
| 生图费用预估（免费 / V5 额度 / 预计 Anlas / 待确认 四态） | ✅ 已实现 |
| 生成后观察到的余额变化 | ✅ 已实现 |
| Prompt Chunks / 收藏提示词与标签 | ✅ 已实现（名称、内容、分组、搜索、填入、替换、删除） |
| PNG 元数据读取与参数恢复 | ⛔ 未实现（第二层） |
| 分享 / 导出 | ⛔ 未实现（第二层） |

Inpaint / Upscale、Vibe Transfer、Precise Reference、多角色 Prompt 仍未实现；
分阶段计划见 [docs/PocketNAI-参考图功能规划书.md](docs/PocketNAI-参考图功能规划书.md)。

---

## 构建

### 环境要求

- JDK 17
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- 不需要单独安装 Gradle，用仓库里的 wrapper

### 命令行

```bash
# 首次运行前指向本机 SDK（该文件已被 .gitignore 排除）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug        # 构建 Debug APK
./gradlew :app:testDebugUnitTest    # 运行单元测试
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### Android Studio

直接用 Android Studio 打开项目根目录即可。项目使用 Gradle Kotlin DSL 与 version catalog（`gradle/libs.versions.toml`），所有依赖版本集中在该文件。

---

## 项目结构

```
app/src/main/java/net/pocketnai/
├── PocketNaiApplication.kt
├── di/AppContainer.kt                 手写依赖装配（无 DI 框架）
├── core/                              纯 Kotlin，无 Android 依赖
│   ├── AppError.kt                    ErrorCode / AppError / Outcome
│   ├── LogRedaction.kt                日志脱敏（Token 与 Prompt 一律不落盘）
│   └── Hashing.kt
├── domain/                            纯 Kotlin，无 Android 依赖
│   ├── model/                         ImageModel / ModelProfile / ModelCatalog / GenerationParams / Generation
│   └── prompt/                        PromptTitle / EmphasisSyntax / PromptRandomizer
├── data/
│   ├── network/                       NovelAiApi / 请求构造 / ZIP 解包 / PNG 校验 / 错误映射
│   ├── security/                      Keystore TokenStore / SessionState
│   ├── local/                         Room 实体 / DAO / 映射
│   ├── files/GenerationFileStore.kt   私有目录布局与原子落盘
│   ├── export/MediaStoreExporter.kt   复制到系统相册
│   ├── settings/SettingsStore.kt
│   └── repo/GenerationRepository.kt   生成流程与历史读写的唯一入口
└── ui/                                Compose + Material 3
    ├── connect/ generate/ gallery/ detail/ settings/
    ├── state/GenerationDraftStore.kt  详情页 → 生成页的“复用参数”通道
    └── common/ theme/
```

`core/` 与 `domain/` 刻意不依赖任何 Android API，因此请求构造、提示词解析、参数校验、ZIP 校验这些最容易出错的逻辑都能在 JVM 单元测试里直接断言，不需要仪器测试或模拟器。

---

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖范围：

- **请求体结构**（`NovelAiRequestBuilderTest`）：V4.5 与 V5 的 `v4_prompt` / `v4_negative_prompt` 结构化提示词、`char_captions` 必须为空数组、`use_coords = false`、API 原始取值（`sampler`、`noise_schedule`、`scale`、`cfg_rescale` 等）、无效组合会被归一化。
- **ZIP 解包与校验**（`ZipImageExtractorTest`）：按 entry 顺序编号、PNG 签名与 IHDR 解析、非 PNG 与路径穿越 entry 被拒绝、entry 数与大小上限、损坏 ZIP 不崩溃、失败时清理临时文件。
- **参数与模型档案**（`ModelCatalogTest`）：四个模型身份、默认组合合法、Sampler × Noise Schedule 约束、越界数值夹取、非法尺寸回退。
- **提示词工具**：标题提取与文件名清理（`PromptTitleTest`）、强调语法权重（`EmphasisSyntaxTest`）、Randomizer 展开与组合数（`PromptRandomizerTest`）。
- **错误映射**（`NovelAiErrorMapperTest`）：401 / 402 / 429 / 5xx / 带提示的 400 分类、超时映射为“结果不确定”、错误明细截断。

---

## 隐私与安全

- Token 只存在 Android Keystore 保护的 AES-256/GCM 密文中，首选项文件 `pocketnai_secure` 已从云备份与设备迁移中排除。
- Token 不会写入数据库、图片元数据、剪贴板或日志。
- 日志只记录请求的方法、路径、状态码与耗时；`Authorization`、请求体、响应体一律不读取。项目不引入 OkHttp 的 `HttpLoggingInterceptor`，避免被误开成 BODY 级别。
- Prompt 在日志里只记录长度与哈希指纹，不记录原文。
- 复制提示词只复制用户看到的正向提示词。
- 应用不建立开发者中转服务器，不自动上传历史、图片或 Prompt。

---

## 已知限制与下一步

1. **所有请求都必须走 `image.novelai.net`**。NovelAI 的 `api.novelai.net` 已不再接受第三方工具的 Persistent API Token（返回 400 `If using a third-party tool, update to the image URL.`）。代码里只保留一个 API 主机常量，并新增了 `ErrorCode.CLIENT_UPDATE_REQUIRED`，让这类迁移错误直接提示“需要更新客户端”，而不是误报成“参数无效”。详见技术决策记录 3.1。
2. **四个模型 ID 已用真实账户证实有效**：`suggest-tags` 会校验 `model` 参数（伪造模型名返回 400），四个 V4.5 / V5 的 Curated / Full ID 全部返回 200。详见技术决策记录 3.4。改动模型 ID 后可以先用这个免费探针验证，不必发起真实生成。
3. **默认参数部分已核对**。`Steps = 23`、`Prompt Guidance = 7.0`、质量标签默认 `Standard`、Resolution `Normal` 档位的三组像素，均已按官方网页版固化。仍需核对的是 Resolution `Large` 档位的像素组合、Sampler × Noise Schedule 组合表、V5 的默认档位，以及 Guidance / CFG Rescale 的合法区间。完整清单见技术决策记录 3.7。
4. **`qualityToggle` 固定发 false**。质量标签由客户端追加进提示词，因此关闭了服务端的自动追加以免叠加两次。这条推理尚未用真实生成结果反查确认（验证方法见技术决策记录 3.5）。
5. **流式预览只有壳**。`GenerationEvent.Intermediate`、`StreamingGenerationTransport` 接口位、失败计数与自动关闭策略都已就位，但传输实现未落地。
6. **随机 Seed 模式下每张图的真实 Seed 未知**。因此 `GeneratedImage.seed` 在随机模式下留空，而不是用“基 Seed + 序号”猜一个值。要填上需要解析 PNG 元数据（第二层能力）。
7. **没有前台服务**。生成期间切到后台，进程被系统回收后该任务会在下次启动时被标记为失败并提示“结果状态不确定”。
8. **NSFW 隐私策略未实现**。最近任务缩略图、通知预览、应用内画廊三处的具体策略待定。
9. **不要用 `subscription.tier == 0 && active == false` 判断账户能否生成图片**。按量购买 Anlas 但不订阅的账户同样是这个表现，却能正常生成。额度不足应由服务端在提交生成时返回 402，由 `INSUFFICIENT_ANLAS` 如实告知。
10. **数据库存在一个遗留列**。`generations.qualityTagsEnabled` 自 v2 起已无业务含义，仅为 Room 的表校验兼容而保留（`generated_images` 以 ON DELETE CASCADE 引用 `generations`，重建表会连带删除用户的图片记录）。原因见技术决策记录与 `GenerationEntity` 注释。

### Prompt Randomizer 语法

这是 PocketNAI 自己的约定，因为 NovelAI 的提示词语法没有随机选项：

```
1girl, <red|blue|green> hair, <smile|laugh>
```

每次点击生成时先固定展开结果，再把展开后的提示词作为请求快照；历史同时保存模板原文与本次实际提示词。用 `\<` 输出字面量 `<`。不支持嵌套。
