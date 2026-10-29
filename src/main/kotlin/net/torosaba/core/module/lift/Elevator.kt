// Lift (croxis, LGPL-3.0-or-later) の BukkitElevator / Floor を Kotlin で書き直したもの
package net.torosaba.core.module.lift

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID

/** 1 つの階。[y] はボタンの高さ (床のガラスは y - 2、立つ高さは y - 1) */
internal class Floor(val number: Int, val y: Int, val button: Block, val name: String)

/** 調べ上げた 1 つのエレベーター。動かすと乗客などの状態も持つ */
internal class Elevator(
    val world: World,
    val baseType: Material,
    val speed: Double,
    val baseY: Int,
    val base: Set<ShaftScan.Column>,
    /** 下から順の階 (number は 1 から) */
    val floors: List<Floor>,
) {
    val id: String = UUID.randomUUID().toString().take(6)

    val passengers = LinkedHashSet<Entity>()
    val holders = LinkedHashMap<Entity, Location>()
    val floorBlocks = LinkedHashMap<Location, BlockState>()
    val aboveFloorBlocks = LinkedHashMap<Location, BlockState>()
    val minecartSpeeds = HashMap<Entity, Vector>()

    lateinit var startFloor: Floor
    lateinit var destFloor: Floor
    var goingUp = false
    var startTime = 0L
    var maxEndTime = 0L
    var centerX = 0.0
    var centerZ = 0.0

    val totalFloors: Int get() = floors.size

    fun floorAtY(y: Int): Floor? = floors.find { it.y == y }
    fun floorN(n: Int): Floor? = floors.getOrNull(n - 1)

    fun sharesBase(other: Elevator): Boolean = world == other.world && base.any { it in other.base }

    /** 昇降路 (土台の列の上、いちばん上の階 + 3 まで) の中にいるか */
    fun isInShaft(entity: Entity): Boolean {
        val loc = entity.location
        if (loc.world != world) return false
        val top = floors.lastOrNull()?.y ?: return false
        return loc.y >= baseY - 1.0 && loc.y <= top + 3.0 && ShaftScan.Column(loc.blockX, loc.blockZ) in base
    }

    /** 昇降路の中の、その階の高さにいるか (乗り物に乗っているときは乗り物の高さを引く) */
    fun isInShaftAtFloor(entity: Entity, floor: Floor): Boolean {
        if (!isInShaft(entity)) return false
        var y = entity.location.y
        entity.vehicle?.let { y -= it.height }
        return y >= floor.y - 1 && y <= floor.y
    }

    fun isInLift(entity: Entity): Boolean = entity in passengers || entity in holders

    fun remove(entity: Entity) {
        entity.fallDistance = 0f
        if (!passengers.remove(entity)) holders.remove(entity)
    }

    /** 昇降路の範囲 (乗る人を探すのに使う) */
    fun shaftBox(): BoundingBox {
        val top = floors.lastOrNull()?.y ?: baseY
        return BoundingBox(
            base.minOf { it.x }.toDouble(), baseY - 1.0, base.minOf { it.z }.toDouble(),
            base.maxOf { it.x } + 1.0, top + 4.0, base.maxOf { it.z } + 1.0,
        )
    }

    /** 動かし始める。時間切れの時刻と、昇降路の真ん中を決める */
    fun start(secondsUntilTimeout: Int) {
        startTime = System.currentTimeMillis()
        // 速さ 1 で 1 ブロックおよそ 0.05 秒
        val rideDuration = (Math.abs(startFloor.y - destFloor.y) * 0.05 * 1000L / speed).toLong()
        maxEndTime = startTime + rideDuration + secondsUntilTimeout * 1000L
        centerX = base.sumOf { it.x }.toDouble() / base.size + 0.5
        centerZ = base.sumOf { it.z }.toDouble() / base.size + 0.5
    }

    override fun toString() = "Elevator[$id]"
}
