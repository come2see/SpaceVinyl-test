# AGENTS.md — SpaceVinyl

## 这个项目是什么

`SpaceVinyl`（包名 `tech.illusion.spacevinyl`，**设备上的展示名是「妙妙音播」**）
是一款 PICO Spatial SDK 空间音乐可视化应用：
体积窗口里放一台 3D 唱机，播放本机音乐时唱片盘转起来，周围有随真实音频频谱律动的立体特效。

完整产品规划见 `../docs/superpowers/specs/2026-08-10-spacevinyl-music-visualizer-design.md`；
可被截图逐条核对的设计基准见 `.spatialsdk/design-contract.md`（当前 **v5.1**）；
上机验证记录见 `.spatialsdk/ui-verification-log.md`。

**当前状态：契约 v5.1 已实现，模拟器（Round 32）与真机双端已装。
歌单菜单渲染已验证；**点击交互仍未验证**（`pico-cli` 无输入命令）。**

## 当前实现了什么

单一 Volumetric WindowContainer（960×960×960dp），内部：

- **`vinylPlayer`** — `asset://modern_vinyl_player.usdz` 加载的唱机模型，运行时用
  `getVisualBounds` 实测包围盒后缩放到 ≈0.42m 宽（实测 `final=(0.402, 0.091, 0.420)`），
  底面落在 Y=-0.31m（`-0.15 + SCENE_Y_OFFSET`），台面顶在 -0.219m。
- **`vinylDisc`** — 模型内 `Vinyl_disk_7` 节点，播放时绕自身 Y 轴恒速旋转 33⅓ RPM（-200°/秒，
  俯视顺时针），起停有指数缓动。
- **`barRing`** — **32 根圆柱**（`MeshResource.createCylinder`，半径 0.005）排成 **200° 单弧**，
  半径 0.18，**立在唱机台面顶 −0.259 上、围着唱片**，开口朝向用户。柱高 0.008–0.140 m。
  配色是由能量插值的黄铜梯度（青铜→黄铜→暖白）。角度与尺寸见契约 §4.3c。
  形态演进（都是实测结论，不要往回改）：v4 的 270° 弧读成"左右两簇"；v4.1 满环遮住唱机且后方
  超边界被裁；v4.2 落地椭圆弧；v4.3 收到唱片周围并改圆柱。
  **柱高必须跟环的尺度成比例**——v4.3 首轮沿用 0.30 m 柱高，在 0.36 直径的环上挤成一把梳子。
- **`peakCaps`** — 64 个悬浮峰值块，标出各频段近期峰值，0.55/秒 匀速下落。
- **`dock`** — SpatialUI 控制面板 v3，作为 **`AttachmentPanel`** 挂在 3D 场景里，位置用米指定
  锚点 `(0, -0.33, +0.38)`、**以底边为轴后倾 80°**（近水平，便于俯视操作）。576×104dp，含曲目信息（歌名/艺术家/黄铜进度条）、
  **播放/暂停 + 切歌**两键（v5.0 合并，原三键）、特效菜单（频谱·极光·律动）。
  切歌键打开**独立的播放列表 `AttachmentPanel`**（v5.5，与 `dock` 分离的第二个 3D 附件实体，
  浮在 dock 前方；MediaStore 查询，需 `READ_MEDIA_AUDIO`）。歌单项 228×36dp（v5.1 缩到 0.6），
  字号 `bodySmall`+`bodyTiny`；面板顶部有标题+X 关闭按钮，**仅点 X 关闭**，选歌立即播放但面板
  不自动收起。配色取自唱机贴图实测，强调色黄铜 `#E4A144`。位置/倾斜/尺寸的唯一依据是
  `.spatialsdk/design-contract.md §3` 与 v5.5 小节。

音频：`MediaPlayer` + `android.media.audiofx.Visualizer`（FFT）跑在专用 `HandlerThread` 上。

## 硬性约束（改代码前必读）

### 1. `SpatialView` 的 `update` **不是每帧回调** ⚠️

这是本项目踩过的最深的坑。`update` 是**重组（recomposition）回调**——Compose 状态不变时它就
不再被调用。曾经把动画放在 `update` 里，结果：60 个分帧生成的实体只生成了 6 个、柱子高度冻结、
唱盘完全不转，而且**两张相隔 5 秒的截图像素级完全一致**才发现。

**所有连续动画必须走 `LaunchedEffect { while(true) { withFrameNanos { … } } }`**，
`SpatialView` 现在**不传 `update` 参数**，`initial` 里把 `SpatialViewContent` 存进
`contentRef` 供帧循环使用。帧循环里有每 120 帧一次的诊断日志（`frame=… dt=… spawned=…
discRate=… discYaw=…`）——**不要删掉它**，动画类问题必须有帧循环自身的计数证据，
不能靠观察产物间接推断（这个项目已经因此误判过一次，把问题归因成"设备帧率低"）。

### 2. 控制面板必须用 `AttachmentPanel`，不能用 Compose `Box` + `Modifier.offset(z=)` ⚠️

想让 2D 面板显示在居中的 3D 模型**前面**时，Box 方案做不到，实测四轮结论：

- `Modifier.offset(z = 280.dp)` → 面板被体积窗口前边界整个裁掉，完全不可见
- 探针实测安全上限是 `200.dp`（0/40/80/120/160/200 都可见，280 被裁）
- 但 `200.dp` + 3D 场景后移 0.14m 之后，面板**仍然在唱机后面**

即"够得着模型前面"和"不被前边界裁掉"这两个条件没有交集。`AttachmentPanel` 是 3D 空间里的
实体，位置直接用米，不受此限。

顺带更正一个曾经写错的结论：`Modifier.offset(z=)` / `zOffset` 在 0.13.3 里**确实存在**，
包路径是 `com.pico.spatial.ui.foundation.layout.*`（不是 `com.pico.spatial.ui.foundation.*`）。
早先因为漏了 `.layout` 编译失败，就误判成"这个版本不支持"。

### 3. `MediaPlayer` / `Visualizer` 必须被强引用持有 ⚠️

不持有强引用的话，GC 会在**几秒内**回收它们、native 资源随之释放、**FFT 回调直接停止**。
v4 首轮实测：`band[]` 只活了 4 秒，之后 20 秒纹丝不动。原因是它们本来是 `Handler.post{}` lambda
里的局部变量。现在由 `AudioSession`（`remember` 持有）保管，并在 `DisposableEffect.onDispose`
里释放。**持有引用与释放引用是同一件事的两面，不要只做一半。**

这个 bug 在 v3 就存在，只是那时柱子本来就不动，一直没暴露——**"看不出问题"不等于没问题**。

### 4. 面板交互无法从命令行点击，但重组是可用的

`pico-cli --help` 确认没有输入/点击类命令，**无法从命令行点击 3D 空间里的 `AttachmentPanel`**。
要验证交互逻辑，用临时定时器走与按钮相同的代码路径取证，验完删掉（v4.4 就是这么验的），
或者交用户在真机上确认。

**用户已在真机确认「可点」**（2026-08-11）：特效菜单展开、切歌弹歌单、选歌切换全部可用。
所以交互机制不再是未知项；未知的只是 agent 侧无法自行点击这个工具限制。

`AttachmentPanel` **会随 Compose 状态重组**——v4.4 实测播放键图标从 ‖ 切到 ▶。
所以菜单展开这类 `mutableStateOf` 驱动的交互机制本身是可用的，未验证的只是点击动作本身。

### 5. 判定"动没动"必须做对照实验 ⚠️

这个项目已经在这件事上错过三次（把"加了 X 之后变慢"直接推断成"X 导致变慢"、把 update 不回调
误判成"设备帧率低"、把 FFT 停止误判成正常）。判定动画/性能问题的正确姿势：

- **别用颜色阈值定位或测量 UI 元素**：这个场景（浅灰床品 + 暖色木质 + 半透明玻璃 UI）
  会让任何颜色判据误命中。本会话失败四次：床单枕头被当成面板（v4.6，据此得出"旋转无效"的错误结论）、
  唱片金色标签被当成播放键（v4.3）、半透明菜单玻璃"不够白"没命中、放宽阈值后整张床都命中（v5.1）。
  **可靠做法是目视定位 + 裁图放大确认。** 自相矛盾的测量要当作测量失效，不能当作被测对象的性质。
  差分对照实验仍然有效——那比较的是同一区域的时间变化，不依赖颜色识别对象。
- **运动性**：取相隔数秒的两张截图做像素差分，同时取一块**静止背景**当对照。
  Round 15 实测：柱区变化像素占比 21.5%/30.7%，背景对照区 **0.00%**。
- **信号链**：看帧日志 `band=[]` 是否每次采样都不同。连续多次完全一致 = 回调停了。
- **帧率**：先**停掉应用**跑一次 `PxrMetric` 对照（模拟器自身可能就处于劣化状态），
  再怀疑代码。

### 6. `AttachmentPanel` 可以旋转，但量不到尺寸

- **旋转有效**：`TransformComponent.setEulerAngles` 对 attachment 实体生效（v4.6 实测，
  −90° 时面板明显压平）。`AttachmentPanelComponent` 源码只有 `width`/`height`/`alignment`，
  没有朝向或 billboard 选项。
- **绕底边旋转要补位移**：`setEulerAngles` 绕实体原点（面板中心）转。绕 X 转 θ 后中心需修正
  `(0, -h(1-cosθ), +h·sinθ)`，h 为面板半高。
- **网格实体的容器边界不适用于 attachment 面板**：v4.7 把面板放到 y≈-0.36 / z=+0.38，
  越过了用方块探针量到的"底面 -0.35、安全半边长 0.33"，但**实测没有被裁**。
  那套边界是 ECS 网格实体的，attachment 走另一套 native 渲染路径。不要互相套用。
- **`getVisualBounds` 对 attachment 返回 (0,0,0)**：2D 内容不计入 ECS 视觉包围盒，
  拿不到面板的米制尺寸。当前 h 用估值 0.039（约 104dp）。要精确值得靠同深度放已知尺寸探针反推。
- **俯视角下 45° 与 0° 的差异很小**（面板朝视线转恰好抵消俯视角），
  判断倾斜是否生效要用大角度（如 90°）做判别实验，不要用小角度硬看。

### 7. 要贴合模型某个部位，就量**那个节点**的包围盒 ⚠️

不要拿整机包围盒推算局部位置。v4.5 踩过：用 `容器底面 + 整机高 0.091` 当唱片盘面高度，
结果柱子悬在盘面上方 **19.5 mm**——因为那 0.091 含唱臂和中轴，盘面比它低。
半径同理：唱机 0.402 m 宽 ≠ 唱片直径 0.306 m，估的 0.18 比实测大 27 mm。

正确做法：`root.findEntity("Vinyl_disk_7")` 之后 `getVisualBounds(relativeTo = null)`，
拿 `max.y` 当盘面、`max(size.x, size.z)/2` 当盘半径。当前实测值：
盘直径 **0.3058 m**（正是 12 吋 LP）、盘面 `y=-0.2784`。

注意时序：模型加载要 10–30 s，而柱子在头 11 帧就生成了。所以**实测值到达前必须有兜底常量，
且位置要逐帧按实测值重算**，不能在生成时缓存——否则实测值到达时已经定死。

### 8. 体积容器的可用边界是**量出来的**，不要凭观感猜 ⚠️

Round 21 探针实测（详见契约 §4.2a）：

- **底面 Y ≈ −0.35**（探针 y=−0.30 可见、−0.40 被裁）
- **上边界 Y > +0.15**
- **X/Z 安全半边长 0.33**（960dp 立方推算半边 ≈0.36，留 3cm 余量）

代码里是 `VOLUME_FLOOR_Y` / `VOLUME_SAFE_HALF_XZ`。**超出边界的实体会被静默裁掉，不报错**
——v4.1 的频谱弧后方落在 z=−0.44，整段消失，我一开始误判成"高频段能量塌陷"，
绕了好几轮才发现是裁剪。摆放任何 3D 内容前先对着这两个常量核算一遍。

**探针必须放在不可能被遮挡的位置。** Round 18/20 把探针放在唱机与柱环之间、或与柱子同高，
15 个一个都没看到，白费两轮设备时间。而且**单点不可见不能推边界**，必须拿到可见/不可见的
分界点才算测到（Round 20 从"y=+0.05 不可见"推出"上边界在 y≈0"，Round 21 实测 +0.15 可见，
推断是错的）。

### 9. 不要硬编码 `/sdcard/Android/data/<pkg>/...` 路径

用 `context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)`。用 `adb shell mkdir` 建出来的
目录属主是 `shell:ext_data_rw`（`drwxrws---`），应用自己的 uid traverse 不进去，`MediaPlayer`
会拿到 `EACCES`。走 `getExternalFilesDir` 由应用自己创建目录，属主才对（`u0_a93`）。

推测试音频的正确姿势：先启动一次应用（让它建目录）→ `adb push` → 再启动。

### 10. 模型自带的动画**确认存在但确认不用**

`Modern_vinyl_player.usdz` 里有且只有一个时变属性：`Vinyl_disk_7` 上的 `xformOp:rotateXYZ`
（45 关键帧 / 2.542 秒），是刚体 Y 轴自转，**没有任何 UsdSkel 骨骼数据**。但那段是
「启动→加速→巡航→刹停」的缓动曲线、总转 540°（1.5 圈，终点姿态离起点差 180°），循环它会呈现
「转起来→停住→跳变→再转」。所以改用程序化恒速旋转。这是**用户确认过的决定**，不要"顺手改回"
播放烘焙动画。详见契约 v2 变更摘要。

### 11. 图形能力清单（0.13.3 源码确认，做视觉效果前先看这里）

**图元**：`createPlane` / `createSphere(r)` / `createCylinder(h,r)` / `createCone(h,r)` /
`createCapsule(h,r)` / `createBox(size, cornerRadius)` / `createTorus(outer,inner)` /
`createVideoPanel` / `createWithMeshModel`。**要用哪个先查这份清单，不要猜也不要自己搭。**

**没有自定义顶点/索引网格 API** —— 全库只有 `createBlendShapeSubsetByIndices`。
所以程序化任意几何（平滑曲面、填充包络）做不出来，只能走 bitmap 贴图。

**`UnlitMaterial` 可调项**：blendingMode / cullingMode / **polygonFillMode** / depthTest /
depthWrite / opacity / baseColor / **baseColorTexture** / applyToneMapping。
单个材质只有**一个** baseColor——单根柱子的纵向渐变做不到，只能拆成多段。

- **线框**：`setPolygonFillMode(PolygonFillMode.LINE)`（`FILL(0)` / `LINE(1)`）。
- **叠加混合（辉光）**：`BlendingMode.ADD`。完整取值 `OPAQUE/TRANSPARENT/ADD/FADE/MASKED`。
  ⚠ SDK 的 `BlendingMode.fromInt` 与枚举声明和 `fromValue` 互相矛盾（1→MASKED vs 1→TRANSPARENT），
  看着是 SDK bug。二者皆 internal，但**不要依赖 `getBlendingMode()` 做写入后往返校验**。
- **自发光**：`PhysicallyBasedMaterial.setEmissiveColor()`。
- **2D 转贴图**：`TextureResource(bitmap)` 接受 `ARGB_8888`/`RGBA_F16` —— 任意 2D 效果可画进
  Bitmap 再贴到 Plane 上。**逐帧更新的开销尚未实测，用之前先做压测。**
- **`ShaderGraphMaterial` 有完整参数 API**：`setParameter(name, Int/Float/Color3/Color4/
  Vector2-4/Matrix3-4/TextureResource)` + `getParameterNames()` + `loadFromAssetBundle()`，
  **可以逐帧驱动着色器**。需要 Spatial Editor authored 资产。
- **BlendShape 权重可逐帧设**：`setBlendShapeWeights(List<Float>)`，
  所以**平滑可变形曲面不需要顶点 API**，只要模型带 morph target。
- **静态贴图**：`TextureResource(path, FROM_ASSETS)` 就是 assets 里一个 PNG，零运行时成本。
  `setBaseColorTexture` 无 UV 变换入口，贴图随网格 UV——竖向渐变条贴在 Box 上会随 Y 缩放拉伸，
  正好是"柱子长高渐变保持比例"。**要做单物体内部渐变，用这个，不要逐帧画 bitmap。**

⚠ **查 API 能力时要把整个文件的公开成员列全。** 本会话犯过：只 grep `ShaderGraphMaterial`
前 14 行就断言"没有参数入口"，据此把可视化方案推到了"逐帧 bitmap 贴图"这条错路上
（详见 `.spatialsdk/visualizer-styles-feasibility.md` 的修订记录）。

15 种参考风格的逐个可行性分析见 `.spatialsdk/visualizer-styles-feasibility.md`。

### 12. `MeshResource` 的图元清单（0.13.3 源码确认）

`createPlane` / `createSphere(radius)` / **`createCylinder(height, radius)`** /
`createCone(height, radius)` / `createCapsule(height, radius)` /
`createBox(size, cornerRadius = 0)` / `createTorus(outer, inner)` / `createVideoPanel` /
`createWithMeshModel`。出处 `core-0.13.3-sources.jar` → `MeshResource.kt`。
**要用哪个先查这份清单，不要猜也不要自己搭。** 注意 `createBox` 是支持圆角的。

### 13. `AnimationResource` / `ParticleComponent` 的构造函数都是 `internal`

这两个类都**不能在代码里手动实例化**（`core-0.13.3-sources.jar` 源码确认），只能从加载好的模型
或 Spatial Editor authored 的 AssetBundle 里拿。`particleSwirl` 因此是用普通 Entity + 小球 Mesh
手工模拟的，不是真正的 GPU 粒子系统。

### 14. UI 一律 SpatialUI + `PicoTheme`，禁止 Material/Material3

### 15. 这台机器上的设备是**跨会话共享**的

上设备必须走 `../.claude/skills/spatial-design-first-build/scripts/device-lock.sh` 抢锁，
装/启/截/停之后立刻释放。截图里出现别的应用窗口（StockQuote/TossAR/SpacePlayer）很可能是
**其它会话正在用**，不是自己的残留，不要去 `force-stop` 它们。

另外注意现在可能同时挂着真机（`PB3B4XJGL2090011G`）和模拟器（`emulator-5554`），
`pico-cli` 的设备解析会犯迷糊——建议显式 `export ANDROID_SERIAL=emulator-5554` 并用
`adb -s <serial>`。

> **展示名与工程名不一致是有意的**：`app_name` = 妙妙音播（用户 2026-08-11 指定），
> 而包名、工程目录、主题样式名 `Theme.SpaceVinylTheme` 仍是 SpaceVinyl。
> 后者是内部标识符，改动没有用户价值，不要"顺手统一"。

## 关键文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt` | 全部场景内容：模型加载/缩放/摆放、帧循环动画、音频+FFT、控制面板 |
| `app/src/main/java/tech/illusion/spacevinyl/Main.kt` | `DefaultWindowContainer { PicoTheme { HomeVolume() } }` |
| `app/src/main/AndroidManifest.xml` | Volumetric 容器配置（style=2, 960³dp）+ `RECORD_AUDIO` + `READ_MEDIA_AUDIO` 权限 |
| `app/src/main/assets/modern_vinyl_player.usdz` | 唱机模型（11.6MB） |
| `.spatialsdk/design-contract.md` | 可截图核对的设计基准，改 UI 前先读 |
| `.spatialsdk/ui-verification-log.md` | 每轮上机验证的差异清单与证据 |
| `.spatialsdk/design-ref/` | 视觉稿 + 模型原始副本（用户 Downloads 里那份一度丢失过，这里是备份）。频谱 v4 视觉稿：`spectrum-v4-mockup.html` |

## 怎么构建 / 运行

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

JDK 25 下 Kotlin 2.1.20 会抛 `IllegalArgumentException: 25.0.2`，必须用 JDK 17。

上设备验证走门禁流程（抢锁 → 装/启/截/停 → 释放），见 `spatial-design-first-build` 技能。

## 已知缺口 / 下一步

1. ~~**播放/暂停是纯前端态**~~ **已接通（v4.4）**：面板按钮 → `AudioSession.setPlaying()` →
   `MediaPlayer.start()/pause()` → `isPlayingNow` → 唱盘转速目标 + 频谱平滑开关。
   暂停时唱盘缓动停住、频谱**冻结**（不是归零，"暂停"语义如此）。
   注意 `uiPlaying`（用户意图，面板图标读它）与 `isPlayingNow`（真实播放态，动画读它）
   是**故意分开**的两个状态，不要合并；另有 `wantPlaying` 供 `onPrepared` 判断是否起播。
2. **`effectChip` 的特效切换是纯前端态**。v4.1 移除粒子后只剩「频谱」一个真实特效，「极光」「律动」都是置灰占位，所以互斥切换暂时无从谈起——真做第二个特效时一并接上。
3. ~~**`MediaPlayer`/`Visualizer`/`HandlerThread` 没有释放**~~ **已修（v4）**。原计划作为独立提交，
   但 v4 实测发现「不持有强引用导致 FFT 回调停止」这个 bug 之后，持有引用与释放引用变成同一件事的
   两面——拆开做会留下真泄漏。现由 `AudioSession` 统一管理，见上方硬性约束第 3 条。
4. **应用内运行时权限申请 UI 没做**，`RECORD_AUDIO` 与 `READ_MEDIA_AUDIO` 都靠
   `adb shell pm grant` 手动授予。这是上真机前必做的一步。
5. ~~**选歌 UI 没做**~~ **已实现（v5.0）**：切歌键弹出 MediaStore 歌单。
   菜单渲染已于 Round 32 验证（0.6 缩放、两行不裁、选中态正确）；
   **点击交互已由用户在真机确认可用**（2026-08-11），Round 12 起的挂账项就此关闭。
6. ~~**帧率待查**~~ **已归因，不是代码问题**：Round 12 做了对照实验——**在应用完全没启动的情况下**，
   `PxrMetric` 仍然报 `FPS=1~4/60`（`Pkg=com.pico.spatial.runtime`，即系统运行时自身），
   且 `GPU=0%`、`FrmGpu≈2ms`（GPU 空闲）而 `FrmTime≈85ms`。**结论：低帧率是模拟器本身处于
   劣化状态，与本应用无关**。此前"可能是 AttachmentPanel 开销"的猜测是错的，已更正。
   遇到帧率异常先跑这个对照（停掉应用看 PxrMetric），不要先怀疑代码。
   顺带说明：模型加载时长也随之从约 3 秒退化到约 30 秒，同一原因。
7. **面数超预算**：模型实测 285,939 三角面，是 Shared Space 参考预算（~175k）的 1.63 倍，86%
   在唱片盘（平盘用细分网格做的）。模拟器上 `dt=0.0167` 稳定 60fps 没掉帧，但**真机需要用
   `pico-cli perf` 复核**。要减面的话减唱片盘即可降到 ~4.2 万面。
