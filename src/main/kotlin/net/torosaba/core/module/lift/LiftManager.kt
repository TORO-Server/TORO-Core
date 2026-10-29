// Lift (croxis, LGPL-3.0-or-later) の BukkitElevatorManager / BukkitLiftRedstoneListener の動作を Kotlin で書き直したもの
package net.torosaba.core.module.lift

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.WallSign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Entity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.util.Vector
import java.util.UUID

/** エレベーターを調べ、動かし、止める */
internal class LiftManager(private val module: LiftModule) {
    private val settings get() = module.settings

    /** 動いているエレベーター */
    val elevators = LinkedHashSet<Elevator>()

    /** 下りで乗っている間、落下ダメージを消す */
    private val fallers = HashSet<UUID>()

    /** 乗る前から飛べたプレイヤー (降りたあとも飛べるままにする) */
    private val fliers = HashSet<UUID>()

    // ---- 調べる ----

    fun isBaseBlock(block: Block) = block.type in settings.blockSpeeds
    fun isButton(block: Block) = block.type in settings.buttonMaterials

    /** 昇降路として通れるブロックか (土台は含まない) */
    fun isValidShaftBlock(block: Block): Boolean {
        val type = block.type
        return type in settings.floorMaterials || type.isAir || !type.isSolid || type in SHAFT_EXTRA || type.name.endsWith("WALL_SIGN")
    }

    /** [button] の下の土台を探し、エレベーターを組み立てる。土台が無いか途中がふさがっていれば null */
    fun createLift(button: Block, cause: String): Elevator? {
        val start = System.currentTimeMillis()
        module.debug("Starting elevator gen caused by: $cause")
        val world = button.world
        var y = button.y - 1
        var baseBlock: Block? = null
        while (y >= world.minHeight) {
            if (y == world.minHeight) {
                module.debug("No elevator base found")
                return null
            }
            val check = world.getBlockAt(button.x, y, button.z)
            if (isValidShaftBlock(check)) {
                y--
                continue
            }
            if (isBaseBlock(check)) {
                baseBlock = check
                break
            }
            module.debug("Shaft obstructed at y $y by ${check.type}")
            return null
        }
        if (baseBlock == null) return null
        val elevator = scan(baseBlock).first
        module.debug("Elevator gen took: ${System.currentTimeMillis() - start} ms.")
        return elevator
    }

    /** 土台のブロックから、つながった土台と階を調べる。2 つ目はふさがっていた場所の説明 */
    fun scan(baseBlock: Block): Pair<Elevator, String> {
        val world = baseBlock.world
        val baseType = baseBlock.type
        val baseY = baseBlock.y
        val columns = ShaftScan.baseColumns(ShaftScan.Column(baseBlock.x, baseBlock.z), settings.maxLiftArea) {
            world.getBlockAt(it.x, baseY, it.z).type == baseType
        }
        val floorOk: ((Int) -> Boolean)? = if (settings.checkFloor) { y ->
            columns.all { c ->
                val type = world.getBlockAt(c.x, y, c.z).type
                type in settings.floorMaterials || type in settings.blockSpeeds || type.isAir
            }
        } else null
        val result = ShaftScan.floors(columns, baseY, settings.maxHeight, world.maxHeight, { x, y, z ->
            val block = world.getBlockAt(x, y, z)
            when {
                !isValidShaftBlock(block) -> ShaftScan.Cell.BLOCKED
                isButton(block) -> ShaftScan.Cell.BUTTON
                else -> ShaftScan.Cell.OPEN
            }
        }, floorOk)

        val floors = result.buttonYs.mapIndexed { index, y ->
            val button = columns.firstNotNullOfOrNull { c -> world.getBlockAt(c.x, y, c.z).takeIf(::isButton) }
                ?: world.getBlockAt(baseBlock.x, y, baseBlock.z)
            Floor(index + 1, y, button, floorName(button))
        }
        val report = result.blockages.joinToString("") { (x, y, z) -> " | $x $y $z of type ${world.getBlockAt(x, y, z).type}" }
        return Elevator(world, baseType, settings.blockSpeeds[baseType] ?: 0.0, baseY, columns, floors) to report
    }

    /** 階の名前。ボタンの下の看板 (古い書き方) か、上の看板の 2 行目 */
    private fun floorName(button: Block): String {
        val below = button.getRelative(BlockFace.DOWN)
        if (below.type.name.endsWith("WALL_SIGN")) {
            val lines = (below.state as? Sign)?.let(::signLines) ?: return ""
            return lines[0].ifEmpty { lines[1] }
        }
        val above = button.getRelative(BlockFace.UP)
        if (above.type.name.endsWith("WALL_SIGN")) {
            val lines = (above.state as? Sign)?.let(::signLines) ?: return ""
            return liftSign(lines).currentName
        }
        return ""
    }

    fun liftSign(lines: List<String>) = LiftSign(settings.currentFloor, settings.destination, lines)

    fun isInALift(entity: Entity) = elevators.any { it.isInLift(entity) }

    // ---- 動かし始める ----

    /** ボタンが押されたとき。看板の行先へ、その階にいる人や物を運ぶ */
    fun activate(button: Block, reason: String) {
        val startTime = System.currentTimeMillis()
        val elevator = createLift(button, reason) ?: return
        if (elevator.totalFloors < 2) return
        module.listener.onElevatorActivated(elevator)

        val sign = button.getRelative(BlockFace.UP).state as? Sign ?: return
        val destination = liftSign(signLines(sign)).destinationFloor
        // 同じ昇降路のエレベーターが動いていれば何もしない
        if (elevators.any { it.sharesBase(elevator) }) return

        val startFloor = elevator.floorAtY(button.y)
        val destFloor = elevator.floorN(destination)
        if (startFloor == null || destFloor == null) {
            module.debug("Start floor or destination floor not found (destination $destination)")
            return
        }
        elevator.startFloor = startFloor
        elevator.destFloor = destFloor
        elevator.goingUp = destFloor.y > startFloor.y
        module.debug("Elevator start floor: ${startFloor.number} (y ${startFloor.y}), destination: ${destFloor.number} (y ${destFloor.y})")

        val baseIterator = elevator.base.iterator()
        for (entity in elevator.world.getNearbyEntities(elevator.shaftBox())) {
            if (!settings.liftMobs && entity !is Player) continue
            if (elevator.isInShaftAtFloor(entity, startFloor)) {
                if (isInALift(entity)) {
                    if (entity is Player) entity.sendMessage("You are already in a lift. Relog in case this is an error.")
                    continue
                }
                if (entity is Minecart) {
                    elevator.minecartSpeeds[entity] = entity.velocity
                    // レールに乗ったトロッコは上がらないので、少しの間レールを外す
                    val rail = entity.location.block
                    if (elevator.goingUp && rail.type in RAILS) {
                        val type = rail.type
                        module.runLater(10L) { rail.type = type }
                        rail.type = Material.AIR
                    }
                }
                if (entity is Player && !entity.hasPermission("lift")) {
                    addHolder(elevator, entity, entity.location)
                    continue
                }
                addPassenger(elevator, entity)
                if (settings.autoPlace && baseIterator.hasNext()) {
                    val c = baseIterator.next()
                    val loc = entity.location
                    entity.teleport(Location(entity.world, c.x + 0.5, loc.y, c.z + 0.5, loc.yaw, loc.pitch), TeleportCause.UNKNOWN)
                }
            } else if (elevator.isInShaft(entity) && !entity.isInsideVehicle) {
                // 動く昇降路の、違う階にいるものはその場に止める
                addHolder(elevator, entity, entity.location)
            }
        }

        // 通り抜ける階の床 (ガラス) を外す
        val range = if (elevator.goingUp) startFloor.number + 1..destFloor.number else destFloor.number + 1..startFloor.number
        for (n in range) {
            val floor = elevator.floorN(n) ?: continue
            for (c in elevator.base) {
                val glass = elevator.world.getBlockAt(c.x, floor.y - 2, c.z)
                elevator.floorBlocks[glass.location] = glass.state
                val above = glass.getRelative(BlockFace.UP)
                if (above.type.name.contains("CARPET") || above.type in RAILS || above.type == Material.REDSTONE_WIRE) {
                    elevator.aboveFloorBlocks[above.location] = above.state
                    above.type = Material.AIR
                }
                glass.type = Material.AIR
            }
        }

        elevator.start(settings.secondsUntilTimeout)
        elevators += elevator
        module.debug("Going ${if (elevator.goingUp) "up" else "down"}, passengers: ${elevator.passengers.size + elevator.holders.size}, took ${System.currentTimeMillis() - startTime}ms")
    }

    private fun addPassenger(elevator: Elevator, entity: Entity) {
        if (entity is Player) setupPlayer(entity)
        if (entity is Vehicle) entity.passengers.filterIsInstance<Player>().forEach(::setupPlayer)
        elevator.passengers += entity
        if (!elevator.goingUp) fallers += entity.uniqueId
        entity.setGravity(false)
    }

    private fun addHolder(elevator: Elevator, entity: Entity, location: Location) {
        if (entity is Player) setupPlayer(entity)
        elevator.holders[entity] = location
        if (!elevator.goingUp) fallers += entity.uniqueId
    }

    /** 動いている間に飛行で蹴られないよう、重力を切って飛行を許す */
    private fun setupPlayer(player: Player) {
        if (player.allowFlight) fliers += player.uniqueId else fliers -= player.uniqueId
        player.setGravity(false)
        player.allowFlight = true
    }

    private fun restorePlayer(player: Player) {
        player.setGravity(true)
        fallers -= player.uniqueId
        if (!fliers.remove(player.uniqueId)) player.allowFlight = false
    }

    // ---- 毎 tick ----

    fun tick() {
        val iterator = elevators.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e.passengers.isEmpty()) {
                iterator.remove()
                endLift(e)
                continue
            }
            // 長く動きすぎた (どこかで引っかかった) ときは、行先へテレポートして終える
            if (System.currentTimeMillis() > e.maxEndTime) {
                module.debug("Ending lift due to timeout.")
                tpPassengersToDest(e)
                iterator.remove()
                endLift(e)
                continue
            }

            val passengers = e.passengers.iterator()
            while (passengers.hasNext()) {
                val passenger = passengers.next()
                if (!passenger.isValid) {
                    passengers.remove()
                    continue
                }
                if (!e.isInShaft(passenger)) {
                    if (settings.preventLeave) {
                        if (passenger is Player) passenger.sendMessage(settings.cantLeave)
                        val base = e.base.first()
                        val loc = passenger.location.apply {
                            x = base.x + 0.5
                            z = base.z + 0.5
                        }
                        passenger.teleport(loc, TeleportCause.UNKNOWN)
                    } else {
                        passengers.remove()
                        restore(passenger)
                        continue
                    }
                }
                // 速さはすぐ落ちるので毎 tick かけ直す
                if (!passenger.isInsideVehicle) {
                    passenger.velocity = Vector(0.0, if (e.destFloor.number > e.startFloor.number) e.speed else -e.speed, 0.0)
                }
                passenger.fallDistance = 0f

                if (reachedDestination(e, passenger)) {
                    val hold = passenger.location.clone().apply { y = e.destFloor.y - 0.5 }
                    passengers.remove()
                    e.holders[passenger] = hold
                }
            }
            for ((holder, location) in e.holders) freeze(holder, location)
        }
    }

    private fun reachedDestination(e: Elevator, passenger: Entity): Boolean {
        val y = passenger.location.y
        return (e.goingUp && y > e.destFloor.y - 0.7) || (!e.goingUp && y < e.destFloor.y - 0.6)
    }

    private fun freeze(entity: Entity, location: Location) {
        entity.velocity = Vector(0, 0, 0)
        entity.fallDistance = 0f
        entity.teleport(location, TeleportCause.UNKNOWN)
    }

    /** 乗客を行先の階の、昇降路の真ん中へテレポートする */
    private fun tpPassengersToDest(e: Elevator) {
        for (passenger in e.passengers) {
            val loc = passenger.location.apply {
                y = e.destFloor.y.toDouble()
                x = e.centerX
                z = e.centerZ
            }
            passenger.teleport(loc, TeleportCause.UNKNOWN)
            passenger.fallDistance = 0f
            if (passenger is Player) passenger.sendMessage("§7Lift timeout - You have been teleported to destination.")
        }
    }

    /** 止める。外した床を戻し、乗客を元の状態に戻し、着いた階に信号を出す */
    private fun endLift(e: Elevator) {
        module.debug("Halting lift $e")
        e.floorBlocks.values.forEach { it.update(true) }
        e.aboveFloorBlocks.values.forEach { it.update(true) }

        for (entity in e.passengers + e.holders.keys) {
            entity.fallDistance = 0f
            fallers -= entity.uniqueId
            entity.setGravity(true)
            when (entity) {
                is Player -> {
                    entity.velocity = Vector(0, 0, 0)
                    restorePlayer(entity)
                }
                is Minecart -> entity.velocity = e.minecartSpeeds[entity] ?: Vector(0, 0, 0)
                else -> if (entity in e.passengers) entity.velocity = Vector(0, 0, 0)
            }
            if (entity is Vehicle) entity.passengers.filterIsInstance<Player>().forEach(::restorePlayer)
        }

        // 着いた階の看板の裏 (壁の向こう) のブロックに信号を出す
        val aboveButton = e.destFloor.button.getRelative(BlockFace.UP)
        val data = aboveButton.blockData
        if (data is WallSign) {
            val face = data.facing.oppositeFace
            val target = aboveButton.getRelative(face).getRelative(face)
            val targetData = target.blockData
            if (targetData is Powerable) {
                targetData.isPowered = true
                target.blockData = targetData
            }
        } else {
            module.debug("Expected sign but found ${aboveButton.type} at ${aboveButton.location}. Cannot set redstone pulse.")
        }
        e.passengers.clear()
        e.holders.clear()
        module.debug("Ended lift (took ${System.currentTimeMillis() - e.startTime}ms)")
    }

    /** 1 つのものをエレベーターから降ろす (昇降路を出た・拾われた・ログアウトしたなど) */
    fun release(entity: Entity) {
        val e = elevators.find { it.isInLift(entity) } ?: return
        e.remove(entity)
        restore(entity)
    }

    private fun restore(entity: Entity) {
        entity.velocity = Vector(0, 0, 0)
        entity.fallDistance = 0f
        entity.setGravity(true)
        if (entity is Player) restorePlayer(entity)
    }

    /** 落下ダメージを消すなら true (1 回だけ) */
    fun consumeFaller(entity: Entity): Boolean = fallers.remove(entity.uniqueId)

    /** 止めるとき (モジュールの停止・再読み込み) は、乗客を行先へ送ってから終える */
    fun endAll() {
        for (e in elevators.toList()) {
            tpPassengersToDest(e)
            endLift(e)
        }
        elevators.clear()
        fallers.clear()
        fliers.clear()
    }

    companion object {
        private val legacy = LegacyComponentSerializer.legacySection()

        val RAILS = setOf(Material.RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL, Material.POWERED_RAIL)

        /** isSolid でなくても通れるものとして元が挙げていたもの */
        private val SHAFT_EXTRA = setOf(Material.LADDER, Material.SNOW, Material.VINE, Material.WATER, Material.REDSTONE_WIRE) + RAILS

        /** 看板の表の 4 行を、§ の色コード付きの文字列で */
        fun signLines(sign: Sign): List<String> = sign.getSide(Side.FRONT).lines().map(legacy::serialize)

        fun writeSign(sign: Sign, lines: List<String>) {
            val side = sign.getSide(Side.FRONT)
            lines.forEachIndexed { i, line -> side.line(i, legacy.deserialize(line)) }
            sign.update()
        }
    }
}
