# 构建、设备验证与待核对事项

本文件是 [根开发约定](../../AGENTS.md) 的专题补充。修改相关功能前必须阅读；跨模块改动需同时阅读索引指向的其他专题。历史实测记录保留其日期，不构成自动发起真实生成、登录或付费调用的授权。

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

## 在设备上跑测试之前

- **`:app:connectedDebugAndroidTest` 默认会在结束时卸载被测应用**，
  而卸载 = 清空应用数据：本机凭据（Keystore 加密）与全部生成历史一起消失。
  已在 `gradle.properties` 里设 `android.injected.androidTest.leaveApksInstalledAfterRun=true`
  挡住这个默认行为；**但换机器/换 IDE 时先确认这条还在**。
- 仪器化测试里读 Room 的 Flow 用 `first()`，**不要写 `toList().first()`** —— Room 的 Flow
  永不结束，`toList()` 会一直等下去，表现是"测试卡死"。
- 仪器化测试（`app/src/androidTest`）覆盖必须依赖 Android API 的行为，例如：
  生成结果的裁切与元数据保全、Room 迁移、分辨率控件文案，以及假 API + Room + 文件存储的生成链路。
  纯逻辑一律放 JVM 单测，不要往 androidTest 里塞。
- 真机截图验证需要应用里能连上账号。测试机的应用数据被上面那条清过一次
  （2026-09-14），遇到"怎么又要重新连"时先想这件事。

## 待核对清单

不要把"按公开 API 语义整理的值"当成官方默认值。当前仍待核对的项集中在
[docs/PocketNAI-技术决策记录.md](../PocketNAI-技术决策记录.md) 第 3.7 节，
改到相关代码时先看一遍。

局部重绘（Inpaint）的调研与当前状态见
[docs/PocketNAI-局部重绘功能规划书.md](../PocketNAI-局部重绘功能规划书.md)（§9 是实施记录）
与技术决策记录第十七、十八节。**实现已完成并真机验证通过，四个模型全部开放** ——
早期"服务端拒绝 infill"的结论是模型 ID 用错所致，别再按那条旧结论处理。详见[图片专题](images.md)的“局部重绘（Inpaint）”一节。

参考图功能（Image2Img / Vibe Transfer / Precise Reference）的清单见
[docs/PocketNAI-参考图功能规划书.md](../PocketNAI-参考图功能规划书.md) 第 3.4 节。
最大张数（A2）、计费（A4）与**滑块初值**都已核对（2026-09-18，技术决策记录 §30.7）：
img2img Strength 0.7 / Noise 0、Precise Reference 三滑块全 1.0、Vibe 0.6 + 1.0
（官方对 V4.5 Full 的 Information Extracted 用 0.7，我们统一 1.0，差异已写进注释）。
**别再把这些值当"经验值"改回去。**

仍未核对的只剩两项：**导演工具（`/ai/augment-image`）的单价**（前端不计价、文档不给数字，
只能靠一次真实调用后的余额差反推，见 §30.5）与**代理 3 秒超时在真机+海外节点是否偏紧**（§29.3）。

低成本的协议探针技巧：`GET /ai/generate-image/suggest-tags?prompt=x&model=<模型ID>` 会校验模型 ID（无效返回 400），
可以用它零成本核对模型 ID，不必发起真实生成。该端点现在也是**标签补全**的数据来源
（见技术决策记录第八节）：不消耗 Anlas，但匹配是**包含式**而非严格前缀，建议里可能出现与输入无关的词。
