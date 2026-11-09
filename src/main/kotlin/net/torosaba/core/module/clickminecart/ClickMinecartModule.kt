package net.torosaba.core.module.clickminecart

import net.torosaba.core.Module
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.vehicle.VehicleExitEvent

/**
 * 旧 ClickMinecart。空手でレールを右クリックするとマインカートが現れてそのまま乗れ、
 * 降りるとカートは消える。
 *
 * 元の実装からの意図的な変更は2点だけである。
 * - 降車したのがプレイヤーでない場合は何もしない (元は Player へのキャストで例外になり、
 *   モブが降りるたびに ERROR ログが出ていた)。
 * - 右クリック対象のブロックが無い場合は何もしない (元は NullPointerException になり得た)。
 */
class ClickMinecartModule : Module("click-minecart", "ClickMinecart"), Listener {

    override fun onEnable() {
        listen(this)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player.isInsideVehicle) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (player.inventory.itemInMainHand.type != Material.AIR) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.RAIL && block.type != Material.POWERED_RAIL) return

        val minecart = player.world.spawnEntity(block.location, EntityType.MINECART)
        minecart.addScoreboardTag(TAG)
        minecart.addPassenger(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleLeave(event: VehicleExitEvent) {
        val vehicle = event.vehicle
        if (TAG !in vehicle.scoreboardTags) return
        val player = event.exited as? Player ?: return

        val loc = player.location
        loc.y += 0.5
        vehicle.removePassenger(player)
        vehicle.remove()
        player.teleport(loc)
    }

    private companion object {
        /** このモジュールが出したカートに付ける印 */
        const val TAG = "cminecart"
    }
}
