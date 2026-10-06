# Caustica — NeoForge 版

把 [Caustica](https://github.com/ComfyFluffy/Caustica) 搬到 NeoForge 上的非官方移植。Caustica 用 Vulkan 硬件光线追踪做 Minecraft 的路径追踪渲染，带 DLSS（超分、光线重建、帧生成）、Bloom、HDR 输出，并支持 LabPBR 材质。

渲染器和着色器都出自上游，这个仓库只负责 NeoForge 适配，以及移植层自己的一些优化。目前适配 Minecraft 26.3。

![Caustica 光追 Minecraft 场景](docs/gallery/2026-07-09_21.25.14.jpg)

## 安装

[下载 0.1.1-neoforge.5](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.5)

| Minecraft | NeoForge | Java |
|---|---|---|
| 26.3 | 26.3.0.39-beta 及之后的 26.3 版本 | 25 |

1. 装好对应版本的 NeoForge。
2. 把 `caustica-neoforge-<版本>.jar` 丢进 `mods` 文件夹，有旧版的话先删掉。
3. 游戏图形后端选 **Vulkan**，进游戏后在「视频设置 → Ray Tracing」里调。

显卡和驱动得支持 Vulkan 光线追踪。DLSS 要 NVIDIA RTX 显卡；HDR 要 HDR 显示器，并且系统里打开了 HDR。

只需要装在客户端。配一个 LabPBR 资源包效果会好很多，比如 [SPBR](https://modrinth.com/resourcepack/spbr)。JAR 里已经带了 Windows x64 和 Linux x64 的原生库，不用另外装 DLSS SDK。

碰到问题请到 [Issues](https://github.com/baizhizzl/caustica-neoforge/issues) 反馈，附上游戏日志、显卡型号和驱动版本。

## 更新记录

### 未发布

- 镜头切换时重置 DLSS 的时序历史。传送、重生、切换维度或世界、按 F5 切换第一/第三人称、调整视距或按 F3+A 之后，DLSS 光线重建和帧生成不再拿上一帧的画面去重投影，切换后的头几帧不会再拖出上一个场景的残影。
- 进入新世界或切换维度时重置自动曝光，比如从下界回到主世界，不会再从下界的曝光慢慢调回来。

### [0.1.1-neoforge.5](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.5) · 2026-10-06

修了 0.1.1-neoforge.4 里方块光源闪烁的问题。

原因出在 .4 新加的「光照预采样」：同一个 16×16×16 区域里的像素，每帧都从同一组 64 个候选光源里挑，而这组候选每帧重抽。结果整片区域的亮度跟着一起跳，降噪器把它当成真实的光照变化保留了下来。现在预采样默认关闭，只作为实验选项保留。

- 预采样的配置键改名为 `lights.experimental-presampling`。.4 写进配置文件的 `lights.presampling = true` 不再生效，下次保存设置时会被删掉。
- 第一次反弹之后的光照改用更少的 RIS 候选：第一次命中还是 8 个，之后默认 4 个（`lights.ris-candidates-indirect`）。多出来的只是零散噪点，降噪器处理得了。
- 选光源时每 4 个候选一批同时读显存，不再一个候选读完才读下一个。选中的光源和原来一模一样，画面不变。
- 只有实体在动时，TLAS 改成原地 refit，不再整棵重建，每个缓冲槽位连续 refit 15 次后完整重建一次。可以用 `composite.tlas-update = false` 关掉。
- TLAS 缓存没受影响，照旧默认开启。

这几项性能改动还没在实机上测过，开关和对比方法见 [PERFORMANCE.md](PERFORMANCE.md)。

### [0.1.1-neoforge.4](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.4) · 2026-10-05

- 地形没变时，不再每帧重写所有静态 TLAS 实例。四个 TLAS 槽位各自记住自己的地形数据，只在区块加载卸载、换世界或坐标重定位时刷新；整个场景都没动时直接跳过 TLAS 构建。
- 实体照常每帧比较、更新。任何实体 BLAS 构建或 refit 之后，所有槽位都会重建一次，避免用到过期的包围盒。
- 新增共享光照预采样（后来发现会导致光源闪烁，已在 .5 默认关闭）。

### [0.1.1-neoforge.3](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.3) · 2026-10-02

- 「视频设置 → Ray Tracing」里可以直接开帧生成、调倍数了，不用再改启动参数。能选到几倍取决于显卡、驱动和窗口系统，最高 6x。
- 加了光线重建开关，模型可选 Auto / D / E / F（RR2），切换不用重启。注意 F 是光线重建模型，不是超分的 L/M 预设。
- 随包的 DLSS 运行库升到 310.9.1，Windows 和 Linux 的 NGX shim 都重新编译了。
- 改帧生成倍数时会同步调整交换链缓冲数量，高倍数下不会再卡在等空闲图像上。
- 修了从 F 切回 Auto 后还沿用旧模型参数的问题；改这些设置时会重置时序历史。

这一版维护者在实机上测过。用帧生成需要打开垂直同步，生成的帧才会一帧帧显示出来。

### [0.1.1-neoforge.2](https://github.com/baizhizzl/caustica-neoforge/releases/tag/v0.1.1-neoforge.2) · 2026-10-02

- 适配 Minecraft 26.3 的 Renderpearl、Vulkan 和 SDL3 接口，以及世界、手持物品、GUI 和实体的渲染。
- 跟进上游：光源采集、NEE、Bloom、天空 LUT、ACES 2.0 SDR/HDR、OpenEXR 截图，以及 GPU 同步方面的修复。
- 重写了模型采集和纹理查询，少了很多临时对象和重复查找；资源重载时的纹理缓存处理也修好了。
- 修了原生库打包，CI 出的成品用的是重新编译的 Windows/Linux NGX shim。

这一版同步到上游提交 [`330acd2`](https://github.com/ComfyFluffy/Caustica/commit/330acd2d743bb6b2e27b53a4adb4a3c852b9e141)，具体见 [UPSTREAM.md](UPSTREAM.md)。

## 从源码构建

需要 JDK 25、Slang 2026.19，以及支持 Vulkan 1.4 的 `spirv-val`。工具放进 `PATH` 就行，也可以用 `VULKAN_SDK`、`CAUSTICA_SLANGC`、`CAUSTICA_SPIRV_VAL` 指定路径。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

Linux 上换成 `bash ./gradlew build` 和 `bash ./gradlew runClient`。

构建结果在 `build/libs/`，别拿 `-sources.jar` 去装。`build` 会顺便跑单元测试、Mixin 目标检查和着色器校验。只想跑移植层的测试和内存分配基准：

```powershell
.\gradlew.bat :core-tests:test :core-tests:benchmarkAllocations
```

默认打包的是仓库里自带的原生库。想打包自己编译的 NGX shim，把它放到 `build/native/ngx_shim/release/`，再指定 DLSS SDK：

```sh
DLSS_SDK=/path/to/DLSS bash ./gradlew build \
  -PngxPlatforms=windows-x64,linux-x64 -PngxVendorConfig=rel -PngxShimConfig=release
```

shim 怎么编译见 [docs/developer_guide.md](docs/developer_guide.md)。

## 致谢

- [ComfyFluffy](https://github.com/ComfyFluffy) 和 Caustica 的各位贡献者，渲染器和着色器都是他们写的。喜欢的话去支持一下[原仓库](https://github.com/ComfyFluffy/Caustica)和 [Modrinth 页面](https://modrinth.com/mod/caustica)。
- [FabricMC](https://github.com/FabricMC/fabric)：quad 发射语义和 sprite 查找算法参考了它的实现（Apache-2.0）。
- [Argon4W/NeoContinuity](https://github.com/Argon4W/NeoContinuity)：NeoForge 上的连接纹理实现和模型收集接口。
- NVIDIA DLSS / NGX：SDK 和原生库。
- [NightConfig](https://github.com/TheElectronWill/night-config)：TOML 配置库（LGPL-3.0）。

## 许可证

源代码和文档采用 LGPL-3.0-or-later，第三方组件各按自己的许可证。详见 [LICENSE.md](LICENSE.md)、[COPYING](COPYING)、[COPYING.LESSER](COPYING.LESSER) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
