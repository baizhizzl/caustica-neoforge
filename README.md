# Caustica — NeoForge 移植版

> **本仓库是 [Caustica](https://github.com/ComfyFluffy/Caustica) 的非官方 NeoForge 移植版，
> 原作者是 [ComfyFluffy](https://github.com/ComfyFluffy)。**
> 渲染器的全部设计、着色器和几乎所有代码均为原作者的成果，本仓库仅包含
> Fabric → NeoForge 的适配工作。
> **本移植版的问题请提到本仓库，不要打扰原作者。**

Caustica 是一个实验性的光线追踪渲染器，面向 Minecraft 26.2 的 Vulkan 渲染后端。
它用硬件光线追踪替换原版世界画面，并支持 NVIDIA DLSS 特性，同时保留
Minecraft 原有的界面与玩法体验。

Caustica 仍处于早期阶段。在渲染器持续完善的过程中，可能会遇到 bug、
部分视觉场景缺失以及频繁的变动。

![Caustica 光追 Minecraft 场景](docs/gallery/2026-07-09_21.25.14.jpg)

## 致谢

- **[ComfyFluffy](https://github.com/ComfyFluffy)** —— Caustica 原作者。
  如果你喜欢这个模组，请去给[原仓库](https://github.com/ComfyFluffy/Caustica)
  和它的 [Modrinth 页面](https://modrinth.com/mod/caustica) 点 Star 支持。
  渲染器本身的全部荣誉属于原作者。
- **[FabricMC](https://github.com/FabricMC/fabric)** —— Fabric Rendering API，
  其语义为本移植的部分实现提供了参考（见下方「借鉴组件」）。
- **[Argon4W](https://github.com/Argon4W/NeoContinuity)** —— NeoContinuity
  （Continuity 的 NeoForge 原生分支）的作者，本移植版的连接纹理捕获与其兼容。

## 功能特性

- Vulkan 硬件路径追踪世界渲染
- DLSS 光线重建（Ray Reconstruction）支持
- DLSS 帧生成（Frame Generation）支持（实验性）
- HDR 输出
- 光追场景中的动态实体渲染
- LabPBR 风格 PBR 材质支持
- OMM（不透明度微贴图）+ SER（着色器执行重排序）优化
- 连接纹理捕获兼容 NeoForge 模型模组
  （例如 [NeoContinuity](https://github.com/Argon4W/NeoContinuity)）

## 运行要求

- Minecraft `26.2` + **NeoForge `26.2.0.57` 或更高版本**
- **启用 Vulkan 图形后端**
- 支持 Vulkan 光线追踪的显卡与驱动
- DLSS 特性需要 NVIDIA RTX 显卡及受支持的驱动
- HDR 输出需要 HDR 显示器并在系统开启 HDR
- Linux 下 HDR 输出需要支持 HDR 的 Wayland 合成器及原生 Wayland 会话
- 推荐搭配 LabPBR 资源包（如 [SPBR](https://modrinth.com/resourcepack/spbr)）以获得更好画质

## 安装方法

1. 为 Minecraft `26.2` 安装 NeoForge `26.2.0.57` 或更高版本。
2. 把 Caustica 的 jar 放进 Minecraft 的 `mods` 文件夹。
3. 以 Vulkan 图形后端启动游戏。
4. 打开「视频设置」调整 Caustica 的渲染器选项。
5. （可选）安装 [NeoContinuity](https://github.com/Argon4W/NeoContinuity)
   以使用连接纹理资源包。

## 使用须知

- Caustica 仅客户端有效。
- DLSS 光线重建与帧生成需要受支持的 NVIDIA 硬件与驱动。
- Linux 下若启动时因栈溢出崩溃，尝试在 Java 参数中加 `-Xss2M` 增大线程栈。
- 可通过 Java 参数改善性能，Minecraft 启动器默认：
  `-XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC`
- 帧生成为实验性功能，需要修改配置文件开启。
- HDR 输出需要 HDR 交换链和正确配置的 HDR 显示器。
- Linux 下启用 HDR 时，Caustica 会自动选择 GLFW 的原生 Wayland 后端；
  X11/XWayland 表面通常不提供所需的 HDR10/PQ 格式。
- 若 Minecraft 在崩溃后回退到了 OpenGL，请重新启用 Vulkan 后端再使用 Caustica。

## 兼容性

Caustica 接管了世界渲染器，因此任何深度修改世界渲染、着色器管线、
后处理或 Vulkan 后端的模组都可能冲突。纯 UI 类模组大概率兼容。

## 移植说明（相对 Fabric 原版的改动）

本移植基于 Caustica **0.1.1**，目标平台从 Fabric 改为 NeoForge：

- Fabric Loader 入口点 → `@Mod` 入口（`CausticaMod`，以及
  `dist = Dist.CLIENT` 的 `CausticaClient`）。
- Fabric API 事件 → NeoForge 事件总线（`ClientTickEvent.Pre`、
  `GameShuttingDownEvent`）；`InvalidateRenderStateCallback` →
  对 `LevelExtractor.allChanged()` 的 mixin 注入。
- `FabricLoader` 的路径/环境接口 → `FMLPaths` / `FMLEnvironment` / `ModList`。
- **完全移除了 Fabric Rendering API（FRAPI）依赖**——NeoForge 没有 FRAPI：
  - `compat/VanillaModelQuads` 基于 vanilla `BakedQuad` 迭代复现了 FRAPI
    `FabricBlockStateModel.emitQuads` 的默认语义，并调用 NeoForge 扩展版
    `collectParts(level, pos, state, random, parts)`，使 NeoForge 模型模组
    （如 NeoContinuity）输出的 quad 能被正确捕获。
  - `compat/AtlasSpriteFinder` 以四叉树反查恢复了上游的 UV→sprite 反向查找
    （用于 UV 重映射的连接纹理 quad），vanilla quad 走声明 sprite 快路径。
  - FRAPI 的 mesh / `SubmitRenderPhase` 支持代码已删除（NeoForge 上不存在
    FRAPI 模组）；保留 NeoForge `submitSpecial` 的默认行为。
- NightConfig 改用 NeoForge JarJar 打包（替代 Fabric 的 `include`）。
- 构建：Fabric Loom → ModDevGradle；着色器在构建期由 Slang/GLSL 源码编译为
  SPIR-V（需要 `slangc`、`glslangValidator`、`spirv-val`）；NVIDIA NGX/DLSS
  原生库原样打包，未做修改。

### 已知移植限制

- 不支持依赖 FRAPI 输出几何体的 Fabric 模组（如 Fabric 版 Continuity）——
  请改用对应的 NeoForge 模组（如 NeoContinuity）。
- 本移植已对全部 26 个 mixin 做了针对 NeoForge 补丁后源码的静态审计，
  并通过了上游单元测试，但真实硬件上的渲染表现仍可能与 Fabric 版存在差异。

## 借鉴组件（出处声明）

除上游 Caustica 代码库本身外，本移植参考或适配了以下第三方组件：

| 组件 | 来源 | 许可证 | 用途 |
|---|---|---|---|
| Caustica（全部渲染器代码、着色器、原生库胶水） | [ComfyFluffy/Caustica](https://github.com/ComfyFluffy/Caustica) | LGPL-3.0-or-later | 模组本体；本仓库是其衍生作品 |
| `FabricBlockStateModel.emitQuads` 默认实现语义 | [FabricMC/fabric](https://github.com/FabricMC/fabric)（fabric-renderer-api-v1） | Apache-2.0 | `compat/VanillaModelQuads` 的行为参照 |
| `SpriteFinderImpl` 算法 | [FabricMC/fabric](https://github.com/FabricMC/fabric)（fabric-renderer-api-v1） | Apache-2.0 | `compat/AtlasSpriteFinder` 的算法蓝本 |
| NVIDIA DLSS/NGX 二进制（`nvngx_dlssd`、`nvngx_dlssg`） | NVIDIA RTX SDK，由上游打包 | NVIDIA 专有（见 `THIRD_PARTY_NOTICES.md`） | DLSS 光线重建 / 帧生成 |
| NightConfig | [TheElectronWill/night-config](https://github.com/TheElectronWill/night-config) | LGPL-3.0 | TOML 配置（经 JarJar 内嵌） |

## 许可证

与上游一致：Caustica 项目自有的源代码与文档采用
**GNU Lesser General Public License v3.0 或更高版本**授权。详见
[LICENSE.md](LICENSE.md)、[COPYING](COPYING) 与
[COPYING.LESSER](COPYING.LESSER)。本移植版作为原作的衍生作品，
同样以 LGPL-3.0-or-later 发布。

发布的成品中可能包含按 NVIDIA 自有许可条款授权的 DLSS/NGX SDK 组件，
详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
