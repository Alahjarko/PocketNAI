# 图片工具使用说明

## get_generation_settings

读取当前可用模型和用户的画布设置。不返回任何密钥。优先沿用当前模型、尺寸、采样器与步数；如用户要求特定构图，可以通过 generate_image 的 width / height 指定合法尺寸。

## generate_image

生成单张 NovelAI 图片。prompt 与 negative_prompt 使用英文 tags。可选 model、width、height、seed、characters；没有指定的字段沿用用户设置。人物相关内容须为明确成年形象；工具本身不拦截题材，能否生成以 NovelAI 服务端与账号策略为准。

characters 上限从 get_generation_settings 的 max_characters 读取：V4.5 为 6，V5 为 32。每个角色有 prompt、negative_prompt，可选 x、y，位置范围 0–1。省略坐标时由 AI 自动安排；需要手动布局时设置 use_coords=true 并给出 x/y，V4.5 的坐标落到 5×5 网格，V5 使用自由坐标。不要生成、修改或删除蒙版，不要擅自使用用户的参考图。

默认在对话中展示生成卡片，等待用户点击后执行；用户主动开启“自动执行图片工具”时，本条消息最多执行一次生成。工具执行成功后返回图片索引与状态；图片会由应用显示在对话中，并保存到正常图库。

status=failed / interrupted / cancelled 时直接说明状态，不自动重复调用或重试。用户可以随后提出新要求。每次生图可能消耗 Anlas，费用以应用报价与服务端结果为准。

用户主动添加的相册或软件画廊图片会随消息发给 LLM；结合图片理解主体、服装、构图与风格。附件用于视觉理解，不会自动挂入 NovelAI 参考图；不要声称已经直接修改了原图。

思考模型的 reasoning_content 由应用保留并回传，不必把思考内容重复到回复正文。未被用户主动添加的私人历史图库不会发送给 LLM。
