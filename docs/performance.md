# TLAS 缓存与光照预采样

这两项有独立开关，默认开启。不改 SPP、反弹次数或 RIS 候选数，也不改 DLSS 设置。

## 改了什么

**TLAS 缓存**：四个缓冲槽位分别记住自己的静态地形实例。区块发布、卸载、世界重置或坐标重定位会让缓存失效；平时只写动态实例区间。动态实例逐项比较地址、索引、掩码、SBT 偏移及变换矩阵，不用哈希判断相等。

TLAS 输入没变，且引用的 BLAS 在中间帧也没有构建或 refit，才跳过构建。某个实体 BLAS 更新时会使所有槽位的构建缓存失效，不能因为当前帧没有 BLAS 操作就复用旧包围盒。需要重建时仍使用 `BUILD`，没有改为 `UPDATE`。

**光照预采样**：每个真实渲染帧先用 compute 生成共享候选池，提前完成 alias 选择、发光颜色解码和矩形光源几何解码。后续 RIS 从池里取候选，但每次查询仍独立选择光源上的采样点并做 reservoir 更新。阴影仍使用当前 TLAS。

候选池包括 1024 个全局候选，以及相机附近至多 8×8×8 个光照网格单元、每单元 64 个局部候选。占用约 2.58 MiB，不随整个光照网格无限增长。超出缓存区域的查询走原来的 alias 路径；只有一个 RIS 候选时不生成用不上的局部池。

默认 8 个 RIS 候选仍是 6 个局部、2 个全局。权重使用原光源层级的混合 PDF，不使用池内出现频率。候选池每帧重写，不复用旧帧的灯光或可见性数据；这不是跨帧或邻域 reservoir 复用。

## 开关

在 `config/caustica.toml` 对应的已有段落中加入或修改以下键，不要重复创建同名段落。修改文件后重启游戏：

```toml
[composite]
tlas-cache = true

[lights]
presampling = true
```

也可以用启动参数覆盖：

```text
-Dcaustica.rt.tlasCache=false
-Dcaustica.rt.lightPresampling=false
```

关掉预采样会跳过整个 compute prepass，恢复逐查询 alias 选样。关掉 TLAS 缓存会恢复每帧写全部实例并构建 TLAS。两项开关均不修改 `lights.ris-candidates`。

## 怎么比较

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

共享有限候选池会改变样本之间的相关性，所以平均估计正确不等于单帧噪声完全一样。当前自动测试覆盖缓存失效、GPU 数据布局和有限候选池的参考估计器；并没有代替实机 GPU 测量或画质对比。是否保留预采样默认开启，应以完整帧时间和噪声表现决定。
