# 0.1.28 发布记录

- [正式版本0.1.28 / build-28](https://github.com/Alahjarko/PocketNAI/releases/tag/build-28)，2026-11-02发布，非草稿、非预发布。
- [下载PocketNAI.apk](https://github.com/Alahjarko/PocketNAI/releases/download/build-28/PocketNAI.apk)。
- 标签与构建源码：`1e06bba1817bab405fefb7aecda4967dc3142d7c`，包含远端`4f123bc`的排队、并发、费用显示与分辨率改动。
- [正式发布流程](https://github.com/Alahjarko/PocketNAI/actions/runs/41470365765)全部成功；[合并后开发验证](https://github.com/Alahjarko/PocketNAI/actions/runs/41469048757)全部成功。

本次增加可中断的颜色模式过渡与按钮动效、详情和生成面板的预测性返回，以及画廊参考来源入口。新选的画廊参考图在参考区和结果详情显示来源小图，可以逐层查看和返回；原图删除不影响参考副本。来源字段随草稿与历史保留，Room12→13只加可空列。旧参考不猜测来源。实现与边界见[细节打磨记录](motion-reference-origins-2026-11-02.md)。

## 校验

- 正式包`versionCode=28`、`versionName=0.1.28`；分享任务配置核对通过。
- APK大小13,067,436字节，SHA-256：`8a2c83b9975aa9ddb8cdc23586f5609206970f13465a70db22e29eb9d724499c`，与本地云端产物和GitHub公开附件digest一致。
- 原有签名兼容性检查通过，沿用证书`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，不卸载、不清数据。
- 正式流程再次执行29项JVM单测，零失败、零跳过。开发流程完成Debug/Release/AndroidTest构建与126张原生渲染；其中9张颜色/来源入口预览已检查浅色、过渡中间色、深色与大字体，合并前后逐字节一致。
- SQLite验证旧数据保留、来源删除不级联参考副本；实际迁移结构与KSP导出的Room13一致。

21项设备测试仅编译、未执行。全程没有安卓模拟器或真实AI请求。物理设备的手势提交/取消、系统窗口、触控和帧率尚未实测，Layoutlib不能替代这些验证。
