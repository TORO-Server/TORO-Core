package net.torosaba.core.module.dynmapworldguard

import net.torosaba.core.Module
import net.torosaba.core.TOROCore
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import java.util.logging.Level

/**
 * 旧 Dynmap-WorldGuard。WorldGuard の領域を dynmap の地図に描く。
 * 設定のキーは元のプラグインと同じ。dynmap か WorldGuard が無いときは何もしない。
 */
class DynmapWorldGuardModule : Module("dynmap-worldguard", "Dynmap-WorldGuard") {
    private var layer: RegionLayer? = null

    override fun onServerLoad(plugin: TOROCore) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return
        try {
            BoostFlag.register(plugin.logger)
        } catch (e: LinkageError) {
            plugin.logger.log(Level.WARNING, "WorldGuard のフラグ ${BoostFlag.NAME} を登録できませんでした", e)
        }
    }

    override fun onEnable() {
        listen(object : Listener {
            /** dynmap か WorldGuard が後から有効になったときに描き始める */
            @EventHandler(priority = EventPriority.MONITOR)
            fun onPluginEnable(event: PluginEnableEvent) {
                if (event.plugin.name == "dynmap" || event.plugin.name == "WorldGuard") activate()
            }
        })
        activate()
    }

    override fun onDisable() {
        layer?.stop()
        layer = null
    }

    private fun activate() {
        if (layer != null) return
        val pm = Bukkit.getPluginManager()
        if (pm.getPlugin("dynmap")?.isEnabled != true || pm.getPlugin("WorldGuard")?.isEnabled != true) return
        layer = try {
            WorldGuardRegionLayer.start(this, RegionMapSettings(config))
        } catch (e: LinkageError) {
            logger.log(Level.WARNING, "dynmap か WorldGuard と連携できませんでした。領域は地図に描きません", e)
            return
        }
        if (layer == null) {
            logger.info("dynmap はありますが、印を描く API を使えないので領域は描きません")
            return
        }
        logger.info("WorldGuard の領域を dynmap に描きます")
    }
}
