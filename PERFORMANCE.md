# 性能优化说明

这里记录移植层做过的性能改动：每项改了什么、怎么开关、怎么自己测。上游渲染器本身的优化不在这里。

先说清楚一点：下面大部分改动只在 CPU 测试和着色器编译层面验证过，GPU 上到底快多少还要实机测。欢迎把测试结果发到 Issues。

## 开关一览

所有开关都在 `config/caustica.toml` 里，也可以用启动参数临时覆盖。改配置文件后要重启游戏。往已有的段落里加键就行，别重复写同名的 `[composite]` 或 `[lights]`。

```toml
[composite]
tlas-cache = true     # 地形没变时复用 TLAS 输入
tlas-update = true    # 只有实体在动时原地 refit TLAS

[lights]
ris-candidates = 8                # 第一次命中的 RIS 候选数，0 关闭方块光源直接光照
ris-candidates-indirect = 4       # 之后每次反弹的候选数，1 到 ris-candidates
experimental-presampling = false  # 共享光照预采样，会导致光源闪烁，别开
```

| 配置键 | 启动参数 | 默认 |
|---|---|---|
| `composite.tlas-cache` | `-Dcaustica.rt.tlasCache` | 开 |
| `composite.tlas-update` | `-Dcaustica.rt.tlasUpdate` | 开 |
| `lights.ris-candidates` | `-Dcaustica.rt.risCandidates` | 8 |
| `lights.ris-candidates-indirect` | `-Dcaustica.rt.risCandidatesIndirect` | 4 |
| `lights.experimental-presampling` | `-Dcaustica.rt.experimentalLightPresampling` | 关 |

旧的 `lights.presampling` 已经不读了，下次保存设置时会从文件里删掉。

## 方块光源的直接光照

每个着色点会从附近的方块光源（火把、萤石、岩浆……）里挑几个候选，用 RIS 选出一个，再打一条阴影射线。挑候选是光照里最贵的一步，主要贵在显存读取：一个附近的候选要连着读三次（光照网格的 section span → section 内的 alias 表 → 光源记录），而且每次读都得等上一次的结果。

### 反弹之后少用几个候选

第一次命中，也就是你直接看到的那个表面，还是用 `ris-candidates` 个候选（默认 8）。从第一次反弹往后改用 `ris-candidates-indirect`（默认 4）。

这样做是因为反弹之后的光已经被衰减过一轮，又被 BSDF 采样糊开了，候选少一点只会多些零散的逐像素噪点，DLSS RR 降得掉，不会成片闪烁。这个值最小是 1，再大也不会超过 `ris-candidates`，所以每个命中点都一定会做方块光源采样，不会漏光。

想换更多帧率可以设成 2；设成和 `ris-candidates` 一样就是原来的效果。

### 候选的显存读取合并成批

一个着色点的几个候选之间其实互不依赖，以前却是一个候选的三次读取全做完，才开始下一个。现在每 4 个候选一批，分三轮发起读取：每一轮把这一批所有候选同一层级的读取一起发出去，下一轮才用结果。这样一批只等三次显存延迟，而不是每个候选各等三次。

随机数还是按候选顺序抽，挑中的光源和逐个挑完全一样，所以画面和噪点都不变。代价是 shader 变大了一些，寄存器压力的实际影响要实机看。

为什么不干脆把 alias 表压成一层、直接少读一次？那样每个网格单元都得把周围 5×5×5 个 section 的所有光源单独展开成一张表，岩浆湖、萤石墙这种地方显存会成倍涨，不划算。

### 共享光照预采样（实验，默认关闭）

这是 0.1.1-neoforge.4 加的功能：每帧先用一个 compute pass 预先挑好一个候选池，包括 1024 个全局候选，再加上相机附近最多 8×8×8 个网格单元、每单元 64 个局部候选，总共约 2.6 MiB。之后所有着色点都从池子里拿候选，省掉了上面那串显存读取。

问题在于，同一个单元里的所有像素这一帧看到的是同一批 64 个候选，而这批候选每帧都重抽。平均下来是对的，但每一帧的误差是整片区域一起承担的：

- 两个一样亮的光源，池子里各占多少个是随机的。只被其中一个照到的表面，单帧亮度上下浮动约 12.5%，整片一起变。
- 周围有一大片岩浆或萤石时，旁边那支火把被抽中的概率可能只有 1/500。64 个候选里没有它的概率约 88%，于是火把附近大部分帧偏暗，偶尔某一帧突然很亮。

逐像素独立挑候选时，这些误差是满屏互不相关的细碎噪点，降噪器能消掉。共享池把它们变成了一整块一起跳动的亮度变化，降噪器会当成真实的光照变化留下来，看到的就是光源闪烁。池子加大只能按 √N 减小幅度，解决不了这种被抽中概率很低、但离得很近的光源。

所以它现在默认关闭。开着它的时候，上面的「合并读取」不生效（走的是候选池），但「反弹后少用候选」照样生效。

## TLAS

每帧都要用所有地形 section 和实体实例构建一棵 TLAS。TLAS 用四个槽位轮流使用，每个槽位等 GPU 用完上一次之后才会被重写。

### 地形实例缓存（`tlas-cache`）

每个槽位记住自己写过的地形实例。地形发布了新版本（区块加载卸载、换世界、坐标重定位）才重写这一段，平时只写实体那一段。实体逐个比较 BLAS 地址、索引、掩码、SBT 偏移和变换矩阵，用的是精确比较，不是哈希。

如果输入完全没变，而且中间没有任何 BLAS 被构建或 refit，就直接复用上次的 TLAS，不再构建。只要有一个实体 BLAS 更新过，所有槽位都要重新处理，不能拿旧的包围盒凑合。

### 原地 refit（`tlas-update`）

需要更新、但地形版本和实例数量都没变时（最常见的情况就是只有生物在走动），槽位用 `UPDATE` 原地 refit 上次的结果，不再整棵 `BUILD`。refit 比重建便宜得多。

refit 不改树的结构，只更新包围盒。实体走得越远，相关节点就越松，光追会慢一点。所以每个槽位最多连续 refit 15 次，之后再有变化就完整重建一次；四个槽位轮换，相当于每个槽位至少每 64 帧重建一次。区块加载卸载、实体出现或消失（实例数变了）都会直接重建。

开了这个选项，槽位会带 `ALLOW_UPDATE` 标志创建，TLAS 占用和 scratch 缓冲会稍大一点。

## 怎么对比测试

1. 同一个存档、同一个站位、同样的画质设置。
2. 等区块都加载完，四个 TLAS 槽位也要先跑热，再记录稳定的帧时间和卡顿。
3. 把帧生成关掉，看真实渲染帧的时间，别拿插帧后的显示帧率算收益。
4. 一次只改一个开关，比如：

| 想看什么 | 怎么设 |
|---|---|
| 反弹后少用候选的收益 | `-Dcaustica.rt.risCandidatesIndirect=8` 对比默认的 4 |
| TLAS refit 的收益 | `-Dcaustica.rt.tlasUpdate=false` 对比默认 |
| TLAS 缓存的收益 | `-Dcaustica.rt.tlasCache=false` 对比默认 |

场景尽量覆盖：静止地形、一堆生物在动、跑图加载区块、远距离传送、切换维度，以及发光方块很多的室内。看光照的时候留意快速移动的遮挡物、放置或拆掉光源、比较暗的角落。

### 帧统计

加上 `-Dcaustica.rt.frameStats=true`，每帧的计数会写到游戏目录下的 `rt-frame-stats/frame.csv`：

- `tlasStaticInstancesWritten`：这一帧写了多少个地形实例，场景稳定之后应该是 0。
- `tlasDynamicInstancesWritten`：写了多少个实体实例，TLAS 被直接复用时也是 0。
- `tlasInstanceBytesFlushed`：提交的 flush 字节数（不算驱动按对齐补齐的部分）。
- `tlasBuilds` / `tlasUpdates` / `tlasBuildsSkipped`：完整重建、原地 refit、直接复用各几次。
- `lightPresampledCandidates`：预采样池这一帧生成了多少条（只在开了预采样时有值）。

`frame.prepareTlas`、`frame.recordTlas`、`frame.lightPresample` 是 CPU 耗时，不是 GPU 耗时。要看 GPU 时间，用 RenderDoc 或 Nsight，按 debug label 找 `frame TLAS … build` / `update` 和 `light proposal prepass`。

## CPU 端：模型采集和纹理查询

### 模型零件临时列表

区块工作线程和实体采集各自持有一个 `VanillaModelQuads.Emitter`。`ScratchLists` 按嵌套深度给每一层回调一个独立的列表，这样重入的回调不会搅乱外层正在遍历的列表。获取和释放都在 `finally` 里配对；释放时会清掉模型引用，太大的列表直接换新的。没有全局共享的可变列表，不同工作线程也不共用暂存容器。

效果是：常见的小列表预热之后，每个模型不再新建 `ArrayList`。但第一次用、嵌套变深、超大模型，或者其他模型代码，仍然可能分配内存。

### UV 和纹理查询

`UvRectIndex` 是一个构建好就不再改的空间索引，用来按 UV 找 sprite，查询时不创建对象。无效输入、落在 atlas 空白处或越界的 UV 返回 null。最大深度 20，节点总数上限为 `min(1_000_000, max(64, 8 * entryCount))`；遇到重复、重叠或浮点精度不够细分的矩形，就放进一个桶里线性查，不会无限细分下去。

`AtlasSpriteFinder` 每个 atlas 只保留一份当前的列表和索引。资源重载前会先等工作线程都停下再清理。查询不持全局锁，索引发布之后也不会再改。

### 分配基准

不需要 Minecraft、窗口或显卡，有 JDK 25 就能跑：

```sh
bash ./gradlew :core-tests:test :core-tests:benchmarkAllocations --console=plain
```

基准用 `ThreadMXBean.getThreadAllocatedBytes` 量当前线程的分配量，对比每次 `new ArrayList<>()` 和复用列表两种写法。两边各预热 100 万次，再各跑三轮、每轮 100 万次，每次都放进同样四个预先建好的模型零件，并把列表写进一个 volatile 字段，防止 JIT 只把其中一边优化掉。

在 Windows x64、Temurin JDK 25.0.4 上的结果：

| 写法 | 每次填充分配 |
|---|---:|
| 每次新建 ArrayList | 80 B |
| 复用列表（预热后） | 0 B |

具体字节数取决于 JVM 的对象布局。这只是个单进程的微基准，说明不了游戏帧率，也不代表整个渲染器不分配内存。

## 自动测试覆盖了什么

`build` 会跑这些：

- 上游的 CPU 单元测试：配置、材质、光源、曝光和颜色、资源包、截图数据等。
- 移植层测试：临时列表的复用、重入、异常时释放和超大列表；UV 索引的边界、空白、非法输入、重复矩形、极小矩形，以及和暴力查询的随机对照；TLAS 缓存的失效和 refit 条件。
- `verifyMixinTargets`：对照指定版本的 Minecraft/NeoForge 和本模组的字节码，检查每个客户端 Mixin 的目标、字段、方法、注入点和回调签名。它不会真的启动 Minecraft、SDL 或显卡。
- 用 Slang 编译所有管线，再用 `spirv-val` 校验生成的 SPIR-V。

这些都替代不了实机测试。下面这些只能进游戏才验证得了：Mixin 在启动时的实际变换、进出和切换世界、区块加载卸载、F3+T 资源重载、HDR、DLSS 光线重建和帧生成、截图、Linux Wayland、动态模型，以及和其他模组一起用。
