# MCP 独立角色（2026-11-15）

## 问题与用法

PocketNAI 生成内核与绘伴已有独立角色，但本机 MCP 的工具 schema、参数白名单和后端投影缺少这些字段，导致外部助手只能把全部人物塞进一个 prompt。现在 `submit_image_task` 的 NovelAI 请求支持 `characters` 和 `use_coords`，复用已有 `ImageChatTools`、`GenerationParams`、请求构造与历史存储，不通过单管道猜测角色，也不改首页草稿。

```json
{
  "request_id": "two-characters-example-001",
  "provider": "novelai",
  "model": "nai-diffusion-5-full",
  "prompt": "two people, garden",
  "negative_prompt": "lowres",
  "use_coords": true,
  "characters": [
    {"prompt": "1.2::blue hair::, white dress", "negative_prompt": "hat", "x": 0.2, "y": 0.3},
    {"prompt": "red hair, black coat", "negative_prompt": "glasses", "x": 0.8, "y": 0.7}
  ]
}
```

每个角色的 `prompt` 必填、非空，角色正/负向各最多 8000 字符。负向默认空；角色数组顺序保留。x/y 为画布中心的 0–1 数值，省略的轴默认 0.5。提供任一坐标时默认启用手动站位；显式 `use_coords=false` 让 AI 安排。V4.5 使用原有 5×5 网格，V5 保留自由坐标；确认摘要显示归一化后的实际参数。模型上限沿用当前内核：V4.5 六个，V5 三十二个。

权重使用各自提示词内的 NovelAI 语法，如 `1.2::blue hair::`，不伪造上游没有的独立 weight/CFG 字段。V5 Medium 使用固定负向；带角色排除词时明确提示先在生图面板改为 High，不静默丢掉排除词。OpenAI 请求不能使用这两个 NovelAI 专用字段。

省略或传空角色数组即为无角色，不继承首页角色、参考图或蒙版。未知角色字段、非法类型/坐标、角色超限在任务发送前拒绝。宏和旧随机模板仍由共用入口展开并冻结；角色正/负向与基础词一起检查凭据，当前 SFW 开关仍按既有规则检查所有正向，负向不参与敏感判定。没有新增题材规则。

## 确认、持久化与去重

任务摘要显示角色数量和布局；「查看提示词与角色」展示每个角色的正/负向和实际站位。`McpImageJob.characterDetails` 是兼容旧记录的可选字符串，默认空；原 AtomicFile 目录、永久去重墓碑与重启不恢复发送的规则不变。写入前校验与读取相同的 128 KiB 上限，超大展开明确报错，不写出下次无法读取的记录。正式生成历史继续保存完整角色和布局字段，不增加 Room schema。

`get_image_capabilities` 给出各模型角色上限、布局与权重用法。提交/查询任务附上实际角色摘要，外部助手可以确认接收结果。修改角色必须使用新请求编号；复用相同编号但改变角色参数会被拒绝，不生成第二次。

## 来源和验证

参考用户的 [NAI-CLI Characters.kt](https://github.com/Alahjarko/NAI-CLI/blob/72cb5b763e23c4cf84fbd2a70fe8275ed8e91cd0/src/main/kotlin/net/naicli/commands/Characters.kt) 与 [提示词文档](https://github.com/Alahjarko/NAI-CLI/blob/72cb5b763e23c4cf84fbd2a70fe8275ed8e91cd0/docs/PROMPTS.md)：角色描述/排除词/站位分别进入正负 `char_captions`。没有照搬 CLI 旧快照的五角色上限或不保存角色历史的限制。

扩展既有协议、MCP 回环与生成链路测试，保持 29 项 JVM + 21 项 Android：校验双角色正负对应、位置、权重文本、自动布局、V4.5 网格、V5 上限、错误类型与未知字段、首页角色隔离、任务记录保存/超限不覆盖、角色修改的去重冲突，以及经过真实生成仓库的假 API 请求与历史详情重读。独立夹具使用同一 MCP 解析器及请求构造器核对角色数组，RikkaHub 原生客户端验收仍走本机假 LLM 和假图片，不消耗服务额度。

合并源码 `2e462d74be4a7e89c7955daaba02b16aac850496` 已通过 29 项 JVM、21 项 Android 回归及生成/MCP 重点 8 项。首次增补的历史测试复用了原夹具固定编号，读取了上一份记录；仅隔离第二次假生成的编号后重跑，全绿，生产生成编号与历史行为没有改变。

正式 R8 包与独立 R8 夹具均构建成功，正式包无假接口入口，签名后的设备包与未签名包的非签名 ZIP 内容一致，原位覆盖安装成功。候选为 versionCode 40 / 0.1.40；未签名 APK SHA-256：`0658fce470abff3876deeef3f61a9d03976aebddad30ea08a74b329fd36cd289`。这是待发布构建；本次没有触发 GitHub 构建或签名工作流，也没有公开 Release。

官方 RikkaHub 3.2.0 的真实原生客户端完成了本轮双角色验收：重新连接后读取新 schema，提交含双角色正/负向、权重及二维坐标的请求；默认确认模式下计数保持 2，确认页完整展示角色 1 `(0.2, 0.3)`、角色 2 `(0.8, 0.7)` 和各自排除词。点击确认后计数变为 3，请求构造器核对正负数组顺序与坐标一致。等待期间只查询原任务，`submit_image_task` 一次；最终以 MCP 实际返回的 Markdown 显示 640 像素宽正文图片，点击全屏、返回后计数仍为 3。逐一查看了确认页、正文图片和全屏截图，确认为已加载的新双几何图，不是占位片或旧缓存。

工作区证据：`pocketnai/artifacts/mcp-characters-2026-11-15/` 的 `acceptance-evidence.tar.gz` 包含测试日志/XML、结构化原生结果、截图与设备设置恢复记录；同目录保留未签名发布候选、云设备签名测试包和隔离假接口包。测试服务已在界面停止，夹具和客户端进程关闭，字号与屏幕超时恢复原值。更新后外部助手需要重新连接/刷新一次 MCP 工具，才能读取新增角色参数。
