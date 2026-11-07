package net.torosaba.core.module.mapdirty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirtyFeedBufferTest {
    private fun snapshot(start: Long, end: Long, world: String, vararg chunks: Pair<Int, Int>): WindowSnapshot =
        WindowSnapshot(
            start,
            end,
            mapOf(world to mapOf(DirtySource.BUKKIT to chunks.map { ChunkMath.pack(it.first, it.second) }.toSet())),
        )

    @Test
    fun `窓をまたいでも重なったチャンクは 1 つに潰れる`() {
        val buffer = DirtyFeedBuffer()
        buffer.offer(snapshot(0, 10, "world", 0 to 0, 1 to 0))
        buffer.offer(snapshot(10, 20, "world", 1 to 0, 2 to 0))
        assertEquals(3, buffer.size())
        val taken = assertNotNull(buffer.take(100))
        // 期間は最初の窓の始まりから最後の窓の終わりまで
        assertEquals(0L, taken.startMillis)
        assertEquals(20L, taken.endMillis)
        assertEquals(3, taken.count(DirtySource.BUKKIT))
        assertTrue(buffer.isEmpty())
        assertNull(buffer.take(100))
    }

    @Test
    fun `経路の区別は落ちて 1 つにまとまる`() {
        val buffer = DirtyFeedBuffer()
        buffer.offer(
            WindowSnapshot(
                0,
                10,
                mapOf(
                    "world" to mapOf(
                        DirtySource.BUKKIT to setOf(ChunkMath.pack(0, 0)),
                        DirtySource.WORLDEDIT to setOf(ChunkMath.pack(0, 0), ChunkMath.pack(5, 5)),
                    )
                ),
            )
        )
        assertEquals(2, buffer.size())
        val taken = assertNotNull(buffer.take(100))
        assertEquals(2, taken.worlds["world"]?.values?.sumOf { it.size })
    }

    @Test
    fun `1 通の上限を超えた分は残る`() {
        val buffer = DirtyFeedBuffer()
        buffer.offer(snapshot(0, 10, "world", 0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0))
        val first = assertNotNull(buffer.take(2))
        assertEquals(2, first.count(DirtySource.BUKKIT))
        assertEquals(3, buffer.size())
        val rest = assertNotNull(buffer.take(100))
        assertEquals(3, rest.count(DirtySource.BUKKIT))
        // 取り出した分と残りを合わせると元の集合に戻る
        val all = (first.worlds["world"]!!.values.single() + rest.worlds["world"]!!.values.single())
        assertEquals((0..4).map { ChunkMath.pack(it, 0) }.toSet(), all)
    }

    @Test
    fun `ワールドが分かれていても上限の範囲で収まる分だけ取る`() {
        val buffer = DirtyFeedBuffer()
        buffer.offer(snapshot(0, 10, "world", 0 to 0, 1 to 0))
        buffer.offer(snapshot(0, 10, "world_nether", 0 to 0))
        val taken = assertNotNull(buffer.take(2))
        assertEquals(2, taken.count(DirtySource.BUKKIT))
        assertEquals(1, buffer.size())
    }

    @Test
    fun `送信用の JSON は種別を持ち記録と同じ中身になる`() {
        val json = snapshot(5, 15, "world", 2 to -1).toFeedJson()
        assertTrue(json.startsWith("{\"t\":\"dirty\",\"start\":5,\"end\":15"), json)
        assertTrue(json.contains("\"world\":{\"bukkit\":[[2,-1]]}"), json)
    }

    @Test
    fun `リージョンへの集約`() {
        // リージョンは 32x32 チャンク。境界と負の座標で床方向に丸める
        assertEquals(0, ChunkMath.regionOf(0))
        assertEquals(0, ChunkMath.regionOf(31))
        assertEquals(1, ChunkMath.regionOf(32))
        assertEquals(-1, ChunkMath.regionOf(-1))
        assertEquals(-1, ChunkMath.regionOf(-32))
        assertEquals(-2, ChunkMath.regionOf(-33))
        val regions = ChunkMath.regionsOf(
            listOf(
                ChunkMath.pack(0, 0),
                ChunkMath.pack(31, 31), // 同じリージョン (0, 0)
                ChunkMath.pack(32, 0), // リージョン (1, 0)
                ChunkMath.pack(-1, -1), // リージョン (-1, -1)
            )
        )
        assertEquals(setOf(ChunkMath.pack(0, 0), ChunkMath.pack(1, 0), ChunkMath.pack(-1, -1)), regions)
    }
}
