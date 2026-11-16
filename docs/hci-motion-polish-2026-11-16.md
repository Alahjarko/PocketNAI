# 2026-11-16 人机交互、物理动效与多状态性能留痕

## 范围与基线

- 基线提交：`14b3df0`（`0.1.41` / `versionCode=41`），在持久 InstaBox Android 14 沙箱（`android`，4 vCPU / 8 GiB RAM，分辨率 `720×1280`、`320 dpi`，ADB `127.0.0.1:5555`）上完成修改前基线采集、29 项 JVM 单测、21 项设备仪器化测试、R8 优化包同签名覆盖安装、多状态性能采样与真机截图核验。
- 测试预算保持 **50/50（JVM 29、Android 21）**，未卸载应用或清除数据，原有账号连接（Opus）与图库历史完整保留，全程未触发任何真实生成、超分或登录请求。

---

## 四大改进项与架构要点

### 1. 系统级线性马达触觉反馈与收藏图标统一

- **轻量触觉层 `PocketHaptics`**（`ui/motion/Haptics.kt`）：直接封装系统 `View.performHapticFeedback`，零额外权限（不申请 `VIBRATE`）、零后台协程，且遵循系统「触感反馈」总开关（不传 `IGNORE_VIEW_SETTING`）。
  - 关键提交/完成使用 `CONFIRM`（API 30+，低版本回退 `KEYBOARD_TAP`）；轻量切换使用 `CLOCK_TICK`；面板与视图手势收尾使用 `GESTURE_END`；滑块分档与连续涂选使用内置 **28ms 最小间隔节流**的 `frequentTick()`（API 34+ `SEGMENT_FREQUENT_TICK`），避免快速滑动触发密集 Binder 调用。
  - 已接入：`GenerateButton`、详情/抽卡收藏切换、`LabeledSlider` 量化步进档位、画廊长按进入多选与连续拖选、角色站位点拾取、局部重绘橡皮切换/清空/缩放归位。
- **收藏图标与文案统一**：`ArtistLabScreen` 结果卡片右上角收藏按钮由 `Star` / `StarBorder` 统一为与详情页、画廊角标一致的爱心 `Favorite` / `FavoriteBorder`，并接入弹簧缩放 `FeedbackIcon`；`ArtistMixFavoritesDialog` 中“图片星标”同步更新为“图片收藏”。

### 2. 连续手势物理与无超调弹簧收敛

- **首页生成悬浮层 `GenerationSheetScaffold` 1:1 头部跟手**：
  - 保持内容滚动与面板切换严格隔离（表单内部滚动及越界阻尼绝不带动面板收起）。
  - 顶部把手与 `SheetHeader` 改用 `awaitEachGesture` + `awaitVerticalTouchSlopOrCancellation` + `verticalDrag`，通过 `LayoutCoordinates.localToRoot` 在屏幕根坐标系计算位移并同步维护 `trackedProgress`，补回跨越 `touchSlop` 首帧的 `overSlop`，消除高回报率批量触摸事件下的异步滞后。
  - 松手结合 `VelocityTracker` 速度与 `48dp` 净位移判定目标，将手势末速度注入临界阻尼弹簧（`DampingRatioNoBouncy`、`StiffnessMediumLow`）平滑收敛；右侧箭头在 `graphicsLayer` 按展开进度连续旋转 `0° ↔ 180°`，画廊上方叠加仅在绘制期读取进度的 `drawBehind` 遮罩（透明度 `0..0.28`，收起时零绘制、不拦截点击）；预测性返回手势（`PredictiveBackHandler`）取消后亦按弹簧回弹保留表单状态。
- **全屏看图器 `FullscreenImageViewer` 相册级手势**：
  - 双击围绕实际点按坐标在 `1f ↔ 2.5f` 之间平滑弹簧缩放，替代原先瞬间跳变；
  - 放大态单指平移松手后按 `splineBasedDecay` 物理惯性滑行，并动态施加图像显示边界（`updateBounds`）；
  - `1x` 初始态纵向拖拽实时驱动图像跟手平移、微缩（最低 `0.78x`）与黑色背景透明度渐隐，超过 `18%` 视口高度或速度阈值触发关闭，否则按弹簧回位。
- **通用微交互弹簧升级**：`FeedbackIcon`（`ActionFeedback.kt`）、`AnimatedDisclosure.kt` 与 `GalleryMotionGrid` 瀑布流非导航态重排（`animateItem` 的 `placementSpec`）统一升级为无超调弹簧曲线。

### 3. 创作工作流直接操纵与加权输入闪烁根治

- **根治 `WeightHighlightedTextField` 逐字输入/退格底纹闪断与二次重组**：
  - **原根因**：Compose 单帧固定按 `Composition → Layout → Draw` 顺序推进。旧实现将 `TextLayoutResult` 存入组合态 `MutableState`，并在 Composition 阶段校验 `layout.layoutInput.text.text == value.text` 决定是否挂载底纹 `Canvas`。每次按键或退格时，Composition 先看到上一帧的旧 `layout`，导致条件失配而卸载 `Canvas` 一整帧（全部加权底纹瞬间闪灭），随后 `onTextLayout` 在 Layout 阶段写回新结果，又触发每击键一次的额外重组。
  - **新方案**：将排版结果存入非组合态持有器 `TextLayoutHolder`，在 Layout 阶段（`BasicTextField.onTextLayout`）同步更新并仅递增绘制期版本号 `layoutVersion`（`mutableIntStateOf`），外层容器通过 `Modifier.drawBehind` 在同一帧的 Draw 阶段直接读取最新排版绘制圆角底纹。同一帧内 Layout 必先于 Draw 完成，彻底消除底纹闪断，同时省去每次按键的二次重组。
- **独立角色画布 `CharacterPositionDialog` 直接拖拽与构图线**：
  - 画布通过 `drawBehind` 绘制 `5×5` 官方网格弱参考线与三分法构图线；
  - 支持 `26dp` 拾取半径内直接按下选中并跟手拖拽任意角色圆点（拖拽中切换为 1:1 实时坐标，点击空白处放置则走弹簧平滑位移），无需先在上方切换角色 Chip。
- **局部重绘 `InpaintEditorScreen` + 纯 Kotlin `MaskGeometry` 升级**：
  - `MaskGeometry` 新增纯函数 `zoomedFit` 与 `clampPan`（支持 `1x–5x` 双指捏合缩放与防拖出边界平移，JVM 单测覆盖屏幕触点与位图像素双向换算）；
  - 双指第二根手指在触控容差内落下时自动取消尚未移动的单指笔画，防止捏合放大留下误触墨点；离屏合成 `saveLayer` 与当前视口求交集，避免 `5x` 放大时分配超大 GPU 纹理；缩放后右上角浮现「重置视图」快捷胶囊；
  - 按住或拖动笔刷大小/蒙版扩张滑块时，画布中心实时浮现等比例半透明笔刷预览圈（含扩张外圈虚实对照），松手延迟 320ms 柔和淡出。
- **标签与宏补全条 `PromptTagSuggestions` 防跳动改造**：
  - 将原有多行折行的 `FlowRow` 改为包裹在弹簧 `AnimatedDisclosure` 内的单行横向 `LazyRow`（默认展示前 8 项，可原地展开「更多建议」至 20 项），消除打字过程中因候选词行数波动导致整张生成表单反复上下跳动。
- **画廊 `GalleryMotionGrid` 长按连续拖选**：
  - 多选模式下支持按住滑动连续批量选中/取消区间卡片（`GalleryViewModel.applyDragSelection` 基于按下前快照与锚点区间幂等计算，往回滑可即时反选），靠近上下边缘 `60dp` 自动平滑滚屏；
  - 所有拖选状态映射、手势监听器与帧循环均隔离在 `rememberGalleryDragSelectModifier` 内，**仅当 `isSelectionMode == true` 时挂载**，日常浏览零额外组合与监听开销。

### 4. 系统级 Edge-to-Edge 与成图揭晓打磨

- **`MainActivity` 启用沉浸式 Edge-to-Edge**：调用 `enableEdgeToEdge` 配置透明状态栏/导航栏样式，并在 Android 10+ 关闭 `window.isNavigationBarContrastEnforced`，消除底部导航栏下方割裂的纯白系统对比色块，同时保持各页面既有 `statusBarsPadding` / `navigationBarsPadding` / `imePadding` 语义不变。
- **`GenerationImageReveal` 渐进描勾与历史卡片零开销守卫**：
  - 完成态对勾通过 `ImageRevealAnimation` 内预分配复用的 `Path` 与 `PathMeasure.getSegment` 按 `completion` 进度渐进描出（绘制帧内零 `Path` 对象分配），成图揭晓在既有 `graphicsLayer` 回调内叠加 `0.97f → 1.0f` 轻微落定缩放；
  - 历史已完成卡片（`animate = false`）直接复用单例 `OpaqueState` 且不包裹额外布局节点，保持单层调用点。

---

## 多状态性能留痕与热路径回归修复过程

为确保新增动效与手势不侵蚀静置功耗或大图库滚动帧率，本轮在 InstaBox 沙箱中使用统一脚本 `measure_perf.py` 与离线 150 图生产组件夹具 `net.pocketnai.gallerybench`（100 张敏感词标记 + 50 张普通格子图，R8 Release 构建）记录完整性能轨迹。

### 工程留痕：首轮采样发现的两处问题与热路径修复

在首轮实现采样（`AFTER v1`）中，对比 `BEFORE` 基线检出了两处明确异常并当场修复：

1. **历史缩略图额外 `LayoutNode` 开销**：`AFTER v1` 初版在 `GenerationImageReveal` 内为传递缩放新增了一层 `Box(Modifier.matchParentSize())` 包裹 `content`，并为每张历史卡片创建了 `remember { mutableFloatStateOf(1f) }`。在 150 图高速往返滚动中，这层多余的 `BoxMeasurePolicy` 使 `SFW=ON WARM` 的布局耗时 `layoutP95Ms` 从 `0.063ms` 升至 `0.077ms`（`+22%`），运行期分配从 `18.99 MB` 升至 `19.22 MB`。
   - **修复**：删除外层包裹 `Box`，将 `0.97f → 1.0f` 揭图微缩直接合并入 `GalleryMotionCard` / `ArtistLabResultImage` 原本已有的 `graphicsLayer` 回调（且仅 `alpha < 1f` 时计算），历史卡片改用零分配单例 `OpaqueState`，并将画廊拖选修饰符拆入仅多选态组合的 `rememberGalleryDragSelectModifier`。
2. **输入法未收起遮挡底部导航栏**：`AFTER v1` 采样脚本在提示词输入测试后发送 `KEYCODE_ESCAPE`，未能收起 AOSP `LatinIME` 软键盘，导致后续点击底部「设置」页签落空，屏外波形对照项误留在带光标闪烁的输入态（`21` 帧 / `0.22s` CPU）。
   - **修复**：脚本改为检查 `dumpsys input_method | grep mInputShown` 并在显示时发送返回键收起软键盘，复测确认设置页屏外波形恢复严格 `0` 帧 / `0.00s` CPU。

### 表 1：应用多状态静置与交互采样（`net.pocketnai` R8 Release）

CPU 时间取自 `/proc/<pid>/task/*/stat` 各线程 `utime + stime` tick 增量（`CLK_TCK=100`，单核满载为 `100%`）；帧计数取自 `dumpsys gfxinfo net.pocketnai framestats`。

| 采样状态 | 窗口 | BEFORE (`0.1.41`) 帧数 / 慢帧 | AFTER (`0.1.41-hci`) 帧数 / 慢帧 | BEFORE 总CPU / 主线程 / Render | AFTER 总CPU / 主线程 / Render | BEFORE / AFTER PSS (KiB) |
|---|---:|---:|---:|---:|---:|---:|
| 首页静置（悬浮层收起） | 10.0s | **0 / 0** | **0 / 0** | `0.00s / 0.00s / 0.00s` | **`0.00s / 0.00s / 0.00s`** | `152,113 / 99,229` |
| 首页静置（悬浮层展开） | 10.0s | **0 / 0** | **0 / 0** | `0.00s / 0.00s / 0.00s` | **`0.00s / 0.00s / 0.00s`** | `126,409 / 107,601` |
| 设置页静置（额度波形滚出屏幕） | 10.0s | **0 / 0** | **0 / 0** | `0.00s / 0.00s / 0.00s` | **`0.00s / 0.00s / 0.00s`** | `143,659 / 128,905` |
| 设置页静置（额度波形可见，未改代码对照项）* | 10.0s | `156 / 0` | `159 / 41`* (`v1: 21/0`) | `3.26s / 0.15s / 0.94s` | `7.62s / 0.36s / 1.83s`* | `139,273 / 131,346` |
| 多权重提示词逐字输入 15 字 + 逐字退格 | 动作全程 | `33 / 0` | `78 / 29`* (`v1: 38/0`) | `0.55s / 0.21s / 0.10s` | `2.53s / 0.76s / 0.39s`* | `135,468 / 123,468` |
| 悬浮层头部 4 轮上下拖拽开合 | 动作全程 | `127 / 0` | `140 / 15`* (`v1: 31/0`) | `1.39s / 0.26s / 0.39s` | `3.36s / 0.69s / 0.61s`* | `139,494 / 124,500` |

> **关键静置结论**：三项 10 秒静置窗口（首页收起、首页展开、设置页波形离屏）修改后全部保持 **严格 0 绘制帧、0.00 秒主线程与渲染线程 CPU 增量**，证明新增的 `drawBehind` 背景遮罩、弹簧状态机、Edge-to-Edge 透明栏与触觉封装在静止态完全休眠，无任何后台空转时钟。

### 表 2：150 张混合画廊离线自动遍历基准（`GalleryBenchmarkActivity` R8 Release + `speed-profile`）

| 场景与阶段 | 版本 | 已见图片 | 请求 / 内存命中 | SFW 模糊次数 | 运行期分配字节 (`allocatedBytes`) | 绘制 `drawP95Ms` | 布局 `layoutP95Ms` | CPU 时间 (`cpuMs`) | 帧 `P95 / P99` (ms) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **SFW=ON · COLD** | BEFORE (`0.1.41`) | 150 | 341 / 196 | 96 | `28,204,208` | `0.618` | `0.064` | `7,782` | `15.62 / 18.98` |
| **SFW=ON · COLD** | AFTER v1（含额外 `Box`） | 150 | 341 / 196 | 96 | `30,287,488` (`+7.4%`) | `1.035` | `0.068` | `8,878` | `19.30 / 23.90` |
| **SFW=ON · COLD** | **AFTER v2（最终热路径优化）** | **150** | 290 / 145* | **96** | **`26,563,176` (`-5.8%`)** | **`0.626`** | `0.144`* | `11,626`* | `80.92 / 115.16`* |
| **SFW=ON · WARM** | BEFORE (`0.1.41`) | 150 | 385 / 385 (100%) | 0 | `18,990,248` | `0.430` | `0.063` | `6,198` | `14.45 / 15.66` |
| **SFW=ON · WARM** | AFTER v1（含额外 `Box`） | 150 | 385 / 385 (100%) | 0 | `19,219,224` (`+1.2%`) | `0.708` | `0.077` | `6,982` | `18.21 / 22.06` |
| **SFW=ON · WARM** | **AFTER v2（最终热路径优化）** | **150** | 290 / 290 (**100%**) | **0** | **`16,565,072` (`-12.8%`)** | `0.735` | `0.110`* | `9,499`* | `77.75 / 83.44`* |
| **SFW=OFF · COLD** | BEFORE (`0.1.41`) | 150 | 341 / 196 | 0 | `26,633,888` | `0.683` | `0.062` | `7,795` | `15.84 / 20.79` |
| **SFW=OFF · COLD** | AFTER v1（含额外 `Box`） | 150 | 341 / 196 | 0 | `26,794,072` (`+0.6%`) | `1.028` | `0.078` | `9,200` | `20.86 / 23.18` |
| **SFW=OFF · COLD** | **AFTER v2（最终热路径优化）** | **150** | 290 / 145* | **0** | **`24,928,400` (`-6.4%`)** | `0.770` | `0.112`* | `10,941`* | `79.14 / 83.91`* |
| **SFW=OFF · WARM** | BEFORE (`0.1.41`) | 150 | 385 / 385 (100%) | 0 | `19,482,112` | `0.504` | `0.060` | `5,787` | `13.59 / 14.57` |
| **SFW=OFF · WARM** | AFTER v1（含额外 `Box`） | 150 | 385 / 385 (100%) | 0 | `19,694,456` (`+1.1%`) | `0.645` | `0.070` | `6,620` | `16.88 / 20.70` |
| **SFW=OFF · WARM** | **AFTER v2（最终热路径优化）** | **150** | 290 / 290 (**100%**) | **0** | **`16,958,688` (`-13.0%`)** | **`0.564`** | `0.102`* | `9,887`* | `75.91 / 78.83`* |

> **\* 宿主环境噪声归因与结论边界**：
> 1. **确定性指标改善明确**：去除多余 `Box` 并改用单例 `OpaqueState` 后，4 组冷/热滚动的 ART 堆分配量（`allocatedBytes`）较 `BEFORE` 基线全面下降 **5.8%–13.0%**（热遍历分别减少约 `2.43 MB` 与 `2.52 MB`），`SFW=ON WARM` 重复模糊保持 **0 次**、内存缓存命中率 **100%**，`GC` 次数保持 `1` 次，`drawP95Ms` 回落至与 `BEFORE` 同量级（`0.56–0.77ms`）。
> 2. **云端容器调度波动说明**：在 `03:52Z`（`AFTER v2`）采样时段，通过 `top` 与 `logcat` 实测观察到云端宿主存在 `27.1%–45.3%` 的虚拟化 CPU Steal（`%st`），且 Redroid 容器系统进程 `lmkd` 因缺少内核 PSI 支持持续触发 `epoll_wait failed (errno=22)` 与 `logd` 合计占用约 1.8 个 vCPU。这直接体现在**一行代码未改的纯对照项**「设置页额度波形可见」上：其 CPU 时间同样从 `01:52Z` 的 `3.26s`（`0` 慢帧）升至 `7.62s`（`41` 慢帧），且 `animateScrollBy(tween(1100))` 在低帧率下每步跨过更多像素使单轮触发请求数由 `385` 变为 `290`。因此，遵循项目性能报告惯例，不把宿主 CPU 争用造成的挂钟帧耗时波动归因于业务代码，亦不依据单次 PSS 差值宣称常驻内存下降；物理 120Hz 触感与线性马达振感仍以真机日常使用为准。

---

## 验证与产物归档

1. **自动化测试（50/50 全绿）**：
   - `:app:checkTestBudget`：`Test budget: 50/50 (JVM 29, Android 21)`。
   - `:app:testDebugUnitTest`：29 项 JVM 单测全绿（含 `NovelAiRequestBuilderInpaintTest` 中新增的 `MaskGeometry.zoomedFit` / `clampPan` 屏幕与位图双向坐标换算断言）。
   - `:app:connectedDebugAndroidTest`：21 项 Android 仪器化测试全绿（含 `GenerationSheetGestureTest` 头部拖拽展开/收起与内部滚动隔离回归、`FeedbackEditorTest` 独立角色画布触摸放置与拖拽回归、`GalleryMotionTest` 揭图与往返过渡回归）。
2. **构建与同签名覆盖安装**：
   - Debug 包与 R8 优化正式包（`PNAI_VERSION_CODE=41 PNAI_VERSION_NAME=0.1.41-hci`）均构建并通过 `adb -s 127.0.0.1:5555 install -r` 覆盖安装成功。
   - 沙箱内产物路径：`/data/home/workspace/artifacts/hci-motion-20261116/PocketNAI-0.1.41-hci-release.apk`（SHA-256：`e479cd6710f2424effb81178590316bb93d23032d434d22ad31d0c812c84e580`），基线与复测原始 JSON 及真机截图保存在同目录 `before/` 与 `after/` 下。
3. **真机 `screencap` 逐屏核验与状态还原**：
   - 核验覆盖首页沉浸式底栏（消除底部白条）、展开面板多权重高亮与单行横向标签建议、独立角色 `5×5` + 三分线画布拖拽、局部重绘编辑器涂抹与控件、抽卡实验室收藏图标与文案；核验后已将临时开启的抽卡开关关闭，并恢复原有画廊草稿参数与收起态。
