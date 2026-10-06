# Caustica — NeoForge 版

Caustica 的 NeoForge 移植版，用 Vulkan 硬件路径追踪渲染 Minecraft，支持 DLSS、Bloom、HDR 和 LabPBR 材质。
适配 Minecraft **26.3**。**0.1.1-neoforge.4** 加入静态 TLAS 缓存和共享光照预采样。

这是 [ComfyFluffy/Caustica](https://github.com/ComfyFluffy/Caustica) 的非官方移植。
渲染器和着色器来自上游，本仓库负责 NeoForge 适配与移植层优化。

![Caustica 光追 Minecraft 场景](docs/gallery/2026-07-09_21.25.14.jpg)

## 下载与安装

**[下载 0.1.1-neoforge.4](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.4)**

| Minecraft | NeoForge | Java |
|---|---|---|
| 26.3 | 26.3.0.39-beta 起，限 26.3 系列 | 25 |

1. 安装对应版本的 NeoForge。
2. 下载 `caustica-neoforge-0.1.1-neoforge.4.jar`，放进实例的 `mods` 文件夹；已有旧版时先移除旧 JAR。
3. 使用 **Vulkan** 图形后端启动，在视频设置中调整渲染选项。

需要支持 Vulkan 光线追踪的显卡和驱动。DLSS 需要受支持的 NVIDIA RTX 显卡；HDR 需要 HDR 显示器，并开启系统 HDR。
模组仅需安装在客户端。搭配 LabPBR 资源包效果更好，例如 [SPBR](https://modrinth.com/resourcepack/spbr)。

发布的 JAR 已包含 Windows x64 和 Linux x64 原生库，不用另装 DLSS SDK。
遇到问题，请带上游戏日志、显卡型号和驱动版本，到[本仓库的 Issues](https://github.com/baizhizzl/caustica-neoforge/issues)反馈。

## 更新记录

### 未发布

- 修复 0.1.1-neoforge.4 中方块光源闪烁的问题：共享光照预采样改为默认关闭。同一光照网格单元（16×16×16 方块）内的所有像素每帧共用同一组 64 个候选光源，候选组每帧重抽，整片区域的亮度会一起跳变，降噪器无法把它当作噪声滤掉。
- 预采样的配置键改为 `lights.experimental-presampling`（启动参数 `-Dcaustica.rt.experimentalLightPresampling=true`），已保存的旧键 `lights.presampling = true` 不再生效，保存设置时自动删除。
- TLAS 缓存不受影响，仍默认开启。

### [0.1.1-neoforge.4](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.4) · 2026-10-05

- 地形没有变化时不再逐帧重写全部静态 TLAS 实例。每个 TLAS 槽位分别缓存地形输入，区块加载、卸载、世界重置和坐标重定位时才刷新；场景完全不变时可以跳过 TLAS 构建。
- 动态实体仍逐帧比较并更新，BLAS 构建或 refit 会使所有 TLAS 槽位的缓存失效，避免复用过期包围盒。
- 新增共享光照预采样：每帧预解码一组全局和相机附近的局部光源候选，RIS 查询共用候选池，但仍为每个查询独立取表面采样点和阴影射线。
- 光照预采样不降低 RIS 候选数、SPP 或反弹次数，也不缓存跨帧可见性。两项优化均可通过 `caustica.rt.tlasCache` 和 `caustica.rt.lightPresampling` 单独关闭。
- 自动测试、着色器编译和 SPIR-V 验证通过。性能切换、计数器和对照场景见 [PERFORMANCE.md](PERFORMANCE.md)。

### [0.1.1-neoforge.3](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.3) · 2026-10-02

- 在「视频设置 → Ray Tracing」里加入 DLSS 帧生成开关和倍数调节，不用再改启动参数。可选倍数由显卡、驱动和窗口系统决定，最高 6x。
- 加入光线重建开关和 Auto / D / E / **F（RR2）** 模型选择，切换后无需重启。
- 更新随包 DLSS 运行库到 **310.9.1**，重新编译 Windows x64 / Linux x64 NGX shim。
- 切换帧生成倍数时同步调整交换链缓冲数量，避免高倍数下等不到空闲图像。
- 修复 F 切回 Auto 后仍保留旧模型参数的问题，切换相关设置时重置时序历史。

本版已由维护者完成实机测试。使用帧生成时需要开启垂直同步，才能让生成帧逐帧显示。
F 是光线重建模型，不是超分辨率的 L/M 预设。

### [0.1.1-neoforge.2](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.2) · 2026-10-02

- 适配 Minecraft 26.3 的 Renderpearl、Vulkan、SDL3 接口，以及世界、手持物品、GUI 和实体渲染。
- 跟进上游光源采集、NEE、Bloom、天空 LUT、ACES 2.0 SDR/HDR、OpenEXR 截图及 GPU 同步修复。
- 重写模型采集与纹理查询，减少临时对象分配和重复查找，修正资源重载时的纹理缓存处理。
- 修正原生库打包流程，CI 成品使用重新编译的 Windows/Linux NGX shim。

该版同步的上游提交为 [`330acd2`](https://github.com/ComfyFluffy/Caustica/commit/330acd2d743bb6b2e27b53a4adb4a3c852b9e141)。
更多细节见[上游同步记录](UPSTREAM.md)和[性能优化说明](PERFORMANCE.md)。

## 从源码构建

准备 JDK 25、Slang **2026.19** 和支持 Vulkan 1.4 的 `spirv-val`。
将工具加入 `PATH`，也可以通过 `VULKAN_SDK`、`CAUSTICA_SLANGC` 和 `CAUSTICA_SPIRV_VAL` 指定位置。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

Linux 使用 `bash ./gradlew build` 或 `bash ./gradlew runClient`。
成品位于 `build/libs/`，安装时不要选 `-sources.jar`。
`build` 会运行单元测试、Mixin 目标检查和着色器验证。

移植层的测试与分配基准可以单独运行：

```powershell
.\gradlew.bat :core-tests:test :core-tests:benchmarkAllocations
```

本地构建默认使用仓库内的原生库。需要打包自己编译的 NGX shim 时，将它放在
`build/native/ngx_shim/release/`，并指定 DLSS SDK：

```sh
DLSS_SDK=/path/to/DLSS bash ./gradlew build \
  -PngxPlatforms=windows-x64,linux-x64 -PngxVendorConfig=rel -PngxShimConfig=release
```

## 致谢与许可证

- **[ComfyFluffy](https://github.com/ComfyFluffy)** 和 Caustica 贡献者：上游渲染器与着色器。欢迎支持[原仓库](https://github.com/ComfyFluffy/Caustica)和 [Modrinth](https://modrinth.com/mod/caustica)。
- **[FabricMC](https://github.com/FabricMC/fabric)**：quad 发射语义与 sprite 查找算法参考，Apache-2.0。
- **[Argon4W/NeoContinuity](https://github.com/Argon4W/NeoContinuity)**：NeoForge 连接纹理实现与相关模型收集接口。
- **NVIDIA DLSS/NGX**：SDK 与原生库。
- **[NightConfig](https://github.com/TheElectronWill/night-config)**：TOML 配置库，LGPL-3.0。

本项目的源代码与文档采用 **LGPL-3.0-or-later**；第三方组件遵循各自的许可。
详见 [LICENSE.md](LICENSE.md)、[COPYING](COPYING)、[COPYING.LESSER](COPYING.LESSER) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
