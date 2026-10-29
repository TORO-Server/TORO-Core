package net.torosaba.core.module.teleportspectator

import net.torosaba.core.Module
import net.torosaba.core.legacyText
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 旧 TeleportSpectator。
 * /stp <プレイヤー> でスペクテイターになってその人にテレポートし、cooldown tick 後に元のゲームモードに戻す。
 * Essentials があれば、テレポートを拒否している相手には飛べない。
 */
class TeleportSpectatorModule : Module("teleport-spectator", "TeleportSpectator"), Listener {

    private class Pending(val previous: GameMode, val task: BukkitTask)

    // 元のゲームモードに戻す待ちのプレイヤー
    private val pending = mutableMapOf<UUID, Pending>()

    private var essentials: EssentialsTeleport? = null

    override fun onEnable() {
        essentials = if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) EssentialsTeleport() else null
        command("stp", description = "/stp [player]") { sender, _, args ->
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
        // 戻す前に止めるときは、その場で元のゲームモードに戻す
        for ((id, p) in pending) Bukkit.getPlayer(id)?.gameMode = p.previous
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

        // 戻す待ちの間にもう一度使ったときは、最初の元のゲームモードに戻す
        val previous = pending.remove(sender.uniqueId)?.also { it.task.cancel() }?.previous ?: sender.gameMode
        sender.gameMode = GameMode.SPECTATOR
        val task = runLater(config.getInt("cooldown").toLong()) {
            pending.remove(sender.uniqueId)
            sender.gameMode = previous
            sender.sendMessage(config.getString("result.cooldown") ?: "")
        }
        pending[sender.uniqueId] = Pending(previous, task)
        sender.teleport(target.location)
        sender.gameMode = GameMode.SPECTATOR
        sender.sendMessage((config.getString("result.teleport") ?: "").format(target.displayName().legacyText()))
        return true
    }

    // 戻す前に抜けたら、その場で元のゲームモードに戻す
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val p = pending.remove(event.player.uniqueId) ?: return
        p.task.cancel()
        event.player.gameMode = p.previous
    }
}
