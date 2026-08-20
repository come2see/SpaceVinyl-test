# 播完自动随机切下一首 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一首歌自然播完后，自动从本地歌单里随机挑一首本轮未播过的曲目继续播放；整轮放完即停止。

**Architecture:** 把「随机去重」的核心逻辑抽成不依赖 Android 的纯函数 `pickUnplayed`，用 JVM 单元测试覆盖；`ShuffleQueue` 是它之上的加锁薄壳，持有歌单快照与已播放集合供音频线程安全访问；`AudioSession` 通过 `OnCompletionListener` 驱动自动切歌，UI 侧沿用既有的帧循环轮询把状态拉回 Compose。

**Tech Stack:** Kotlin, Jetpack Compose, PICO Spatial SDK (SpatialUI), `android.media.MediaPlayer`, JUnit 4.13.2

## Global Constraints

- 所有 2D UI 必须用 SpatialUI（`com.pico.spatial.ui.*`）包在 `PicoTheme` 内；禁止 Material/Material3。
- 不新增任何第三方依赖（不引入 Robolectric、mockk 等）。
- 面板尺寸常量一律不动：`DOCK_W = 512.dp`、`DOCK_H = 104.dp` 及 `DOCK_WIDTH_BREAKDOWN` 保持原值。本功能不加任何 UI 控件。
- 不加随机/顺序开关；已播放集合不持久化；不做「上一首」历史栈。
- 已播放集合的 key 用 `SongItem.uri`（MediaStore 唯一标识），**不用** `title`。
- 记账时机：**开始播放时**记账（在 `playUri` 内），不是播完时记账。
- 持有 Context 必须用 `applicationContext`，禁止持有 Activity Context。
- 唯一需要加锁的地方是 `ShuffleQueue` 的内部状态；频谱数据沿用既有的无锁取舍，不要改。
- 每个 task 结束时 `./gradlew :app:assembleDebug` 必须通过。

---

### Task 1: `pickUnplayed` 纯函数与单元测试

把随机去重逻辑放进一个不依赖任何 Android 类型的泛型函数，这样它能用真正的 JVM 单元测试覆盖。`Uri`、`MediaPlayer` 在 JVM 单测里不可用，所以核心逻辑必须先脱离它们。

**Files:**
- Create: `app/src/main/java/tech/illusion/spacevinyl/content/ShufflePlaylist.kt`
- Test: `app/src/test/java/tech/illusion/spacevinyl/content/PickUnplayedTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `internal fun <T> pickUnplayed(all: List<T>, played: Set<T>, random: Random): T?` — 从 `all` 中随机返回一个不在 `played` 里的元素；无候选时返回 `null`。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacevinyl/content/PickUnplayedTest.kt`：

```kotlin
package tech.illusion.spacevinyl.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PickUnplayedTest {

    @Test
    fun `picks the only unplayed item`() {
        assertEquals("c", pickUnplayed(listOf("a", "b", "c"), setOf("a", "b"), Random(0)))
    }

    @Test
    fun `returns null when every item is played`() {
        assertNull(pickUnplayed(listOf("a", "b"), setOf("a", "b"), Random(0)))
    }

    @Test
    fun `returns null for an empty list`() {
        assertNull(pickUnplayed(emptyList<String>(), emptySet(), Random(0)))
    }

    @Test
    fun `ignores played entries that are absent from the list`() {
        // 歌单被重查后，已播集合里可能残留已不在歌单中的曲目，不能因此少给候选
        assertEquals("a", pickUnplayed(listOf("a"), setOf("zzz"), Random(0)))
    }

    @Test
    fun `never returns a played item across many draws`() {
        val all = listOf("a", "b", "c", "d")
        val played = setOf("b", "d")
        repeat(200) { seed ->
            val picked = pickUnplayed(all, played, Random(seed))
            assertTrue("picked=$picked", picked == "a" || picked == "c")
        }
    }

    @Test
    fun `eventually returns every unplayed item`() {
        // 防止被实现成「总是返回第一个未播的」——那样就不是随机播放了
        val all = listOf("a", "b", "c")
        val seen = mutableSetOf<String>()
        repeat(200) { seed -> pickUnplayed(all, emptySet(), Random(seed))?.let { seen += it } }
        assertEquals(setOf("a", "b", "c"), seen)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacevinyl.content.PickUnplayedTest"`
Expected: 编译失败，`Unresolved reference: pickUnplayed`

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/java/tech/illusion/spacevinyl/content/ShufflePlaylist.kt`：

```kotlin
package tech.illusion.spacevinyl.content

import kotlin.random.Random

/**
 * 随机播放列表 —— 「随机挑一首没播过的」这件事的全部逻辑。
 *
 * 刻意与 `HomeVolume.kt` 分开：那个文件里全是 Compose 与 Spatial SDK 的东西，
 * 依赖 `Uri`/`MediaPlayer` 等平台类型，在 JVM 单元测试里跑不起来。
 * 把纯逻辑放在这里，核心的随机去重才能被真正测到（见 `PickUnplayedTest`）。
 */

/**
 * 从 [all] 里随机返回一个不在 [played] 中的元素；全部已播（或 [all] 为空）时返回 null。
 *
 * 泛型且只依赖 [Random]，不碰任何 Android 类型——这是它可被 JVM 单测覆盖的前提。
 * [random] 由调用方注入，测试才能用固定种子断言行为。
 *
 * [played] 里允许存在不属于 [all] 的残留项（歌单重查后可能出现），按交集处理即可。
 */
internal fun <T> pickUnplayed(all: List<T>, played: Set<T>, random: Random): T? {
    val candidates = all.filter { it !in played }
    if (candidates.isEmpty()) return null
    return candidates[random.nextInt(candidates.size)]
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacevinyl.content.PickUnplayedTest"`
Expected: 6 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/ShufflePlaylist.kt \
        app/src/test/java/tech/illusion/spacevinyl/content/PickUnplayedTest.kt
git commit -m "feat: 新增 pickUnplayed 随机去重纯函数与单元测试"
```

---

### Task 2: `ShuffleQueue` 与歌单数据迁移

把 `SongItem`/`querySongs` 移入 `ShufflePlaylist.kt`（它们本就属于「播放列表」这一职责），并加上 `ShuffleQueue`——供音频线程安全访问的加锁薄壳。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/ShufflePlaylist.kt`
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`（删除 `SongItem` 与 `querySongs`，约 45 行）

**Interfaces:**
- Consumes: `pickUnplayed(all, played, random)`（Task 1）
- Produces:
  - `internal data class SongItem(val uri: Uri, val title: String, val artist: String)`
  - `internal fun querySongs(context: Context): List<SongItem>`
  - `internal class ShuffleQueue` 带 `fun setTracks(list: List<SongItem>)`、`fun markPlayed(uri: Uri)`、`fun nextRandom(): SongItem?`、`fun reset()`

- [ ] **Step 1: 把 `SongItem` 与 `querySongs` 从 HomeVolume.kt 剪切到 ShufflePlaylist.kt**

从 `HomeVolume.kt` 删除 `private data class SongItem(...)` 整行、以及它上方的 KDoc 注释块和整个 `private fun querySongs(context: Context): List<SongItem> { ... }` 函数（含其 KDoc）。

把它们粘贴到 `ShufflePlaylist.kt` 的 `pickUnplayed` 之前，并把两处 `private` 改为 `internal`：

```kotlin
/** 歌单里的一首歌。`uri` 是 MediaStore 的 content URI，`MediaPlayer` 可直接播。 */
internal data class SongItem(val uri: Uri, val title: String, val artist: String)

/**
 * 查 MediaStore 里可播放的音乐。
 *
 * 只取 `IS_MUSIC != 0` 的条目——设备上还索引着一堆语音片段/提示音，混进歌单没意义。
 * 需要 `READ_MEDIA_AUDIO`（API 33+）；没授权时 query 会抛异常或返回空，这里统一吃掉并返回空列表，
 * 由调用方显示"没有可播放的音乐"。
 */
internal fun querySongs(context: Context): List<SongItem> {
    val out = mutableListOf<SongItem>()
    try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += SongItem(
                    uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()
                    ),
                    title = c.getString(titleCol) ?: "未命名",
                    artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "未知艺术家",
                )
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w("SpaceVinyl", "querySongs failed: ${t.javaClass.simpleName}: ${t.message}")
    }
    android.util.Log.w("SpaceVinyl", "querySongs: ${out.size} tracks")
    return out
}
```

在 `ShufflePlaylist.kt` 顶部补齐 import：

```kotlin
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlin.random.Random
```

同时从 `HomeVolume.kt` 删掉这一行——`MediaStore` 只被刚移走的 `querySongs` 用到，留着会是未使用 import：

```kotlin
import android.provider.MediaStore
```

`import android.net.Uri` **要保留**：`playUri(context: Context, uri: Uri)` 的签名仍需要它。

- [ ] **Step 2: 编译确认迁移没破坏引用**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（`HomeVolume.kt` 里对 `SongItem`/`querySongs` 的调用同包可见，无需 import）

- [ ] **Step 3: 加 `ShuffleQueue`**

追加到 `ShufflePlaylist.kt` 末尾：

```kotlin
/**
 * 随机播放队列：持有歌单快照与本轮已播放集合，回答「下一首是谁」。
 *
 * **为什么必须自己持一份快照**：自动切歌发生在 MediaPlayer 的回调线程（音频 HandlerThread），
 * 而歌单在 UI 侧是 `mutableStateListOf`（Compose 状态，属主线程）。跨线程直接读 Compose
 * 状态列表不安全，所以音频线程必须有一份自己能安全访问的副本。
 *
 * **为什么这里加锁**：本项目对频谱数据（`bandTarget` 等）有意采用不加锁的取舍，理由是
 * 可视化用途、读到上一帧的值不影响正确性。播放列表状态**不适用**那个取舍——它会被主线程
 * （`setTracks`、手动选歌）与音频线程（`onCompletion`）同时访问，抽错会造成重复播放或
 * 提前停止，是用户直接能看到的错误行为。
 */
internal class ShuffleQueue {
    private val lock = Any()
    private var tracks: List<SongItem> = emptyList()
    private val played = mutableSetOf<Uri>()

    /** 歌单刷新时同步快照。已播放集合按 uri 继续有效，不清空。 */
    fun setTracks(list: List<SongItem>) = synchronized(lock) {
        tracks = list.toList()
    }

    /** 记一首为「本轮已播」。由 `playUri` 统一调用（开始播放即记账）。 */
    fun markPlayed(uri: Uri) = synchronized(lock) {
        played += uri
    }

    /** 随机挑一首本轮未播的；本轮已放完（或歌单为空）时返回 null。 */
    fun nextRandom(): SongItem? = synchronized(lock) {
        pickUnplayed(tracks, tracks.filter { it.uri in played }.toSet(), Random.Default)
    }

    /** 清空已播放集合，开始新一轮。 */
    fun reset() = synchronized(lock) {
        played.clear()
    }
}
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 确认 Task 1 的测试仍然通过**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacevinyl.content.PickUnplayedTest"`
Expected: 6 个测试全部 PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/ShufflePlaylist.kt \
        app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt
git commit -m "refactor: 歌单数据迁入 ShufflePlaylist.kt 并新增 ShuffleQueue"
```

---

### Task 3: 音频层自动切歌

拆掉单曲循环，挂上 completion 回调驱动自动切歌。本 task 完成后**已能听到自动换歌**，只是面板标题还不会更新（Task 4 补）。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`（`AudioSession` 类、`startAudioPipeline`）

**Interfaces:**
- Consumes: `ShuffleQueue`（Task 2）
- Produces: `AudioSession` 新增 `val shuffle: ShuffleQueue`、`@Volatile var currentTrack: SongItem?`、`@Volatile var appContext: Context?`、`fun playSong(song: SongItem)`

- [ ] **Step 1: 给 `AudioSession` 加字段**

在 `AudioSession` 类里 `playbackUnavailable` 声明之后插入：

```kotlin
    /** 本次会话的随机播放队列。由播放器自己拥有——它就是这个播放器的播放队列。 */
    val shuffle = ShuffleQueue()

    /**
     * 当前曲目。由音频线程写入（`playUri`）、帧循环（主线程）轮询后同步到 Compose 状态，
     * 与 `progress` 走同一条「从音频层拉状态到 UI」的既有通道，故 `@Volatile`。
     */
    @Volatile var currentTrack: SongItem? = null

    /**
     * 自动切歌时 `setDataSource(context, uri)` 需要 Context，但那发生在音频回调线程里、
     * 拿不到 composition 的 context。**必须存 applicationContext**——存 Activity Context
     * 会随这个长生命周期对象一起泄漏整个 Activity。
     */
    @Volatile var appContext: Context? = null
```

- [ ] **Step 2: `playUri` 改为记账 + 关闭单曲循环**

把 `playUri` 里的 `mp.isLooping = true` 改为 `false`，并在方法开头记账。改动后 `playUri` 的开头与 `isLooping` 一行如下：

```kotlin
    fun playUri(context: Context, uri: Uri) {
        wantPlaying = true
        // 开始播放即记账（不是播完才记账）：否则「中途被手动切走、没听完」的曲目永远不入账，
        // 之后仍会被随机抽到，与「排除已播放」的意图冲突。
        shuffle.markPlayed(uri)
        playbackUnavailable = false
```

```kotlin
                // isLooping 必须为 false：单曲循环下 OnCompletionListener 永远不触发，
                // 自动切下一首就无从实现。
                mp.isLooping = false
```

- [ ] **Step 3: 在 `playUri` 里挂 completion 回调**

在 `playUri` 的 `mp.setOnPreparedListener { ... }` 之后、`mp.prepareAsync()` 之前插入：

```kotlin
                mp.setOnCompletionListener { onTrackCompleted() }
```

- [ ] **Step 4: 加 `playSong` 与 `onTrackCompleted`**

在 `AudioSession` 的 `playUri` 方法之后插入：

```kotlin
    /**
     * 播放一首歌，并记住它是「当前曲目」供 UI 同步。
     * 手动选歌与自动切歌都走这里，`currentTrack` 才不会漏更新。
     */
    fun playSong(song: SongItem) {
        currentTrack = song
        // 不静默 return：这个文件刚因为「静默 return 导致点歌没反应且 logcat 无痕」
        // 排查过一轮，同样的坑不留第二个。
        val ctx = appContext ?: run {
            playbackUnavailable = true
            android.util.Log.w("SpaceVinyl", "playSong skipped: appContext not set yet")
            return
        }
        playUri(ctx, song.uri)
    }

    /**
     * 一首歌自然播完。在音频线程被回调。
     *
     * 当前曲目已在 `playUri` 时入账，所以不会被 `nextRandom()` 再抽到。
     * 抽不到下一首 = 本轮放完：停止播放，并**清空已播放集合**——「停」是一轮结束的
     * 一次性信号，不是永久关闭自动连播。若不清空，用户之后手动选任何一首歌，
     * 放完都会立刻再次停止，功能自锁。
     */
    private fun onTrackCompleted() {
        val next = shuffle.nextRandom()
        if (next != null) {
            android.util.Log.w("SpaceVinyl", "autoNext: ${next.title}")
            playSong(next)
        } else {
            // wantPlaying 也要置 false：否则切后台再回前台，ON_RESUME 会把
            // wasPlayingBeforeBackground 当真而误续播。帧循环也靠它把 uiPlaying 拉回 false。
            wantPlaying = false
            isPlayingNow = false
            shuffle.reset()
            android.util.Log.w("SpaceVinyl", "shuffle round finished — stopping, played set cleared")
        }
    }
```

- [ ] **Step 5: 在 `startAudioPipeline` 里存 applicationContext**

`startAudioPipeline` 当前签名为 `(bandTarget: FloatArray, session: AudioSession)`，没有 context 参数。改为接收 context 并存下 applicationContext。

把函数签名改为：

```kotlin
private fun startAudioPipeline(
    context: Context,
    bandTarget: FloatArray,
    session: AudioSession,
) {
    session.appContext = context.applicationContext
    try {
```

并把调用点（`SpatialView` 的 `initial` 内）改为：

```kotlin
                startAudioPipeline(context, bandTarget, audioSession)
```

- [ ] **Step 6: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt
git commit -m "feat: 播完自动随机切下一首，整轮放完即停"
```

---

### Task 4: UI 状态同步

把音频层的「当前曲目」和「已停止」同步回面板。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`（歌单查询 `LaunchedEffect`、`onPickSong`、帧循环）

**Interfaces:**
- Consumes: `audioSession.shuffle.setTracks(list)`、`audioSession.currentTrack`、`audioSession.wantPlaying`、`audioSession.playSong(song)`（Task 2、3）
- Produces: 无（终端 task）

- [ ] **Step 1: 歌单查完后同步快照给 ShuffleQueue**

把歌单查询的 `LaunchedEffect(permissionsGranted)` 改为：

```kotlin
    LaunchedEffect(permissionsGranted) {
        val loaded = withContext(Dispatchers.IO) { querySongs(context) }
        songs.clear()
        songs.addAll(loaded)
        // 音频线程自动切歌时读的是这份快照，不是上面的 Compose 列表。
        audioSession.shuffle.setTracks(loaded)
    }
```

- [ ] **Step 2: `onPickSong` 改走 `playSong`**

把 `attachments` 里的 `onPickSong` 改为：

```kotlin
                        onPickSong = { song ->
                            currentTitle = song.title
                            currentArtist = song.artist
                            uiPlaying = true
                            // 走 playSong 而不是 playUri，currentTrack 才会一起更新——
                            // 自动切歌与手动选歌共用同一条记账与状态更新路径。
                            audioSession.playSong(song)
                        }
```

- [ ] **Step 3: 帧循环同步曲目名与停止态**

先在 `HomeVolume` 的状态声明区（`progressAccum` 那一行之后）加一个「上次已同步的曲目」引用槽，
沿用文件里既有的 `remember { arrayOfNulls(...) }` 模式：

```kotlin
    // 上次已同步到 Compose 状态的曲目。用**引用比较**而不是比 title——
    // 同名不同曲（两首歌重名）时比 title 会漏掉 artist 的更新。
    val lastSyncedTrack = remember { arrayOfNulls<SongItem>(1) }
```

再在帧循环里已有的 `playbackUnavailable` 纠正块之后插入：

```kotlin
                // 自动切歌发生在音频线程，曲目名要靠帧循环拉回 Compose 状态。
                // 与下面的 progress 轮询同一个模式；切歌是低频事件，不必节流。
                val track = audioSession.currentTrack
                if (track != null && track !== lastSyncedTrack[0]) {
                    lastSyncedTrack[0] = track
                    currentTitle = track.title
                    currentArtist = track.artist
                }

                // 一轮放完时音频层会把 wantPlaying 置 false，UI 跟着回到「可播放」态。
                // 用 wantPlaying 而不是 isPlayingNow：后者在刚点播放、prepare 还没完成时
                // 仍是 false，拿它同步会把用户刚点亮的播放态立刻打回去。
                // wantPlaying 由 playUri/setPlaying 在调用线程同步写入，是可靠的「意图」信号。
                if (!audioSession.wantPlaying && uiPlaying) {
                    uiPlaying = false
                }
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 确认单元测试仍通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt
git commit -m "feat: 自动切歌的曲目名与停止态同步到面板"
```

---

### Task 5: 真机验证与契约升版

空间容器无法截图（`screencap` 在本设备失败）、体积窗口无法用 `adb input tap` 驱动，所以**自动切歌与停止两处必须由用户实机确认**；agent 侧只提供日志证据，不得声称已验证 UI。

**Files:**
- Modify: `.spatialsdk/design-contract.md`

**Interfaces:**
- Consumes: 前四个 task 的全部产出
- Produces: 无

- [ ] **Step 1: 安装并启动**

```bash
./gradlew :app:assembleDebug
adb -s PB311XKGL4160087B install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PB311XKGL4160087B shell am force-stop tech.illusion.spacevinyl
adb -s PB311XKGL4160087B logcat -c
adb -s PB311XKGL4160087B shell am start -n tech.illusion.spacevinyl/.platform.LaunchActivity
```

Expected: `Success` 且启动无崩溃

- [ ] **Step 2: 起一个后台日志捕获**

```bash
nohup adb -s PB311XKGL4160087B logcat -s SpaceVinyl:W > /tmp/shuffle-verify.log 2>&1 &
```

Expected: 后台进程启动；日志里应先出现 `querySongs: N tracks`

- [ ] **Step 3: 请用户实机操作并确认**

请用户在头显里：选一首歌 → 让它播到自然结束 → 观察是否自动换到另一首 → 反复直到全部放完。

需要用户确认的三点：
1. 一首放完后**自动**换了下一首（不用手动操作）
2. 换到的歌**没有重复**本轮已经放过的
3. 全部放完后**停下来**了（唱盘停转、频谱冻结、播放键回到 ▶）

- [ ] **Step 4: 核对日志证据**

```bash
grep -E "playUri:|autoNext:|round finished" /tmp/shuffle-verify.log
```

Expected:
- 每次自动切歌一条 `autoNext: <曲名>` 紧跟一条 `playUri: content://...`
- 所有 `playUri` 的 uri **互不重复**（这是「排除已播放」生效的直接证据）
- 最后一首放完后出现 `shuffle round finished — stopping, played set cleared`，且此后不再有 `playUri`
- 全程没有 `playUri failed` / `playUri skipped`

若 uri 出现重复或提前 stop，回到 Task 3 检查记账时机，不要在 UI 层打补丁。

- [ ] **Step 5: 收尾并升契约版本**

```bash
pkill -f "logcat -s SpaceVinyl:W"
```

在 `.spatialsdk/design-contract.md` 中把标题与元信息的 `v5.3` 改为 `v5.4`、`用户确认时间` 末尾追加 `；v5.4 2026-08-19`，并在 `### v5.3 变更摘要` 之前插入 v5.4 变更摘要，内容需覆盖：

- 需求与已确认决策（一轮放完即停、不加 UI 开关、不持久化）
- `isLooping = true → false` 是硬前提（单曲循环下 completion 回调永不触发）
- 新增 `ShufflePlaylist.kt`：`pickUnplayed` 纯函数（6 个 JVM 单测）+ `ShuffleQueue`（加锁理由）
- 记账时机为「开始播放」而非「播完」，及其后果（中途切走的歌本轮不再出现）
- 停止时清空已播放集合的理由（否则功能自锁）
- 帧循环用 `wantPlaying` 而非 `isPlayingNow` 同步停止态的理由
- 面板尺寸未变（本功能零 UI 改动）

- [ ] **Step 6: 提交**

```bash
git add .spatialsdk/design-contract.md
git commit -m "docs: 契约升 v5.4 —— 播完自动随机切下一首"
```

---

## Self-Review

**Spec coverage：** spec 各节均有对应 task —— §2 拆 isLooping → Task 3 Step 2；§3 ShuffleQueue 与加锁 → Task 2 Step 3；§3 uri 作 key → Task 2 `played: MutableSet<Uri>`；§4 记账时机 → Task 3 Step 2；§4 切歌流程 → Task 3 Step 4；§5 停止清空 → Task 3 Step 4 的 `shuffle.reset()`；§6 帧循环同步 → Task 4 Step 3；§7 停止表现（`wantPlaying=false`）→ Task 3 Step 4；§8 边界情况 → Task 1 单测覆盖空列表/全已播/残留项，Task 4 Step 1 覆盖歌单重查；§9 验证计划 → Task 5；§10 不做的事 → Global Constraints。

**Placeholder scan：** 无 TBD/TODO；每个代码步骤都给了完整可粘贴的代码；未出现「参照 Task N」式省略。

**Type consistency：** `pickUnplayed(all, played, random)` 在 Task 1 定义、Task 2 `nextRandom()` 调用，签名一致；`SongItem` 在 Task 2 转 internal 后被 Task 3 的 `currentTrack`/`playSong` 与 Task 4 使用；`playSong(song: SongItem)` 在 Task 3 定义、Task 4 Step 2 调用；`shuffle.setTracks(list)` 在 Task 2 定义、Task 4 Step 1 调用；`markPlayed(uri: Uri)` 在 Task 2 定义、Task 3 Step 2 调用；`startAudioPipeline(context, bandTarget, session)` 在 Task 3 Step 5 同时改签名与调用点。

**Self-review 中修正的三处：** ①`playSong` 的 null-context 分支原为静默 `return`，改为置 `playbackUnavailable` 并打日志——本文件刚因同类静默 return 排查过一轮；②帧循环同步曲目改用引用比较 + `lastSyncedTrack` 槽，原先比 `title` 会在同名不同曲时漏更新 artist；③补上 `HomeVolume.kt` 中 `MediaStore` import 的删除（`Uri` 保留）。

**已知的验证边界：** `ShuffleQueue` 本身没有单元测试——它依赖 `Uri`（Android framework 类，JVM 单测中不可用），而项目约束不允许引入 Robolectric。核心的随机去重逻辑已由 Task 1 的 6 个单测覆盖，`ShuffleQueue` 剩下的只是加锁与持快照的薄壳，靠 Task 5 的真机日志（uri 不重复 + 一轮后停止）作集成验证。这是有意的取舍，不是遗漏。
