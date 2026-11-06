# NovelAI V5 Full Effort 核对（2026-11-06）

依据官方 [Effort 文档](https://docs.novelai.net/en/image/effort/) 及当日匿名可读的网页前端。未调用真实 NovelAI 生图，未消耗 Anlas；下列“核对”是官方客户端协议和公式核对，不是服务端成功生成保证。

## 参数与界面

- 默认 High，保留原 V5 Full 请求。Medium 仅用于 V5 Full，包括其局部重绘；其它模型不展示此项，归一化为 High。
- Medium 通过模型 ID 选择：普通生成 `nai-diffusion-5-full-medium`，局部重绘 `nai-diffusion-5-full-medium-inpainting`。不存在自行新增的请求参数 `effort`。
- Medium 固定 14 步、`k_euler_ancestral`、V5 的 `karras` 调度，省略 `cfg_rescale`。Guidance 仍可调。
- 官方 Medium 的 `fixedSettings` 为 `{steps:14,sampler:"k_euler_ancestral",ucPresetId:"heavy"}`。请求仍发送由 Heavy 预设生成的负向前缀，忽略自定义基础负向和角色负向；不是删除整个负向字段。正向含已有 NSFW 标签时，继续沿用现有预设前缀规则。
- 编辑草稿保留 High 下填写的负向内容，切回 High 可继续编辑；Medium 发送前冻结为实际有效的 Heavy 预设和空自定义负向，历史不会把未发送的词冒充实际参数。
- 草稿 DTO、历史 `effort` 列、详情、PNG 导入、对话图片上下文和完整参数共同保留该值。旧草稿/历史缺失值为 High。官方 PNG `Source` 哈希 `93F4BD30` / `70AB5786` 映射到 V5 Full Medium。
- 开启 Opus Normal 最低 28 步选项时，Normal 与 Medium 的固定 14 步不兼容；界面阻止该组合，不发送 Medium + 28。自定义尺寸不受 Normal 下限限制。

## 计费

令 `A=max(width*height,65536)`，`a=2951823174884865e-21`，`b=5753298233447344e-22`。

Medium 的单图基础价为 `max(ceil(((a*A+b*A*23)/1.75)*1.5*strength),2)`。先用 23 步成本折算，不是在原公式里简单把步数改成 14；除以 1.75 前不取整。原有订阅免费额度、张数和附加费逻辑继续应用。

已固定离线断言：832×1216 为 15 Anlas，强度 0.5 为 8；1536×1024 为 22。以上是付费分支示例，不能作为账号当前免费权益或服务端扣费保证。

## 可复核来源

以下均为 `https://novelai.net/_next/static/chunks/` 下的官方脚本，抓取时部署参数 `dpl=b94bdee-41520193098-1`。本地调查文件保存在被忽略的 `.tooling/effort-20261106`，不会进入 APK 或提交。

| 脚本 | 用途 | SHA-256 |
|---|---|---|
| `1qr-_uukhj8ho.js` | 模型 ID、固定参数、元数据哈希 | `c86de7b60f86da1c96ecc8de60c5a7bace6aea750279733769997401d976196a` |
| `0vm-a10r598mm.js` | fixedSettings 应用、固定 Heavy 与角色负向清空 | `439922ea9879ae9d5b4e4b147eb97bf3711e1356995b6b73c1ec450802c50515` |
| `274ff5q_u23hy.js` | Medium 成本公式及 V5 倍率 | `875cacaec9486695b79aa3819f7191d4ef0f3752517336200aa67482468b6980` |

`3rft98kuftgbq.js` 的请求构造还确认 V5 调度与按模型能力省略 CFG Rescale；`3ayz70v6mlfvn.js` 的网页界面按 fixedSettings 隐藏固定的步数/采样器控制。
