package tech.illusion.spacevinyl.content

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlin.random.Random

/**
 * 随机播放列表 —— 「随机挑一首没播过的」这件事的全部逻辑。
 *
 * 刻意与 `HomeVolume.kt` 分开：那个文件里全是 Compose 与 Spatial SDK 的东西，
 * 依赖 `Uri`/`MediaPlayer` 等平台类型，在 JVM 单元测试里跑不起来。
 * 把纯逻辑放在这里，核心的随机去重才能被真正测到（见 `PickUnplayedTest`）。
 */

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
