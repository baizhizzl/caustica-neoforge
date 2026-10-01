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
