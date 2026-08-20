# 设计：播完自动随机切下一首（排除已播放）

- 日期：2026-08-19
- 状态：已获用户确认
- 影响文件：`app/src/main/java/tech/illusion/spacevinyl/content/HomeVolume.kt`、
  `app/src/main/java/tech/illusion/spacevinyl/content/ShufflePlaylist.kt`（新增）、
  `app/src/test/java/tech/illusion/spacevinyl/content/PickUnplayedTest.kt`
- 关联契约：`.spatialsdk/design-contract.md`（本功能落地后升 v5.4）

## 1. 需求

用户原话：「播放完一首歌后能否让其随机播放下一首（随机播放列表排除已播放的曲目）？」

即：一首播放自然结束后，从本地歌单里**随机**挑一首**本轮还没播过**的曲目继续播放。

### 已确认的产品决策

| 决策点 | 结论 | 备注 |
|---|---|---|
| 一轮全部放完后 | **停止播放** | 用户明确选择，不是无限循环 |
| 停止时是否清空已播放集合 | **清空** | 见 §5，否则功能会自锁 |
| 是否加随机/顺序 UI 开关 | **不加** | YAGNI；面板 512dp 宽度是逐项累加值，改动需重新上机验尺寸 |
| 已播放集合是否持久化 | **不持久化** | 仅进程内，重启即新一轮 |
| 「上一首」历史栈 | **不做** | 需求未提 |

## 2. 前提改造：拆掉单曲循环

`HomeVolume.kt` 现有 `mp.isLooping = true`（`playUri` 内）。

**单曲循环状态下 `OnCompletionListener` 永远不会触发**，所以这是实现自动切歌的**硬前提**：
必须先改为 `isLooping = false` 并挂上 completion 回调。

## 3. 新增 `ShuffleQueue`

职责单一：持有歌单快照与已播放集合，回答「下一首是谁」。

```kotlin
private class ShuffleQueue {
    fun setTracks(list: List<SongItem>)   // 歌单刷新时同步快照
    fun markPlayed(uri: Uri)              // 由 playUri 统一调用，见 §4
    fun nextRandom(): SongItem?           // null = 本轮已放完
    fun reset()                           // 清空已播放集合
}
```

### 为什么必须独立持有快照，不能直接读 `songs`

自动切歌发生在 **MediaPlayer 回调线程**（音频 HandlerThread），而 `songs` 是
`mutableStateListOf<SongItem>`——Compose 状态，属主线程。跨线程直接读 Compose 状态列表不安全，
所以音频线程必须有一份自己能安全访问的快照。

### 为什么这里必须加锁

本文件对 `bandTarget` 等频谱数据有意采用「不加锁」的取舍——理由是可视化用途，读到上一帧的值
不影响正确性。**播放列表状态不适用该取舍**：`played` 集合与 `tracks` 快照会被主线程
（`setTracks`、手动选歌的 `markPlayed`）与音频线程（`onCompletion` → `nextRandom`/`markPlayed`）
同时访问，抽错会造成**重复播放或提前停止**，是有真实用户可见后果的。

因此 `ShuffleQueue` 的所有方法用 `synchronized` 保护内部状态。这是本设计里唯一需要加锁的地方。

### 用 `uri` 而非 `title` 作为集合 key

`SongItem.uri` 是 MediaStore 的唯一标识，能正确区分同名曲目；`title` 会把重名歌误判为同一首。

## 4. 记账时机与自动切歌流程

### 记账规则：**开始播放时记账，不是播完时记账**

统一在 `playUri()` 内部 `markPlayed(uri)`，手动选歌与自动切歌共用同一条路径。

为什么不在 `onCompletion` 里记账：那样「中途被手动切走、没听完」的曲目永远不会入账，
之后仍会被随机抽到，与「排除已播放」的意图冲突。以「开始播过」为准更健壮，规则也只有一条。

### 流程

```
歌曲自然播完
  → onCompletion（音频线程）
  → nextRandom()                      // 当前曲目已在 playUri 时入账，不会被抽中
      ├─ 返回歌曲 → playUri(它)        // 复用现有切歌路径，内部自行 markPlayed
      └─ 返回 null → 停止播放 + reset()
```

复用现有 `playUri()` 而不新写一条播放路径：它已经处理了 `reset()` + `setDataSource()` +
`prepareAsync()`，以及「`reset()` 不改变 `audioSessionId`，所以 Visualizer 绑定一次即可横跨切歌」
这条关键不变量。

## 5. 停止时清空已播放集合（关键决策）

若停止时**不**清空，已播放集合会永久保持「全满」状态，于是用户之后手动选任何一首歌，
放完都会立刻再次停止——功能自锁，体验退化。

语义定义：**「停」是一轮结束的一次性信号，不是永久关闭自动连播。** 清空之后，用户手动选歌或
点播放键即自然进入新一轮。

## 6. UI 状态同步

自动切歌时面板曲目名/艺人必须跟着变，但切歌决策发生在音频线程。

**走帧循环轮询**：`AudioSession` 暴露 volatile 的「当前曲目」字段，帧循环发现变化时同步到
Compose 状态。这与现有 `progress` 轮询（`PROGRESS_UPDATE_INTERVAL_SEC`）是同一个模式——
帧循环已经是「从音频层拉状态到 UI」的既有通道，不新开线程跳转路径。

切歌是低频事件，不违反项目关于「高频传感数据不要绕 2D UI」的约束。

## 7. 停止时的面板表现

| 元素 | 表现 | 来源 |
|---|---|---|
| 唱盘 | 缓动停转 | 现有逻辑跟 `isPlayingNow` |
| 频谱 | 冻结在当前高度 | 同上 |
| 曲目名 | 保留最后一首 | 让用户知道刚放完什么 |
| 播放键 | 回到 ▶ 且可点 | `hasTrack` 仍为 true |
| 进度条 | 满格 | `currentPosition == duration` |

`wantPlaying` 必须一并置 `false`——否则切后台再回前台，`ON_RESUME` 会把
`wasPlayingBeforeBackground` 当真而误续播。

点播放键会重播最后那首；因已播放集合已清空，它放完即进入新一轮随机。

## 8. 边界情况

| 情况 | 行为 |
|---|---|
| 歌单为空 | `nextRandom()` 返回 null，不动作 |
| 歌单只有 1 首 | 放完即停并重置，等效手动重播 |
| 播放中歌单被重查（权限变化触发） | `setTracks` 更新快照；已播放集合按 `uri` 继续有效 |
| 用户手动暂停 | 不产生 completion 事件，无影响 |
| 用户手动选歌 | 开始播放即计入已播放集合（§4 规则） |
| 用户中途手动切走、没听完 | 同样已入账，不会再被随机抽到（§4 规则的直接后果） |
| `playUri` 对某首失败 | 现有 `playbackUnavailable` 机制把 `uiPlaying` 拉回 false |

## 9. 验证计划

真机（`PB311XKGL4160087B`）为准，因空间容器无法截图（`screencap` 失败）、体积窗口无法用
`adb input tap` 驱动，故**自动切歌与停止两处需用户实机确认**，agent 侧提供日志证据。

- 编译 + 安装 + 启动无崩溃
- logcat 应出现连续的 `playUri: content://...`（每次自动切歌一条），且 uri **不重复**
- 一轮放完时应出现停止日志，且此后不再有 `playUri`
- 用户确认：听到自动换歌、唱盘持续转、放完全部后停下

## 10. 明确不做

- 随机/顺序模式开关（UI 与契约 §3.2 宽度表都要改）
- 已播放集合持久化
- 「上一首」历史栈
- 跨轮的加权随机 / 避免连续同艺人等策略
