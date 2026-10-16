# 开发与命令行说明

[返回使用说明](../README.md)

## 从源码准备运行环境

日常使用请下载 [Release 便携版](https://github.com/wocenimenma0220/Standalone-Litematic-Renderer/releases/latest)。源码仓库不包含 Java / Electron 运行时、完整原版资源和外观材质 ZIP。

在 Windows 上构建需要：

- JDK 21 或更新版本，包含 `javac` 和打包所需的 `jlink`。
- Node.js / npm，用于安装 Electron；已有 `tools/node` 时，安装脚本会优先使用它。
- PowerShell 7（`pwsh`），用于执行下面的构建命令。
- Windows 自带的 .NET Framework C# 编译器，用于编译启动器。
- 完整的 `assets/vanilla` 与运行所需的 `assets/builtin` 资源。可从对应 Release 的便携包复制 `assets` 目录到项目根，保留相同结构。

在项目根运行：

```powershell
.\install.bat
pwsh -ExecutionPolicy Bypass -File .\verify.ps1 -JdkHome "C:\path\to\jdk" -Schematic "C:\path\to\example.litematic"
pwsh -ExecutionPolicy Bypass -File .\package.ps1 -JdkHome "C:\path\to\jdk"
```

`install.bat` 安装 Electron 依赖；`verify.ps1` 编译引擎，并从多个角度渲染指定投影进行检查。请显式传入自己的测试文件，避免自动选中工作区里最大的投影。

`package.ps1` 编译 Java 引擎和启动器，准备精简 Java 运行时与 Electron，生成 `dist/投影预览/` 和 `dist/投影预览-<版本>.zip`，并打印校验值。常用开关包括 `-ReuseRuntime`、`-SkipElectron`、`-SkipRootLauncherCopy`、`-Version` 和 `-PackageName`。

构建后双击项目根的 `投影预览.exe`。单独构建启动器可运行 `tools/launcher/build-launcher.ps1`。

## 命令行导出

便携包的启动器支持不打开界面的 PNG 导出。在 PowerShell 中运行：

```powershell
& ".\投影预览.exe" --cli "C:\models\example.litematic" "C:\renders\example.png" 1024 1024 45 30
```

最后四个参数依次为宽、高、水平旋转和俯仰角，均可省略，默认 `1024 1024 45 30`。

也可以在已编译的源码目录中直接调用引擎：

```text
java "-Dlitematicrender.home=." -cp bin io.github.litematicrender.Main --render "example.litematic" "out.png" 1024 1024 45 30
java "-Dlitematicrender.home=." -cp bin io.github.litematicrender.Main --anim "example.litematic" "out.gif" 512 480
java "-Dlitematicrender.home=." -cp bin io.github.litematicrender.Main --serve
```

`--anim` 默认输出 `animation.gif`，尺寸 512、480 帧、每帧 2 厘秒，即 50 FPS、9.6 秒一周。`--serve` 启动供桌面界面使用的行式 JSON-RPC 服务，协议实现见 `src/io/github/litematicrender/serve/JsonRpcServer.java`。

底部输入行中的 `//select` 是桌面界面的交互指令，与这里的启动参数分开使用。

## 设置与数据目录

以下路径均相对于程序所在目录。

| 路径 | 内容 |
| --- | --- |
| `settings/ui.json` | 按键绑定、预览方式、光照、背景、面板、视距、缓存选项与欢迎弹窗设置 |
| `settings/background.img` | 用户导入的背景图片 |
| `renders/packs.json` | 导入材质包的路径与优先级 |
| `settings/removed-builtins.json` | 取消自动加载的内置外观包 |
| `skins/settings.json` | 皮肤联网开关 |
| `skins/textures`、`skins/profiles`、`skins/local` | 皮肤贴图、资料缓存和本地覆盖 |
| `cache/scenes`、`cache/previews`、`renders/.gpu` | 自动生成的场景与预览缓存 |
| `logs/app.log`、`logs/launcher.log` | 应用与启动器日志 |

旋转、俯仰、缩放、输出分辨率、漫游位置与当前选区不会在重启后恢复。导出位置由保存对话框指定。

Java 堆上限默认根据机器总内存与启动时可用内存选择。可通过环境变量 `LITEMATIC_RENDER_HEAP_MB` 设置 512–32768 MB；它与界面中的预览几何缓存预算是不同的限制。

## 项目结构与验证

| 目录 / 文件 | 作用 |
| --- | --- |
| `src/io/github/litematicrender/` | Java 引擎：NBT、投影、资源、模型、光照、渲染和 RPC |
| `webapp/` | Electron 主进程、后台导出、设置与桌面界面 |
| `webapp/renderer/` | GPU 预览、漫游、选择工具、按键和指令界面 |
| `assets/` | 原版、模型、字体和材质资源 |
| `tools/launcher/` | Windows 启动器源码与构建脚本 |
| `tools/verify/` | 各功能的回归、界面验证和性能研究脚本 |
| `bin/`、`runtime/`、`dist/` | 编译结果、Java 运行时和分发包，不纳入源码版本管理 |
| `评估与测评/` | 更新记录、架构、调查和待办 |

界面测试入口为 `tools/verify/RunHiddenElectron.ps1`，可通过 `-Script` 选择脚本。部分验证需要先生成对应投影夹具，具体依赖见脚本；验证产物留在忽略的输出目录中。

大投影可用 `LargeOpenBenchmark.java` 分阶段测量。`-Dlitematicrender.mesh.workers=1` 强制串行，默认依据 CPU / 可用堆最多四个区块工作线程。`large-open-ui.cjs` 的参数依次为 `baseline` / `current`、输出标签和可选基线类目录；脚本使用独立设置与缓存。并行边界、字节一致性和取消测试见 `ParallelMeshRegression.java`，索引兼容及损坏检查见 `MeshIndexRegression.java`。

`LITEMATIC_RENDER_TEST_HEAP_MB=7936` 可让 `large-open-ui.cjs` 覆盖大内存分支。测试必须验证生成期间近景、缓存近景和关闭 LOD 后存在真实材质的 LOD0 区块，不能仅凭远景总览、完整场景计数或无错误日志判定成功。大型分块光照的算法选择同时受固定工作量和内存预算约束，不能只在受限堆下测量。

资源加载返回 `timings` 与 `imageCacheHit`。`-Dlitematicrender.resources.cache=false` 禁用基础图片缓存；`-Dlitematicrender.resources.cache.directory=...` 可指定软件目录内的隔离缓存，`ResourceImageCacheRegression.java` 覆盖完整动画像素和资源失效。缓存格式或读取语义变化时更新 `resource-images-v1`，引擎内容变化也会使缓存失效。保留退出清理开关的原有语义。

`warm-open-ui.cjs` 在真实界面按逐页 / 批次 / 逐页 / 批次交错测试，比较像素及近景几何；`batch-transfer-regression.cjs` 覆盖 CRC、归属、关闭与受损未来页隔离。批次最多 128 页 / 2 MiB，CPU 页数据只在正在执行的上传期间暂存，不能扩展为无预算的常驻缓存。`window.__resourcesReady` 用于启动时资源初始化与打开投影的先后顺序。

发布前同步 `package.ps1`、`webapp/package.json`、`webapp/package-lock.json`、`tools/launcher/Launcher.cs` 中的版本，并更新 README 和更新记录。文档修改不需要重编译程序或改写已发布的版本标签。

渲染和大投影技术细节见 [LOD_STREAMING.md](../评估与测评/LOD_STREAMING.md)、[RESEARCH_NOTES.md](../评估与测评/RESEARCH_NOTES.md) 和 [综合评估](../评估与测评/投影渲染与区块缓存综合评估.md)。
