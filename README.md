# Caustica — NeoForge 26.3 移植版

> **这是 [ComfyFluffy/Caustica](https://github.com/ComfyFluffy/Caustica) 的非官方 NeoForge 移植。**
> 上游渲染器的设计、着色器和主要实现属于原作者及其贡献者。本仓库维护 NeoForge 适配、
> 26.3 图形接口迁移以及移植层的性能优化。请将移植版的问题提交到本仓库。

Caustica 用 Vulkan 硬件路径追踪替换 Minecraft 的世界渲染，并保留原版界面与玩法。
这是实验性渲染器；当前构建是 **26.3 测试候选版**，不应当把编译成功视为全部硬件场景已验证。

![Caustica 光追 Minecraft 场景](docs/gallery/2026-07-09_21.25.14.jpg)

## 当前版本与验证范围

| 项目 | 当前目标 |
|---|---|
| 模组版本 | `0.1.1-neoforge.2` |
| Minecraft | `26.3`，版本范围 `[26.3,26.4)` |
| NeoForge | 构建基线 `26.3.0.39-beta`，版本范围 `[26.3.0.39-beta,26.4)` |
| Java | 25 |
| 上游正式版本 | `0.1.1`，发布于 2026-07-28 |
| 实际同步的上游 main | [`330acd2d743bb6b2e27b53a4adb4a3c852b9e141`](https://github.com/ComfyFluffy/Caustica/commit/330acd2d743bb6b2e27b53a4adb4a3c852b9e141) |

上游 main 包含正式版之后的改动；这里同步的是上述固定提交，而不是只改版本号。
完整来源与适配边界见 [UPSTREAM.md](UPSTREAM.md)。构建产物的清单也记录了上游提交和目标平台。

当前自动验证包括：64 个 CPU 单元测试、26 个客户端 Mixin 的 277 项目标字节码契约，
以及全部构建期 Slang 着色器的 SPIR-V 编译和验证。**尚未完成真实世界渲染、HDR 显示器、
DLSS RR/帧生成、Linux Wayland 或 NeoContinuity 的游戏内回归测试。**
静态 Mixin 检查不能替代真实启动时的注入和渲染测试。

## 本次同步与重写

### 保留并同步上游渲染器

- 新的光源采集、光源网格和层次结构，材质发光数据及 NEE 相关路径。
- Bloom、天空 LUT、ACES 2.0 SDR/HDR 颜色变换、曝光调试和 OpenEXR 截图。
- Slang 管线目录、通过反射生成的 Java 着色器结构和描述符绑定。
- GPU 提交、资源生命周期和同步方面的上游修复。

### 重写 NeoForge 适配热路径

- 区块和实体模型收集使用调用方拥有的、支持嵌套回调的临时列表；释放时清除引用，
  超大列表不长驻缓存。仍使用带世界位置的 NeoForge `collectParts`，不牺牲动态模型语义。
- UV → sprite 查询使用不可变、查询时不分配对象的索引；限制树深和节点数，
  对重叠、重复或极小 UV 矩形保留有界终止路径。
- 纹理缓存以 atlas 为键；资源重载和关闭时在区块工作线程排空之后清理。
- 捕获器复用 quad 回调，避免每个方块创建新的回调对象。

模型零件列表的独立分配基准，在此处的 JDK 25 上从 **80 B/方块降至预热后的 0 B/方块**。
这只衡量列表存储的分配，**不是整个模型或整帧零分配，也不是 FPS 提升承诺**。
复现方式与边界见 [PERFORMANCE.md](PERFORMANCE.md)。

### 26.3 平台适配

- 使用 Renderpearl 图形 API、Vulkan feature 集合和新版命令/管线接口。
- SDL3 后端初始化；Linux Wayland 会话优先使用原生 Wayland，同时尊重显式用户设置。
- 更新世界渲染、手持物品、屏幕效果及 GUI 的注入点，使用共享透明 UI 合成目标。
- 适配实体提交、`UvMapping`、物品 quad 集合、文字背景与顶点 `Uv3` 接口。
- 曝光调试项通过 NeoForge 事件注册；配置库通过 JarJar 内嵌。
- 不依赖 Fabric Loader/API/FRAPI。Fabric-only 几何体接口没有直接兼容保证。

## 使用要求

- Minecraft **26.3**、Java **25** 和上述 NeoForge 版本范围。
- 启用 **Vulkan** 图形后端，使用支持 Vulkan 光线追踪的显卡和驱动。
- DLSS 功能需要受支持的 NVIDIA RTX 显卡与驱动。
- HDR 需要 HDR 显示器、系统 HDR 配置，以及支持所需交换链格式的窗口环境。
- 建议使用 LabPBR 资源包，例如 [SPBR](https://modrinth.com/resourcepack/spbr)。
- 本模组仅客户端有效。与深度修改世界渲染、后处理或 Vulkan 的模组可能冲突。

把构建得到的 jar 放入对应 26.3 实例的 `mods` 目录，以 Vulkan 后端启动，再到视频设置中调整。
不要把旧的 26.2 成品混入同一实例。测试时建议备份存档并先使用单独的测试实例。
如果 NGX 初始化发生线程栈溢出，可参考开发运行配置使用 `-Xss16m`；
不建议未经测量直接叠加 JVM 性能参数。

## 构建与测试

依赖：JDK 25、Slang `slangc`（本次验证为 **2026.19**）、支持 Vulkan 1.4 的 `spirv-val`。
着色器已经统一为 Slang，构建不再需要 `glslangValidator`。

将工具加入 `PATH`，或设置 `VULKAN_SDK`。也可以显式设置 `CAUSTICA_SLANGC`、
`CAUSTICA_SPIRV_VAL` 覆盖工具位置；显式覆盖优先于 SDK 和 `PATH`。

```powershell
.\gradlew.bat build
.\gradlew.bat :core-tests:test :core-tests:benchmarkAllocations
.\gradlew.bat verifyMixinTargets
.\gradlew.bat runClient
```

Linux 对应使用 `bash ./gradlew ...`。成品在 `build/libs/`；不要安装 `-sources.jar`。
`build` 包含完整单元测试和 Mixin 静态审计，不需要为 CPU 测试下载游戏纹理或音效。
`runClient` 才是需要游戏资源、窗口及图形硬件的实际客户端运行。
Windows 构建使用 `file.encoding=COMPAT` 避免含中文路径的启动参数文件与本机编码不一致；
Java 源文件仍明确按 UTF-8 编译。

本地默认打包仓库中现有的 Windows/Linux x64 原生库，无需本地安装 DLSS SDK。
CI 重新编译两个平台的 NGX shim，并显式选择平台以替换原生库：

```sh
DLSS_SDK=/path/to/DLSS bash ./gradlew build \
  -PngxPlatforms=windows-x64,linux-x64 -PngxVendorConfig=rel -PngxShimConfig=release
```

此模式需要 `build/native/ngx_shim/release/` 中的 shim 和所选 SDK 库；缺失时构建失败，
不会悄悄回退到旧 shim。CI 固定并校验 Slang 下载文件的 SHA-256。

## 致谢与许可证

- **[ComfyFluffy](https://github.com/ComfyFluffy)** 和 Caustica 贡献者：上游渲染器及着色器。
  欢迎支持[原仓库](https://github.com/ComfyFluffy/Caustica)和 [Modrinth](https://modrinth.com/mod/caustica)。
- **[FabricMC](https://github.com/FabricMC/fabric)**：FRAPI 默认 quad 发射语义及 sprite 查找算法参考，Apache-2.0。
- **[Argon4W/NeoContinuity](https://github.com/Argon4W/NeoContinuity)**：NeoForge 连接纹理实现；
  移植层保留其所需世界模型收集接口，但当前 26.3 组合尚未游戏内实测。
- **NVIDIA DLSS/NGX**：专有 SDK 与二进制，适用其自身许可。
- **[NightConfig](https://github.com/TheElectronWill/night-config)**：LGPL-3.0，JarJar 打包的 TOML 配置库。

本项目自身源代码与文档采用 **LGPL-3.0-or-later**，保留上游版权和第三方来源。
详见 [LICENSE.md](LICENSE.md)、[COPYING](COPYING)、[COPYING.LESSER](COPYING.LESSER)
和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
