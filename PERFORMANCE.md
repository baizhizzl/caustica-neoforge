# 移植层性能优化与验证

## 可验证的改动

### 模型零件临时列表

`VanillaModelQuads.Emitter` 由区块工作捕获器和实体捕获器各自持有。
`ScratchLists` 按嵌套调用深度提供独立列表，避免重入回调破坏外层遍历；
所有获取/释放配对在 `finally` 中完成。释放会清除模型引用，超过保留上限的列表会被替换。
不引入全局可变列表，也不让不同区块工作线程共用一份暂存容器。
Quad sink 同样在捕获器创建时缓存。

对常见小列表，预热之后不再为每个模型创建 `ArrayList` 和其数组。
这不保证其他模型代码、首次使用、深度增长或超大模型不分配内存。

### UV 和纹理缓存

`UvRectIndex` 以不可变索引回答半开矩形查询，查询循环不创建对象。
无效输入、atlas 空白和越界 UV 返回 null。
最大深度 20；总节点预算为 `min(1_000_000, max(64, 8 * entryCount))`；
重复、重叠和浮点精度不足时使用终止桶，而不是无限细分。
索引构建不是零分配，重叠终止桶也可能退化为局部线性查询。

`AtlasSpriteFinder` 每个 atlas 保留一份当前列表/索引，资源重载前在工作线程排空后清理。
查询不持有全局缓存锁；工人获取的索引在发布之后不再修改。

## 分配基准复现

依赖 JDK 25，无需 Minecraft 资源、窗口或 GPU：

```sh
bash ./gradlew :core-tests:test :core-tests:benchmarkAllocations --console=plain
```

Windows 使用 `.\gradlew.bat`。基准通过 `ThreadMXBean.getThreadAllocatedBytes` 测量当前线程，
比较原始 `new ArrayList<>()` 和重用列表。两条路径各预热 1,000,000 次，
再分别运行三轮、每轮 1,000,000 次。每次加入相同的四个预先创建的模型零件，
并让列表逃逸到 volatile 字段，避免只消除其中一条路径的分配。

本次 Windows x64、Temurin JDK 25.0.4 的结果：

| 被测存储路径 | 每次列表填充的分配 |
|---|---:|
| 原始 ArrayList 路径 | 80.00 B |
| 重用列表（预热之后） | 0.00 B |

字节数取决于 JVM 对象布局。输出同时显示每次操作的耗时，但单进程、固定顺序的微基准
不足以证明端到端速度，更不能推导游戏 FPS 或整体渲染器零分配。

## 回归验证

`build`/`check` 执行：

- 52 个上游 CPU 单元测试：配置、材质、光源、曝光/颜色、资源包和截图数据等。
- 12 个移植层测试：列表复用/重入/异常释放/超大列表，UV 边缘/空白/非法输入/
  重复矩形/极小矩形，以及随机查询对照。
- `verifyMixinTargets`：读取指定 MC/NeoForge SDK 与本模组字节码，审计 26 个客户端
  Mixin 的 277 项契约，不初始化 Minecraft、SDL 或 GPU。
- 通过 Slang 编译并使用 `spirv-val --target-env vulkan1.4` 验证全部生成管线。

尚未实测的项目：启动时的实际 Mixin 变换、进入/切换世界、区块加载/卸载、
F3+T 资源重载、HDR、DLSS RR/FG、截图、Linux Wayland、动态模型和第三方模组组合。
这些运行测试需要真实客户端和硬件；CPU 测试及静态审计不能替代它们。

## TLAS 缓存与光照预采样

这两项有独立开关。TLAS 缓存默认开启；光照预采样是实验选项，默认关闭。两项都不改 SPP、反弹次数或 RIS 候选数，也不改 DLSS 设置。

### 改了什么

**TLAS 缓存**：四个缓冲槽位分别记住自己的静态地形实例。区块发布、卸载、世界重置或坐标重定位会让缓存失效；平时只写动态实例区间。动态实例逐项比较地址、索引、掩码、SBT 偏移及变换矩阵，不用哈希判断相等。

TLAS 输入没变，且引用的 BLAS 在中间帧也没有构建或 refit，才跳过构建。某个实体 BLAS 更新时会使所有槽位的构建缓存失效，不能因为当前帧没有 BLAS 操作就复用旧包围盒。需要重建时仍使用 `BUILD`，没有改为 `UPDATE`。

**光照预采样**：每个真实渲染帧先用 compute 生成共享候选池，提前完成 alias 选择、发光颜色解码和矩形光源几何解码。后续 RIS 从池里取候选，但每次查询仍独立选择光源上的采样点并做 reservoir 更新。阴影仍使用当前 TLAS。

候选池包括 1024 个全局候选，以及相机附近至多 8×8×8 个光照网格单元、每单元 64 个局部候选。占用约 2.58 MiB，不随整个光照网格无限增长。超出缓存区域的查询走原来的 alias 路径；只有一个 RIS 候选时不生成用不上的局部池。

默认 8 个 RIS 候选仍是 6 个局部、2 个全局。权重使用原光源层级的混合 PDF，不使用池内出现频率。候选池每帧重写，不复用旧帧的灯光或可见性数据；这不是跨帧或邻域 reservoir 复用。

### 开关

在 `config/caustica.toml` 对应的已有段落中加入或修改以下键，不要重复创建同名段落。修改文件后重启游戏：

```toml
[composite]
tlas-cache = true

[lights]
experimental-presampling = false
```

也可以用启动参数覆盖：

```text
-Dcaustica.rt.tlasCache=false
-Dcaustica.rt.experimentalLightPresampling=true
```

旧键 `lights.presampling` 不再读取，保存设置时会从文件中删除。

关掉预采样会跳过整个 compute prepass，恢复逐查询 alias 选样。关掉 TLAS 缓存会恢复每帧写全部实例并构建 TLAS。两项开关均不修改 `lights.ris-candidates`。

### 怎么比较

用同一存档、相同站位和画质，分别测：

| 对照 | TLAS 缓存 | 光照预采样 |
| --- | --- | --- |
| 原路径 | 关 | 关 |
| 只测静态缓存 | 开 | 关 |
| 两项都开 | 开 | 开 |

先等区块加载完成，再记录稳定帧时间和卡顿。四个 TLAS 槽位都需要暖机。临时关掉帧生成，以真实渲染帧时间比较，不能拿生成后的显示帧率当作渲染收益。

开启 `-Dcaustica.rt.frameStats=true` 后，计数写入游戏目录的 `rt-frame-stats/frame.csv`：

- `tlasStaticInstancesWritten`：本帧写入的静态实例数。稳定场景暖机后应为 0。
- `tlasDynamicInstancesWritten`：写入的动态实例数。TLAS 完全复用时也为 0。
- `tlasInstanceBytesFlushed`：提交 flush 的逻辑字节数，不含驱动按内存原子大小做的取整。
- `tlasBuilds` / `tlasBuildsSkipped`：构建或复用次数。
- `lightPresampledCandidates`：本帧生成的池条目数。

`frame.prepareTlas`、`frame.recordTlas`、`frame.lightPresample` 是 CPU 计时，不是 GPU 执行耗时。GPU 对比可用 RenderDoc / Nsight 的 `light proposal prepass` 标签，并把 prepass 和后续光追的总耗时一起看。

测试场景至少包括静止地形、移动实体、区块加载卸载、远距离传送、切换维度，以及大量发光方块的室内场景。光照部分还要看快速移动遮挡物、增删光源和低亮度区域。

### 预采样为什么默认关闭

共享有限候选池会改变样本之间的相关性。期望值仍然正确，但同一光照网格单元内的所有像素在同一帧看到的是同一组 64 个局部候选：

- 两个功率相同的光源，某一帧池里各占多少条服从二项分布。只被其中一个照亮的表面，单帧亮度标准差约为 12.5%，而且整个单元一起变化。
- 单元邻域里有大量发光方块（岩浆、萤石）时，近处的火把被选中的概率可能只有 1/500。64 个候选里没有它的概率约为 88%，于是火把附近整片区域在多数帧偏暗，偶尔一帧突然很亮。

逐像素独立选样时，这些误差在屏幕上是互不相关的高频噪声，DLSS RR 和时序降噪可以消掉；共享池把它变成单元大小、逐帧变化的低频误差，降噪器会把它当成真实的光照变化保留下来，看起来就是光源闪烁。加大池子只能按 √N 缩小幅度，无法解决低概率近光源的问题。

当前自动测试覆盖缓存失效、GPU 数据布局和有限候选池的参考估计器，不能代替实机画质对比。
