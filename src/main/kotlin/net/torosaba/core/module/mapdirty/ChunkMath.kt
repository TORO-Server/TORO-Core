package net.torosaba.core.module.mapdirty

/** チャンク座標の変換。チャンクは (x, z) を 1 つの Long に詰めて集合で持つ */
object ChunkMath {
    /** ブロック座標から、そのブロックを含むチャンクの座標 (負の座標でも床方向に丸める) */
    fun chunkOf(block: Int): Int = block shr 4

    fun pack(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFF_FFFFL)

    fun unpackX(packed: Long): Int = (packed shr 32).toInt()

    fun unpackZ(packed: Long): Int = packed.toInt()

    /** ブロック範囲 [minX..maxX] x [minZ..maxZ] と重なるチャンクの集合 */
    fun chunksInBlockRange(minX: Int, minZ: Int, maxX: Int, maxZ: Int): Set<Long> {
        if (minX > maxX || minZ > maxZ) return emptySet()
        val result = HashSet<Long>()
        for (cx in chunkOf(minX)..chunkOf(maxX)) {
            for (cz in chunkOf(minZ)..chunkOf(maxZ)) {
                result += pack(cx, cz)
            }
        }
        return result
    }
}
