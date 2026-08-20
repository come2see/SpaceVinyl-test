package tech.illusion.spacevinyl.content

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import tech.illusion.spacevinyl.R
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.layout.offset
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SpaceVinyl 主场景，实现范围以 `.spatialsdk/design-contract.md` **v4.1** 为准：
 * `vinylPlayer`（唱机模型）+ `vinylDisc`（播放时旋转的唱盘）+ `barRing`（满环频谱柱）+
 * `peakCaps`（峰值块）+ `dock`（底部控制面板）。频谱由真实
 * `android.media.audiofx.Visualizer` 音频响应驱动，信号链细节见契约 §4.1。
 *
 * **v4.1 已移除 `particleSwirl`（粒子律动）**——用户要求移除唱机上方的粒子效果。菜单里的
 * 「律动」改为置灰占位，保留槽位。历史记录：那个实现是用普通 Entity + 小球 Mesh 手工模拟的，
 * 不是真正的粒子系统，因为 `ParticleComponent` 的构造函数是 internal
 * （`core-0.13.3-sources.jar` 源码确认），只能随 Spatial Editor authored 的 AssetBundle 加载。
 *
 * `vinylDisc` 重要说明（契约 v2 变更摘要有完整论证）：模型**自带**一段动画，但**故意不用**。
 * 用 pxr 离线解析确认，全场景唯一的时变属性是 `Vinyl_disk_7` 上的 `xformOp:rotateXYZ`
 * （45 关键帧 / 2.542 秒），是一段「启动→加速→巡航→刹停」的缓动曲线，总转 540°（1.5 圈，
 * 终点姿态离起点差 180°）。循环它会呈现「转起来→停住→跳变→再转」。所以这里改为程序化恒速
 * 旋转 33⅓ RPM，方向与烘焙动画一致（负 Y = 俯视顺时针，和真实黑胶一致）。
 *
 * **播放/暂停已接通真实 `MediaPlayer`**（v4.4）：面板按钮 → `AudioSession.setPlaying()` →
 * `MediaPlayer.start()/pause()` → `isPlayingNow`。唱盘旋转与频谱动画都跟 `isPlayingNow`，
 * 所以暂停时三者一致停住。特效切换仍是前端态，那是独立缺口。
 *
 * **v5.2：已移除内置默认曲目**（原 `files/Music/track.mp3` + 硬编码的「夜神游」占位信息）。
 * 冷启动是「未选择曲目」空态：面板显示占位文案、播放键置灰不可点，唯一的音源入口是
 * 切歌键弹出的本地歌单（MediaStore）。
 *
 * 同批修掉的真 bug：音频管线（`MediaPlayer`/`Visualizer`/`HandlerThread`）原先是**嵌在
 * 内置曲目的加载流程里**创建的，`track.mp3` 不存在就提前 return，`session.mediaPlayer`
 * 永远是 null；而 `playUri()` 开头就是 `mediaPlayer ?: return`——于是「从歌单点歌完全没
 * 反应且无任何日志」。现在管线在 `startAudioPipeline()` 里无条件建好、空转等音源。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 频谱「声弧」v4.2 —— 依据 .spatialsdk/design-contract.md v4.2 / design-ref/spectrum-v4-mockup.html
// 改这里任何数字请同步改契约并升版本号，不要只改代码。
// ─────────────────────────────────────────────────────────────────────────────

/** 频段数。v4.2 起一段对应一根柱子（不再镜像）。 */
private const val BAND_COUNT = 32
/**
 * 柱子总数。v4.2 起**不再左右镜像**：64 根铺在 200° 弧上间距只有 0.0164 m，
 * 柱宽 0.016 会糊成一片；32 根间距 0.0327 m、净间隙 16.7 mm，通透且是标准 EQ 读法。
 */
private const val BAR_COUNT = BAND_COUNT
// ── 体积容器的可用边界（Round 21 探针实测，不是推算）──
// 探针沿 Y 逐点投放并数可见个数：y=+0.15/+0.05/-0.02/-0.10/-0.20/-0.30 可见，
// y=-0.40/-0.50/-0.60/-0.70 被裁。屏幕间距与世界间距的比值稳定在 820 px/m，佐证可见的是前 6 个。
// 结论：**底面在 -0.30 与 -0.40 之间**，取 -0.35；上边界高于 +0.15。
// 按 manifest 的 960dp 立方推算半边长 ≈0.36 m，故 X/Z 可用范围取 ±0.33（留 3cm 余量）。
private const val VOLUME_FLOOR_Y = -0.35f
private const val VOLUME_SAFE_HALF_XZ = 0.33f

// v4.3：柱子改立在**唱机台面上、唱片周围**，所以环回到正圆，半径 0.18。
//
// 为什么不能又贴容器底面又紧贴唱片——这是几何约束，不是选参数：
// 椭圆要完全避开 0.402×0.420 的机身矩形足迹，必须满足 (0.201/RX)² + (0.21/RZ)² ≤ 1。
// v4.2 的 RX=0.32/RZ=0.28 代入得 0.957，刚好通过；再收紧就会在对角方向插进机身，
// 放大又会越过容器安全边界 0.33。所以"落地 + 紧贴唱片"无解。
// 改为立在台面上之后不再需要避开机身（本来就踩在它上面），约束变成"在唱片之外、机身之内"：
//   唱片半径 0.151 < R=0.18 < 机身半宽 0.201  ✓（离线验算：零出台面、零插唱片）
// 兜底值，模型加载完成前使用；之后由唱片盘实测覆盖（见 ringGeom）。
private const val RING_RADIUS_FALLBACK = 0.18f
/** 圆柱柱体半径。环长 200°×0.18 → 相邻间距 0.0196 m，取 0.005 留出 9.6 mm 净间隙。 */
private const val BAR_CYL_RADIUS = 0.005f
/** 唱机高度实测值（`final=(0.402, 0.091, 0.420)`）。台面顶 = 容器底面 + 这个高度。 */
private const val VINYL_HEIGHT_M = 0.091f
/**
 * 柱体与唱片盘沿之间的净间隙。
 * 0.002 是避免 z-fighting 的最小值；用户要求再远离外径 1 cm，故取 0.012。
 */
private const val RING_EDGE_CLEARANCE = 0.012f

// 排布：**唱机后方的 200° 单弧**，正前方整片留空给控制面板和唱机。
// θ 以 +X 轴起算、绕 +Y 逆时针；θ=90° 是正前方（+Z，朝向用户），θ=270° 是正后方。
// 弧从 170° 起、扫 200° 到 370°(=10°)，即空出 θ∈(10°,170°) 的整个前半区。
// 两端落在 z=-0.088，在唱机前沿 (+0.07) 之后，所以唱机与面板完全不被挡。
//
// 演进过程（都是实测结论，不要往回改）：
//   v4  270° 弧 + 正前方 90° 缺口 → 读成"左右两簇"
//   v4.1 满环 360°（取消缺口）    → 柱子又压到面板那一侧，且把唱机整个遮住
//   v4.2 单弧 200°，开口朝用户    → 三个约束同时满足
// "两簇"的真正成因是**前后同时断开**：前面是人为缺口，后面是高频死频段塌成最小高度
// （Round 17 实测 k=29/30/31 只有 0.090/0.086/0.057 m，其余 0.10–0.27 m）。
// 只留一处开口就不会再读成两坨。
private const val ARC_START_DEG = 170f
private const val ARC_SWEEP_DEG = 200f

// 整个 3D 场景（唱机 + 频谱弧）统一的下沉量。2026-08-11 用户反馈"播放器太高了"。
private const val SCENE_Y_OFFSET = -0.16f

// 整个 3D 场景统一后移量（负 Z = 远离用户）。用户要求控制面板在播放器**前面**，但 2D Compose
// 内容能往前推的距离有上限（实测 200dp 可用、280dp 被裁）。唱机深 0.42m 且原本居中，光靠推面板
// 不一定够，所以同时把 3D 场景往后挪，两边一起拉开距离。
// 取 -0.14m 而不是更大值：BoxDepthPoC 实测 AttachmentPanel 在 z=+0.35m 处可见，说明体积在这个
// 量级上是够的，但后向边界没有实测过，保守一点避免唱机背面被裁。
// Round 21 实测后调整：-0.14 时唱机后沿正好落在 z=-0.35（贴着容器后墙），身后没有任何空间，
// 弧线无法绕到它后面。前移到 -0.04 后唱机占 z∈[-0.25,+0.17]，弧线后方 z=-0.32 有 7cm 余量。
private const val SCENE_Z_OFFSET = -0.04f

// 柱底落在**与唱机底面同一个地平面**（用户要求"底部与容器体积贴合"）。
// v4/v4.1 曾抬到 -0.20 以躲开控制面板，但 v4.2 把正前方整片让空之后，
// 面板所在的 z=+0.16 区域已经没有柱子，不需要再靠抬高来躲。
// 柱底立在**唱机台面顶**（唱片所在的平面），不是容器底面——用户要求"摆放到唱片周围"。
// 整个组合（唱机连同柱子）仍然落在容器底面上。
private const val BAR_BASE_Y_FALLBACK = VOLUME_FLOOR_Y + VINYL_HEIGHT_M
// 柱高必须跟环的尺度成比例。环半径从 0.32 收到 0.18（唱片周围）之后，
// 原来的 0.30 m 最大柱高比整个环的直径（0.36）还大，从正面看会挤成一把梳子并挡住唱片
// （Round 23 实测）。改为最高 0.14 m ≈ 环直径的 0.39，读起来是"唱片周围的一圈频谱"。
private const val BAR_UNIT_HEIGHT = 0.008f
private const val BAR_MAX_EXTRA_HEIGHT = 0.132f
/** 峰值块尺寸与落速。悬浮在该频段近期峰值高度上，缓慢下落——"正在跟踪音乐"最直接的视觉线索。 */
/** 峰值圆盘半径**与柱体截面一致**（用户要求），所以直接引用同一个常量，不另取值。 */
private const val PEAK_CAP_RADIUS = BAR_CYL_RADIUS
private const val PEAK_CAP_HEIGHT = 0.004f
private const val PEAK_CAP_GAP = 0.006f
private const val PEAK_FALL_PER_SEC = 0.55f

// ── 信号链参数（依据真机实测标定，见契约 v4 §2）──
// 有效频段上限取 7 kHz：实测 8-bit FFT 在此之上全部钉死在量化噪底 1.0，给再多柱子也不会动。
private const val F_LO_HZ = 40f
private const val F_HI_HZ = 7000f
/** 每频段自适应量程的最小动态范围（dB）。防止只抖 1~2 dB 的频段被放大成满量程噪声。 */
private const val AGC_MIN_RANGE_DB = 12f
private const val AGC_FLOOR_RISE_DB_PER_SEC = 2.0f
private const val AGC_CEIL_FALL_DB_PER_SEC = 6.0f
/** 自适应量程与绝对量程的加权。纯 AGC 会让所有柱子齐平顶、丢掉低频主导的自然形状。 */
private const val W_AGC = 0.65f
private const val W_ABS = 0.35f
private const val ABS_LO_DB = 6f
private const val ABS_HI_DB = 42f
/** 帧率级平滑。v3 只在 FFT 回调率（实测 9.9 Hz）上平滑，10 Hz 阶跃本身就像"没跟上音乐"。 */
private const val TAU_ATTACK_SEC = 0.035f
private const val TAU_RELEASE_SEC = 0.28f
/** 面板进度条轮询 MediaPlayer 位置的节流间隔——4Hz 对一条细进度条足够，不必跟满帧率。 */
private const val PROGRESS_UPDATE_INTERVAL_SEC = 0.25f
// 一次性同步创建全部 Entity（含 Mesh/Material 构造）在设备有竞争负载时
// 实测跟一次 ANR（Input dispatching timed out）同时出现过——根因没能确证，但这个改动本身
// 没有坏处：把创建摊到多帧上，每帧最多建这么多个，避免单帧内一次性做完全部同步工作。
private const val SPAWN_PER_FRAME = 6
/** 生成顺序：[0, BAR_COUNT) 柱子 → [BAR_COUNT, BAR_COUNT*2) 峰值块。共 64 个。 */
private const val TOTAL_ENTITIES = BAR_COUNT * 2

private const val VINYL_ASSET_URI = "asset://modern_vinyl_player.usdz"
private const val VINYL_DISC_NODE = "Vinyl_disk_7"

// 目标最大水平尺寸（米）。barRing 内圈净半径 = 0.30 - 0.011 = 0.289m，取 0.42m 让唱机每侧
// 留约 8cm 余量，不会插进柱子里。实际缩放系数在运行时用 getVisualBounds 实测算出——因为该
// usdz 的 metersPerUnit=0.01 与 /scene/Meshes 上的 scale=(100,100,100) 恰好互相抵消，导入器
// 认不认这个单位会造成 100 倍的尺寸分叉（6.85m vs 685m），实测可以免疫这个分叉。
private const val VINYL_TARGET_WIDTH_M = 0.42f
// 唱机底面与柱底同落在**容器底面**上（用户要求"底部与容器体积贴合"）。
private const val VINYL_BASE_Y = VOLUME_FLOOR_Y
// 离线用 pxr 量出来的兜底缩放（唱片直径 5.2071 原始单位 → 0.302m 的 12 吋 LP）。
// 只有 getVisualBounds 量不到东西时才用到。
private const val VINYL_FALLBACK_SCALE = 0.058f

// 33⅓ RPM = 200°/秒。负号 = 俯视顺时针，既符合真实黑胶，也和 usdz 里烘焙动画的方向一致
// （原始 timeSamples 从 -90° 走到 -630°）。
private const val DISC_DEG_PER_SEC = -200f
// 起停缓动时间常数（秒）——把烘焙动画里唯一值得保留的"起停手感"用程序化方式还原。
private const val DISC_SPIN_TAU_SECONDS = 0.9f

// 控制面板改用 AttachmentPanel 放进 3D 场景，位置直接用米指定 —— 和唱机模型同一个坐标系。
//
// 为什么不用普通 Compose Box + Modifier.offset(z=)：2D Compose 内容渲染在体积窗口自己的平面上，
// 那个平面到 3D 内容的 Z 映射（dp→米）始终没能标定准，而且可用余量很窄。实测两轮：
//   · 280dp  → 面板整个被前边界裁掉，完全不可见
//   · 200dp（探针实测的安全上限）+ 场景后移 0.14m → 面板仍在唱机**后面**
// 也就是说"够得着模型前面"和"不被前边界裁掉"这两个条件在 Box 方案里没有交集。
// AttachmentPanel 没有这个问题：它是 3D 空间里的实体，位置就是米，可精确控制前后关系。
// （BoxDepthPoC 实测 AttachmentPanel 在 z=+0.35m 处渲染正常，说明体积在这个量级上够用。）
//
// 唱机在场景后移后占 z ∈ [-0.35, +0.07]（中心 -0.14、深 0.42），面板放在 +0.16 稳稳在它前面
// （留 9cm 余量），同时不过分前伸——离视点越近透视畸变越大，面板会被压到画面下边缘去。
// Y 取 -0.28（比唱机底面 -0.31 略高）：面板比唱机近 0.30m，透视会把它显示得更靠下，
// 所以要"抬高一点"才能在观感上落在唱机正下方。首版取 -0.42 实测太低、掉出画面底边。
private const val DOCK_ATTACH_Y = -0.33f   // 用户要求再下降 5cm（原 -0.28）
// 场景前移 0.10 后唱机前沿到了 z=+0.17，面板必须跟着前移才能保持在它前面。
// +0.28 仍在 BoxDepthPoC 实测过的 AttachmentPanel 可见范围内（+0.35 处可见）。
private const val DOCK_ATTACH_Z = 0.38f    // 用户要求向用户侧靠近 10cm（原 0.28）
private const val DOCK_ATTACHMENT_ID = "playback_dock"

// 播放列表面板：独立于 dock 的第二个 AttachmentPanel，浮在 dock 前方（v5.5）。
// 位置/倾斜是首版猜测值，未经真机验证——见 design-contract.md v5.5 的"播放列表面板位置"一节，
// 如被容器前边界裁掉，按该节记录的回退步长调整 PLAYLIST_ATTACH_Z 并把调整过程记回契约。
private const val PLAYLIST_ATTACHMENT_ID = "playback_playlist"
private const val PLAYLIST_ATTACH_Y = -0.20f
private const val PLAYLIST_ATTACH_Z = 0.36f   // 比 dock（+0.38）更靠前 0.08m
private const val PLAYLIST_TILT_DEG = 0f      // 用户要求不倾斜——面板保持竖直，正对用户（原首版猜测值 -25° 已废弃）
// 按 dock 的 0.039m / 104dp 比例估算（本面板首屏约 266dp 高：40dp 标题行 + 最多 6×36dp 列表项）。
// 只在面板第一次真正显示内容、但当帧测量仍为 0 时才会用到，见 Task 3。
private const val PLAYLIST_HALF_HEIGHT_FALLBACK = 0.10f

// 面板以**底边为轴**向后倾斜，面朝上，方便用户从上往下看（用户要求 45°）。
// 负号 = 面法线从 +Z 转向 +Y：绕 X 转 θ 时 +Z →(0, -sinθ, cosθ)，θ=-45° 得 (0, +0.707, +0.707)。
private const val DOCK_TILT_DEG = -60f     // 用户反馈 80° 过大，改为 60°
// 半高兜底值（约 104dp）。真实值在运行时由 attachment 实体的包围盒实测覆盖——
// v4.5 刚在"拿整机高度推算局部尺寸"上栽过，这里不再估。
private const val DOCK_HALF_HEIGHT_FALLBACK = 0.039f

@Composable
fun HomeVolume() {
    val context = LocalContext.current
    val barRing = remember { mutableListOf<Entity>() }
    val clock = remember { FloatArray(1) }
    val spawnedCount = remember { IntArray(1) }
    // 逐频段目标能量，由 Visualizer 的 FFT 回调线程（约 20 Hz）写入、帧循环（主线程，60 Hz）读取。
    // 没有加锁——只是可视化用途，读到上一帧的值不影响正确性，是音频可视化里常见的取舍。
    val bandTarget = remember { FloatArray(BAND_COUNT) }
    // 帧率级平滑后的显示值与峰值保持值，只在主线程读写。
    val bandDisplay = remember { FloatArray(BAND_COUNT) }
    val bandPeak = remember { FloatArray(BAND_COUNT) }
    val overallLevel = remember { FloatArray(1) }
    // 每根柱子的固定极坐标位置与材质句柄（颜色随能量逐帧变，所以要留住材质引用）。
    // 环半径与柱底 Y **由唱片盘节点运行时实测得出**，不用常量推算。
    // 教训：曾用"容器底面 + 唱机包围盒高 0.091"当柱底，但那个高度包含唱臂和中轴，
    // 盘面比它低不少，柱子于是悬在盘面上方（用户放大截图发现）。
    // 索引 0 = 环半径，1 = 柱底 Y。模型加载完成前先用常量兜底。
    val ringGeom = remember { floatArrayOf(RING_RADIUS_FALLBACK, BAR_BASE_Y_FALLBACK) }
    val barMaterial = remember { arrayOfNulls<UnlitMaterial>(BAR_COUNT) }
    // 上一次写入材质的颜色档位，用来避免每帧对 128 个材质做无意义的 setBaseColor。
    val barColorBucket = remember { IntArray(BAR_COUNT) { -1 } }
    val peakCaps = remember { mutableListOf<Entity>() }
    // 真实播放态：由 MediaPlayer 的 onPrepared 回调（音频 HandlerThread）写入、update（主线程）
    // 读取，跟 bandEnergy 同一个"可视化用途、不加锁"的取舍。
    // 面板按钮的图标跟这个（点击即刻反馈）；唱盘与频谱跟 audioSession.isPlayingNow（真实播放态）。
    // 两者最终收敛，但不是同一个东西——真实态要等 MediaPlayer 真的 start()/pause() 才变。
    // v5.2：初值改为 false。已无内置默认曲目，冷启动时确实没有任何东西在播，
    // 初值 true 会让面板一开机就显示「暂停」图标（=自称正在播放）。
    var uiPlaying by remember { mutableStateOf(false) }
    // 应用切到后台前是否正在播放——只有这里为真，回到前台才自动续播，不然会覆盖用户自己按过的暂停。
    var wasPlayingBeforeBackground by remember { mutableStateOf(false) }
    // 由 composition 持有音频对象的强引用。不持有的话 GC 会在几秒内回收它们、FFT 回调随之停止
    // ——v4 首轮实测就是这个症状（详见 AudioSession 的注释）。
    val audioSession = remember { AudioSession() }
    // 歌单（MediaStore 查询结果）与当前曲目信息。曲目信息是面板显示用的状态，选歌后要跟着变。
    val songs = remember { mutableStateListOf<SongItem>() }
    // v5.2：不再有内置默认曲目，冷启动是「未选择曲目」的空态。空串 = 没选歌，
    // 面板据此显示占位文案并把播放键置为不可用（没有音源时点它只会是个假动作）。
    var currentTitle by remember { mutableStateOf("") }
    var currentArtist by remember { mutableStateOf("") }
    // 面板进度条：轮询 MediaPlayer 当前位置算出来，节流写入见帧循环里的 progressAccum。
    var currentProgress by remember { mutableStateOf(0f) }
    val progressAccum = remember { FloatArray(1) }
    // 上次已同步到 Compose 状态的曲目。用**引用比较**而不是比 title——
    // 同名不同曲（两首歌重名）时比 title 会漏掉 artist 的更新。
    val lastSyncedTrack = remember { arrayOfNulls<SongItem>(1) }
    // 播放列表面板的开关状态（v5.5）。由切歌键的 PlaylistToggleButton 翻转，
    // 独立 AttachmentPanel 的内容据此在空 Box 与真实列表之间切换。
    var playlistOpen by remember { mutableStateOf(false) }

    // RECORD_AUDIO 喂频谱、READ_MEDIA_AUDIO 喂应用内歌单，都是运行时权限，装完 App 或每次
    // 重装都要重新要一遍。没有专门的引导页——拒绝就让频谱/歌单保持静止，不阻断播放本身。
    var permissionsGranted by remember { mutableStateOf(hasAudioPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsGranted = hasAudioPermissions(context) }
    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_AUDIO)
            )
        }
    }

    val vinylAnchor = remember { arrayOfNulls<Entity>(1) }
    val discEntity = remember { arrayOfNulls<Entity>(1) }
    val discYaw = remember { FloatArray(1) }      // 度
    val discRate = remember { FloatArray(1) }     // 度/秒，向目标转速缓动
    val lastNanos = remember { LongArray(1) }
    val frameCount = remember { LongArray(1) }
    // initial 里拿到的 SpatialViewContent，供下面的每帧循环生成实体用。
    val contentRef = remember { arrayOfNulls<SpatialViewContent>(1) }
    // 控制面板的附件实体，以及"是否已完成倾斜"的一次性标志。
    val dockEntity = remember { arrayOfNulls<Entity>(1) }
    val dockTilted = remember { BooleanArray(1) }
    // 播放列表面板的附件实体，以及"是否已完成倾斜"的一次性标志（同 dockEntity/dockTilted 模式）。
    val playlistEntity = remember { arrayOfNulls<Entity>(1) }
    val playlistTilted = remember { BooleanArray(1) }

    Box(modifier = Modifier.fillMaxSize()) {
        SpatialView(
            modifier = Modifier.fillMaxSize(),
            initial = { content, attachments ->
                contentRef[0] = content
                startAudioPipeline(context, bandTarget, audioSession)
                loadVinylPlayer(content, vinylAnchor, discEntity, ringGeom)

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
                // 倾斜修正在帧循环里一次性做（见 Task 3），不依赖 playlistOpen——理由同上。
                attachments.entity(id = PLAYLIST_ATTACHMENT_ID)?.apply {
                    components[TransformComponent::class.java]
                        ?.setPosition(Vector3(0f, PLAYLIST_ATTACH_Y, PLAYLIST_ATTACH_Z))
                    content.addEntity(this)
                    playlistEntity[0] = this
                } ?: android.util.Log.w(
                    "SpaceVinyl",
                    "playlist attachment entity missing for id=$PLAYLIST_ATTACHMENT_ID " +
                        "— a second AttachmentPanel may not be supported on this SpatialView"
                )
            },
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
            // 注意：这里**故意没有传 update 参数**。
            // 实测教训（round4 两张相隔 5 秒的截图完全一致才发现）：SpatialView 的 update 回调是
            // **重组（recomposition）回调，不是每帧渲染回调**。Compose 状态不变时它就不再被调用，
            // 导致：分帧生成的实体只生成了一批就停住、柱子高度冻结、唱盘完全不转。
            // 所有连续动画改由下面的 withFrameNanos 帧循环驱动。
        )

    }

    // 歌单查询键在 permissionsGranted 上：权限对话框是异步的，首次查询大概率会在用户点"允许"
    // 之前就跑完、查到 0 条——不重新查一遍，歌单会在整个会话里一直空着。
    // 放到 IO 线程——ContentResolver 查询在主线程上是阻塞调用，这个文件已经因为
    // "主线程阻塞"吃过一次 ANR（同步 MediaPlayer.prepare()）。
    LaunchedEffect(permissionsGranted) {
        val loaded = withContext(Dispatchers.IO) { querySongs(context) }
        songs.clear()
        songs.addAll(loaded)
        // 音频线程自动切歌时读的是这份快照，不是上面的 Compose 列表。
        audioSession.shuffle.setTracks(loaded)
    }

    // 真正的每帧驱动。withFrameNanos 挂在 Compose 的帧时钟上，回调在主线程执行
    // （Entity/TransformComponent 的操作都是 @MainThread，正好满足）。
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nowNanos ->
                val content = contentRef[0] ?: return@withFrameNanos

                // 分帧生成实体，避免单帧内一次性创建 60 个（这个文件为此踩过一次 ANR）。
                if (spawnedCount[0] < TOTAL_ENTITIES) {
                    var createdThisFrame = 0
                    while (spawnedCount[0] < TOTAL_ENTITIES && createdThisFrame < SPAWN_PER_FRAME) {
                        val index = spawnedCount[0]
                        when {
                            index < BAR_COUNT -> {
                                val material = UnlitMaterial.create()
                                barMaterial[index] = material
                                val bar = Entity()
                                bar.components.set(
                                    ModelComponent(
                                        // createCylinder(height, radius) —— 0.13.3 源码确认存在
                                        // （MeshResource.kt:342）。高度按 BAR_UNIT_HEIGHT 建，
                                        // 逐帧靠 Y 轴缩放拉伸，与原来的 Box 方案一致。
                                        MeshResource.createCylinder(BAR_UNIT_HEIGHT, BAR_CYL_RADIUS),
                                        material
                                    )
                                )
                                content.addEntity(bar)
                                barRing.add(bar)
                            }
                            else -> {
                                val cap = createPeakCapEntity()
                                content.addEntity(cap)
                                peakCaps.add(cap)
                            }
                        }
                        spawnedCount[0]++
                        createdThisFrame++
                    }
                }

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

                // 播放列表面板的倾斜修正：与 dock 同一套一次性修正模式（见上）。这里不需要
                // 等 playlistOpen 为真——AttachmentPanel.getVisualBounds 对 2D 内容恒返回
                // (0,0,0)（AGENTS.md 硬规则 #6 / design-contract.md §4.6b 已确认，dock 自己的
                // 同一段代码同样如此），所以“测到真实内容再修正”这个前提本来就不成立：h 实际
                // 上永远是 PLAYLIST_HALF_HEIGHT_FALLBACK。既然如此就应该像 dock 一样无条件跑一次，
                // 好处是面板从第一帧起就已经摆正，不会等到用户第一次点开才“跳”一下。
                if (!playlistTilted[0]) {
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

                val dt = if (lastNanos[0] == 0L) 0f
                else ((nowNanos - lastNanos[0]) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                lastNanos[0] = nowNanos

                // uiPlaying 是按钮点击即改的前端态，默认就是"播放中"（乐观值），正常情况下等
                // 真实播放（audioSession.isPlayingNow）跟上就收敛一致。但音频初始化/切歌确定
                // 失败时 isPlayingNow 永远不会变 true，没有这一步的话面板会一直卡在"播放中"，
                // 用户看到的是一首其实根本没在放的歌。playbackUnavailable 一旦置位就纠正一次。
                if (audioSession.playbackUnavailable && uiPlaying) {
                    uiPlaying = false
                }

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

                clock[0] += dt
                val t = clock[0]

                // 帧率级平滑：起振快、回落慢。FFT 只有约 20 Hz，直接用它的值会看得出阶跃。
                // **暂停时整段跳过**：柱高与峰值块冻结在当前值，与唱盘一起停住。
                // 用户要求"点击暂停时频谱和音乐盒应该一致暂停"。
                if (dt > 0f && audioSession.isPlayingNow) {
                    val kAttack = 1f - exp(-dt / TAU_ATTACK_SEC)
                    val kRelease = 1f - exp(-dt / TAU_RELEASE_SEC)
                    for (k in 0 until BAND_COUNT) {
                        val target = bandTarget[k]
                        val gain = if (target > bandDisplay[k]) kAttack else kRelease
                        bandDisplay[k] += (target - bandDisplay[k]) * gain
                        bandPeak[k] = max(bandPeak[k] - PEAK_FALL_PER_SEC * dt, bandDisplay[k])
                    }
                }

                // 整体音量。v4.1 移除粒子后已无消费者，但保留计算——它是帧日志里判断信号链健康度
                // 最省事的单一指标（v3 时它被 17 个死频段稀释到 0.03，v4 起是 0.5 量级）。
                var levelSum = 0f
                for (k in 0 until BAND_COUNT) levelSum += bandDisplay[k]
                val level = levelSum / BAND_COUNT
                overallLevel[0] = level

                val ringR = ringGeom[0]
                val barBase = ringGeom[1]

                barRing.forEachIndexed { i, bar ->
                    val angle = barAngleRad(i)
                    val bx = cos(angle) * ringR
                    val bz = SCENE_Z_OFFSET + sin(angle) * ringR
                    val energy = bandDisplay[i % BAND_COUNT]
                    val height = BAR_UNIT_HEIGHT + BAR_MAX_EXTRA_HEIGHT * energy
                    val transform = bar.components[TransformComponent::class.java]
                    transform?.setScaleVector(Vector3(1f, height / BAR_UNIT_HEIGHT, 1f))
                    transform?.setPosition(Vector3(bx, barBase + height / 2f, bz))

                    // 颜色由能量决定（不是柱子序号）——颜色本身也随音乐变化，是第二条响应线索。
                    // 量化成 24 档只在跨档时写材质，避免每帧 64 次无意义的 setBaseColor。
                    val bucket = (energy * 24f).toInt().coerceIn(0, 24)
                    if (barColorBucket[i] != bucket) {
                        barColorBucket[i] = bucket
                        barMaterial[i]?.setBaseColor(energyColor(bucket / 24f))
                    }
                }

                peakCaps.forEachIndexed { i, cap ->
                    val angle = barAngleRad(i)
                    val peakHeight = BAR_UNIT_HEIGHT + BAR_MAX_EXTRA_HEIGHT * bandPeak[i % BAND_COUNT]
                    cap.components[TransformComponent::class.java]?.setPosition(
                        Vector3(
                            cos(angle) * ringR,
                            barBase + peakHeight + PEAK_CAP_GAP,
                            SCENE_Z_OFFSET + sin(angle) * ringR
                        )
                    )
                }

                // 唱盘：播放时向 33⅓ RPM 缓动升速，停止时缓动降到 0。
                val disc = discEntity[0]
                if (disc != null && dt > 0f) {
                    val target = if (audioSession.isPlayingNow) DISC_DEG_PER_SEC else 0f
                    val k = 1f - exp(-dt / DISC_SPIN_TAU_SECONDS)
                    discRate[0] += (target - discRate[0]) * k
                    discYaw[0] = (discYaw[0] + discRate[0] * dt) % 360f
                    disc.components[TransformComponent::class.java]
                        ?.setEulerAngles(EulerAngles(0f, discYaw[0], 0f))
                }

                // 面板进度条：轮询 MediaPlayer 位置。节流到 4Hz——没必要每帧（60Hz）都推一次
                // Compose 状态，那只会让面板整块多余重组。
                progressAccum[0] += dt
                if (progressAccum[0] >= PROGRESS_UPDATE_INTERVAL_SEC) {
                    progressAccum[0] = 0f
                    val newProgress = try {
                        val mp = audioSession.mediaPlayer
                        val duration = mp?.duration ?: -1
                        if (mp != null && duration > 0) {
                            (mp.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                        } else 0f
                    } catch (t: Throwable) {
                        // MediaPlayer 不在合法状态（尚未 prepared/已释放）时 getter 会抛异常。
                        0f
                    }
                    if (newProgress != currentProgress) currentProgress = newProgress
                }

                // 帧循环健康度诊断：每 120 帧打一行，用来证明帧循环真的在跑、以及唱盘在转。
                // （上一版把动画放在 update 里，就是因为没有这种证据才没能及时发现它不跑。）
                frameCount[0]++
                if (frameCount[0] % 30L == 0L) {
                    android.util.Log.w(
                        "SpaceVinyl",
                        "frame=${frameCount[0]} dt=${"%.4f".format(dt)} spawned=${spawnedCount[0]}" +
                            " playing=${audioSession.isPlayingNow} discRate=${"%.1f".format(discRate[0])}" +
                            " discYaw=${"%.1f".format(discYaw[0])}" +
                            " level=${"%.3f".format(level)}" +
                            // 频段显示值：用来证明"柱子确实在动"，不必靠看截图猜。
                            // 全是 0.00 或全部一模一样 = 信号链断了，不是渲染问题。
                            " band=[" + (0 until BAND_COUNT step 4)
                                .joinToString(",") { "%.2f".format(bandDisplay[it]) } + "]" +
                            // 末三段单独打：Round 17 实测它们会塌到 0.057~0.090 m（高频贴着量化
                            // 噪底），是"弧看起来断了一截"的成因，值得长期盯着。
                            " tail=[" + (BAND_COUNT - 3 until BAND_COUNT)
                                .joinToString(",") { "%.2f".format(bandDisplay[it]) } + "]"
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 加了 11.6MB 的模型之后，泄漏成本从"60 个程序化图元"变成"贴图解码后约 64MB RGBA"，
            // 清理不再可选。destroy(recursively = true) 会带走 anchor 下整棵子树（含加载的模型根）。
            vinylAnchor[0]?.destroy(recursively = true)
            vinylAnchor[0] = null
            discEntity[0] = null
            // 持有了引用就必须释放，否则强引用变成真泄漏。
            audioSession.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (audioSession.wantPlaying) {
                        wasPlayingBeforeBackground = true
                        audioSession.setPlaying(false)
                        uiPlaying = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlayingBeforeBackground) {
                        wasPlayingBeforeBackground = false
                        audioSession.setPlaying(true)
                        uiPlaying = true
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * 加载唱机模型，实测包围盒后缩放/摆放，并按名找到唱盘节点。
 *
 * 尺寸**运行时实测**而不是硬编码：该 usdz 的 `metersPerUnit=0.01` 与 `/scene/Meshes` 上的
 * `scale=(100,100,100)` 恰好互相抵消，导入器认不认这个单位会导致 100 倍的尺寸分叉
 * （6.85m vs 685m）。实测一次就免疫这个分叉，代价只是一次 getVisualBounds 调用。
 *
 * Y 轴必须做 pivot 修正：模型底面在原点下方 1.77（原始单位）处，不修正的话唱机会沉到柱环下面。
 */
private suspend fun loadVinylPlayer(
    content: SpatialViewContent,
    vinylAnchor: Array<Entity?>,
    discEntity: Array<Entity?>,
    ringGeom: FloatArray,
) {
    try {
        val root = Entity.loadSuspend(uriString = VINYL_ASSET_URI)

        val anchor = Entity()
        anchor.setName("VinylPlayerAnchor")
        content.addEntity(anchor)
        anchor.addChild(root)
        vinylAnchor[0] = anchor

        // 在 anchor 局部空间量原始包围盒（此时 anchor 的 scale 还是 1）。
        val raw = root.getVisualBounds(relativeTo = anchor, recursive = true)
        val maxHoriz = maxOf(raw.size.x, raw.size.z)
        val scale = if (maxHoriz <= 1e-4f) VINYL_FALLBACK_SCALE else VINYL_TARGET_WIDTH_M / maxHoriz

        // pivot 修正：XZ 对中、底面贴 anchor 原点。子实体局部坐标在父 scale 之前生效，
        // 所以这里用的是未缩放的原始单位。
        root.components[TransformComponent::class.java]
            ?.setPosition(Vector3(-raw.center.x, -raw.min.y, -raw.center.z))

        anchor.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(scale))
            setPosition(Vector3(0f, VINYL_BASE_Y, SCENE_Z_OFFSET))
        }

        discEntity[0] = root.findEntity(VINYL_DISC_NODE)

        // 环半径与柱底都按**唱片盘自身**的实测包围盒定，不用整机高度推算。
        // 整机包围盒高 0.091 包含唱臂与中轴，盘面比它低；半径同理，唱机 0.402 宽 ≠ 唱片直径。
        discEntity[0]?.let { disc ->
            val db = disc.getVisualBounds(relativeTo = null, recursive = true)
            val discRadius = maxOf(db.size.x, db.size.z) / 2f
            if (discRadius > 1e-3f) {
                // 柱体外切于盘沿：环半径 = 盘半径 + 柱半径 + 净间隙
                ringGeom[0] = discRadius + BAR_CYL_RADIUS + RING_EDGE_CLEARANCE
                ringGeom[1] = db.max.y          // 柱底落在盘面上
                android.util.Log.w(
                    "SpaceVinyl",
                    "disc measured: size=${db.size} max.y=${db.max.y} radius=$discRadius " +
                        "→ ringRadius=${ringGeom[0]} barBaseY=${ringGeom[1]} " +
                        "(fallback was ${RING_RADIUS_FALLBACK}/${BAR_BASE_Y_FALLBACK})"
                )
            }
        }

        // 一次性诊断。用 Log.w 不用 Log.d——本机 adb logcat 看不到 debug 级别。
        val after = anchor.getVisualBounds(relativeTo = null, recursive = true)
        android.util.Log.w(
            "SpaceVinyl",
            "vinyl raw=${raw.size} min=${raw.min} center=${raw.center} " +
                "scale=$scale final=${after.size} disc=${discEntity[0] != null}"
        )
        if (discEntity[0] == null) dumpEntityNames(root)
    } catch (t: Throwable) {
        // 资产缺失/格式不支持不该拖垮整个 App——这个文件已经在音频初始化那里吃过一次
        // "只 catch 了 SecurityException，结果被 IOException 打崩进程"的亏。
        android.util.Log.w("SpaceVinyl", "Vinyl model load failed: ${t.javaClass.simpleName}: ${t.message}")
    }
}

/** 唱盘节点按名找不到时的兜底诊断：把整棵实体树的名字打到 logcat，好知道导入器实际起了什么名。 */
private fun dumpEntityNames(root: Entity) {
    val names = mutableListOf<String>()
    fun walk(e: Entity, depth: Int) {
        if (names.size > 160) return
        names += "${"  ".repeat(depth)}${e.getName()}"
        e.getChildren().forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    names.chunked(20).forEachIndexed { i, chunk ->
        android.util.Log.w("SpaceVinyl", "entityTree[$i] " + chunk.joinToString(" | "))
    }
}

/** RECORD_AUDIO 喂频谱 FFT，READ_MEDIA_AUDIO 喂应用内歌单——两个都是运行时权限。 */
private fun hasAudioPermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

/**
 * 持有音频对象的强引用，并负责释放。
 *
 * **这不是可选的整洁性改进，是修 bug。** v4 首轮上机实测：`band[]` 只活了约 4 秒（frame 30–200
 * 一直在变），之后 20 秒纹丝不动。根因是 `MediaPlayer`/`Visualizer` 原本是 `Handler.post{}`
 * lambda 里的局部变量——lambda 返回后没有任何强引用，被 GC 回收、native 资源随之释放、
 * FFT 回调就停了。这个 bug 在 v3 同样存在，只是那时柱子本来就不动，一直没暴露。
 *
 * 既然必须持有引用，就必须配套释放，否则强引用变成真泄漏
 * （11.6MB 模型的贴图之外，又多一份 MediaPlayer + Visualizer + HandlerThread）。
 */
private class AudioSession {
    @Volatile var mediaPlayer: MediaPlayer? = null
    @Volatile var visualizer: Visualizer? = null
    @Volatile var handlerThread: HandlerThread? = null

    /**
     * 真实播放态。唱盘旋转与频谱动画都跟这个标志，**不是面板按钮的前端态**。
     * 由音频线程写入、帧循环（主线程）逐帧读取，所以必须 `@Volatile`。
     */
    @Volatile var isPlayingNow: Boolean = false

    /**
     * 用户意图。`prepareAsync` 完成时按它决定是否真的 `start()`——否则"准备完成之前就点了暂停"
     * 会被 onPrepared 里的无条件 start() 覆盖掉。
     *
     * v5.2：初值改为 false。已无内置默认曲目，冷启动时没有任何播放意图；留成 true 会让
     * ON_PAUSE 把 `wasPlayingBeforeBackground` 误置为真，回前台又去 start() 一个没有音源的
     * MediaPlayer，把 `uiPlaying` 错误地点亮成「正在播放」。
     */
    @Volatile var wantPlaying: Boolean = false

    /**
     * 真实播放**确定失败**（音频文件缺失/`setDataSource`/`prepareAsync` 抛异常）。
     * `uiPlaying` 是点击即改的前端态，正常情况下等真实播放跟上就收敛一致；但失败时
     * `isPlayingNow` 永远不会变 true，没有这个标志的话 `uiPlaying` 就没有任何信号能被拉回
     * false——面板会一直显示"播放中"，用户点了播放键实际却什么声音都没有。
     * 帧循环（主线程）据此把 `uiPlaying` 拉回 false；写入方是音频 HandlerThread，故 `@Volatile`。
     */
    @Volatile var playbackUnavailable: Boolean = false

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

    /**
     * 切歌：把 MediaPlayer 切到另一个音源。
     *
     * 用 `reset()` + `setDataSource(context, uri)` 而不是新建 MediaPlayer——**关键**：
     * `Visualizer` 是绑在 `mediaPlayer.audioSessionId` 上的，`reset()` 不会改变同一实例的 session id，
     * 所以频谱管线可以原样继续工作；换成新实例就得连 Visualizer 一起重建。
     */
    fun playUri(context: Context, uri: Uri) {
        wantPlaying = true
        // 开始播放即记账（不是播完才记账）：否则「中途被手动切走、没听完」的曲目永远不入账，
        // 之后仍会被随机抽到，与「排除已播放」的意图冲突。
        shuffle.markPlayed(uri)
        // **必须在调用线程同步清掉**，不能挪进下面的 Runnable：调用方（onPick，主线程）
        // 刚把 uiPlaying 置为 true，如果这个标志还残留着上一次失败的 true，帧循环会在
        // Runnable 到达音频线程之前就把 uiPlaying 强拉回 false，而之后没有任何东西会把它
        // recover——结果是「音乐真的在放，但图标显示暂停」。
        playbackUnavailable = false
        val work = Runnable {
            // 这条日志是这次 bug 的直接教训：管线没建起来时这里原本静默 return，
            // 「点歌没反应」在 logcat 里完全没有痕迹，排查只能靠读代码。
            val mp = mediaPlayer ?: run {
                playbackUnavailable = true
                android.util.Log.w("SpaceVinyl", "playUri skipped: MediaPlayer not created yet")
                return@Runnable
            }
            try {
                isPlayingNow = false
                mp.reset()
                mp.setDataSource(context, uri)
                // isLooping 必须为 false：单曲循环下 OnCompletionListener 永远不触发，
                // 自动切下一首就无从实现。
                mp.isLooping = false
                mp.setOnPreparedListener {
                    if (wantPlaying) {
                        it.start()
                        isPlayingNow = true
                    }
                }
                mp.setOnCompletionListener { onTrackCompleted() }
                mp.prepareAsync()
                android.util.Log.w("SpaceVinyl", "playUri: $uri")
            } catch (t: Throwable) {
                playbackUnavailable = true
                // wantPlaying 也必须拉回 false：这次播放已经确定失败，不会再有 onPrepared 或
                // completion 回调把它清掉。留着 true 会让下一次 ON_PAUSE 把
                // wasPlayingBeforeBackground 误锁为真，回前台时 ON_RESUME 又去 start() 一个
                // Idle、没有音源的 MediaPlayer——这正是 wantPlaying 这个字段本身要防止的场景
                // （见字段注释），此处必须保持同一个不变量。
                wantPlaying = false
                android.util.Log.w("SpaceVinyl", "playUri failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        val looper = handlerThread?.looper
        if (looper == null || !Handler(looper).post(work)) work.run()
    }

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
        // completion 消息和 pause 消息都排在同一个音频 HandlerThread 的队列里；如果播完的瞬间
        // 用户暂停或应用切后台（setPlaying(false) 把 wantPlaying 设为 false），completion 消息
        // 仍可能排在 pause 之前先被处理到。没有这个判断，就会在「已经决定不放了」之后又调用
        // playSong() 把 wantPlaying 重新置回 true 并 start() 下一首——播放在后台复活。
        if (!wantPlaying) return
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

    /** 切换真实播放/暂停。MediaPlayer 操作放回创建它的 HandlerThread 上执行。 */
    fun setPlaying(want: Boolean) {
        wantPlaying = want
        val work = Runnable {
            val mp = mediaPlayer ?: return@Runnable
            try {
                if (want) mp.start() else mp.pause()
                isPlayingNow = want
            } catch (t: Throwable) {
                // 尚未 prepared 时 start()/pause() 会抛 IllegalStateException：
                // 忽略即可，wantPlaying 已记下意图，onPrepared 会照它执行。
                android.util.Log.w("SpaceVinyl", "setPlaying($want): ${t.javaClass.simpleName}")
            }
        }
        val looper = handlerThread?.looper
        if (looper == null || !Handler(looper).post(work)) work.run()
    }

    /** 释放全部音频资源。在创建它们的 HandlerThread 上执行，拿不到该线程时退化为就地执行。 */
    fun release() {
        val thread = handlerThread
        handlerThread = null
        val work = Runnable {
            // 逐个 try：任何一个失败都不该阻止后面的释放。
            try { visualizer?.enabled = false } catch (t: Throwable) { /* 已释放/状态非法 */ }
            try { visualizer?.release() } catch (t: Throwable) { }
            try { mediaPlayer?.stop() } catch (t: Throwable) { }
            try { mediaPlayer?.release() } catch (t: Throwable) { }
            visualizer = null
            mediaPlayer = null
            isPlayingNow = false
            thread?.quitSafely()
        }
        val looper = thread?.looper
        if (looper == null || !Handler(looper).post(work)) work.run()
    }
}

/**
 * 频段 → FFT bin 的划分，**连续、不重叠、每段至少 1 个 bin**。
 *
 * 纯对数等分在低频会退化：1024 点 FFT @48 kHz 的分辨率是 46.875 Hz/bin，40–100 Hz 只有 1~2 个
 * bin，前 4 段会被取整压成同一个 bin——画出 4 根数据完全相同的柱子（v4 设计阶段实测到过）。
 * 强制单调推进后自然收敛成 mel 式混合刻度：低 19 段每段 1 bin（线性 47 Hz 步进），
 * 高 13 段按对数展宽到 7 kHz。
 */
private fun computeBandEdges(binHz: Float, binCount: Int, lo: IntArray, hi: IntArray) {
    var cur = max(1, (F_LO_HZ / binHz).toInt())
    for (k in 0 until BAND_COUNT) {
        val edgeHi = F_LO_HZ * (F_HI_HZ / F_LO_HZ).pow((k + 1).toFloat() / BAND_COUNT)
        var h = max(cur + 1, (edgeHi / binHz).roundToInt())
        // 给后面的段留够 bin，避免高频段被挤成 0 宽
        h = min(h, binCount - (BAND_COUNT - k - 1))
        lo[k] = cur
        hi[k] = h
        cur = h
    }
}

/**
 * 建立音频管线（`MediaPlayer` + `Visualizer`）并订阅 FFT 回调，把逐频段目标能量写进
 * [bandTarget]、真实播放态写进 `session.isPlayingNow`（唱盘旋转与频谱动画跟的就是这个标志）。
 *
 * **v5.2：本函数不再加载任何默认曲目，也不再自动起播。** 它只把管线搭好并空转等待，
 * 真正的音源全部由 `AudioSession.playUri()`（用户从歌单选歌）设置。
 *
 * 这个拆分是在修一个真 bug，不是整洁性改动：上一版把 `MediaPlayer` 的创建**嵌在
 * 内置测试曲目 `track.mp3` 的加载流程里**，文件不存在时提前 `return`，于是
 * `session.mediaPlayer` 永远是 null；而 `playUri()` 开头就是 `mediaPlayer ?: return`，
 * 结果「从歌单点歌 → 完全没反应、也没有任何日志」。管线的生命周期必须与
 * 某个具体音源文件解耦，否则少一个文件就等于整个播放功能不存在。
 *
 * 幅度映射是 **每频段自适应量程 + 绝对 dB 窗口的加权**，不是固定系数。原因是实测证据：
 * `android.media.audiofx.Visualizer` 的 FFT 是 8-bit，有效动态范围只有约 42 dB，量化噪底恒为
 * 1.0。v3 用的 `avg / 90f` 让 24 根柱子里 17 根的高度变化不足 2 毫米。自适应量程的好处是
 * **只要该频段有真实变化就满量程地动**；而幅度恒定的频段量程为零，诚实地保持 0，
 * 不靠抬噪底假装在动。
 *
 */
private fun startAudioPipeline(
    context: Context,
    bandTarget: FloatArray,
    session: AudioSession,
) {
    session.appContext = context.applicationContext
    try {
        // 整个音频初始化放到专用 HandlerThread，调用方线程（SpatialView initial，主线程）不做阻塞
        // 调用——同步 MediaPlayer.prepare() 实测触发过 ANR，prepareAsync() + 回调才是正确写法。
        val handlerThread = HandlerThread("SpaceVinylAudioViz").apply { start() }
        session.handlerThread = handlerThread
        Handler(handlerThread.looper).post {
            // 阶段一：MediaPlayer。**不设任何音源**——只是把实例和它的 audioSessionId 建出来，
            // 等 playUri() 来填音源。它失败才是「真的没法播放」。
            val mediaPlayer = try {
                MediaPlayer().also {
                    session.mediaPlayer = it   // 强引用，否则会被 GC，见 AudioSession 注释
                }
            } catch (t: Throwable) {
                session.playbackUnavailable = true
                android.util.Log.w(
                    "SpaceVinyl",
                    "MediaPlayer create failed: ${t.javaClass.simpleName}: ${t.message}"
                )
                return@post
            }

            // 阶段二：Visualizer。**必须与阶段一分开 catch**：Visualizer 需要 RECORD_AUDIO，
            // 被拒时只应该让频谱静止，不该让播放功能跟着一起「不可用」——所以这一段
            // 无论如何都不碰 playbackUnavailable。
            try {
                // 新建的 MediaPlayer 在构造时就已分配 audioSessionId，无需先 setDataSource；
                // 后续 playUri() 的 reset()+setDataSource() 也不会改变它，所以这条 Visualizer
                // 绑定一次就能横跨所有切歌（这正是 playUri 用 reset 而非新建实例的原因）。
                val visualizer = Visualizer(mediaPlayer.audioSessionId)
                session.visualizer = visualizer    // 同上：不持有就会在几秒后停止回调

                // 频段划分要等第一次回调拿到真实 samplingRate 才能算（实测本机是 48 kHz，
                // 不是常被默认的 44.1 kHz）。
                val bandLo = IntArray(BAND_COUNT)
                val bandHi = IntArray(BAND_COUNT)
                val bandsReady = BooleanArray(1)
                // 每频段自适应量程的上下沿（dB），只在这个回调线程里读写。
                val floorDb = FloatArray(BAND_COUNT)
                val ceilDb = FloatArray(BAND_COUNT) { AGC_MIN_RANGE_DB }
                val lastFftNanos = LongArray(1)

                visualizer.setEnabled(false)
                visualizer.captureSize = Visualizer.getCaptureSizeRange()[1]
                visualizer.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            val data = fft ?: return
                            // 数据布局：data[0]=DC 实部、data[1]=Nyquist 实部，其后每个 bin k 占
                            // data[2k]/data[2k+1] 的实部/虚部。所以 bin 数是 size/2，bin k 的频率
                            // 是 k * binHz。
                            val binCount = data.size / 2
                            if (binCount < BAND_COUNT + 2) return
                            // samplingRate 单位是毫赫兹。binHz = (sr/2) / (size/2) = sr / size。
                            val binHz = (samplingRate / 1000f) / data.size
                            if (binHz <= 0f) return

                            if (!bandsReady[0]) {
                                computeBandEdges(binHz, binCount, bandLo, bandHi)
                                bandsReady[0] = true
                                android.util.Log.w(
                                    "SpaceVinyl",
                                    "fft sr=${samplingRate / 1000}Hz size=${data.size} bins=$binCount " +
                                        "binHz=${"%.2f".format(binHz)} bands=" +
                                        (0 until BAND_COUNT).joinToString(",") {
                                            "${(bandLo[it] * binHz).toInt()}-${(bandHi[it] * binHz).toInt()}"
                                        }
                                )
                            }

                            // 量程跟踪要用真实时间间隔，不能假设固定回调率。
                            val now = SystemClock.elapsedRealtimeNanos()
                            val dt = if (lastFftNanos[0] == 0L) 0.05f
                            else ((now - lastFftNanos[0]) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.5f)
                            lastFftNanos[0] = now

                            for (k in 0 until BAND_COUNT) {
                                var sum = 0f
                                var count = 0
                                for (bin in bandLo[k] until bandHi[k]) {
                                    val re = data[2 * bin].toInt()
                                    val im = data[2 * bin + 1].toInt()
                                    sum += sqrt((re * re + im * im).toFloat())
                                    count++
                                }
                                val avg = if (count > 0) sum / count else 1f
                                // 幅度恒为 1.0（量化噪底）时 db 恒为 0，量程收敛到最小值，
                                // agc 恒为 0 —— 死频段诚实地保持不动，这是有意的。
                                val db = 20f * log10(max(avg, 1f))

                                if (db < floorDb[k]) floorDb[k] = db
                                else floorDb[k] += AGC_FLOOR_RISE_DB_PER_SEC * dt
                                if (db > ceilDb[k]) ceilDb[k] = db
                                else ceilDb[k] -= AGC_CEIL_FALL_DB_PER_SEC * dt
                                if (ceilDb[k] < floorDb[k] + AGC_MIN_RANGE_DB) {
                                    ceilDb[k] = floorDb[k] + AGC_MIN_RANGE_DB
                                }

                                val agc = (db - floorDb[k]) / (ceilDb[k] - floorDb[k])
                                val absolute = (db - ABS_LO_DB) / (ABS_HI_DB - ABS_LO_DB)
                                bandTarget[k] = (W_AGC * agc + W_ABS * absolute).coerceIn(0f, 1f)
                            }
                        }
                    },
                    // 不再主动减半：没有理由把本来就只有 20 Hz 的更新率再砍掉一半。
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true
                )
                visualizer.enabled = true
            } catch (t: Throwable) {
                // 兜底捕获 Throwable：早期只 catch SecurityException，结果构造 Visualizer 抛的
                // 异常没被接住，在子线程直接把整个进程崩掉了。
                // **这里绝不能置 playbackUnavailable**：Visualizer 需要 RECORD_AUDIO，被拒时
                // 播放本身完全正常，只是频谱不动——把播放标记成不可用是错的。
                android.util.Log.w(
                    "SpaceVinyl",
                    "Visualizer init failed (spectrum disabled, playback unaffected): " +
                        "${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    } catch (t: Throwable) {
        session.playbackUnavailable = true
        android.util.Log.w("SpaceVinyl", "Audio pipeline setup failed: ${t.message}")
    }
}

/**
 * 第 [index] 根柱子在弧上的角度（弧度）。唱机后方 200° 单弧，正前方留空。
 *
 * index 0（最低频）在弧的左端 θ≈173°，index 31（最高频）在右端 θ≈367°(=7°)，
 * 中段绕过正后方。低频到高频从左扫到右，是标准 EQ 读法。
 */
private fun barAngleRad(index: Int): Float {
    val deg = ARC_START_DEG + ((index + 0.5f) / BAND_COUNT) * ARC_SWEEP_DEG
    return deg * (PI.toFloat() / 180f)
}

// design-style: fixed-model-derived-color —— 频谱梯度，取自唱机黄铜色板（契约 v4 §4）。
// 这是可视化自身的色彩语言，不是 UI 皮肤，不随 PicoTheme 主题切换。
private val BAR_BRONZE = floatArrayOf(0.541f, 0.353f, 0.141f)   // #8A5A24
private val BAR_BRASS = floatArrayOf(0.894f, 0.631f, 0.267f)    // #E4A144，与面板强调色同色
private val BAR_HOT = floatArrayOf(1.000f, 0.914f, 0.753f)      // #FFE9C0

/** 能量 → 颜色。低能量青铜、中段黄铜、高能量暖白。 */
private fun energyColor(e: Float): Color4 {
    val (from, to, u) = if (e < 0.5f) {
        Triple(BAR_BRONZE, BAR_BRASS, e / 0.5f)
    } else {
        Triple(BAR_BRASS, BAR_HOT, (e - 0.5f) / 0.5f)
    }
    return Color4(
        from[0] + (to[0] - from[0]) * u,
        from[1] + (to[1] - from[1]) * u,
        from[2] + (to[2] - from[2]) * u,
        1f
    )
}

/** 峰值块：始终亮色，不随能量变——它的作用是标出近期峰值，颜色再变就读不出来了。 */
private fun createPeakCapEntity(): Entity {
    // 薄圆盘：柱体是圆柱，峰值块用方块会显得不协调
    val mesh = MeshResource.createCylinder(PEAK_CAP_HEIGHT, PEAK_CAP_RADIUS)
    // design-style: fixed-model-derived-color —— #FFF3DC
    val material = UnlitMaterial.create().apply { setBaseColor(Color4(1f, 0.953f, 0.863f, 1f)) }
    val cap = Entity()
    cap.components.set(ModelComponent(mesh, material))
    return cap
}

// ─────────────────────────────────────────────────────────────────────────────
// 控制面板 v3 —— 尺寸/配色的唯一依据是 .spatialsdk/design-contract.md §3。
// 改这里的任何数字，请同步改契约并升版本号，不要只改代码。
// ─────────────────────────────────────────────────────────────────────────────

// 面板整体
private val DOCK_W = 512.dp        // 契约 §3.2 逐项累加：510 → 取 512（见下方 DOCK_WIDTH_BREAKDOWN）
private val DOCK_H = 104.dp        // 主按钮 64 + 上下内边距 20×2
private val DOCK_RADIUS = 36.dp
private val DOCK_PAD_H = 20.dp
private val DOCK_GAP_SECTION = 16.dp   // 区块之间
private val DOCK_GAP_TRANSPORT = 8.dp  // 传输键之间

// 曲目信息块
private val TRACK_W = 180.dp       // 定宽：歌名长短变化时整条面板不抖动
private val TRACK_BAR_H = 3.dp
private val TRACK_BAR_RADIUS = 2.dp

// 按钮
private val PLAY_SIZE = 64.dp
private val PLAY_ICON = 26.dp
private val TRANSPORT_ICON = 20.dp
private val FX_BTN_W = 96.dp
private val FX_BTN_H = 40.dp
private val FX_CHEVRON = 12.dp
private val DIVIDER_H = 28.dp

// 菜单
private val MENU_W = 220.dp
// 歌单菜单：用户反馈 380×60 过宽过大，整体缩到 **0.6**。
// 这些常量与特效菜单分开（特效菜单保持原尺寸，用户只说歌单大）。
// 注意连带影响：项高 60→36 之后放不下 bodyMedium+bodySmall 两行，字号必须同步降一级
// （改用 bodySmall + bodyTiny），否则文字会被裁。
private val SONG_MENU_W = 228.dp        // 380 × 0.6
private val SONG_ITEM_H = 36.dp         // 60 × 0.6
private val SONG_MENU_RADIUS = 12.dp    // 20 × 0.6
private val SONG_MENU_PAD = 5.dp        // 8 × 0.6
private val SONG_ITEM_RADIUS = 7.dp     // 12 × 0.6
private val SONG_CHECK = 10.dp          // 16 × 0.6
/** 未选择曲目时的占位文案（v5.2 起不再有内置默认曲目）。 */
private const val NO_TRACK_TITLE = "未选择曲目"
private const val NO_TRACK_HINT = "点击切歌键选择本地音乐"
private val MENU_RADIUS = 20.dp
private val MENU_PAD = 8.dp
private val MENU_ITEM_H = 48.dp
private val MENU_ITEM_RADIUS = 12.dp
private val MENU_DOT = 8.dp
private val MENU_CHECK = 16.dp

// 播放列表面板专属布局尺寸（v5.5）。宽度/行高沿用上面 SONG_MENU_* / SONG_ITEM_* 系列，这里只补
// 面板自己新增的标题行高、列表最大可见高度（超过就滚动，避免面板随歌单数量无限变高）、关闭图标尺寸。
private val PLAYLIST_HEADER_H = 48.dp
private val PLAYLIST_LIST_MAX_H = SONG_ITEM_H * 6
private val PLAYLIST_CLOSE_ICON = 14.dp

// 宽度推导（契约 §3.2）。IconButtonDefaults.Regular 实测是 56×48dp（不是 v3 初稿写的 48×48），
// 按契约的让路条款"优先用组件默认值、再回填表格并重算总宽"，用 56 计入。
// v4.9 把「上一首 / 下一首」合并成单个「切歌」键，去掉 56 + 8，总宽 574 → 510 → 取 512：
//   20 + 180 + 16 + 1 + 16 + 64 + 8 + 56 + 16 + 1 + 16 + 96 + 20 = 510
private const val DOCK_WIDTH_BREAKDOWN =
    "20+180+16+1+16+64+8+56+16+1+16+96+20=510"

// design-style: fixed-model-derived-color —— 黄铜强调色。
// PicoTheme 没有黄铜类角色，而本版要求配色跟唱机模型走（用户已确认），二者只能取其一。
// 取值依据：胡桃木贴图实测 H≈15–30°/S 27–43%/L 15–26%，取同色相族提亮提饱和至 H35/S75/L58。
private val BRASS = Color(0xFFE4A144)
// design-style: fixed-model-derived-color —— 黄铜底上的深色图标，保证对比度
private val BRASS_INK = Color(0xFF241608)

/**
 * 特效档位。`implemented = false` 的项**不会出现在菜单里**（v5.3 起，见 [EffectMenuButton]）。
 *
 * 枚举项刻意保留而不是删掉：它们是「计划中但本版不做」的记录，把 `implemented` 改回 true
 * 就会自动重新出现在菜单里，不需要再动菜单代码。
 *
 * 决策沿革：v4.1 曾选择「置灰占位、保留槽位」，理由是保留可发现性、也不动刚验证过的面板尺寸；
 * v5.3 用户要求隐藏——一个点不动的选项对用户没有信息价值，反而像是坏了。
 */
private enum class EffectKind(val label: String, val dotColor: Color, val implemented: Boolean) {
    // design-style: fixed-model-derived-color —— 色点代表各特效在场景里的实际色调
    Spectrum("频谱", Color(0xFF8FD14F), true),    // barRing 全色相扫描，取中段绿作代表色
    Aurora("极光", Color(0xFF7FD4C1), false),     // 尚未实现，契约 §3.5 明确本版不做
    // v4.1：用户要求移除唱机上方的粒子律动效果，`particleSwirl` 已删除。
    // 日后要重做「律动」时把 implemented 改回 true 即可。
    Rhythm("律动", Color(0xFFFFD98C), false),
}

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
    // 空标题 = 还没选歌（v5.2 起不再有内置默认曲目）。没有音源时播放键必须不可用，
    // 否则点它只是个假动作：MediaPlayer 没有 data source，start() 会抛异常被吞掉。
    val hasTrack = title.isNotBlank()

    Box(
        modifier = Modifier
            .size(DOCK_W, DOCK_H)
            .clip(RoundedCornerShape(DOCK_RADIUS))
            // SDK 原生玻璃，不再叠纯色把玻璃盖掉（spatial-ui-design-style 硬规则）
            .backgroundMaterial(true, Material.Regular),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DOCK_PAD_H),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackInfo(title = title, artist = artist, progress = progress)

            Spacer(Modifier.width(DOCK_GAP_SECTION))
            DockDivider()
            Spacer(Modifier.width(DOCK_GAP_SECTION))

            // 主操作：全场唯一的实心黄铜块。未选歌时置灰且不可点（enabled = false 让
            // clickable 自身不再响应，不用靠空 lambda 假装禁用）。
            Box(
                modifier = Modifier
                    .size(PLAY_SIZE)
                    .clip(CircleShape)
                    .background(if (hasTrack) BRASS else BRASS.copy(alpha = 0.3f))
                    .clickable(enabled = hasTrack, onClick = onTogglePlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_dock_pause else R.drawable.ic_dock_play
                    ),
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(PLAY_ICON),
                    tint = if (hasTrack) BRASS_INK else BRASS_INK.copy(alpha = 0.45f)
                )
            }
            Spacer(Modifier.width(DOCK_GAP_TRANSPORT))

            // 切歌键 → 打开独立的播放列表 AttachmentPanel（v5.5，取代原锚定 Menu 下拉，
            // 见 docs/superpowers/specs/2026-08-20-playlist-panel-design.md）。
            PlaylistToggleButton(
                open = playlistOpen,
                onToggle = onTogglePlaylist,
            )

            Spacer(Modifier.width(DOCK_GAP_SECTION))
            DockDivider()
            Spacer(Modifier.width(DOCK_GAP_SECTION))

            EffectMenuButton(
                active = activeEffect,
                open = menuOpen,
                onToggle = { menuOpen = !menuOpen },
                onPick = { activeEffect = it; menuOpen = false },
                onDismiss = { menuOpen = false }
            )
        }
    }
}

@Composable
private fun TrackInfo(title: String, artist: String, progress: Float) {
    // 空标题 = 未选歌。占位文案用 labelTertiary（弱化层级），不要用 labelPrimary
    // 假装那是一首真的曲目——那正是上一版「显示着夜神游但根本没在播」的观感来源。
    val hasTrack = title.isNotBlank()
    Column(modifier = Modifier.width(TRACK_W)) {
        Text(
            text = if (hasTrack) title else NO_TRACK_TITLE,
            color = if (hasTrack) PicoTheme.colorScheme.labelPrimary
            else PicoTheme.colorScheme.labelTertiary,
            style = PicoTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasTrack) artist else NO_TRACK_HINT,
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRACK_BAR_H)
                .clip(RoundedCornerShape(TRACK_BAR_RADIUS))
                .background(PicoTheme.colorScheme.fillTertiary)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(TRACK_BAR_RADIUS))
                    .background(BRASS)   // design-style: fixed-model-derived-color
            )
        }
    }
}

@Composable
private fun DockDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(DIVIDER_H)
            .background(PicoTheme.colorScheme.dividerLine)
    )
}

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
    Column(
        modifier = Modifier
            .width(SONG_MENU_W)
            .clip(RoundedCornerShape(SONG_MENU_RADIUS))
            .backgroundMaterial(true, Material.Regular)
            .padding(SONG_MENU_PAD)
    ) {
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

@Composable
private fun EffectMenuButton(
    active: EffectKind,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (EffectKind) -> Unit,
    onDismiss: () -> Unit,
) {
    Box {
        Row(
            modifier = Modifier
                .size(FX_BTN_W, FX_BTN_H)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    // design-style: fixed-model-derived-color（激活态底 16% alpha）
                    if (open) BRASS.copy(alpha = 0.16f) else PicoTheme.colorScheme.fillTertiary
                )
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = active.label,
                color = if (open) BRASS else PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyMedium,
                maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(
                    if (open) R.drawable.ic_dock_chevron_up else R.drawable.ic_dock_chevron_down
                ),
                contentDescription = null,
                modifier = Modifier.size(FX_CHEVRON),
                tint = if (open) BRASS else PicoTheme.colorScheme.labelSecondary
            )
        }

        if (open) {
            Menu(
                onDismissRequest = onDismiss,
                modifier = Modifier.width(MENU_W),
                cornerRadius = MENU_RADIUS,
                padding = PaddingValues(MENU_PAD),
            ) {
                // v5.3：未实现的特效从菜单里**隐藏**（用户要求），不再做置灰占位。
                // 枚举项本身保留（见 EffectKind），所以这里是过滤而不是删项——
                // 日后把 implemented 改回 true，它就自动重新出现在菜单里。
                //
                // 连带简化：菜单里出现的项现在**一定可选**，所以下面不再有任何
                // 置灰 / 「未实现」徽标 / onClick = null 的分支。留着那些分支就是死代码，
                // 会让人误以为菜单还会渲染不可选项。
                EffectKind.entries.filter { it.implemented }.forEach { fx ->
                    val selected = fx == active
                    MenuItem(
                        title = {
                            Text(
                                text = fx.label,
                                color = if (selected) BRASS else PicoTheme.colorScheme.labelPrimary,
                                style = PicoTheme.typography.bodyMedium
                            )
                        },
                        modifier = Modifier.height(MENU_ITEM_H),
                        cornerSize = MENU_ITEM_RADIUS,
                        onClick = { onPick(fx) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(MENU_DOT)
                                    .clip(CircleShape)
                                    .background(fx.dotColor)
                            )
                        },
                        trailingIcon = if (selected) ({
                            Icon(
                                painter = painterResource(R.drawable.ic_dock_check),
                                contentDescription = null,
                                modifier = Modifier.size(MENU_CHECK),
                                tint = BRASS
                            )
                        }) else null
                    )
                }
            }
        }
    }
}
