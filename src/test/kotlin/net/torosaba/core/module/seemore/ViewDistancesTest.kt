package net.torosaba.core.module.seemore

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewDistancesTest {
    @Test
    fun `クライアントの距離を上限で抑え、シミュレーション距離より下げない`() {
        // 上限 64 は 32 に抑える
        assertEquals(32 to 33, ViewDistances.targets(48, 64, 10, 6))
        assertEquals(16 to 17, ViewDistances.targets(16, 64, 10, 6))
        assertEquals(6 to 3, ViewDistances.targets(2, 64, 10, 6))
        // -1 はワールドの描画距離
        assertEquals(10 to 11, ViewDistances.targets(20, -1, 10, 6))
        assertEquals(12 to 13, ViewDistances.targets(20, 12, 10, 6))
    }

    @Test
    fun `実効の平均距離`() {
        assertEquals(10.0, ViewDistances.effective(21 * 21 * 3, 3), 1e-9)
        assertEquals(0.0, ViewDistances.effective(0, 0))
    }
}
