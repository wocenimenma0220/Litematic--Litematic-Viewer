# Litematic Render

离线渲染 Minecraft Litematica 投影文件（`.litematic`）为 PNG 图片的桌面软件。
不启动 Minecraft，按 **MC 26.3 客户端真实算法** 复刻渲染（正交投影、面剔除、逐顶点 AO、
CardinalLighting、multipart 方块状态、流体高度场、colormap 染色、透明背景导出）。

## 架构

```
Electron 外壳（界面 = HTML/CSS/JS）
   └─ JSON-RPC（stdin/stdout，一行一个 JSON）─→ Java 渲染引擎子进程
                                                    └─ runtime/（jlink 精简运行时，~48MB）
```

渲染核心是纯 CPU 计算，不依赖任何 GUI 库，所以界面用 Web 技术重做，引擎保持 Java。

## 使用

1. 双击 **`启动.bat`** 打开界面
2. 点「打开 .litematic…」选择投影文件
3. 拖动旋转/俯仰滑块实时预览（低分辨率），点「导出 PNG…」按所选分辨率出图

命令行渲染（不需要 Electron）：

```
命令行渲染.bat 投影.litematic 输出.png 1024 1024
```

## 目录结构

```
litematic-render/
├─ src/                  Java 引擎源码
├─ bin/                  编译产物（class 文件）
├─ runtime/              jlink 精简 Java 运行时（便携关键）
├─ assets/vanilla/       内置 MC 26.3 全部原版资源
├─ webapp/               Electron 外壳
│  ├─ main.js            主进程：窗口 + 引擎子进程管理
│  ├─ engine-client.js   JSON-RPC 客户端（单工作槽串行化）
│  ├─ runtime.js         查找运行时：runtime/ → 工作区 JDK → JAVA_HOME → PATH
│  └─ renderer/          界面（index.html / style.css / renderer.js）
├─ dist/                 打包输出
├─ 启动.bat              图形界面
├─ 命令行渲染.bat        命令行渲染
├─ install.bat           拉取 Electron 依赖（仅从源码跑时需要）
└─ package.ps1           生成绿色便携包
```

## 从源码构建

```powershell
# 1. 编译 Java 引擎
javac -encoding UTF-8 -d bin (Get-ChildItem src -Recurse -Filter *.java).FullName

# 2. 构建精简运行时（需要 JDK 25）
jlink --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages `
      --compress=zip-6 --output runtime

# 3. 拉取 Electron（首次，需联网）
.\install.bat

# 4. 启动
.\启动.bat
```

## 打包

```powershell
.\package.ps1            # 输出 dist/LitematicRender/
Compress-Archive -Path dist\LitematicRender -DestinationPath dist\LitematicRender.zip -Force
```

产出约 **175 MB** 的 zip，解压双击 `启动.bat` 即可用，**目标机不需要装 Java，也不需要 Node**。

## 引擎协议

Java 端 `--serve` 走行式 JSON-RPC，stdout 只走协议（日志全部改道 stderr）：

| 方法 | 参数 | 说明 |
|---|---|---|
| `ping` | — | 存活检查 |
| `loadResources` | `root?` | 加载资源包目录（默认 `assets/vanilla`），结果缓存 |
| `open` | `path` | 读取 `.litematic`，返回元数据与区域列表 |
| `render` | `out,width,height,rotation,slant,transparent,background,diagnostics` | 出图到文件 |
| `renderPreview` | 同上（略去 `out`） | 出图并以 base64 内联返回，供界面预览 |
| `cancel` | — | 取消当前渲染 |

`render` 返回里带 `unresolved` 字段时会列出**未能解析的资源**（缺失模型/贴图），
不会静默画成粉黑格子——这是这个版本新增的自检能力。

## 渲染算法来源

关键算法逐条对照 MC 26.3 客户端源码（`26.3-client-src/`）移植：

| 实现 | 对应原版 |
|---|---|
| `SceneBuilder.ambientOcclusion` | `BlockModelLighter.prepareQuadAmbientOcclusion`（逐顶点 `(s1+s2+对角+中心)/4`，含 light-permeable 退化规则） |
| `SceneBuilder.CARDINAL` | `CardinalLighting.DEFAULT` = down 0.5 / up 1.0 / N,S 0.8 / W,E 0.6 |
| `BlockShape.shadeBrightness` | `BlockBehaviour.getShadeBrightness` = 全方块 0.2，否则 1.0 |
| `SceneBuilder.emitFluid` | `FluidRenderer.tesselate`（逐角高度场、`getHeight`、`calculateAverageHeight` 十次加权） |
| `ModelBaker.selectVariants` | `MultiPartBlockStateModel`（multipart 的 `when` 条件求值，含 OR/AND 嵌套） |
| `SceneBuilder.shouldRenderFace` | `Block.shouldRenderFace` + `skipRendering` |
| `FontRenderer` | `BitmapProvider`（`font/include/default.json` 的贴图 + 字符网格 + 最右非透明列定字宽） |
| `BlockEntityData.readSign` | `SignBlockEntity` 的 `front_text`/`back_text`（1.20+）与 `Text1..Text4`（旧版） |

### 全方块判定（不用硬编码列表）

原版靠 Java 代码判断 `isSolidRender()` / `canOcclude()`，纯数据端拿不到。
`BlockShape` 改为**从数据反推**：模型必须是「每面恰好 1 个 quad 且铺满整面」的单一方块，
且所有贴图完全不透明。这样箱子、漏斗、比较器、玻璃、树叶等自动不被当成遮挡体——
早期版本用硬编码名单，导致漏斗/箱子下方的面被错误剔除，画面出现透明空洞。

## 已知限制

- **中文告示牌文字画不出来**：原版的 `font/include/unifont.json` provider 列表是空的，
  中文由游戏启动时单独下载的 Unifont 提供，不在资源包里。含拉丁字符的告示牌正常
  （该投影 43 个有字告示牌里 31 行含拉丁字符已绘制），纯中文的会被自检报告出来。
- **方块实体只做了告示牌文字**：箱子/潜影盒内容物、旗帜图案、头颅皮肤未做
  （静态图上通常不可见）。实体（掉落物、盔甲架、矿车）也未做。

## 系统要求

- Windows（其他平台需自行调整 `.bat` 启动脚本）
- 无需预装 Java / Node
- 建议 4GB+ 内存
