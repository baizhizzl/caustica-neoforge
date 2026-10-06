# 和上游的关系

上游是 [ComfyFluffy/Caustica](https://github.com/ComfyFluffy/Caustica)（LGPL-3.0-or-later），目前跑在 Fabric 和 Minecraft 26.2 上。这个仓库把它移植到 NeoForge 和 Minecraft 26.3，并自己维护这部分适配。

## 同步到哪了

| | 提交 |
|---|---|
| 上游 main | [`330acd2`](https://github.com/ComfyFluffy/Caustica/commit/330acd2d743bb6b2e27b53a4adb4a3c852b9e141)（Add ACES 2.0 HDR display pipeline (#35)） |
| 当时核对的上游正式版 | `0.1.1`（2026-07-28） |
| 双方共同的内容基线 | `ddf36c0e073b53ffdeecf411ea92f0ac5170f358` |
| 这次移植的起点 | `27db069f94532900061e3cb393998a3ad3cde516`（MC 26.2 移植版） |

同步的做法是：拿上游某个提交的源码快照，以共同基线为底做三方内容合并，然后作为本仓库的普通提交落地。本仓库原有的 Git 历史保留不动。这不是 Git 意义上的 merge，提交历史里也不会出现上游的提交。

## 哪些跟上游走，哪些自己维护

跟上游同步：Java 渲染器、着色器、材质、颜色查找表、构建时的反射代码生成器，以及 CPU 单元测试。

本仓库自己维护：NeoForge 入口和事件注册、JarJar 打包、模型采集适配、模组元数据和 CI。

几点额外说明：

- 语言文件只同步英文（`en_us.json`），其他语言不动。
- 原生 shim 的 C++ 源码没改，仓库里带的 DLSS 二进制也没换。用显式的原生打包参数构建时，会把 CI 编译的 shim 和指定 SDK 里的文件打进 JAR。

和上游代码差别比较大的地方：

- Minecraft 26.3 里 Renderpearl / Vulkan 的包结构和 feature 结构变了，VMA 对齐分配和 SDL3 初始化接口也变了。
- 世界、GUI、手持物品的渲染接入点，以及实体提交 API。
- 曝光调试项改用 NeoForge 的 `RegisterDebugEntriesEvent` 注册。
- 模型临时列表由工作线程和采集器各自持有、支持重入；atlas 缓存跟着资源重载的生命周期走；UV 查询用有上限的索引。
- Slang 2026.19 下 `MaterialHeader` 要显式初始化，否则条件分支里会报未初始化读取的警告。
- 不再依赖 Fabric Loader、Fabric API 或 FRAPI 专属的 quad 提交接口。

## 版本锁定

`gradle.properties` 里锁死了 Minecraft 26.3、NeoForge 26.3.0.39-beta 和同步的上游提交。Minecraft 和 NeoForge 的依赖范围上限都是 26.4（不含），不会把还没出的下一版当成兼容。

构建出的 JAR 会在 `META-INF/MANIFEST.MF` 里写明来源：

- `Implementation-Version`
- `Caustica-Upstream-Revision`
- `Caustica-Minecraft-Version`
- `Caustica-NeoForge-Version`

## 测试能保证什么

构建和自动测试只能证明：在锁定的 SDK 版本下，源码能编译、字节码接口对得上、CPU 端逻辑正确、着色器能生成。Mixin 检查只核对目标类、字段、方法、注入点和回调签名，不会真的执行 Mixin 变换。

真实客户端能不能启动、各家驱动的表现、显示输出、资源重载时的 GPU 同步、DLSS，以及和其他模组的兼容性，都得在测试实例里实际跑过才知道。目前也没有跨显卡的 FPS 对比数据。
