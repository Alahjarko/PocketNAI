# MCP 正文图片（2026-11-15）

本轮修复与独立角色增补已合并发布为 [0.1.40](release-0.1.40.md)，沿用此前验收的 Instabox 编译产物和原公开签名。

## 问题与改动

用户在 RikkaHub 使用 PocketNAI MCP 生图成功后，图片只出现在工具结果的小缩略图内。0.1.39 的验收确认了工具图片能交付，没有验证正文图片的尺寸和可读性。本轮以正文大图的实际显示作为验收条件。

RikkaHub 将 MCP `ImageContent` 保存为工具附件；它的 Markdown 图片组件则通过 URL 加载独立正文图片。单纯增大 ImageContent 分辨率不会改变工具卡片的显示尺寸。PocketNAI 保留原 ImageContent，并在 `get_image_result` 的 TextContent 中返回：

- `image_url`：只在同一手机上可读取的单图链接；
- `markdown`：可直接放入最终回复正文的图片标记；
- `display_instructions`：要求助手原样使用 Markdown，放在代码块外；显示失败时查询原任务，不重新生图；
- 预览宽高和原图在 PocketNAI 画廊的说明。

初始化说明、工具说明和能力描述同步给出正文图片用法。旧客户端继续接收原图片附件；已有任务可以重新调用取图工具获得链接，无需重新生成。生产预览仍是最长边 1536 像素的 JPEG，不更改生成参数、原图、费用或去重规则。

## 本机图片读取

只在原 `127.0.0.1:47761` 监听器中增加 `/mcp-image/<单图授权>` 的 GET／HEAD 响应。正文图片加载器不能添加 MCP 的 Authorization 请求头，因此取图工具在认证通过后签发一个独立的 256 位随机单图查看授权。链接不含配对值、提供商密钥、任务提示词、图片 ID 或文件路径；不能用它遍历图库或生成图片。

授权仅在内存中保留，30 分钟后失效，最多 128 个；同图重复取回时复用当前链接并续期。停止连接、锁屏关闭或重置连接令牌时清空全部链接；重连后对原任务再次取图得到新链接。旧 URL 无法向重新开启的服务下载图片。

图片请求继续检查 Host、Origin、请求方法和授权，不接受附带的查询参数。不声明 CORS，不放开 LAN、任意路径或上游明文 HTTP。每次下载重新读取该图并执行现有 SFW／文件存在性检查；停止或重开连接发生在读取过程中时，结果不再交付。只返回有界 PNG／JPEG 图片字节，不返回 HTML、SVG、重定向或原始错误。

图片响应使用 `private, max-age=86400`，允许客户端保存已经获准分享的不可变图片；MCP JSON 仍为 `no-store`。缓存与已保存的工具图片属于客户端，PocketNAI 不能撤回已经交付的副本。首次加载或缓存缺失时需要连接仍开启；缓存保留和离线历史展示取决于客户端，不能保证任意客户端永久缓存。

单图链接是用户要求的图片交付内容，允许出现在工具结果及外部助手正文；PocketNAI 不把授权存进数据库、首选项、PNG 元数据或日志，也不把配对令牌放进 URL。Room schema、FileProvider 路径、生成授权和 SFW 开关的含义均不改变。

## 验证入口

仍为 29 项 JVM + 21 项 Android，总预算 50。扩展现有 MCP 验收，覆盖授权过期／限额／清除、真实回环 GET／HEAD、PNG 字节与解码尺寸、错误来源／Host／查询参数／未知链接、SFW 拒绝、连接结束后的旧链接失效以及原任务重取且不增加生成次数。

独立 `localMcpBenchmark` 包的假接口返回 832×1216 本地几何图片，按 MCP 实际返回的 Markdown 输出正文，不自行拼接图片 URL。它的计数器跨进程保存、每张图单独保存，便于核对重新加载不会再次生成。普通 APK 不包含这些假接口。

源码 `97f5e77` 已在既有 Instabox 环境完成 29 项 JVM、21 项设备回归与生成/MCP 重点 8 项；正式 R8 包、同签名设备包和独立 R8 夹具构建成功。正式包不含测试服务，设备签名包与未签名包的非签名 ZIP 内容逐项相同，覆盖安装成功。

实际官方 RikkaHub 3.2.0 客户端的离线对照已通过：旧版只出现工具小缩略图；新版依次调用提交、查询、取图，最终正文出现已加载的 640 像素宽图片（屏幕宽 720），点击打开全屏查看器，返回恢复对话。单次流程假生图计数只增加 1，点击/返回不增加计数。使用同一手机上的假 LLM 和本地几何图片，没有真实 LLM、生图或搜索请求。

证据保存在工作区 `pocketnai/artifacts/mcp-inline-2026-11-15/`：`inline-evidence-97f5e77.tar.gz` 包含完整日志、测试 XML、结构化结果和前后截图。独立角色增补后的合并候选另见[角色记录](mcp-characters-2026-11-15.md)。

参考：[RikkaHub 的 MCP 图片交付](https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt)、[Markdown 图片组件](https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt)。只读取设计参考，没有修改 RikkaHub 或复制其组件实现。
