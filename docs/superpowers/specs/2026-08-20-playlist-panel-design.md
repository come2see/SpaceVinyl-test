# 设计：切歌键弹出独立播放列表面板（取代锚定菜单）

- 日期：2026-08-20
- 状态：已获用户确认
- 影响文件：`app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`、
  `app/src/main/res/drawable/ic_dock_close.xml`（新增）
- 关联契约：`.spatialsdk/design-contract.md`（本功能落地后升 v5.5）、`AGENTS.md`（"当前实现了什么"一节需补充新面板）

## 1. 需求

用户原话：「当前用户点击下一首时不再弹窗菜单选项，而是在容器窗口前弹出播放列表，当用户选中后立即播放，
当用户通过点击播放列表上的 X 关闭列表页面」。

拆解成三点：

1. 切歌键触发的不再是锚定在按钮下方的下拉菜单，而是一个"浮在容器/唱机前方"的独立播放列表面板。
2. 选中曲目立即播放（这条在现有代码里已经成立，`onPick` 本就会立刻调用 `onPickSong`）。
3. 面板的关闭**只能**通过面板上的 X 按钮，不支持点击面板外部区域关闭。

### 已确认的产品决策

| 决策点 | 结论 | 备注 |
|---|---|---|
| 面板实现方式 | 新建独立 `AttachmentPanel` 实体，真机调位 | 见 §3；用户在得知需要多轮真机调位的成本后，仍选择这条路线而非复用 `Menu` 组件的低成本方案（见 §2 的"已否决替代方案"） |
| 关闭交互 | 仅 X 关闭，不做点击面板外部关闭 | 面板本身不是 Popup，没有"外部点击"概念，天然满足 |
| 选中曲目后面板是否自动关闭 | **不自动关闭**，面板保持打开 | 用户要连续试听/切换多首时更方便；当前播放曲目靠 `selected` 态高亮 |
| 触发按钮（图标/位置） | 保持现状（`ic_dock_next`，面板既有位置） | 改动最小；这颗按钮从"打开锚定菜单"变成"打开/关闭播放列表面板"的开关 |
| 面板宽度/行高 | 维持现有紧凑尺寸（228dp 宽 / 36dp 行高） | 复用现有 `SONG_MENU_W`/`SONG_ITEM_H` 等常量取值，改动最小 |

## 2. 已否决的替代方案：复用 `Menu` 组件

排查 `com.pico.spatial.ui.design.menu.Menu` 源码（`design-0.13.3-sources.jar`）确认：`Menu` 底层是
`SpatialDialogAsPopup`，本质是开一个真正独立的空间嵌套窗口（`SpaceWindowManager` 的 nested window
type），已在真机验证过可点击可用（AGENTS.md 记录"切歌弹歌单、选歌切换全部可用"）。

理论上只需要：

- `Menu(..., properties = PopupProperties(dismissOnClickOutside = false))`（标准 Compose API）
- 内容顶部加标题 + X 关闭按钮
- 去掉 `onPick` 里的自动关闭

即可零新增 3D 实体、零真机调位成本地满足全部三条需求。

**该方案已呈报用户，用户明确选择不采用**，理由是希望播放列表是像 `dock` 一样"真正在 3D 空间里、
可控前后关系"的独立面板，而不是系统弹窗层级里"浮在前面但具体空间深度不由本项目控制"的对话框。
这是一次有意识的取舍，不是遗漏——如果后续真机调位成本超出预期，这条路线是现成的回退方案。

## 3. 架构：新增独立 `AttachmentPanel`

### 3.1 实体生命周期

不引入运行时"打开时创建、关闭时销毁"的实体增减逻辑——这个项目的 `SpatialView` 已经明确弃用了
`update` 回调（AGENTS.md 硬规则 #1），任何依赖"重组时做一次性设置"的新代码路径都要谨慎。

做法：像 `dock` 一样，`initial` 阶段就创建好 `PLAYLIST_ATTACHMENT_ID` 对应的实体、设好初始位置，
**从此常驻**。可见性完全交给 Compose 内容：

```kotlin
AttachmentPanel(id = PLAYLIST_ATTACHMENT_ID) {
    if (playlistOpen) {
        PlaylistPanelContent(songs = songs, currentTitle = title, onPick = onPickSong, onClose = { playlistOpen = false })
    } else {
        Box(Modifier.size(0.dp))   // 关闭态：零尺寸，不可见
    }
}
```

这与 `dock` 本身"内容随 Compose 状态重组"的已验证模式（AGENTS.md 硬规则 #4）完全一致，不是新模式。

### 3.2 倾斜与位置的一次性修正

复用 `dock` 的 `dockTilted` 一次性标志模式，新增 `playlistTilted`。**关键差异**：`dock` 从一开始
内容就是非空的，可以在帧循环里直接测量；播放列表面板关闭时内容是零尺寸 `Box`，此时测量毫无意义。

所以这次修正必须**同时满足 `playlistOpen == true` 且测到非零包围盒**才执行一次：

```kotlin
if (playlistOpen && !playlistTilted[0]) {
    playlistEntity[0]?.let { panel ->
        val b = panel.getVisualBounds(relativeTo = null, recursive = true)
        val measured = b.size.y / 2f
        if (measured > 1e-3f) {
            // 同 dock：绕底边旋转 = 绕中心旋转 + 位移补偿，见 §3.3 取值
            playlistTilted[0] = true
        }
    }
}
```

修正只做一次、永久生效——用户关闭再打开面板时不会重新计算位置，符合 `dock` 的先例。

### 3.3 空间位置（初始猜测值，需真机验证）

| 项 | 值 | 依据 |
|---|---|---|
| X | 0 | 与 `dock`、唱机同一水平中心 |
| Y | -0.20m | 比 `dock`（-0.33）高：面板更靠近视点、更竖直，透视不会把它显得很低 |
| Z | +0.46m | 比 `dock`（+0.38）更靠前 0.08m，体现"浮在容器/dock 前方"。**+0.38 是已知唯一安全点**（v4.7 实测），+0.46 是全新区域，未经验证 |
| 倾斜角 | -25° | 比 `dock` 的 -60° 更竖直——这是"读列表"面板，不是"俯视操作"的控制面板，更朝向用户便于阅读 |

`VOLUME_SAFE_HALF_XZ`（0.33）不适用于此处——AGENTS.md 硬规则 #6 已明确该边界只对 ECS 网格实体
有效，`AttachmentPanel` 走独立的 native 渲染路径，边界未知，需要靠真机实测（同 `dock` 当年的
`BoxDepthPoC` 方法论），不能凭这个常量判断越界与否。

**如果 +0.46 被裁剪**：回退策略是逐步减小 Z（如 0.42 → 0.40），每次退让 0.02–0.04m，直到真机可见，
并把最终生效值和这次探测过程记入契约——不能悄悄改了常量却不留下"为什么改"的记录（沿用本项目
一贯做法，见 `dock` 当年的多轮调整记录）。

## 4. 内容与交互

- 顶部标题行：`Text("播放列表")` + 右侧 X `IconButton`（新增 `ic_dock_close.xml`，参照
  `ic_dock_check.xml` 的简单矢量风格：24dp viewport，纯路径图标，运行时用 `tint` 参数着色），
  点击 → `playlistOpen = false`。这是唯一关闭入口。
- 列表项复用现有 `MenuItem`（已确认可脱离 `Menu` 独立使用，见 `MenuItem.kt` 源码——它只是一个
  `Box`+`Row` 组合，不依赖 `Menu` 提供的 Popup/dismiss 语义），逐项渲染曲名 + 艺人 + 选中态对勾，
  样式与现有下拉菜单项一致。
- 选中曲目 → 立即调用 `onPickSong` 播放，**不再自动关闭面板**（移除现有 `onPick` 里的
  `songMenuOpen = false` 那一步），用户可连续切歌直到自己点 X。
- 歌单为空 → 沿用现状占位文案"没有可播放的音乐"。
- 触发按钮：面板上现有"切歌"图标（`ic_dock_next`），位置、图标都不变；它控制的状态从
  "是否展开锚定菜单"变成"是否打开这个独立播放列表面板"。

## 5. 影响范围

- `HomeVolume.kt`：
  - 新增 `PLAYLIST_ATTACHMENT_ID`、位置/倾斜常量、`playlistEntity`/`playlistTilted` 状态、
    帧循环里的一次性修正逻辑（同 §3.2）。
  - 新增 `PlaylistPanelContent` composable（标题行 + X + 歌单列表）。
  - `SongMenuButton` 改为纯开关按钮，删除其中的 `Menu`/`MenuItem` 弹出逻辑与相关尺寸常量的
    "锚定菜单"专属含义（尺寸常量数值保留，语义上变成"播放列表面板"的常量，具体是否重命名留给
    实现阶段决定）。
- 新增 `app/src/main/res/drawable/ic_dock_close.xml`。
- `.spatialsdk/design-contract.md`：升版本号至 v5.5，记录本节决策、位置常量、"已否决替代方案"
  （§2）、待验证项。
- `AGENTS.md`："当前实现了什么"一节补充新面板的存在与用途。

## 6. 验证计划

跟 `dock` 当年一致的方法论：

- 编译 + 真机安装启动，`logcat` 确认无致命异常。
- 真机截图确认：面板可见、未被前边界裁掉、朝向合理、文字可读。若 +0.46m 被裁，按 §3.3 的回退
  策略调整并记录。
- 交互验证（`pico-cli` 无法模拟点击，AGENTS.md 硬规则 #4 已有先例）：由用户在真机上确认——
  切歌键能打开/关闭播放列表面板、点歌立即播放、面板选歌后不会自己关掉、点 X 能关闭。
  agent 侧仅能提供"点击路径本身在代码里正确连接"的静态证据，不能自证已验证。

## 7. 明确不做

- 不做点击面板外部区域关闭。
- 不做面板拖拽/手动调整位置的用户交互——位置是固定常量，只能通过改代码调整。
- 不改变面板宽度/行高（维持现有紧凑尺寸），不重新设计歌单项的视觉样式。
- 不做"上一首"或播放历史相关功能——与本次需求无关。
- 不保留旧的 `Menu` 锚定下拉作为后备开关或 A/B 选项——直接替换，不做双轨。
