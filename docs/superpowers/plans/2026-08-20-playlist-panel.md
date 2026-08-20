# 播放列表 AttachmentPanel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 切歌键改为打开一个独立的、浮在 `dock` 面板前方的 3D `AttachmentPanel` 播放列表，取代现有锚定在按钮下方的 `Menu` 下拉；面板只能通过面板上的 X 按钮关闭，选歌立即播放且面板保持打开。

**Architecture:** 新增第二个常驻 `AttachmentPanel` 实体（`PLAYLIST_ATTACHMENT_ID`），像现有 `dock` 一样在 `SpatialView` 的 `initial` 里一次性创建、用米指定位置；内容随 `playlistOpen` 这个 Compose 状态在"零尺寸空 Box"与"完整播放列表内容"之间切换，倾斜/位置的一次性修正复用 `dock` 已验证的帧循环单次修正模式（`dockTilted` 那一套），只是多一个"必须先真正显示内容才测量"的门槛。

**Tech Stack:** Kotlin + Jetpack Compose（PICO SpatialUI `com.pico.spatial.ui.*`）、PICO Spatial ECS（`Entity`/`TransformComponent`/`AttachmentPanel`）。

## Global Constraints

- 面板宽度/行高维持现有紧凑尺寸：228dp 宽（`SONG_MENU_W`）、36dp 行高（`SONG_ITEM_H`）——原样复用，不新定义。
- 关闭交互**仅** X 按钮，不做点击面板外部关闭；面板不是 Popup，本身没有"外部点击"概念。
- 选中曲目后面板**不自动关闭**，只播放。
- 触发按钮沿用现有 `ic_dock_next` 图标与面板既有位置，不新增/不移动按钮。
- 不使用 `Menu`/`MenuItem` 的 Popup 弹出机制承载这个面板（已否决方案，见 spec §2）；`MenuItem` 本身作为无状态列表行组件仍可复用。
- 不得使用 `SpatialView` 的 `update` 参数（AGENTS.md 硬规则 #1）；所有一次性设置放在 `initial` + 帧循环里已有的 `LaunchedEffect(Unit) { while(true) { withFrameNanos { ... } } }`。
- `VOLUME_SAFE_HALF_XZ`（0.33）不适用于 `AttachmentPanel`（AGENTS.md 硬规则 #6），新面板的 Z 位置边界未知，需真机验证，不能用这个常量判断越界。
- 项目构建必须用 JDK 17：`export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home`（JDK 25 下 Kotlin 2.1.20 会报错）。
- 参考 spec：`docs/superpowers/specs/2026-08-20-playlist-panel-design.md`。

---

## 文件结构

| 文件 | 改动 |
|---|---|
| `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt` | 新增播放列表相关常量、`PlaylistPanelContent`/`PlaylistToggleButton` composable、`HomeVolume` 里的状态/实体/帧循环逻辑；删除 `SongMenuButton` 与其内嵌的 `Menu`/`MenuItem` 弹出逻辑 |
| `app/src/main/res/drawable/ic_dock_close.xml` | 新增：X 关闭图标（新文件） |
| `.spatialsdk/design-contract.md` | 升版本号至 v5.5，记录本功能的位置/倾斜常量与决策依据 |
| `AGENTS.md` | "当前实现了什么"一节替换歌单描述段落 |

---

### Task 1: 新增播放列表面板的 Compose 内容（纯新增，不改动现有调用路径）

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`（新增 imports、常量、两个新 composable；不删除、不修改现有 `SongMenuButton`/`PlaybackDockContent`/`HomeVolume` 代码）
- Create: `app/src/main/res/drawable/ic_dock_close.xml`

**Interfaces:**
- Produces：
  - `PlaylistPanelContent(songs: List<SongItem>, currentTitle: String, onPick: (SongItem) -> Unit, onClose: () -> Unit): Unit`（`@Composable`，`private`）
  - `PlaylistToggleButton(open: Boolean, onToggle: () -> Unit): Unit`（`@Composable`，`private`）
  - 常量：`PLAYLIST_ATTACHMENT_ID: String`、`PLAYLIST_ATTACH_Y: Float`、`PLAYLIST_ATTACH_Z: Float`、`PLAYLIST_TILT_DEG: Float`、`PLAYLIST_HALF_HEIGHT_FALLBACK: Float`、`PLAYLIST_HEADER_H: Dp`、`PLAYLIST_LIST_MAX_H: Dp`、`PLAYLIST_CLOSE_ICON: Dp`
  - `R.drawable.ic_dock_close`
- Consumes（本任务内部使用，均已存在于文件里）：`SongItem`、`BRASS`、`SONG_MENU_W`/`SONG_MENU_RADIUS`/`SONG_MENU_PAD`/`SONG_ITEM_H`/`SONG_ITEM_RADIUS`/`SONG_CHECK`、`TRANSPORT_ICON`、`R.drawable.ic_dock_next`、`R.drawable.ic_dock_check`

- [x] **Step 1: 新增图标资源**

创建 `app/src/main/res/drawable/ic_dock_close.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="@android:color/white" android:pathData="M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"/>
</vector>
```

（与现有 `ic_dock_check.xml` 同一风格：24dp viewport、纯路径、运行时靠 `Icon(tint=...)` 着色。）

- [x] **Step 2: 补三个新 import**

在 `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt` 第 24 行（`import androidx.compose.foundation.layout.PaddingValues`）之后插入：

```kotlin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
```

- [x] **Step 3: 在第 267 行（`private const val DOCK_ATTACHMENT_ID = "playback_dock"`）之后插入播放列表的空间位置常量**

```kotlin

// 播放列表面板：独立于 dock 的第二个 AttachmentPanel，浮在 dock 前方（v5.5）。
// 位置/倾斜是首版猜测值，未经真机验证——见 design-contract.md v5.5 的"播放列表面板位置"一节，
// 如被容器前边界裁掉，按该节记录的回退步长调整 PLAYLIST_ATTACH_Z 并把调整过程记回契约。
private const val PLAYLIST_ATTACHMENT_ID = "playback_playlist"
private const val PLAYLIST_ATTACH_Y = -0.20f
private const val PLAYLIST_ATTACH_Z = 0.46f   // 比 dock（+0.38）更靠前 0.08m
private const val PLAYLIST_TILT_DEG = -25f    // 比 dock 的 -60° 更竖直——这是"读列表"面板，不是俯视操作的控制面板
// 按 dock 的 0.039m / 104dp 比例估算（本面板首屏约 266dp 高：40dp 标题行 + 最多 6×36dp 列表项）。
// 只在面板第一次真正显示内容、但当帧测量仍为 0 时才会用到，见 Task 3。
private const val PLAYLIST_HALF_HEIGHT_FALLBACK = 0.10f
```

- [x] **Step 4: 在第 1211 行（`private val MENU_CHECK = 16.dp`）之后插入播放列表面板的布局尺寸常量**

```kotlin

// 播放列表面板专属布局尺寸（v5.5）。宽度/行高沿用上面 SONG_MENU_* / SONG_ITEM_* 系列，这里只补
// 面板自己新增的标题行高、列表最大可见高度（超过就滚动，避免面板随歌单数量无限变高）、关闭图标尺寸。
private val PLAYLIST_HEADER_H = 40.dp
private val PLAYLIST_LIST_MAX_H = SONG_ITEM_H * 6
private val PLAYLIST_CLOSE_ICON = 14.dp
```

- [x] **Step 5: 在 `SongMenuButton` 函数定义之前（现第 1385 行 `@Composable` / `private fun SongMenuButton(` 之前）新增两个 composable**

```kotlin
/**
 * 播放列表面板内容——独立 `AttachmentPanel` 的内容（v5.5），取代原先挂在切歌键下方的 `Menu` 下拉。
 * 顶部标题 + X 关闭按钮是唯一关闭入口，面板本身不是 Popup，没有"点外部区域关闭"的概念。
 */
@Composable
private fun PlaylistPanelContent(
    songs: List<SongItem>,
    currentTitle: String,
    onPick: (SongItem) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(SONG_MENU_W)
            .clip(RoundedCornerShape(SONG_MENU_RADIUS))
            .backgroundMaterial(true, Material.Regular)
            .padding(SONG_MENU_PAD)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PLAYLIST_HEADER_H),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "播放列表",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.bodyMedium
                )
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dock_close),
                        contentDescription = "关闭",
                        modifier = Modifier.size(PLAYLIST_CLOSE_ICON),
                        tint = PicoTheme.colorScheme.labelPrimary
                    )
                }
            }
            if (songs.isEmpty()) {
                // 空态要说清是什么情况，不要只给一个空列表
                MenuItem(
                    title = {
                        Text(
                            text = "没有可播放的音乐",
                            color = PicoTheme.colorScheme.labelTertiary,
                            style = PicoTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.height(SONG_ITEM_H),
                    cornerSize = SONG_ITEM_RADIUS,
                    onClick = null,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = PLAYLIST_LIST_MAX_H)
                        .verticalScroll(rememberScrollState())
                ) {
                    songs.forEach { song ->
                        val selected = song.title == currentTitle
                        MenuItem(
                            title = {
                                Text(
                                    text = song.title,
                                    color = if (selected) BRASS else PicoTheme.colorScheme.labelPrimary,
                                    style = PicoTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            subtitle = {
                                Text(
                                    text = song.artist,
                                    color = PicoTheme.colorScheme.labelSecondary,
                                    style = PicoTheme.typography.bodyTiny,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.height(SONG_ITEM_H),
                            cornerSize = SONG_ITEM_RADIUS,
                            onClick = { onPick(song) },
                            trailingIcon = if (selected) ({
                                Icon(
                                    painter = painterResource(R.drawable.ic_dock_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(SONG_CHECK),
                                    tint = BRASS
                                )
                            }) else null
                        )
                    }
                }
            }
        }
    }
}

/**
 * 切歌键——现在是纯开关按钮，控制的是独立播放列表 `AttachmentPanel` 的开关状态，
 * 不再像旧版 `SongMenuButton` 那样自带一个锚定在按钮下方的 `Menu`。
 */
@Composable
private fun PlaylistToggleButton(open: Boolean, onToggle: () -> Unit) {
    IconButton(
        onClick = onToggle,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dock_next),
            contentDescription = "播放列表",
            modifier = Modifier.size(TRANSPORT_ICON),
            tint = if (open) BRASS else PicoTheme.colorScheme.labelPrimary
        )
    }
}

```

注意：这一步只是新增代码，`PlaylistPanelContent`/`PlaylistToggleButton` 此刻还没有任何调用方，
`SongMenuButton` 与现有调用路径原样保留、行为不变。

- [x] **Step 6: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`。新函数未被调用，编译器最多给出"unused"警告（Kotlin 对 `private` 函数未使用通常不报错，若报警可忽略——Task 2 会消费它们）。

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt app/src/main/res/drawable/ic_dock_close.xml
git commit -m "feat: 新增播放列表面板内容组件（尚未接入）"
```

---

### Task 2: 接入独立 AttachmentPanel，替换旧的锚定菜单

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt:277-500`（`HomeVolume` 函数体：状态声明、`initial`、`attachments`）
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`（`PlaybackDockContent` 函数签名与函数体；删除 `SongMenuButton`）

**Interfaces:**
- Consumes（Task 1 产出）：`PlaylistPanelContent(songs, currentTitle, onPick, onClose)`、`PlaylistToggleButton(open, onToggle)`、`PLAYLIST_ATTACHMENT_ID`、`PLAYLIST_ATTACH_Y`、`PLAYLIST_ATTACH_Z`
- Produces：`HomeVolume` 内新增的 `playlistOpen: Boolean`（Compose 状态）、`playlistEntity: Array<Entity?>`（`remember` 持有，供 Task 3 使用）、`playlistTilted: BooleanArray`（同上，供 Task 3 使用）；`PlaybackDockContent` 新签名新增 `playlistOpen: Boolean` 与 `onTogglePlaylist: () -> Unit` 两个参数，移除 `songs`/`onPickSong` 两个参数

- [x] **Step 1: 在 `HomeVolume` 里新增 `playlistOpen` 状态**

在第 322 行 `val lastSyncedTrack = remember { arrayOfNulls<SongItem>(1) }` 之后插入：

```kotlin
    // 播放列表面板的开关状态（v5.5）。由切歌键的 PlaylistToggleButton 翻转，
    // 独立 AttachmentPanel 的内容据此在空 Box 与真实列表之间切换。
    var playlistOpen by remember { mutableStateOf(false) }
```

- [x] **Step 2: 新增播放列表实体与"是否已完成倾斜"标志**

在第 348 行 `val dockTilted = remember { BooleanArray(1) }` 之后插入：

```kotlin
    // 播放列表面板的附件实体，以及"是否已完成倾斜"的一次性标志（同 dockEntity/dockTilted 模式）。
    val playlistEntity = remember { arrayOfNulls<Entity>(1) }
    val playlistTilted = remember { BooleanArray(1) }
```

- [x] **Step 3: 在 `initial` 里创建播放列表实体**

现有代码（第 358-366 行）：

```kotlin
                // 控制面板作为 3D 场景里的附件实体，位置用米指定，稳稳落在唱机前方。
                attachments.entity(id = DOCK_ATTACHMENT_ID)?.apply {
                    // 这里**只设位置、先不旋转**：包围盒要在未旋转状态下量才是真实高度。
                    // 倾斜与绕底边的位移补偿在帧循环里一次性完成（见 dockTilted）。
                    components[TransformComponent::class.java]
                        ?.setPosition(Vector3(0f, DOCK_ATTACH_Y, DOCK_ATTACH_Z))
                    content.addEntity(this)
                    dockEntity[0] = this
                }
            },
```

改为（新增播放列表实体的创建，紧跟在 dock 实体设置之后、`initial` lambda 结束 `}` 之前）：

```kotlin
                // 控制面板作为 3D 场景里的附件实体，位置用米指定，稳稳落在唱机前方。
                attachments.entity(id = DOCK_ATTACHMENT_ID)?.apply {
                    // 这里**只设位置、先不旋转**：包围盒要在未旋转状态下量才是真实高度。
                    // 倾斜与绕底边的位移补偿在帧循环里一次性完成（见 dockTilted）。
                    components[TransformComponent::class.java]
                        ?.setPosition(Vector3(0f, DOCK_ATTACH_Y, DOCK_ATTACH_Z))
                    content.addEntity(this)
                    dockEntity[0] = this
                }

                // 播放列表面板同理，浮在 dock 前方（v5.5）。同样只设位置、不旋转，
                // 倾斜修正在帧循环里做，且要等面板真正显示内容才测量（见 Task 3）。
                attachments.entity(id = PLAYLIST_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]
                        ?.setPosition(Vector3(0f, PLAYLIST_ATTACH_Y, PLAYLIST_ATTACH_Z))
                    content.addEntity(this)
                    playlistEntity[0] = this
                }
            },
```

- [x] **Step 4: 更新 `attachments = { ... }` 块——改造 dock 的调用参数并新增播放列表 AttachmentPanel**

现有代码（第 368-390 行）：

```kotlin
            attachments = {
                AttachmentPanel(id = DOCK_ATTACHMENT_ID) {
                    PlaybackDockContent(
                        isPlaying = uiPlaying,
                        onTogglePlay = {
                            uiPlaying = !uiPlaying
                            audioSession.setPlaying(uiPlaying)
                        },
                        title = currentTitle,
                        artist = currentArtist,
                        progress = currentProgress,
                        songs = songs,
                        onPickSong = { song ->
                            currentTitle = song.title
                            currentArtist = song.artist
                            uiPlaying = true
                            // 走 playSong 而不是 playUri，currentTrack 才会一起更新——
                            // 自动切歌与手动选歌共用同一条记账与状态更新路径。
                            audioSession.playSong(song)
                        }
                    )
                }
            }
```

改为：

```kotlin
            attachments = {
                AttachmentPanel(id = DOCK_ATTACHMENT_ID) {
                    PlaybackDockContent(
                        isPlaying = uiPlaying,
                        onTogglePlay = {
                            uiPlaying = !uiPlaying
                            audioSession.setPlaying(uiPlaying)
                        },
                        title = currentTitle,
                        artist = currentArtist,
                        progress = currentProgress,
                        playlistOpen = playlistOpen,
                        onTogglePlaylist = { playlistOpen = !playlistOpen },
                    )
                }
                // 独立于 dock 的播放列表面板（v5.5）。关闭态渲染零尺寸空 Box——不引入运行时
                // 实体增删，和 dock 本身"内容随状态重组"是同一个已验证模式。
                AttachmentPanel(id = PLAYLIST_ATTACHMENT_ID) {
                    if (playlistOpen) {
                        PlaylistPanelContent(
                            songs = songs,
                            currentTitle = currentTitle,
                            onPick = { song ->
                                currentTitle = song.title
                                currentArtist = song.artist
                                uiPlaying = true
                                // 走 playSong 而不是 playUri，currentTrack 才会一起更新——
                                // 自动切歌与手动选歌共用同一条记账与状态更新路径。
                                audioSession.playSong(song)
                            },
                            onClose = { playlistOpen = false },
                        )
                    } else {
                        Box(Modifier.size(0.dp))
                    }
                }
            }
```

- [x] **Step 5: 修改 `PlaybackDockContent` 签名与函数体**

现有签名与内部状态（约第 1245-1261 行）：

```kotlin
@Composable
private fun PlaybackDockContent(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    title: String,
    artist: String,
    progress: Float,
    songs: List<SongItem>,
    onPickSong: (SongItem) -> Unit,
) {
    var songMenuOpen by remember { mutableStateOf(false) }
    var activeEffect by remember { mutableStateOf(EffectKind.Spectrum) }
    var menuOpen by remember { mutableStateOf(false) }
```

改为：

```kotlin
@Composable
private fun PlaybackDockContent(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    title: String,
    artist: String,
    progress: Float,
    playlistOpen: Boolean,
    onTogglePlaylist: () -> Unit,
) {
    var activeEffect by remember { mutableStateOf(EffectKind.Spectrum) }
    var menuOpen by remember { mutableStateOf(false) }
```

现有调用（约第 1301-1311 行）：

```kotlin
            // 切歌 → 弹出应用内歌单（v5.0）。
            // 用与特效菜单同一套 SpatialUI `Menu`/`MenuItem`——那套组件的弹出与重组都已上机验证过，
            // 复用它比新搭一个浮层风险低得多。
            SongMenuButton(
                open = songMenuOpen,
                songs = songs,
                currentTitle = title,
                onToggle = { songMenuOpen = !songMenuOpen },
                onPick = { songMenuOpen = false; onPickSong(it) },
                onDismiss = { songMenuOpen = false },
            )
```

改为：

```kotlin
            // 切歌键 → 打开独立的播放列表 AttachmentPanel（v5.5，取代原锚定 Menu 下拉，
            // 见 docs/superpowers/specs/2026-08-20-playlist-panel-design.md）。
            PlaylistToggleButton(
                open = playlistOpen,
                onToggle = onTogglePlaylist,
            )
```

- [x] **Step 6: 删除整个 `SongMenuButton` 函数**

删除现有的 `SongMenuButton` 函数定义（从 `@Composable` / `private fun SongMenuButton(` 开始，到该函数结尾的 `}` 为止，即原第 1385-1467 行一整段）。`EffectMenuButton` 及其用到的 `Menu`/`MenuItem` 不受影响，原样保留。

- [x] **Step 7: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`，无未解析引用（`SongMenuButton` 已无调用方且已删除；`Menu`/`MenuItem` 的 import 仍被 `EffectMenuButton` 和新的 `PlaylistPanelContent` 使用，不会报 unused-import）。

- [x] **Step 8: Commit**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt
git commit -m "feat: 切歌键改为打开独立播放列表 AttachmentPanel，移除锚定 Menu 下拉"
```

---

### Task 3: 播放列表面板的倾斜/位置一次性修正

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`（帧循环 `LaunchedEffect(Unit) { while(true) { withFrameNanos { ... } } }` 内部）

**Interfaces:**
- Consumes（Task 2 产出）：`playlistOpen: Boolean`、`playlistEntity: Array<Entity?>`、`playlistTilted: BooleanArray`、`PLAYLIST_ATTACH_Y`/`PLAYLIST_ATTACH_Z`/`PLAYLIST_TILT_DEG`/`PLAYLIST_HALF_HEIGHT_FALLBACK`（Task 1 常量）
- Produces：无新增公共接口，纯内部行为

- [x] **Step 1: 在 dock 的倾斜修正块之后插入播放列表的修正块**

现有代码（约第 452-475 行）：

```kotlin
                // 面板倾斜：一次性完成。放在帧循环而不是 initial，因为 initial 阶段面板可能还没
                // 完成布局、包围盒会是 0。量到有效高度后才施加旋转与绕底边的位移补偿。
                if (!dockTilted[0]) {
                    dockEntity[0]?.let { dock ->
                        val b = dock.getVisualBounds(relativeTo = null, recursive = true)
                        val measured = b.size.y / 2f
                        val h = if (measured > 1e-3f) measured else DOCK_HALF_HEIGHT_FALLBACK
                        val rad = DOCK_TILT_DEG * (PI.toFloat() / 180f)
                        // 绕底边旋转 = 绕中心旋转 + 反向补偿底边的位移
                        val cy = DOCK_ATTACH_Y - h * (1f - cos(rad))
                        val cz = DOCK_ATTACH_Z + h * sin(rad)
                        dock.components[TransformComponent::class.java]?.apply {
                            setEulerAngles(EulerAngles(DOCK_TILT_DEG, 0f, 0f))
                            setPosition(Vector3(0f, cy, cz))
                        }
                        dockTilted[0] = true
                        android.util.Log.w(
                            "SpaceVinyl",
                            "dock tilted ${DOCK_TILT_DEG}deg: bounds=${b.size} halfH=$h " +
                                "(measured=${measured > 1e-3f}) center=(0, $cy, $cz) " +
                                "was=(0, $DOCK_ATTACH_Y, $DOCK_ATTACH_Z)"
                        )
                    }
                }
```

在这段之后（`val dt = ...` 之前）插入：

```kotlin

                // 播放列表面板的倾斜修正：与 dock 同一套一次性修正模式（见上），唯一差异是
                // 关闭态内容是零尺寸空 Box，量出来的包围盒没有意义——必须等 playlistOpen 为真、
                // 面板已经在渲染真实列表内容时才做这次测量与修正。
                if (playlistOpen && !playlistTilted[0]) {
                    playlistEntity[0]?.let { panel ->
                        val b = panel.getVisualBounds(relativeTo = null, recursive = true)
                        val measured = b.size.y / 2f
                        val h = if (measured > 1e-3f) measured else PLAYLIST_HALF_HEIGHT_FALLBACK
                        val rad = PLAYLIST_TILT_DEG * (PI.toFloat() / 180f)
                        val cy = PLAYLIST_ATTACH_Y - h * (1f - cos(rad))
                        val cz = PLAYLIST_ATTACH_Z + h * sin(rad)
                        panel.components[TransformComponent::class.java]?.apply {
                            setEulerAngles(EulerAngles(PLAYLIST_TILT_DEG, 0f, 0f))
                            setPosition(Vector3(0f, cy, cz))
                        }
                        playlistTilted[0] = true
                        android.util.Log.w(
                            "SpaceVinyl",
                            "playlist tilted ${PLAYLIST_TILT_DEG}deg: bounds=${b.size} halfH=$h " +
                                "(measured=${measured > 1e-3f}) center=(0, $cy, $cz) " +
                                "was=(0, $PLAYLIST_ATTACH_Y, $PLAYLIST_ATTACH_Z)"
                        )
                    }
                }
```

- [x] **Step 2: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt
git commit -m "feat: 播放列表面板的倾斜/位置一次性修正"
```

---

### Task 4: 更新设计契约与 AGENTS.md

**Files:**
- Modify: `.spatialsdk/design-contract.md`
- Modify: `AGENTS.md`

**Interfaces:** 无代码接口，纯文档。

- [x] **Step 1: 升版本号**

`.spatialsdk/design-contract.md` 第 1 行：

```markdown
# 设计契约 — SpaceVinyl v5.4
```

改为：

```markdown
# 设计契约 — SpaceVinyl v5.5
```

第 9 行：

```markdown
- 契约版本：v5.4
```

改为：

```markdown
- 契约版本：v5.5
```

第 10 行末尾（"…v5.2 / v5.3 2026-08-19；v5.4 2026-08-19"）之后追加：

```markdown
；v5.5 2026-08-20
```

- [x] **Step 2: 在 "### v5.4 变更摘要" 小节之前插入新的 v5.5 小节**

```markdown
### v5.5 变更摘要（2026-08-20）—— 切歌键改为打开独立播放列表面板

用户需求：「点击下一首时不再弹窗菜单选项，而是在容器窗口前弹出播放列表，选中后立即播放，
点击播放列表上的 X 关闭列表页面」。设计过程见
`docs/superpowers/specs/2026-08-20-playlist-panel-design.md`。

#### 5.5a 已确认决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 面板实现方式 | 新建独立 `AttachmentPanel` 实体，真机调位 | 用户在得知需要多轮真机调位成本后，仍选择这条路线而非复用 `Menu` 组件的低成本方案（见下方"已否决替代方案"） |
| 关闭交互 | 仅 X 关闭 | 面板不是 Popup，没有"外部点击"概念，天然满足 |
| 选中曲目后是否自动关闭面板 | **不自动关闭** | 用户要连续试听/切换多首时更方便 |
| 触发按钮 | 保持现状（`ic_dock_next`，面板既有位置） | 改动最小 |
| 面板宽度/行高 | 维持现有紧凑尺寸（228dp / 36dp） | 复用现有常量，改动最小 |

#### 5.5b 已否决的替代方案：复用 `Menu` 组件

排查 `com.pico.spatial.ui.design.menu.Menu` 源码确认：`Menu` 底层是 `SpatialDialogAsPopup`，
本质是开一个真正独立的空间嵌套窗口，已在真机验证过可用（见本文档历史版本记录）。理论上只需要
`Menu(..., properties = PopupProperties(dismissOnClickOutside = false))` + 标题/X + 去掉自动关闭，
即可零新增 3D 实体、零真机调位成本满足全部需求。**该方案已呈报用户，用户明确选择不采用**，
理由是希望播放列表是像 `dock` 一样"真正在 3D 空间里、可控前后关系"的独立面板。这是一次有意识
的取舍——如果后续真机调位成本超出预期，这条路线是现成的回退方案。

#### 5.5c 播放列表面板位置（首版猜测值，未经真机验证）

| 项 | 值 | 依据 |
|---|---|---|
| X | 0 | 与 `dock`、唱机同一水平中心 |
| Y | -0.20m | 比 `dock`（-0.33）高：面板更靠近视点、更竖直，透视不会把它显得很低 |
| Z | +0.46m | 比 `dock`（+0.38）更靠前 0.08m。+0.38 是已知唯一安全点（v4.7 实测），+0.46 是全新区域 |
| 倾斜角 | -25° | 比 `dock` 的 -60° 更竖直——这是"读列表"面板，不是"俯视操作"的控制面板 |

`VOLUME_SAFE_HALF_XZ`（0.33）不适用于此处（硬规则 #6：该边界只对 ECS 网格实体有效）。
**如果 +0.46 被裁剪**：回退策略是逐步减小 Z（如 0.42 → 0.40），每次退让 0.02–0.04m，直到真机
可见，并把最终生效值和这次探测过程记入本契约的下一个版本号。

#### 5.5d 验证状态

代码编译通过、静态走查点击路径正确（切歌键→`playlistOpen`→面板内容→选歌→`onPickSong`→X→
`onClose`）。**面板可见性/是否被裁剪/朝向/点击交互均需真机确认**——`pico-cli` 无点击类命令，
agent 侧无法自行触发，结果以真机会话记录为准（同 v5.4 的验证方法论）。
```

- [x] **Step 3: 更新 `AGENTS.md` 的面板描述段落**

`AGENTS.md` 里"当前实现了什么"一节，现有段落（`dock` 描述的最后几句）：

```markdown
  切歌弹出**应用内 SpatialUI 歌单**（MediaStore 查询，需 `READ_MEDIA_AUDIO`）。
  歌单 228×36dp/项（v5.1 缩到 0.6），字号 `bodySmall`+`bodyTiny`，**向下弹出**。配色取自唱机贴图实测，强调色黄铜
  `#E4A144`。尺寸与配色的唯一依据是 `.spatialsdk/design-contract.md §3`。
```

改为：

```markdown
  切歌键打开**独立的播放列表 `AttachmentPanel`**（v5.5，与 `dock` 分离的第二个 3D 附件实体，
  浮在 dock 前方；MediaStore 查询，需 `READ_MEDIA_AUDIO`）。歌单项 228×36dp（v5.1 缩到 0.6），
  字号 `bodySmall`+`bodyTiny`；面板顶部有标题+X 关闭按钮，**仅点 X 关闭**，选歌立即播放但面板
  不自动收起。配色取自唱机贴图实测，强调色黄铜 `#E4A144`。位置/倾斜/尺寸的唯一依据是
  `.spatialsdk/design-contract.md §3` 与 v5.5 小节。
```

- [x] **Step 4: Commit**

```bash
git add .spatialsdk/design-contract.md AGENTS.md
git commit -m "docs: 设计契约升 v5.5 —— 播放列表改为独立 AttachmentPanel"
```

---

### Task 5: 真机构建、安装与验证

**Files:**
- Modify: `.spatialsdk/ui-verification-log.md`（追加本轮验证记录）

**Interfaces:** 无代码接口。

- [x] **Step 1: 构建 debug APK**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`，产物在 `app/build/outputs/apk/debug/app-debug.apk`。

- [x] **Step 2: 安装并启动**

按项目既有门禁流程抢锁后安装启动（参考 `spatial-emulator-usage`/`pico-cli` 技能选择当前可用设备的
具体安装/启动命令；若走原始 adb，等价命令为）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tech.illusion.spacevinyl/tech.illusion.spacevinyl.platform.LaunchActivity
```

- [x] **Step 3: 收集 logcat 证据**

```bash
adb logcat -d | grep -E "SpaceVinyl|AndroidRuntime"
```

预期：能看到 `playlist tilted -25.0deg: bounds=... halfH=... center=...` 这一行（证明面板第一次
打开时倾斜修正跑过），且没有 `AndroidRuntime` 致命异常。

- [x] **Step 4: 真机截图确认外观**

请用户在真机上打开应用、点击切歌键，确认：

- 播放列表面板可见，没有被容器前边界裁掉（如被裁，按 design-contract.md v5.5c 的回退步长调整
  `PLAYLIST_ATTACH_Z` 后回到 Task 3 重新构建验证）
- 面板朝向合理、文字可读
- 面板视觉上确实浮在 `dock` 前方（比 `dock` 更靠近视点）

- [x] **Step 5: 真机交互确认**

请用户在真机上确认（`pico-cli` 无点击类命令，agent 侧无法自行触发）：

- 点击切歌键能打开/关闭播放列表面板
- 点击列表里的曲目能立即播放，面板**不会**自己关掉
- 点击面板右上角的 X 能关闭面板
- 点击面板以外的区域（如唱机模型、`dock` 面板）**不会**关闭播放列表面板

- [x] **Step 6: 记录验证结果**

把 Step 3-5 的实际结果（包括任何 Z 值回退调整）追加到 `.spatialsdk/ui-verification-log.md`，
格式参照该文件里既有轮次的记录方式（有什么证据说什么话，未验证的项如实标注"未验证"）。

- [x] **Step 7: Commit**

```bash
git add .spatialsdk/ui-verification-log.md
git commit -m "docs: 记录播放列表面板真机验证结果"
```

（若 Step 4/6 发现需要调整 `PLAYLIST_ATTACH_Z`/`PLAYLIST_ATTACH_Y`/`PLAYLIST_TILT_DEG`，连同
`.spatialsdk/design-contract.md` 的 v5.5c 表格一起改，作为本任务的追加 commit，不新开任务。）
