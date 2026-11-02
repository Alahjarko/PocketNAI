# 修正颜色按钮的交互开销

用户在0.1.28真机反馈：深浅色按钮点击后的变化仍然卡顿。上一轮静态渲染只能说明样式，没有证明手机上的动画流畅度；本轮撤回以静态图代替性能证据的判断。

## 可确认的代码问题与修正

- 0.1.28在应用根部逐帧创建整套ColorScheme，同时把逐帧权重底纹放进staticCompositionLocalOf。按钮本身还有颜色追踪动画，主题每变一帧，局部目标色又变化；图标AnimatedVisibility反复改变文字所在行的宽度。
- 本轮移除应用层整套主题色和权重底纹的逐帧插值。主题一次提交；权重色使用跟踪读取的compositionLocalOf和按主题记忆的值；系统栏图标仅在相关模式变化时设置。
- 按钮保留本地临界阻尼弹簧、按压缩放和原生ripple。动画状态只在drawBehind、graphicsLayer、Canvas和BasicText的ColorProducer中读取。图标固定占位，不因选中状态改变文字位置。点击已选项不重复写设置，快速反向选择沿用当前动画值。
- 不升级Compose/Kotlin，不修改生成、费用、参考图、数据或内容规则。

参考：[RikkaHub应用层主题](https://github.com/rikkahub/rikkahub/blob/5ad19405c1a14899c69bb92e249a3adb3f65fbbd/app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt)直接提供主题色；[Android绘制阶段与图层](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)说明延后读取动画状态可避免把绘制变化传回布局。BasicText的延后颜色接口已按本项目使用的Foundation1.7.6源码核对。

## 动画时钟A/B回放

在相同的中性设置示例中，分别使用0.1.28的原主题/按钮代码（仅在preview源集保存）与本轮生产组件。每组在160ms、256ms、352ms执行三次模式变化，回放1200ms，采样73帧，包含中途反向切换。场景按实际动画时钟推进，不用手工指定动画进度代替状态变化。

| 计数边界 | 0.1.28 | 本轮 |
| --- | ---: | ---: |
| 示例页面重组 | 33 | 4 |
| 示例页面外层测量 | 1 | 1 |
| 颜色卡片外层测量 | 1 | 1 |

计数边界不包括每个文字节点的内部测量，也不测GPU、系统触控延迟或物理手机帧率。两段原生动画均有73个实际输出帧；还检查浅色/深色与大字体端点。此结果证明该回放中去除了全局逐帧重组，不能据此宣称达到RikkaHub的真机流畅度。

[源码014260b的云端验证](https://github.com/Alahjarko/PocketNAI/actions/runs/41476443897)全部通过：29项JVM单测、静态与动画回放、Debug/Release/AndroidTest构建、打包配置和原签名检查。21项设备测试仅编译，未执行；没有新增@Test方法，没有安卓模拟器或真实AI请求。历史版本副本只编入preview测试源集，不进入安装包。

已作为[0.1.29正式发布](https://github.com/Alahjarko/PocketNAI/releases/tag/build-29)，完整版本、原签名和文件摘要见[发布记录](release-0.1.29.md)。
