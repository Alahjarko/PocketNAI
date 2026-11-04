# 150张混合画廊与SFW滚动性能验证

## 测试场景

用户指出多图画廊、尤其SFW开启后的滚动掉帧，要求约100张带敏感提示词的图片和50张普通图片。本轮使用代码生成的纯色格子图，不调用任何生成服务，不消耗Anlas。

- 150个不同文件、150个不同图片身份，100张提示词为 `checkerboard landscape, 0.001::nsfw::`，50张为普通场景提示词。分类调用生产 `SfwPolicy.containsSensitivePrompt`，实际计数为100/50。
- 尺寸为832×1216、1216×832和1024×1024；本夹具带标记图片为前两种，普通图片为方图。前后比较使用完全相同的文件和顺序。
- 独立包名 `net.pocketnai.gallerybench`，使用普通Application并移除INTERNET权限。不会初始化生产账号容器、读取凭据、修改用户Room数据库或把样本塞进真实历史。
- 直接使用生产 `GalleryMotionGrid`、`GalleryMotionCard`、`SfwAsyncImage`、主题与图片动效根容器。数据是内存GalleryItem列表，SFW索引为预先计算的稳定集合；不测数据库首载及后台生成期间的索引变化。
- 以固定速度自动遍历到末尾再回到顶部，先COLD再WARM，两轮各约13.98秒，报告确认看到了全部150张。COLD指进程内首次遍历，初始可见图片在计时前已加载，PNG文件系统缓存可能已热，不等同于系统冷启动。

环境为同一MuMu 6.8.8 / Android 15 / 1080×1920 / 180Hz，正式R8优化构建。计时阶段没有并行编译任务。通过Coil事件记录请求、内存命中、模糊次数和耗时；通过FrameMetrics记录完整窗口帧时间；通过ART统计分配量与GC，通过Process统计CPU时间，通过Debug.MemoryInfo记录PSS。计数器与测量开销在对照和修改版均开启。

## 发现与修复

1. **混合缓存反复淘汰小图**：原缓存预算为40,265,318字节，普通图和48px SFW图进入同一个LRU。150图往返后，再次遍历仍重复模糊42–46次。模糊输入最大边始终48px，排除了这组样本发生全尺寸模糊的猜测。
2. **总预算内保护小图**：`SfwImageMemoryCache` 首次收到SFW结果时，从原预算中划出最多2MiB（小内存预算最多四分之一），保护独立版本键的小图；普通图保留剩余空间。没有使用SFW的会话继续使用完整预算；首次切分迁移原有普通图缓存。两区继续使用Coil原来的强/弱引用LRU及trim回调，clear同时清理两区。未增加磁盘缓存，也未改变模糊强度或原图。
3. **取消历史图加载完成后的无用重组**：历史卡片不参与揭图，onSuccess无需再写decoded状态；只有活动任务更新这个状态。解码失败、活动任务对勾及原位揭图保持原有逻辑。

同一个诊断APK内仅切换缓存策略的单变量比较（SFW开启，WARM）：

| 指标 | 原LRU | 保护小图 |
|---|---:|---:|
| 请求数 / 内存命中 | 418 / 354 | 418 / 392 |
| 重复模糊次数 | 42 | 0 |
| 模糊总耗时 | 45.83ms | 0ms |
| CPU时间 | 4666ms | 4418ms |
| 运行中分配字节数 | 34,062,384 | 31,514,672 |
| 帧P95 / P99 | 3.72 / 6.19ms | 3.41 / 4.28ms |

最终代码加入历史卡片重组修正后，两次独立进程复跑SFW WARM：

| 指标 | 第一次 | 第二次 |
|---|---:|---:|
| 已见图片 | 150 | 150 |
| 请求数 / 内存命中 | 418 / 392 | 416 / 390 |
| 重复模糊次数 / 加载错误 | 0 / 0 | 0 / 0 |
| CPU时间 | 2570ms | 2635ms |
| 运行中分配字节数 | 29,999,152 | 30,130,224 |
| 帧P95 / P99 | 1.88 / 5.26ms | 1.87 / 10.89ms |
| 超8.33ms / 超16.67ms帧 | 4 / 1 | 60 / 2 |
| PSS | 50,012 KiB | 50,183 KiB |

关闭SFW的最终WARM仍使用原完整预算40,265,318字节，缓存实际占用39,854,304字节，与原方案一致；416次请求命中191次、模糊0次、错误0次，CPU 4371ms，分配42,917,936字节。普通图工作集超出缓存容量，仍有重复解码；本轮没有通过无限增大缓存来掩盖该成本。

## 结论边界

重复模糊归零、内存命中上升和分配量减少在多次运行中可重复。CPU与帧耗时受宿主调度、JIT和模拟器图形驱动影响，不能把一次CPU差值全部归因于某个小改动。第二次仍有10.89ms的P99和少量长帧，不能声称已经彻底消除尾部掉帧。

这组简单格子PNG中，SFW开启并不比关闭整体更慢；它定位了一个实际的混合缓存问题，但没有完整重现用户手机上所有SFW掉帧。样本解码复杂度低于真实大体积插画，不覆盖所有图像压缩特征、热降频和厂商帧率策略。PSS没有稳定下降，不宣称降低常驻内存。

## 复现与回归

构建隔离性能夹具：

```powershell
.tooling/gradle-8.11.1/bin/gradle.bat :app:assembleRelease -PgalleryBenchmark=true
adb -s 127.0.0.1:5559 install -r app/build/outputs/apk/release/app-release.apk
adb -s 127.0.0.1:5559 shell am start -n net.pocketnai.gallerybench/net.pocketnai.benchmark.GalleryBenchmarkActivity --ez sfw true --ez auto true --ez protected true --es label sample
```

使用 `--ez sfw false` 对比关闭；使用 `--ez protected false` 对比原缓存策略（历史卡片的重组修正仍生效）。报告写入夹具自己的外部files目录 `sample-COLD.json` / `sample-WARM.json`。去掉 `--ez auto true` 可手动滚动。

普通发布必须省略 `-PgalleryBenchmark=true`；此夹具源码和入口不进入生产APK。性能fixture不是新注册的JUnit用例，回归预算仍为29项JVM和21项设备测试。既有GalleryMotionTest扩展了大普通图挤压下的SFW命中、完整原图揭开、trim释放强引用和clear清理检查。

本地原始数据、夹具APK、截图与辅助采集脚本在 `.tooling/sfw-perf-20261104/`。首次基础探测为 `before-on/off`，同APK缓存对照为 `cache-default/protected`，最终复跑为 `final-on-a/b` 与 `final-off`。

实现核对：[Coil 2.7.0 MemoryCacheService](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/memory/MemoryCacheService.kt)、[MemoryCache接口](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/memory/MemoryCache.kt)、[Android FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)。
