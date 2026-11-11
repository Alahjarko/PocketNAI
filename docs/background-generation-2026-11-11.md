# 锁屏与后台生成修复（2026-11-11）

## 原因与实现

抽卡实验室已有专用前台服务；首页NovelAI、OpenAI生成和绘伴原先没有对应保护。绘伴还由Activity的ViewModelStore持有，页面所在Activity结束会取消回复/后续工具。

- 新增进程级`AndroidBackgroundTasks`，首页NovelAI、OpenAI与绘伴共用一个`dataSync`前台服务。用户点击生成/发送时同步登记，在服务就绪后才允许发送请求；不在LLM返回图片工具时才尝试从后台启动服务。
- 各任务持独立票据，服务按全部活跃票据更新通知。某一任务完成不影响其他任务；最后一项退出时移除通知、释放唤醒锁并停止服务。不会携带提示词、密钥或请求参数进入服务Intent/通知。
- CPU部分唤醒锁每次有10分钟超时，工作中每5分钟续期；等待图片确认时释放，确认后重新获取。保留原有用户停止、串行间隔、OpenAI最多三路并发及单次请求不重试策略。
- 绘伴改由`AppContainer`持有，与普通生成共用进程生命周期。回到页面接回同一份当前状态，不重建回复或重发POST。
- 服务不设置自动重启，不序列化待发送请求。保护失效后阻止尚未发送的请求；已发送的图片请求继续尽力保存返回结果。进程被强制结束/系统超时不代表生成一定未计费，重启仍按既有不确定结果处理，不自动重试。

## 系统边界

Android限制后台启动前台服务，因此保护从用户可见的点击开始，见[启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。Android15及以上对`dataSync`前台服务另有累计时限，达到时限时本服务执行清理并停止后续工作，见[服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)。唤醒锁只在任务工作时持有，遵循[释放要求](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/release)。

本轮不申请忽略电池优化、不改变真实生成权限、不增加重试；强行停止应用、断网或厂商额外限制仍可能中断任务。

## 验证

扩展既有`realForegroundServiceCompletesFakeBatchWithHomeAndScreenOff`，没有新增测试方法，注册预算仍为29项JVM加21项设备测试。夹具使用隔离首选项、内存Room、临时文件及假LLM/生图接口，不使用用户凭据或真实请求。

专项在已有Instabox Android14设备通过，覆盖：

- 从真实前台Activity启动普通生成，重建并结束Activity，再按Home/熄屏，仍只调用一次假API并保存结果。
- OpenAI与绘伴共用服务；OpenAI先结束后，绘伴继续受保护。LLM在页面结束、屏幕关闭后返回图片工具，等待用户确认时CPU唤醒锁释放，确认后重新获取并完成单次NAI请求。
- 每次最后一个任务结束后，任务票据、前台服务与活跃唤醒锁均清零；保护启动失败时不发送请求、不留下任务占用或生成记录。
- 通知截图使用临时授予的通知权限，截图后恢复原先未授权状态。完整设备回归另在通知权限未授权的状态运行，避免依赖该可选权限才能后台执行。

检查唤醒锁时仅读取`dumpsys power`的活跃列表；系统保留的ACQ/REL历史不代表当前仍持有。截图与构建/测试日志保存在工作区`artifacts/background-generation-2026-11-11`。

源码`e484edf`的29项JVM与21项设备回归全部通过；生成链路7项先行回归及后台专项另行通过。文档同步后的`5a39bd6`完成R8优化构建、29项JVM复核和设备覆盖安装，0.1.37沿用原公开签名发布。包内容、签名、标签与附件核对见[发布记录](release-0.1.37.md)。
