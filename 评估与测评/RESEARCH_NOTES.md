# litematic-render 技术调研总结 + 实施蓝图

工作区：D:\litematic-display
软件目录：D:\litematic-display\render\litematic-render

## 0. 架构变更（已实施）

原计划是 Java 25 + Swing 自绘 GUI。**该方案已废弃**：自绘字体/控件成本极高，
`gui/` 整个包（含 DebugFont1~7）已移到 `archive/swing-ui/`。

现架构：

```
Electron 外壳（HTML/CSS/JS 界面）
   └─ JSON-RPC（stdin/stdout，一行一 JSON）→ Java 渲染引擎子进程
                                                └─ runtime/（jlink java.base+java.desktop，47.9MB）
```

- 渲染核心保持 Java（纯 CPU，无 GUI 依赖），一行没丢
- 界面、交互、打包全部交给 Electron
- 绿色便携包 175MB，目标机不需要 Java / Node

## 0.1 对照 quickcraft 与 WikiRenderer 的结论

读完了 `quickcraft-main`（124KB 的 `QuickLitematicaPreview3D.java` + 各版本
`QuickLitematicaPreviewAccess.java`）与 `WikiRenderer-26.3` 全量源码。要点：

| 问题 | quickcraft | WikiRenderer |
|---|---|---|
| 面剔除 | **自己一行都没写**，全部交给 MC 的 `BlockModelRenderer.render(..., cull=true)` | 交给原版 `SectionCompiler` |
| 光照 | 单方向光 `(0,0.35,0.25)` 视图空间变换；六向系数是 MC 的 `CardinalLighting.DEFAULT` | 两个方向光随等轴角旋转，目标「顶100%/左80%/右60%」 |
| AO | MC `BlockModelLighter` | 同左 |
| 方块实体 | **调原生 `BlockEntityRenderDispatcher`**，无 mixin | 调原生，且 mixin 掉距离判定 |
| 半透明排序 | `sortQuads(byDistance(0,0,1000))` **只在烘焙时排一次**，转视角不重排（已知弱点） | 自写 `OrthographicSort` 用真实 MVP，旋转时自动重排 |
| 数据源 | 读 `.litematic` / 选区快照，假光照满亮 | **只渲染已加载的真实世界区域**，不读 litematic |

**对离线渲染器最重要的两条**：
1. 它们能"偷懒"是因为有 MC 运行时。我们没有，所以 `shouldRenderFace` / AO / 模型
   tessellation 必须自己实现——**但算法可以直接抄 MC 客户端源码**（见 §0.2）。
2. quickcraft 的缓存版本注释记了一个关键坑：
   > v14：UV 恢复 float32，并保留完整 light 坐标，避免斜视面跨出方块图集 sprite 边界。

   即**UV 精度不足会导致贴图渗色**。我们的 `uFrac/vFrac` 是插值后重映射到图集，
   必须留足精度并钳位（已在 `Rasterizer` 中钳到 `0.9999`）。

## 0.2 已修复的硬伤（本轮）

用真实投影 `曼波mis_(咸鱼lite_v2+)_v2.4.2.litematic` 量化验证：

| 问题 | 证据 | 修复 |
|---|---|---|
| **multipart 完全未实现** | 1288 个 blockstate 里 104 个是 multipart；该投影 **997 个方块（6.44%）一个面都没画**，含红石线×629、堆肥桶×149、石砖墙×90、玻璃板×75 | `ModelBaker.selectVariants` 实现 `when` 条件求值（OR/AND 嵌套、`a\|b` 多值），`SceneBuilder` 逐个 emit。未渲染方块降到 **0** |
| **面剔除把非全方块当遮挡体** | `isSolidOccluder()` 只看硬编码 id 名单，漏斗×1813/箱子×1314/比较器×347 全被误判 → 下方与侧面被错误剔除，实测 **8384 个内部透明空洞** | 新增 `BlockShape`：从数据反推（模型须「每面恰好 1 quad 且铺满整面」+ 贴图全不透明）。箱子/漏斗/台阶/玻璃自动不遮挡 |
| **完全没有 AO** | `SceneBuilder` 注释写着 "flat (non-AO) lighting for v1" | 按 `BlockModelLighter.prepareQuadAmbientOcclusion` 实现逐顶点 AO，含 light-permeable 退化规则与 per-face 顶点重映射 |
| **流体是平板** | 固定 `14/16` 高度，侧面 UV 取 `0..0.5` | 按 `FluidRenderer` 实现逐角高度场（`getHeight` / `calculateAverageHeight` 十次加权） |
| **半透明排序不对** | 按整 quad 质心排序，相交几何排序错误 | `SchematicRenderer.subdivide` 把大 quad 切成 4×4 再按相机深度**从远到近**绘制，半透明不写深度 |
| **贴图渗色** | 图集采样越界取到邻格像素 | `Rasterizer` 钳位 `uFrac/vFrac` 到 `[0, 0.9999]` |
| **粉黑格子静默出现** | `chain`×5 方块画成 missingno | `BlockRegistry.LEGACY_RENAMES`（`minecraft:chain` → `minecraft:iron_chain`，铜链加入时改的名）；并新增 `RenderDiagnostics` 自检，未解析资源不再静默 |

`RenderDiagnostics` 现在会在渲染结束时报告未能解析的模型/贴图，CLI 与 GUI 都会显示。

## 0.3 方块实体 / 告示牌文字（本轮已做）

原版用 `BlockEntityRenderer` 画告示牌文字、箱子内容物等。我们没有 MC 运行时，
按「能静态呈现的就自己画」的原则处理：

**先修了一个根因 bug**：`LitematicaSchematic.readBlockEntities` 把 TileEntity 的
`x/y/z` 又减了一次 region `Position`。实测（`DiagTeCoords`）证明 litematic 里 TE 坐标
**已经是 region 局部坐标**——减完之后每个方块实体都指向错误的格子（该投影 4206 个
TE 全部错位，例如 `birch_wall_sign` 被算成 `hopper` 再算成 air）。
这直接影响所有方块实体查询，不只是告示牌。

**已实现**：
- `BlockEntityData.readSign`：解析 1.20+ 的 `front_text`/`back_text`（messages 是 JSON
  文本组件列表，含颜色与发光标记），兼容旧版 `Text1..Text4`
- `FontRenderer`：直接读 `font/include/default.json` 的 bitmap provider
  （贴图 + ascent + 字符网格），按「最右非透明列 +1」算字宽——这就是 MC 比例字体的做法，
  不需要任何 TTF。实测载入 **2453 个字形 / 6 个 provider**，输出与原版一致（带阴影）
- `SceneBuilder.emitSignText`：把文字画到告示牌两面（yaw 从 `rotation`/`facing` 取，
  背面做镜像），quad 用 `SceneMesh.text` 直接采样文字图像（新增 `Image direct` 路径）

**该投影实测**：50 个告示牌，43 个有文字，其中 31 行含拉丁字符并成功绘制
（如 `prefill one empty`、`Start switch`、`@Marcowind`）。

**已知限制**：内置资源包**没有中文字形**——原版的 `font/include/unifont.json`
provider 列表是**空的**，中文由游戏启动时单独下载的 Unifont 提供，不在客户端 jar 里。
所以纯中文的告示牌（该投影 12 个）画不出字，会被 `RenderDiagnostics` 明确报告，
而不是画成乱码或空白。要补的话需要额外拿 unifont 的 hex 数据。

## 0.4 仍未做

- 箱子/潜影盒/木桶的内容物（GUI 里看不到，静态图上通常也不可见）
- 旗帜图案、头颅皮肤、床的枕头颜色
- 实体（掉落物、盔甲架、矿车等）


## 需求（用户确认）
1. 离线渲染 .litematic 文件的桌面软件（不开 MC）
2. 仿 MC 原版 + WikiRenderer 的渲染方式与算法
3. 具备 WikiRenderer 的全部功能
4. 大文件夹包含小文件夹/项目，打包 zip 即可在别机运行（绿色便携）
5. 内置全部 MC 原版资源，支持导入其它资源包（zip 或文件夹）
6. 参考/挪用 quickcraft-main 的部分代码
7. 用真实 .litematic 测试（曼波mis_(咸鱼lite_v2+)_v2.4.2.litematic 已在工作区根目录）

## 技术选型（依据调研）
- **Java 25 + Swing/AWT 自绘软件光栅化**（也可加 LWJGL，但先做纯 CPU 光栅化保证零依赖）；
- 直接复用 26.3 反编译源码中**纯数据**的类：模型 JSON 解析（CuboidModel/FaceBakery/QuadCollection）、
  BlockStateModelLoader/ModelDiscovery/ModelBakery、ModelBlockRenderer/BlockModelLighter、
  SpriteLoader/Stitcher/SpriteContents/MipmapGenerator、BlockColors/BlockTintSources、
  FluidRenderer/FluidStateModelSet、NativeImage(自己实现 PNG 编解码，避免 LWJGL STB 依赖)；
- .litematic 解析：直接抄 litematica 的 LitematicaSchematic.readSubRegionsFromData +
  LitematicaBlockStateContainer/LitematicaBitArray + malilib CompoundData 体系（纯 Java）；
- 渲染管线：镜像 WikiRenderer：
  - 正交投影（rotY+rotX 的 view 矩阵，rotation/slant 可调，预设 isometric 35.264/dimetric 30）
  - 每个非空气方块生成 quads（面剔除用 Block.shouldRenderFace 逻辑+AO），按 SOLID/CUTOUT/TRANSLUCENT 分层
  - 半透明排序：先按深度排序 quad（正交下用 MVP 第三行投影深度，参考 OrthographicSort.java）
  - 采样 atlas：把所有 sprite 按 Stitcher 拼成一张大图，直接双线性/最近邻采样
  - 输出：逐像素写 RGBA→PNG（透明背景，alpha 包围盒裁剪），迭代重渲染实现像素级 scale
- GUI：Swing 预览窗口 + 导出选项面板（WikiRenderer 的 PropertyBundle 概念简化为 UI 控件）
- 动画：静止物品/方块跳过；有动画贴图时逐帧 time=frametime 累积重渲染帧序列→gif（纯 Java 写 GIF 编码器或逐帧 PNG 序列）
- 资源包管理：内置原版 assets（26.3-client-src/assets 直接复制进 litematic-render/assets/vanilla），
  支持导入 zip(FilePackResources 同款 ZipFile 遍历)/文件夹(PathPackResources 同款)，
  多包按顺序叠加（后导入的覆盖），blockstates/models/textures/lang 全部走同一 ResourceManager。

## 已验证的关键实现细节（file:line）
### 模型烘焙
- CuboidModel JSON: parent/elements/from/to/faces/uv/cullface/tintindex/rotation/rescale — CuboidModel.java:39-93
- 面烘焙: FaceBakery.bakeQuad:50-126；FaceInfo 顶点表 FaceInfo.java:10-46
- UV 默认计算 defaultFaceUV:26-35；uvlock: BlockModelRotation.WithUvLock:55-75
- 父链解析: ModelDiscovery（parent 队列 + valid 传播）:31-137；texture slots 解析 TextureSlots.Resolver:88-149
- blockstates 加载: BlockStateModelLoader.loadBlockStates:33-93（variants + multipart）
- multipart 条件: KeyValueCondition(! 取反、| 分隔), CombinedCondition(AND/OR) Condition.java:16-41
- 模型选择: SimpleModelWrapper.bake:23-36
- item 生成模型: ItemModelGenerator（layer0-4 拉伸挤出）:23-241
- 物品定义 items/*.json → type minecraft:model → 引用 item/xxx 模型（离线只做方块，物品后续）
### 贴图
- NativeImage.read: STB load RGBA（自己用 javax.imageio 替代，注意 alpha 预乘问题：STB 不预乘，imageio 也不预乘，OK）
- PNG 写出: NativeImage.writeToFile:373-401（STB）；自己用 ImageIO TYPE_INT_ARGB 写
- 动画 .mcmeta: AnimationMetadataSection（frametime/frames/interpolate）；SpriteContents.AnimatedTexture:258-277 (frameRowSize = fullW/frameW)
- atlas 装配: Stitcher(贪心 power-of-two 扩容):24-110；Preparations.regions→TextureAtlasSprite(u0..v1)
- Transparency: computeTransparency（有没有 alpha=0/非255 像素）→ ChunkSectionLayer.byTransparency:28-34
- missing 纹理: MissingTextureAtlasSprite.generateMissingImage(16,16) 粉黑棋盘
### 方块渲染
- tesselateBlock: ModelBlockRenderer.java:49-75（AO 条件: ambientOcclusion && lightEmission==0 && part.useAmbientOcclusion）
- 面剔除: Block.shouldRenderFace:295-327（getFaceOcclusionShape + skipRendering + OCCLUSION_CACHE）
- CardinalLighting: DOWN 0.5 / UP 1.0 / N,S 0.8 / W,E 0.6（CardinalLighting.java:8）
- AO: BlockModelLighter.AO 完整逻辑（含 4 corner + light-permeable 对角采样 + non-cubic 权重）
  及 litematica AOProcessorModern（同构实现，可直接对照）
- tint: BlockColors.createDefault:20-46（grass/foliage/water 等 colormap 或常量）；
  colormap 采样 ColorMapColorUtil.get(temp,rain,pixels,def)，pixels=256x256 int[]（LegacyStuffWrapper.getPixels）
  Biome.getGrassColor:201-215（temp/downfall→colormap UV）
  plains biome: temperature 0.8 downfall 0.4（data/minecraft/worldgen/biome/plains.json），water #3f76e4
- 方块实体模型: 跳过 v1（箱子/头颅等走 special renderer，成本高）
### 流体
- FluidRenderer.tesselate:62-336（6 邻取 fluid、高度场、flow 方向 UV、tint=water colormap）
- 高度: FlowingFluid.getOwnHeight=amount/9.0，amount: source=8 else LEVEL(1..7)；hasSameAbove→1.0
- 流向: FlowingFluid.getFlow:53-97（邻高度差+下落项，normalize）
- 面: isNeighborSameFluid / isFaceOccludedByNeighbor(8/9) / shouldRenderBackwardUpFace
- 水 tint: BlockTintSources.water()→BiomeColors.getAverageWaterColor→plains 固定 0x3f76e4
- LiquidBlock.getFluidState: LEVEL(0..7)→stateCache[min(level,8)]（0=source,1..7=flowing 8-level）
### 离线 bootstrap
- SharedConstants.tryDetectVersion→读 /version.json（classpath）→DetectedVersion.createFromJson:32-44
  （自带 BUILT_IN 兜底:26-30，world_version 5023）
- Bootstrap.bootStrap:43-65 → BuiltInRegistries.bootStrap:401-405（createContents+freeze+validate）
  - StaticBootstrapHook 需要 /version.json 在 classpath（把 26.3-client-src/version.json 放进我们 jar 资源）
- ClientBootstrap.bootstrap:18-33（SpriteSources 等 codec 注册）
- 注册表: BuiltInRegistries.BLOCK 来自 Blocks 类静态初始化（BlockIds 引用）→ blockstate 注册
  需要先 SharedConstants + Bootstrap 之后再访问任何 Block 类
### 资源包
- 资源查找路径: assets/<ns>/<path>；PathPackResources.getResource:69-87
- zip 包: FilePackResources（ZipFile 遍历、namespace 提取 extractNamespace:92+）
- 多包: MultiPackResourceManager（packs 顺序，后面的覆盖前面的）:23-55
- pack.mcmeta: PackMetadataSection（description+supportedFormats）→ 离线兼容性检查可放宽
### 渲染输出
- WikiRenderer 导出: RenderableDispatcher.drawIntoTexture:112-136（清屏 alpha0）；
  裁剪 ImageCropper.getCropData:29-55（alpha 包围盒）；缩放=迭代重渲染:142-223
- 相机: setOrtho(-aspect,aspect,1,-1,-1000,1000)，view=scale→translate→rotateX(180+slant)→rotateY(rotation)
  （DefaultPropertyBundle.applyToViewMatrix:134-142）
- 45/135/225/315° 时顶 100%左 80%右 60%（WikiRenderer 方向光）——但 MC 原版地形不走方向光而走 CardinalLighting，
  我们渲染 litematic 用 MC 的 CardinalLighting + AO（保持原版观感），方向光留给特殊模式
### quickcraft 可参考
- QuickLitematicaPreview3D.MeshBuilder.build:2287-2383：容器快速扫描（long word 全零跳过）
- visitNonAirBlocks:2393-2464：word 零跳过 + palette[0]==air 快速路径 → 我们照抄这个扫描逻辑
- RegionBlockView:2212-2270：getBlockState 越界=air 的接口形态 → 我们的 SchematicBlockAccess 同构
- PreviewCache/进度回调/取消传播 → 我们的任务进度模型

## .litematic 测试文件
- D:\litematic-display\曼波mis_(咸鱼lite_v2+)_v2.4.2.litematic（gzip NBT, header 1F 8B 已确认）
- 结构: Version/MinecraftDataVersion/Metadata{Name,Author,Description,RegionCount,TotalVolume,TotalBlocks,EnclosingSize,PreviewImageData}/Regions

## 阶段计划
1. core-nbt：malilib CompoundData 体系移植（纯 Java）
2. core-world：BlockState 注册 + .litematic 解析 + 容器访问
3. core-assets：ResourceManager（目录+zip+多包叠加）
4. core-model：blockstates→models→BakedQuad 烘焙 + atlas 装配（PNG 独立实现）
5. core-render：软件光栅化（正交投影+AO+面剔除+tint+流体+分层排序）→PNG
6. gui：Swing 预览 + 导出对话框 + 资源包管理
7. packaging：绿色包结构（jre 已有 jdk-25 → 复制出 jlink 精简运行时或直接带 bin/java）
