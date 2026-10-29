package net.torosaba.core.module.textbeacon

import org.bukkit.Location
import java.util.UUID

/** ワールド名とブロック座標 */
internal data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {
    constructor(loc: Location) : this(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)
}

/** 看板 1 枚と、入るとメッセージが出るブロックの集まり */
internal class Beacon(
    val id: UUID,
    val sign: BlockKey,
    val message: String,
    val owner: UUID?,
    var ownerName: String?,
) {
    val world: String get() = sign.world
    val blocks = mutableListOf<BlockKey>()
}

internal const val MAX_CUBE_SIZE = 5

/** 座標を 1 つの Long にまとめる (x, z は 26 bit、y は 12 bit) */
internal fun pack(x: Int, y: Int, z: Int): Long =
    ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()

private val FACES = arrayOf(
    intArrayOf(0, 0, -1), intArrayOf(0, 0, 1), intArrayOf(1, 0, 0),
    intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
)

/**
 * [seed] とつながった (面で接する) ブロックを集める。
 * 範囲が各辺 [MAX_CUBE_SIZE] を超えて広がったら null。
 */
internal fun collectConnected(seed: IntArray, isTarget: (Int, Int, Int) -> Boolean): List<IntArray>? {
    var minX = seed[0]; var minY = seed[1]; var minZ = seed[2]
    var maxX = seed[0]; var maxY = seed[1]; var maxZ = seed[2]
    val list = mutableListOf<IntArray>()
    val queue = ArrayDeque<IntArray>()
    val visited = HashSet<Long>()
    queue.add(seed)
    visited.add(pack(seed[0], seed[1], seed[2]))

    while (queue.isNotEmpty()) {
        val (x, y, z) = queue.removeFirst()
        if (!isTarget(x, y, z)) continue
        list += intArrayOf(x, y, z)
        minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
        maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
        if (maxX - minX > MAX_CUBE_SIZE || maxY - minY > MAX_CUBE_SIZE || maxZ - minZ > MAX_CUBE_SIZE) return null
        for ((dx, dy, dz) in FACES) {
            val nx = x + dx; val ny = y + dy; val nz = z + dz
            if (!isTarget(nx, ny, nz)) continue
            if (visited.add(pack(nx, ny, nz))) queue.add(intArrayOf(nx, ny, nz))
        }
    }
    return list
}

/** 各辺が [MAX_CUBE_SIZE] 以内に収まっているか */
internal fun isWithinCube(points: List<IntArray>): Boolean {
    if (points.isEmpty()) return true
    fun span(i: Int) = points.maxOf { it[i] } - points.minOf { it[i] }
    return span(0) <= MAX_CUBE_SIZE && span(1) <= MAX_CUBE_SIZE && span(2) <= MAX_CUBE_SIZE
}
