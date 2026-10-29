// Lift (croxis, LGPL-3.0-or-later) の BukkitLiftPlayerListener / BukkitVehicleListener / BukkitLift#onPlayerMove の動作を Kotlin で書き直したもの
package net.torosaba.core.module.lift

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

internal class LiftListener(private val module: LiftModule) : Listener {
    private val settings get() = module.settings
    private val manager get() = module.manager

    /** マウスホイールで行先を選んでいる最中のプレイヤー */
    private class ScrollSelection(val elevator: Elevator, val liftSign: LiftSign, val sign: Sign, val currentFloor: Floor)

    private val scrolling = HashMap<UUID, ScrollSelection>()

    // ---- 看板で行先を選ぶ ----

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        // 右クリックは手ごとに 2 回来るので、利き手の分だけ扱う
        if (event.hand == EquipmentSlot.OFF_HAND) return
        val player = event.player
        if (!player.hasPermission("lift.change")) return
        val clicked = event.clickedBlock ?: return
        if (clicked.type !in settings.signMaterials) return
        val button = clicked.getRelative(BlockFace.DOWN)
        if (!manager.isButton(button)) return
        val sign = clicked.state as? Sign ?: return

        val elevator = manager.createLift(button, player.name) ?: return
        if (elevator.totalFloors < 1) return // ただのボタンと看板
        if (elevator.totalFloors == 1) {
            player.sendMessage(settings.oneFloor)
            return
        }
        // ここで読むのは、ボタンの上の看板がほかのプラグインのものかもしれないから
        val liftSign = manager.liftSign(LiftManager.signLines(sign))
        // 看板の編集画面を開かせない
        if (event.action == Action.RIGHT_CLICK_BLOCK) event.setUseInteractedBlock(Event.Result.DENY)

        if (scrolling.remove(player.uniqueId) != null) {
            event.isCancelled = true
            player.sendMessage(settings.scrollSelectDisabled)
            return
        }
        val currentFloor = elevator.floorAtY(button.y)
        if (currentFloor == null) {
            player.sendMessage("Elevator generator says this floor does not exist. Check shaft for blockage")
            return
        }

        // 素手ならホイールで選ぶ。何か持っていれば次の階へ進める
        if (settings.mouseScroll && player.inventory.itemInMainHand.type == Material.AIR && !liftSign.isEmpty) {
            scrolling[player.uniqueId] = ScrollSelection(elevator, liftSign, sign, currentFloor)
            player.sendMessage(settings.scrollSelectEnabled)
        } else {
            changeDestination(elevator, liftSign, sign, currentFloor, 1)
        }
    }

    @EventHandler
    fun onHeldItemChange(event: PlayerItemHeldEvent) {
        val player = event.player
        val selection = scrolling[player.uniqueId] ?: return
        // 回した向き (0 と 8 の間は回り込み)
        val step = when {
            event.previousSlot == 0 && event.newSlot == 8 -> -1
            event.previousSlot == 8 && event.newSlot == 0 -> 1
            event.newSlot > event.previousSlot -> 1
            event.newSlot < event.previousSlot -> -1
            else -> return
        }
        val signLoc = selection.sign.location
        if (signLoc.world != player.world || player.location.distance(signLoc) > 3) {
            removeSelection(player)
            player.sendMessage(settings.scrollSelectDisabled)
            return
        }
        changeDestination(selection.elevator, selection.liftSign, selection.sign, selection.currentFloor, step)
    }

    private fun changeDestination(elevator: Elevator, liftSign: LiftSign, sign: Sign, current: Floor, step: Int) {
        liftSign.setCurrentFloor(current.number)
        val dest = FloorCycle.next(liftSign.destinationFloor, current.number, elevator.totalFloors, step)
        liftSign.setDestinationFloor(dest)
        liftSign.setDestinationName(elevator.floorN(dest)?.name ?: "")
        LiftManager.writeSign(sign, liftSign.lines)
        module.debug("Completed sign update")
    }

    /** エレベーターが動き出したら、同じ昇降路でホイールを使っている人の選択を終える */
    fun onElevatorActivated(elevator: Elevator) {
        scrolling.entries.removeIf { it.value.elevator.sharesBase(elevator) }
    }

    private fun removeSelection(player: Player) {
        scrolling.remove(player.uniqueId)
    }

    // ---- ボタンを押したとき ----

    @EventHandler
    fun onBlockRedstoneChange(event: BlockRedstoneEvent) {
        // 押したとき (信号が入ったとき) だけ
        if (event.oldCurrent != 0 || event.newCurrent <= 0) return
        val block = event.block
        if (manager.isButton(block) && block.getRelative(BlockFace.UP).type in settings.signMaterials) {
            manager.activate(block, "Button press")
            return
        }
        if (!settings.redstone) return
        // redstone: true なら、2 つ隣までの看板付きのボタンも動かす
        val button = redstoneTarget(block) ?: return
        manager.activate(button, "Redstone signal event")
    }

    private fun redstoneTarget(block: Block): Block? {
        for (face in SIDES) {
            val b = block.getRelative(face)
            for (face2 in SIDES) {
                val b2 = b.getRelative(face2)
                if (manager.isButton(b2) && b2.getRelative(BlockFace.UP).type in settings.signMaterials) return b2
            }
        }
        return null
    }

    // ---- 動いている間 ----

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (!settings.preventEntry || manager.elevators.isEmpty()) return
        val player = event.player
        for (e in manager.elevators) {
            if (e.world != event.to.world) continue
            if (e.isInShaft(player) && !e.isInLift(player)) {
                event.isCancelled = true
                player.sendMessage(settings.cantEnter)
                player.velocity = player.location.direction.multiply(-1)
                return
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        if (!manager.isInALift(event.vehicle)) return
        (event.exited as? Player)?.sendMessage(settings.unsafe)
        event.isCancelled = true
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        if (manager.isInALift(event.item)) manager.release(event.item)
    }

    @EventHandler
    fun onInventoryPickupItem(event: InventoryPickupItemEvent) {
        if (manager.isInALift(event.item)) manager.release(event.item)
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        if (event.cause == EntityDamageEvent.DamageCause.FALL && manager.consumeFaller(event.entity)) event.isCancelled = true
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        manager.release(event.player)
        removeSelection(event.player)
    }

    @EventHandler
    fun onPlayerKick(event: PlayerKickEvent) {
        manager.release(event.player)
        removeSelection(event.player)
    }

    private companion object {
        val SIDES = listOf(BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH)
    }
}
