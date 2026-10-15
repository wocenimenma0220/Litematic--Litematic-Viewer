# Sodium 与 EBE 渲染优化评估

更新日期：2026-10-13。对照对象为本机 `sodium-dev`、`EnhancedBlockEntities-1.21.4` 源码与投影预览 Java / WebGL 渲染管线。保留 1.0.8 的评估及对照数据，补充 **1.0.9** 的实施状态。

## 1.0.9 实施结果

已按本轮计划依次完成：生成贴图生命周期修复、光照容量检查及队列瘦身、GIF 场景复用、字体和告示牌缓存、4 顶点索引网格、分块视锥剔除和区域邻居索引，并继续完成稀疏光照、部分形状 AO 与面遮挡、BSP 透明分割和穿面触发。

| 改动 | 验证与边界 |
| --- | --- |
| 生成贴图 | 基础资源表与本场景生成资源分开；切换投影可恢复被覆盖的基础贴图，实体合成贴图不残留；相同尺寸及像素不重建图集 |
| 光照内存 | 分配前用 long 检查尺寸、单数组限制和可用内存，坐标加法检查溢出；三份坐标传播队列改为一份单元索引，传播顺序保持不变 |
| 稀疏光照 | 按投影密度及内存容量自动选择路径；只为占用区段及传播边界分配数组，空区段按列与侧向天空光处理；393,216 个单元与密集路径一致，相距 2000 格的两个区域只需 16 个光照区段，真实投影文件的自动切换检查通过 |
| 光照正确性 | 修复屋顶下错误的直接天空光与传播队列丢更新；对照原版 BlockBehaviour 修正水/含水方块 1 级衰减；补充火焰、熔炉、红石灯、蜡烛的发光状态；局部面 AO 双线性插值、内缩深度混合，方块同方向光照及 AO 复用 |
| 面遮挡 | 按实际模型边界面的 16×16 覆盖掩码保守比较，仅剔除被完全覆盖的面；透明/裁剪区域不参与实体遮挡，已检查相同与不同高度的台阶及玻璃 |
| GIF | 桌面和命令行只构建一次场景；原始帧暂存磁盘、同一调色板逐帧编码；失败保留原输出并清理临时文件；64 MiB 原始帧在 48 MiB 堆上导出并解码通过 |
| 动画周期 | 只考虑场景实际使用的动画，能容纳时选择共同周期，超出帧数限制时提示截断；初始化遵循原版元数据中的首帧，修复火焰从错误源帧开始的问题 |
| 字体与文字 | 字体随 ModelBaker 资源会话复用；文字、颜色、荧光、牌型组成缓存键，最多保留 256 个面板；按实际面板像素生成稳定贴图 ID，实现不同位置共用贴图 |
| 网格 | LMESH3 每面 4 个顶点，追加双精度 BSP 平面和节点元数据；查看器兼容 LMESH1/2，普通层支持 32 位或分批 16 位索引，透明层保留无大索引时的三角形展开回退 |
| 分块 | 按真实顶点包围盒剔除 16 格区段，保留跨出所属方格的模型；合并连续可见绘制区间；仅对可见透明面排序，平移改变可见集合也会触发重排 |
| 采样稳定性 | 图集重新排列暴露旧的归一化 float 采样边界误差。CPU 改为贴图局部像素坐标后，无关贴图导致图集重新排列的检查从 99 个差异像素降到 0 |
| 复杂透明排序 | 相交面先切分，保留 UV、AO、贴图和点选归属；玻璃/水/传送门共用 BSP。正常节点只在穿过分割平面或可见集合变化时重排。交叉红蓝面局部前后颜色、旋转缓存、纯平移穿面触发与旧显卡回退通过 CPU/GPU 检查 |

第一阶段在原 238,848 个导出面的投影上，8 个建筑内部视角提交 **86,234～182,116** 个面，区段绘制调用最多 13 次，开关剔除逐像素一致，见 [第一阶段界面回归](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/desktop.json)。引入部分面剔除和 BSP 切分后几何面数变化，不能把两阶段的面数直接当作同一指标。

最终界面检查的 252,110 个片面，在 8 个建筑内部视角下提交 93,073～195,050 个片面，最多 13 次绘制调用，开关剔除逐像素一致，见 [最终原目录界面回归](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/final-desktop.json)。最终实际投影的 BSP 有 **117,849 个透明片面、9,590 个节点，最大深度 25，没有触发局部回退**；网格文件 **36,764,228 字节**。GIF 使用同一缓存场景，与直接编码同组渲染帧的文件逐字节一致，图集重排也没有改变像素。见 [场景和 GIF 验证](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/advanced-export/scene-export.json)。边界与光照检查见 [光照及形状回归](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/advanced/advanced.json)，相交透明面显卡检查见 [BSP GPU 回归](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/advanced/bsp-gpu.json)。这些测量不代表所有显卡上的 FPS 提升。

4 顶点布局把每面顶点文件载荷从 216 字节降到 144 字节，降低约 33%；GPU 仍需索引，不能把文件减小比例当作全部显存的减小比例。已验证新旧布局像素一致、旧显卡的索引回退、平移后可见性更新、玻璃/水排序与船内水面遮罩。

仍保留明确边界：模型推导的遮挡与光照不是原版运行时体素形状的完整替代；BSP 达到分片或树深度上限时仅对相应叶节点回退深度排序；更多装备外观按实际需求补充。以下为实施前的 1.0.8 评估与历史依据。

## 1.0.8 对照清单（历史）

| 原建议 | 1.0.8 状态 | 剩余工作 |
| --- | --- | --- |
| GIF 场景复用 | 普通预览、PNG 和点选复用场景；GIF 仍逐帧重建 | 桌面与命令行两条 GIF 入口同步改造 |
| 字体、告示牌及生成贴图缓存 | 字体已支持原版中英文与荧光文字；缓存仍属于单次构建 | 资源会话字体缓存、按内容去重、生成贴图清理 |
| 分块与可见性 | WebGL 仍按材质提交整份网格 | 分块包围盒、视锥剔除、区域索引、稀疏光照 |
| 透明排序 | 已统一玻璃、水、传送门排序；大批量改用精确基数排序 | 复杂相交面的几何关系排序与局部触发 |
| AO、光照及遮挡 | 已有逐顶点 AO、双通道光照与部分内缩面修正 | 部分形状的遮挡判断、面光照缓存 |
| 紧凑几何 | 仍是每面 6 顶点、每顶点 36 字节 | 4 顶点配索引，再评估压缩属性 |
| EBE 特殊模型 | 箱子家族、六向潜影盒、钟、装饰陶罐纹样已补全 | 按具体缺失外观补充，已不作为整项待办 |

1.0.7 还完成了漫游鼠标自由转向、每帧合并视角更新、静止时停止绘制，以及同 NBT 物品合并。1.0.8 增加了用户指定的矿车、船、雪傀儡、铁傀儡与猪灵静态实体，并处理船内水面遮罩、实体点选和载具库存。其他实体仍按既定范围忽略。

## 1.0.8 建议执行顺序（历史）

| 顺序 | 改动 | 解决的问题 | 工作规模 |
| --- | --- | --- | --- |
| 1 | 生成贴图生命周期修复、光照数组容量检查 | 连续切换投影留下旧贴图；相距很远的区域可能造成内存不足或整数溢出 | 小到中 |
| 2 | GIF 导出复用已构建场景 | 每帧重复计算几何、光照、文字、皮肤和图集 | 小 |
| 3 | 字体缓存、告示牌贴图去重、避免无变化图集重建 | 重复加载中文字库、重复贴图、整张图集反复拼装 | 小到中 |
| 4 | 4 顶点配索引 | 降低网格文件、上传数据与常驻显存 | 中 |
| 5 | 分块几何、视锥剔除、区域索引与稀疏光照 | 大投影中视野外几何仍参与绘制与透明排序；空白区域占内存 | 大 |
| 6 | 透明面分类、拓扑或 BSP 排序 | 复杂玻璃与水相交时，面中心排序仍可能错序 | 大 |
| 7 | 面光照缓存、部分形状遮挡、剩余 NBT 外观 | 改善台阶与楼梯细节；按具体外观缺失补充 | 中到大 |

这些是适配判断，不是已经测得的整体 FPS 提升。

## 1. 先复用 GIF 场景

当前普通 PNG 导出会通过 `RenderEngine.sceneMesh()` 复用几何，但 GIF 的 `renderAnimation()` 每帧调用带 `ModelBaker` 参数的 `SchematicRenderer.render()`，这个重载会新建 `SceneBuilder`。水、火等纹理帧在变化，静态几何与告示牌文字却跟着重复构建。

可在动画开始时取得一次 `SceneMesh`，随后每帧只更新纹理 tick、光栅化和编码。命令行动画也有同样的重复构建。缓存需要在投影、材质包、皮肤覆盖发生变化时失效，不能跨不同资源状态复用。

当前入口：[RenderEngine.renderAnimation](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/serve/RenderEngine.java:390)、[命令行动画](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/CliRenderer.java:120)、[每帧重建的重载](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/SchematicRenderer.java:44)。

EBE 将固定部分变成烘焙模型，动画部分单独处理，并在资源重载时清除模型缓存。这种分离适合本项目的静态投影：[DynamicBakedModel](D:/litematic-display/EnhancedBlockEntities-1.21.4/src/main/java/foundationgames/enhancedblockentities/client/model/DynamicBakedModel.java:23)、[箱子模型缓存与动画](D:/litematic-display/EnhancedBlockEntities-1.21.4/src/main/java/foundationgames/enhancedblockentities/client/render/entity/ChestBlockEntityRendererOverride.java:21)。

历史 1.0.5 对照实验为 973 ms → 448 ms，见 [历史结果](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/scene-cache.json)。本轮用 1.0.8 和同一用户投影复测：固定 256×256、3 个动画 tick，逐帧重建 **1049 ms**，构建一次后复用 **437 ms**，3 帧逐像素一致，样本耗时降低约 **58%**。见 [1.0.8 复测](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/scene-cache-1.0.8.json)。复用路径计入一次场景构建；两组均不包含资源首次加载、GIF 编码。单次小样本不能推算所有投影的整体 FPS 或导出提升。

GIF 还存在两项独立改进：

- `GifWriter` 先收集全部帧建立统一调色板，同时把编码结果保存在内存。假设实际导出 64 帧，2048×2048 的 ARGB 帧本身就需 1 GiB，4096×4096 则需 4 GiB，尚未计入场景及编码缓存。实际帧数受周期上限影响。可用磁盘临时帧或两遍渲染保留统一调色板，同时逐帧编码到输出流；直接逐帧换调色板容易造成颜色闪烁。
- 动画周期当前取**整个资源图集**的最短周期，而非场景实际使用的动画。最短周期也不能保证其他纹理在结尾衔接。建议从场景引用贴图确定周期，在帧数上限内选择共同周期或明确截断策略，并验证多种流体混合时的循环接缝。

## 2. 缓存字体与生成贴图

`SceneBuilder` 的字体对象属于单次场景，每次重建遇到有字的告示牌都会重新加载。`SignGeometry` 用坐标给文字贴图命名，相同内容在不同位置仍会生成多份相同图片。`SpriteAtlas.replaceGeneratedSprites()` 有生成贴图时会重新拼装整个图集。

建议将字体缓存放在资源会话内；按文字、颜色、荧光、牌型和字体版本缓存文字图片；相同内容共用图集条目。下一步可把静态地形与动态文字/皮肤分开管理，或保留稳定图集槽位。更换字体资源时需要清空旧缓存。

当前入口：[字体加载](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/scene/SceneBuilder.java:299)、[文字贴图生成](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/scene/SignGeometry.java:23)、[图集重建](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/SpriteAtlas.java:55)。

**本轮复现的生命周期问题：** `replaceGeneratedSprites()` 只删除旧的 `sign/` 和 `skin/` 条目；1.0.8 新增的 `litematicrender:entity/` 合成贴图没有清除。染色皮甲、铁傀儡裂纹等旧条目会留到下一个投影，直到资源会话重建。最小实验中，1 个基础贴图加告示牌、染色皮甲后为 3 个条目，切换到无生成贴图场景后仍为 2 个；完全相同的生成贴图再次提交也重新分配了整张图集。见 [复现结果](D:/litematic-display/render/litematic-render/tools/verify/optimization-output/atlas-lifecycle-1.0.8.json)。这是已复现的条目残留，长时间使用的内存增幅尚未测量。

建议独立保留基础资源表和本场景生成资源表，每次按两者构建最终图集。实体也会把 `minecraft:item/…` 等原名贴图加入生成集合，不能仅用命名空间前缀删除全部生成资源，否则可能误删或无法恢复基础条目。按资源版本及生成集合内容判断是否需要重新拼装；图集 UV 改变时同步更新网格。

EBE 告示牌实现保留正反面文字渲染，将牌体作为模型处理；它没有在这里替我们实现中文字体缓存：[SignBlockEntityRendererOverride](D:/litematic-display/EnhancedBlockEntities-1.21.4/src/main/java/foundationgames/enhancedblockentities/client/render/entity/SignBlockEntityRendererOverride.java:12)。

## 3. 分块与可见性

Sodium 收集可见区段，再按区域组织绘制列表。当前 WebGL 按材质绘制整份网格，漫游到建筑内部时仍提交远处和视野外的几何。可以把几何切为 16×16×16 的区段，保存各自包围盒，先做视锥测试，再提交可见部分。

区段数多时也会增加绘制调用，需要按区域合批；透明区段之间仍需处理绘制顺序，不能只是拆块后任意绘制。

当前 `blockAt()` 会遍历各投影区域。可先做空间索引，再为构建区段准备带邻居边界的连续数组。Sodium 的局部光照缓存也采用连续数组；它读取 Minecraft 已有的光照数据，不能直接代替我们自己的离线光照传播。

另外，当前 `computeLight()` 会分配整个总包围盒的 `BlockState[]`，`LightVolume` 又分配两份 byte 数组；区域相距很远时，空白空间也占内存。按 4 字节压缩对象引用估算，512³ 单元仅这三份数组就约 **768 MiB**，尚不含传播队列、方块对象和网格。源码注释中的 134 MB 只对应一份 byte 数组，不能作为整体估算。

三处数组长度仍使用 `int` 乘法，分配前没有容量检查。例如两个单方块区域分别位于 (0,0,0) 和 (2000,2000,2000)，总包围盒为 2001³，即 8,012,006,001 单元，超出 Java 单数组范围，`int` 乘积还会溢出。这是根据代码推导的失败条件，未实际申请该大数组。应先用 `long` 检查尺寸与内存预算、提供可理解的错误提示，再实现稀疏区段存储；天空光和方块光必须继续跨区段传播。

源码依据：[可见区段收集](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector.java:25)、[局部数组缓存](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/model/light/data/ArrayLightDataCache.java:12)、[当前邻居查询](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/scene/SceneBuilder.java:86)、[密集光照数组](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/scene/SceneBuilder.java:207)。

## 4. 透明排序

Sodium 区分无需排序、静态顺序和动态顺序，并用面之间的前后关系、拓扑排序及 BSP 等方法处理较复杂场景。相机移动跨过相关面平面时才触发区段重排。

当前项目已经将玻璃、水、传送门混合排序，并在视角方向不变时跳过重排。1.0.7 将超过 2048 个面的比较排序替换成精确 64 位深度基数排序，保持深度及同深度面顺序规则。1.0.8 发布验证使用实际投影的 **97,870 个透明面**、20 个视角样本，排序及索引准备平均耗时由 **12.81 ms → 4.00 ms**，降低约 69%，见 [桌面验证数据](D:/litematic-display/render/litematic-render/tools/verify/saved-entity-output/desktop.json)。该项测量使用真实面中心与模拟 GL 调用，**不包含实际 GPU 上传及绘制，也不是 FPS**。

仍然使用细分面的中心深度比较，相交面和环形遮挡关系可能无法准确表示。排序算法加速没有改变这一几何近似。增加细分只能缓解问题，还会增加顶点和排序负担。目前只有方向不变时的整体缓存，没有 Sodium 的区段分类、拓扑排序或 BSP 触发管理。

建议先保留简单场景的现有快速路径，为复杂相交场景引入几何关系排序；存在循环遮挡时需要切分或回退策略。跨材质排序、流体背面剔除和不写透明深度的现有修复必须保留。漫游中也不能仅凭旋转方向决定 BSP 是否更新，相机位置可能跨越面平面。

源码依据：[Sodium 分类](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortType.java:10)、[触发管理](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/trigger/SortTriggering.java:1)、[面关系判断](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/data/TopoGraphSorting.java:19)、[当前排序](D:/litematic-display/render/litematic-render/webapp/renderer/gpu-view.js:266)。

## 5. AO、光照与面剔除

Sodium 的光照管线分别处理整面、局部面、内缩面和不规则面，并缓存同一方块各方向的面光照。其遮挡比较缓存对相邻面的体素形状做差集判断，不只是判断邻居是否为完整立方体。

当前项目已经有逐顶点 AO、天空光/方块光分离和内缩面的部分修正，但局部形状及任意模型仍有近似。可以先增加“同方块同方向只计算一次”的缓存，再完善内缩深度插值与部分面的遮挡判断。透明贴图、树叶和荧光文字不能被当成普通实体遮挡面处理；修改前后需要对照楼梯、台阶、活板门、玻璃及发光方块。

源码依据：[SmoothLightPipeline](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/model/light/smooth/SmoothLightPipeline.java:40)、[ShapeComparisonCache](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/ShapeComparisonCache.java:9)。

## 6. 几何数据瘦身

Sodium 的 `CompactChunkVertex` 是每顶点 20 字节，并按区段使用局部坐标。当前项目每顶点 9 个 float，即 36 字节，一面展开为 6 个顶点，顶点数据为 216 字节。

可以先保留 float 精度，将每面改为 4 个顶点及正确的三角形索引。不透明面按 32 位索引计算，顶点加索引为 144+24=168 字节，比现有 216 字节少约 22%；这是数据量推算，不是 FPS 预测。透明面目前已有排序索引，改动时必须同步修改索引生成及回退路径。

1.0.8 用户投影导出的 238,848 个面，按当前布局顶点载荷约 **49.20 MiB**；4 顶点加 32 位索引约 **38.27 MiB**。这些是格式推算，仅代表一份几何数据；浏览器解析数组、GPU 缓冲、透明排序数组与原始文件还会各自占内存。透明面原本另有排序索引，实际总占用应按层分别测量。无 `OES_element_index_uint` 支持时需要小批次 16 位索引或兼容回退，船内水遮罩层也要同步改造。

之后再评估 UV、颜色和光照的紧凑编码。不能只把 6 顶点改成 4 顶点而继续调用 `drawArrays`，否则会破坏三角形连接；也不能照搬 Sodium 的局部坐标编码去截断大型投影的全局坐标。

源码依据：[20 字节格式](D:/litematic-display/sodium-dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex.java:12)、[当前导出格式](D:/litematic-display/render/litematic-render/src/io/github/litematicrender/render/MeshExporter.java:48)。

## 7. 方块实体外观

EBE 有箱子、钟、潜影盒、装饰陶罐等模型与选择器，陶罐还会按各面的纹样选择模型。适合继续补全特殊方块的静态外观和 NBT 图案，而不是给投影预览引入完整 Minecraft 客户端运行时。

原计划中的箱子、六向潜影盒、钟与装饰陶罐四面纹样已完成；头颅与告示牌也有独立几何、皮肤及文字处理。这一项应从“整体待实现”改为具体外观回归清单。1.21.4 的实体数据仍需与项目所用资源版本对照。

1.0.8 实体的下一步可先缓存船、矿车和傀儡的模型模板，按实例变换生成几何；目前 `EntityGeometry` 会逐实体创建部件树，贴图缓存也只存在于单次构建。外观方面仍可完善猪灵幼体盔甲、复杂持物与装备纹饰。非玩家头颅当前继续采用之前约定的 Steve 占位模型。是否补全这些外观应按实际投影需求决定，不扩展到用户未要求的实体种类。

源码依据：[模型清单](D:/litematic-display/EnhancedBlockEntities-1.21.4/src/main/java/foundationgames/enhancedblockentities/client/model/ModelIdentifiers.java:25)、[陶罐模型及图案](D:/litematic-display/EnhancedBlockEntities-1.21.4/src/main/java/foundationgames/enhancedblockentities/client/render/entity/DecoratedPotBlockEntityRendererOverride.java:29)。

## 1.0.8 时的实施建议（已按本轮结果推进）

建议下一轮先修生成贴图清理与光照容量检查，再完成 GIF 场景复用、字体缓存与告示牌去重。这些改动目标明确，本轮已获得场景复用和图集生命周期的直接证据。

随后做 4 顶点索引格式与分块视锥剔除，把大投影漫游作为主要验证场景。先剔除不透明与裁剪层，再设计透明层的可见面列表及跨区段顺序；不能仅按区段中心排序就假定透明关系正确。不要把基于开放世界遮挡传播的 Sodium 区段遍历原样移植，静态投影中的空区域和观察者位置有不同边界条件。

每阶段分别记录资源加载、场景构建、透明排序、GPU 上传、实际帧耗时和峰值内存。回归重点包括连续切换染色装备投影、相距很远及负尺寸区域、中英文荧光告示牌、玻璃与水相交、船内水遮罩，以及相同 NBT 容器点选。分块或缓存改造必须保留方块和实体的点选归属。复杂透明面关系排序与更精确 AO 在对应画面用例上单独推进。
