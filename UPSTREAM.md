# 上游来源与平台边界

## 固定来源

- 原始 NeoForge 仓库：`baizhizzl/caustica-neoforge`。
- 本次开发起点：`27db069f94532900061e3cb393998a3ad3cde516`（MC 26.2 移植）。
- 上游：<https://github.com/ComfyFluffy/Caustica>，LGPL-3.0-or-later。
- 本次核对的最新正式发布：`0.1.1`，2026-07-28。
- 本次同步的 main 提交：`330acd2d743bb6b2e27b53a4adb4a3c852b9e141`，
  提交标题 `Add ACES 2.0 HDR display pipeline (#35)`。
- 已知共同内容基线：`ddf36c0e073b53ffdeecf411ea92f0ac5170f358`。

同步使用固定提交的源代码快照，对共同基线执行三方内容比较，保留原仓库的 Git 历史。
这是内容同步，不是伪造上游提交或声称进行了已拉取分支的 Git merge。
上游仍使用 Fabric/MC 26.2；MC 26.3 / NeoForge 的适配由本仓库维护。

## 同步边界

同步上游 Java 渲染器、着色器、材质、颜色查找表、构建期反射生成器和 CPU 测试。
保留 NeoForge 入口点、事件注册、JarJar、模型收集适配、模组元数据和 CI。
仅同步 English locale；其他语言文件不因本次开发改变。
本次没有改写原生 shim 的 C++ 源码，也没有更换原仓库中携带的 DLSS 二进制。
显式 native packaging 模式会把 CI 编译的 shim 和指定 SDK 文件打入 jar。

移植层的主要差异：

- Renderpearl/Vulkan 的新 package、feature 结构、VMA 对齐分配及 SDL3 初始化接口。
- 世界/GUI/手持物品渲染接缝和实体提交 API。
- NeoForge `RegisterDebugEntriesEvent` 注册曝光调试项。
- worker/capture-owned 可重入模型临时列表、atlas 生命周期缓存和有界 UV 索引。
- Slang 2026.19 要求下的 `MaterialHeader` 明确初始化，避免条件分支的未初始化读告警。
- 不再使用 Fabric Loader、Fabric API 或 FRAPI 的专属 quad 提交接口。

## 版本约束与成品可追溯性

`gradle.properties` 固定 MC 26.3、NeoForge 26.3.0.39-beta 和上游 revision。
Minecraft/NeoForge 的依赖范围均有 26.4 上界；不会把未知下一个图形 API 版本当成兼容。
Jar 的 `META-INF/MANIFEST.MF` 记录：

- `Implementation-Version`
- `Caustica-Upstream-Revision`
- `Caustica-Minecraft-Version`
- `Caustica-NeoForge-Version`

## 验证边界

构建和自动测试验证的是指定 SDK 的源代码、字节码接口、CPU 行为和生成着色器。
Mixin 审计检查目标、字段、方法、注入位置/序号与回调签名，不运行实际 Mixin 变换器。
真实客户端启动、驱动、显示输出、资源重载中的 GPU 同步、DLSS 与第三方模组组合
仍必须在测试实例中实测；当前不存在已验证的跨 GPU FPS 提升结论。
