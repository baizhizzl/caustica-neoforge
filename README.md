# Caustica (NeoForge)

[Caustica](https://github.com/ComfyFluffy/Caustica) 的非官方 NeoForge 移植版，面向 Minecraft 26.3。

Caustica 是基于 Vulkan 硬件光线追踪的路径追踪渲染模组，用实时路径追踪替代原版世界渲染，同时保留原版 UI 和游戏逻辑。本仓库负责 Fabric 到 NeoForge 的平台适配与维护，渲染器与着色器均来自上游项目。

> 遇到本移植版的问题请在[本仓库 Issues](https://github.com/baizhizzl/caustica-neoforge/issues) 反馈，请勿向原作者仓库提交。

![Caustica 光追场景](docs/gallery/2026-07-09_21.25.14.jpg)

更多截图见 [Gallery](docs/gallery.md)。

## 功能特性

- **Vulkan 硬件路径追踪**：完整世界路径追踪渲染，包含方块光源直接光照（NEE）、大气散射天空、水体折射与吸收、动态实体渲染
- **NVIDIA DLSS**：支持超分辨率（DLSS SR）、光线重建（Ray Reconstruction，支持 D / E / F 模型切换）与多帧生成（Frame Generation，最高 6x）
- **色彩与后处理**：ACES 2.0 色调映射、自动曝光、Bloom、SDR 与 HDR10 输出、OpenEXR 截图导出
- **材质与加速**：支持 LabPBR 材质标准，支持 OMM（不透明度微贴图）与 SER（着色器执行重排序）硬件加速
- **连接纹理兼容**：接入 NeoForge 模型扩展接口，兼容 [NeoContinuity](https://github.com/Argon4W/NeoContinuity) 等模组

## 运行要求

| 环境 | 版本 / 要求 |
|---|---|
| Minecraft | `26.3` |
| NeoForge | `26.3.0.39-beta` 及以上 |
| Java | `25` |
| 图形后端 | **Vulkan**（需在视频设置中启用） |
| 显卡与驱动 | 支持 Vulkan 硬件光线追踪（Ray Tracing） |

- **DLSS 功能**：需要 NVIDIA RTX 系列显卡及对应驱动。Windows x64 与 Linux x64 原生库已内置于模组 JAR 中，无需单独安装。
- **HDR 输出**：需要 HDR 显示器并在系统设置中开启 HDR；Linux 下需运行在支持 HDR 的原生 Wayland 会话下。
- **材质包**：推荐搭配 [SPBR](https://modrinth.com/resourcepack/spbr) 等 LabPBR 资源包以获得完整材质效果。

## 安装与使用

1. 安装适用于 Minecraft `26.3` 的 NeoForge（`26.3.0.39-beta` 或更新版本）。
2. 从 [Releases](https://github.com/baizhizzl/caustica-neoforge/releases) 下载最新的 `caustica-neoforge-<版本>.jar`，放入 `.minecraft/mods` 目录。
3. 启动游戏，在视频设置中将图形后端切换为 **Vulkan**。
4. 在「视频设置 → Ray Tracing」中调整光追、DLSS、帧生成与显示选项。

### 注意事项

- 本模组仅需在**客户端**安装。
- 开启 **DLSS 帧生成**时，请同时开启**垂直同步（VSync）**，否则生成帧无法按稳定节奏输出。
- 若游戏崩溃后自动回退到 OpenGL 后端，重启后请重新在视频设置中切回 Vulkan。
- Linux 下若启动时出现栈溢出（StackOverflowError），可在 JVM 参数中添加 `-Xss2M`。

## 兼容性

Caustica 会接管世界渲染管线，因此与修改世界渲染、着色器管线、后处理或 Vulkan 后端的其他模组通常不兼容。纯 UI 类、玩法类模组一般可正常配合使用。

## 从源码构建

构建需要安装：

- JDK 25
- [Slang](https://github.com/shader-slang/slang) `2026.19`（`slangc`）
- Vulkan SDK（包含支持 Vulkan 1.4 的 `spirv-val`）

将工具加入 `PATH`，或通过环境变量 `VULKAN_SDK`、`CAUSTICA_SLANGC`、`CAUSTICA_SPIRV_VAL` 指定路径：

```bash
# 编译并打包（产物位于 build/libs/）
./gradlew build

# 启动测试客户端
./gradlew runClient
```

Windows PowerShell 下使用 `.\gradlew.bat`。原生 NGX shim 的编译说明见 [docs/developer_guide.md](docs/developer_guide.md)，与上游的同步记录见 [UPSTREAM.md](UPSTREAM.md)，移植层性能参数说明见 [PERFORMANCE.md](PERFORMANCE.md)。

## 致谢

- **[ComfyFluffy](https://github.com/ComfyFluffy)** 及上游贡献者：Caustica 渲染器与着色器的原作者。欢迎前往[上游仓库](https://github.com/ComfyFluffy/Caustica)与 [Modrinth 页面](https://modrinth.com/mod/caustica) 支持原项目。
- **[FabricMC](https://github.com/FabricMC/fabric)**：模型 Quad 发射语义与 Atlas Sprite 查找实现参考了 Fabric Rendering API（Apache-2.0）。
- **[Argon4W/NeoContinuity](https://github.com/Argon4W/NeoContinuity)**：NeoForge 连接纹理模组支持。
- **NVIDIA DLSS / NGX**：DLSS SDK 与运行时库。
- **[NightConfig](https://github.com/TheElectronWill/night-config)**：TOML 配置文件解析（LGPL-3.0）。

## 许可证

本项目自有源代码与文档基于 **LGPL-3.0-or-later** 许可发布，详见 [LICENSE.md](LICENSE.md)、[COPYING](COPYING) 与 [COPYING.LESSER](COPYING.LESSER)。

构建产物中包含的 NVIDIA DLSS / NGX 组件及其他第三方代码适用各自的许可证条款，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
