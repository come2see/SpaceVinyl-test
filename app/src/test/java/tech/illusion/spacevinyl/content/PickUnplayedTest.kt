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

    // 「一轮放完每首恰好一次、然后停止」是这个功能的核心契约，但它是多次抽取叠加在
    // 不断变化的 played 集合上才浮现的性质，单次抽取的用例测不出提前停止或漏抽这类
    // off-by-one 错误。真机上的自动切歌/整轮停止目前又验证不到（该设备 screencap 对
    // 空间窗口失败、adb input tap 驱动不了体积窗口），所以这条不变量两侧都还没有证据——
    // 但这里可以在零生产代码改动下直接对 pickUnplayed 跑完整一轮来补上，
    // 不需要引入 android.net.Uri 或 Robolectric。
    @Test
    fun `a round visits every item exactly once, then stops`() {
        val all = listOf("a", "b", "c", "d")
        val played = mutableSetOf<String>()
        val order = mutableListOf<String>()
        while (true) {
            val next = pickUnplayed(all, played, Random(7)) ?: break
            played += next          // 对应生产代码「开始播放即记账」的时机
            order += next
        }
        assertEquals(all.size, order.size)          // 没有提前停止
        assertEquals(all.toSet(), order.toSet())    // 没有重复、没有漏掉
    }
}
