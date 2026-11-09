package net.torosaba.core.module.teleportspectator

import net.torosaba.core.Module
import net.torosaba.core.legacyText
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionDefault
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 旧 TeleportSpectator。
 * /stp <プレイヤー> でスペクテイターになってその人にテレポートし、cooldown tick 後に元のゲームモードに戻す。
 * Essentials があれば、テレポートを拒否している相手には飛べない。
 */
class TeleportSpectatorModule : Module("teleport-spectator", "TeleportSpectator"), Listener {

    private class Pending(val origin: Location, val previous: GameMode, val task: BukkitTask)

    // 元のゲームモードに戻す待ちのプレイヤー
    private val pending = mutableMapOf<UUID, Pending>()

    private var essentials: EssentialsTeleport? = null

    override fun onEnable() {
        essentials = if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) EssentialsTeleport() else null
        permission("teleportspectator.use", PermissionDefault.TRUE, "スペクテイターテレポートを使う")
        command("stp", permission = "teleportspectator.use", description = "/stp [player]") { sender, _, args ->
            if (sender is Player) {
                stp(sender, args)
            } else {
                sender.sendMessage("§cプレイヤーのみ実行できます")
                true
            }
        }
        listen(this)
    }

    override fun onDisable() {
        // 戻す前に止めるときは、元の位置とゲームモードに戻す
        for ((id, p) in pending) {
            val player = Bukkit.getPlayer(id) ?: continue
            player.teleport(p.origin)
            player.gameMode = p.previous
        }
        pending.clear()
        essentials = null
    }

    private fun stp(sender: Player, args: Array<String>): Boolean {
        val error = "§4" + config.getString("error.default")
        val target = args.firstOrNull()?.let { Bukkit.matchPlayer(it).firstOrNull() }
        if (target == null) {
            sender.sendMessage(error)
            return false
        }
        if (essentials?.isTeleportEnabled(target) == false) {
            sender.sendMessage(("§4" + config.getString("error.teleportBlock")).format(target.displayName().legacyText()))
            return false
        }

        // 戻す待ちの間にもう一度使ったときは、最初の元の位置とゲームモードを維持する
        val prevPending = pending.remove(sender.uniqueId)?.also { it.task.cancel() }
        val previous = prevPending?.previous ?: sender.gameMode
        val origin = prevPending?.origin ?: sender.location.clone()

        sender.gameMode = GameMode.SPECTATOR
        val cooldown = (config.getInt("cooldown").toLong()).coerceAtLeast(1L)
        val task = runLater(cooldown) {
            val p = pending.remove(sender.uniqueId) ?: return@runLater
            sender.teleport(p.origin)
            sender.gameMode = p.previous
            sender.sendMessage(config.getString("result.cooldown") ?: "")
        }
        pending[sender.uniqueId] = Pending(origin, previous, task)
        sender.teleport(target.location)
        sender.gameMode = GameMode.SPECTATOR
        sender.sendMessage((config.getString("result.teleport") ?: "").format(target.displayName().legacyText()))
        return true
    }

    // 戻す前に抜けたら、元の位置とゲームモードに戻す
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val p = pending.remove(event.player.uniqueId) ?: return
        p.task.cancel()
        event.player.teleport(p.origin)
        event.player.gameMode = p.previous
    }
}
