package net.torosaba.core.module.mapdirty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirtyWindowTest {
    @Test
    fun `チャンクへの集約と経路の内訳`() {
        var now = 0L
        val window = DirtyWindow(10_000) { now }
        // 同じチャンクのブロックは 1 つにまとまる
        window.add("world", 0, 0, DirtySource.BUKKIT)
        window.add("world", 15, 15, DirtySource.BUKKIT)
        window.add("world", 16, 0, DirtySource.BUKKIT) // チャンク (1, 0)
        window.add("world", 100, 100, DirtySource.WORLDEDIT) // チャンク (6, 6)
        now = 10_000
        val snapshot = assertNotNull(window.takeIfDue())
        assertEquals(0L, snapshot.startMillis)
        assertEquals(10_000L, snapshot.endMillis)
        assertEquals(2, snapshot.count(DirtySource.BUKKIT))
        assertEquals(1, snapshot.count(DirtySource.WORLDEDIT))
        assertEquals(setOf(ChunkMath.pack(0, 0), ChunkMath.pack(1, 0)), snapshot.worlds["world"]?.get(DirtySource.BUKKIT))
        // 取り出した後は空になる
        assertNull(window.takeIfDue())
        assertNull(window.flush())
    }

    @Test
    fun `負のブロック座標は床方向のチャンクになる`() {
        assertEquals(0, ChunkMath.chunkOf(0))
        assertEquals(0, ChunkMath.chunkOf(15))
        assertEquals(1, ChunkMath.chunkOf(16))
        assertEquals(-1, ChunkMath.chunkOf(-1))
        assertEquals(-1, ChunkMath.chunkOf(-16))
        assertEquals(-2, ChunkMath.chunkOf(-17))
        // pack と unpack は往復する
        val packed = ChunkMath.pack(-3, 7)
        assertEquals(-3, ChunkMath.unpackX(packed))
        assertEquals(7, ChunkMath.unpackZ(packed))
    }

    @Test
    fun `窓は最初の変更から数え、閉じるまでは取り出せない`() {
        var now = 5_000L
        val window = DirtyWindow(10_000) { now }
        assertNull(window.takeIfDue()) // まだ何も無い
        window.add("world", 0, 0, DirtySource.BUKKIT)
        now = 14_999
        assertNull(window.takeIfDue())
        now = 15_000
        assertEquals(5_000L, assertNotNull(window.takeIfDue()).startMillis)
        // 次の窓は次の変更から始まる
        now = 20_000
        window.add("world", 0, 0, DirtySource.BUKKIT)
        now = 29_999
        assertNull(window.takeIfDue())
        now = 30_000
        assertEquals(20_000L, assertNotNull(window.takeIfDue()).startMillis)
    }

    @Test
    fun `flush は窓の途中でも取り出す`() {
        var now = 1_000L
        val window = DirtyWindow(60_000) { now }
        assertNull(window.flush())
        window.add("world", 0, 0, DirtySource.BUKKIT)
        now = 2_000
        val snapshot = assertNotNull(window.flush())
        assertEquals(1_000L, snapshot.startMillis)
        assertEquals(2_000L, snapshot.endMillis)
        assertNull(window.flush())
    }

    @Test
    fun `WorldEdit の範囲からチャンク集合への変換`() {
        // 1 チャンクぴったり
        assertEquals(setOf(ChunkMath.pack(0, 0)), ChunkMath.chunksInBlockRange(0, 0, 15, 15))
        // チャンク境界をまたぐと 4 チャンク
        assertEquals(
            setOf(ChunkMath.pack(0, 0), ChunkMath.pack(1, 0), ChunkMath.pack(0, 1), ChunkMath.pack(1, 1)),
            ChunkMath.chunksInBlockRange(8, 8, 23, 23),
        )
        // 負の範囲
        assertEquals(setOf(ChunkMath.pack(-1, -1)), ChunkMath.chunksInBlockRange(-16, -16, -1, -1))
        assertEquals(
            setOf(ChunkMath.pack(-1, -1), ChunkMath.pack(0, -1), ChunkMath.pack(-1, 0), ChunkMath.pack(0, 0)),
            ChunkMath.chunksInBlockRange(-8, -8, 7, 7),
        )
        // 範囲の全ブロックを 1 つずつ集めた結果 (WorldEdit の extent がたどる経路) と一致する
        val viaBlocks = HashSet<Long>()
        for (x in -8..7) for (z in -8..7) {
            viaBlocks += ChunkMath.pack(ChunkMath.chunkOf(x), ChunkMath.chunkOf(z))
        }
        assertEquals(ChunkMath.chunksInBlockRange(-8, -8, 7, 7), viaBlocks)
        // 逆向きの範囲は空
        assertTrue(ChunkMath.chunksInBlockRange(10, 10, 0, 0).isEmpty())
    }

    @Test
    fun `記録の JSON にワールドと経路別のチャンクが入る`() {
        var now = 0L
        val window = DirtyWindow(1_000) { now }
        window.add("world", 32, -16, DirtySource.BUKKIT) // チャンク (2, -1)
        window.add("world", 32, -16, DirtySource.WORLDEDIT)
        window.add("world_nether", 0, 0, DirtySource.BUKKIT)
        now = 1_000
        val json = assertNotNull(window.takeIfDue()).toJson()
        assertTrue(json.contains("\"start\":0,\"end\":1000"))
        assertTrue(json.contains("\"world\":{\"bukkit\":[[2,-1]]"))
        assertTrue(json.contains("\"worldedit\":[[2,-1]]"))
        assertTrue(json.contains("\"world_nether\":{\"bukkit\":[[0,0]]}"))
        assertTrue(json.contains("\"counts\":{\"bukkit\":2,\"worldedit\":1}"))
    }
}
