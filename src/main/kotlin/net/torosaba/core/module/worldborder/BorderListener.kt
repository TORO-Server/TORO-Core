// WorldBorder (Brettflan, BSD-2-Clause License) の WBListener / BlockPlaceListener / MobSpawnListener を移植したもの
package net.torosaba.core.module.worldborder

import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.server.PluginEnableEvent

internal class BorderListener(private val module: WorldBorderModule) : Listener {
    private val settings get() = module.settings

    /** テレポートの行き先が境界の外なら、内側へ差し替える */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        if (settings.knockBack == 0.0) return
        if (settings.debug) module.logger.info("Teleport cause: ${event.cause}")
        val newLoc = module.checker.check(event.player, event.to, returnLocationOnly = true, notify = true) ?: return
        if (event.cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && settings.denyEnderpearl) {
            event.isCancelled = true
            return
        }
        event.setTo(newLoc)
    }

    /** ポータルの行き先が境界の外なら、内側へ差し替える */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerPortal(event: PlayerPortalEvent) {
        if (settings.knockBack == 0.0 || !settings.portalRedirection) return
        val newLoc = module.checker.check(event.player, event.to, returnLocationOnly = true, notify = false) ?: return
        event.setTo(newLoc)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!settings.preventBlockPlace) return
        if (outside(event.blockPlaced.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (!settings.preventMobSpawn) return
        if (outside(event.location)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = module.checker.forget(event.player)

    /** dynmap が後から有効になったときに印を出す */
    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "dynmap") module.setupDynmap()
    }

    private fun outside(loc: Location): Boolean {
        val border = settings.borders[loc.world?.name ?: return false] ?: return false
        return !border.insideBorder(loc.x, loc.z, settings.shapeRound)
    }
}
