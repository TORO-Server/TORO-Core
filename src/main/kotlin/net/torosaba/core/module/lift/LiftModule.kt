// Lift (croxis, LGPL-3.0-or-later) の動作を Kotlin で書き直したもの
package net.torosaba.core.module.lift

import net.torosaba.core.Module
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionDefault

/**
 * 旧 Lift。土台のブロック (baseBlockSpeeds) の上の昇降路に、ボタンと看板 ([lift] の看板) を置いた階を作り、
 * 看板で選んだ階へボタンで人や動物を運ぶエレベーター。
 */
class LiftModule : Module("lift", "Lift") {
    internal lateinit var settings: LiftSettings
        private set
    internal lateinit var manager: LiftManager
        private set
    internal lateinit var listener: LiftListener
        private set

    override fun onEnable() {
        permission("lift", PermissionDefault.TRUE, "Can ride in a lift")
        permission("lift.change", PermissionDefault.TRUE, "Can change lift floors")
        permission("lift.reload", PermissionDefault.OP, "Reloads the lift plugin")

        settings = LiftSettings(config, logger)
        manager = LiftManager(this)
        listener = LiftListener(this)
        listen(listener)
        runTimer(1L, 1L) { manager.tick() }

        command("lift", permission = "lift", description = "Scans a lift.", usage = "/<command> [reload]",
            tab = { sender, _, args -> if (args.size == 1 && sender.hasPermission("lift.reload")) listOf("reload") else emptyList() },
        ) { sender, _, args ->
            when {
                args.firstOrNull().equals("reload", ignoreCase = true) -> {
                    if (!sender.hasPermission("lift.reload")) {
                        sender.sendMessage("§cYou don't have permission.")
                        return@command true
                    }
                    manager.endAll()
                    reloadConfig()
                    settings = LiftSettings(config, logger)
                    sender.sendMessage("Lift successfully reloaded")
                    true
                }
                args.isEmpty() && sender is Player -> {
                    scan(sender)
                    true
                }
                else -> false
            }
        }
        debug("maxArea: ${settings.maxLiftArea}, autoPlace: ${settings.autoPlace}, checkFloor: ${settings.checkFloor}, baseBlocks: ${settings.blockSpeeds}")
    }

    override fun onDisable() {
        if (::manager.isInitialized) manager.endAll()
    }

    /** `/lift`。足元の土台からエレベーターを調べて報告する */
    private fun scan(player: Player) {
        val time = System.currentTimeMillis()
        player.sendMessage("Starting scan")
        val below = player.location.block.getRelative(BlockFace.DOWN)
        if (!manager.isBaseBlock(below)) {
            player.sendMessage("Not a valid base block type: ${below.type}")
            player.sendMessage("Options are: ${settings.blockSpeeds}")
            return
        }
        val (elevator, report) = manager.scan(below)
        player.sendMessage("Base block type: ${elevator.baseType} | Size: ${elevator.base.size}")
        player.sendMessage("Floor scan reports: $report")
        player.sendMessage("Floors: " + elevator.floors.joinToString(", ") { "${it.number}${if (it.name.isNotEmpty()) " (${it.name}§r)" else ""}" })
        player.sendMessage("Total time generating elevator: ${System.currentTimeMillis() - time}")
    }

    internal fun debug(message: String) {
        if (settings.debug) logger.info("[DEBUG] $message")
    }
}
