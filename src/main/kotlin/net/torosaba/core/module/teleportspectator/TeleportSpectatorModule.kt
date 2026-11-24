package net.torosaba.core.module.teleportspectator

import net.torosaba.core.Module
import net.torosaba.core.legacyText
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.permissions.PermissionDefault
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/** ターゲット選択の可視性フィルタ (テストできるよう Bukkit に触らない部分) */
internal object SpectatorTargetSelector {
    fun <T> selectVisible(candidates: List<T>, canSee: (T) -> Boolean): T? =
        candidates.firstOrNull(canSee)
}

/**
 * 旧 TeleportSpectator。
 * /stp <プレイヤー> でスペクテイターになってその人にテレポートし、cooldown tick 後に元のゲームモードに戻す。
 * Essentials があれば、テレポートを拒否している相手には飛べない。
 */
class TeleportSpectatorModule : Module("teleport-spectator", "TeleportSpectator"), Listener {

    private class Pending(val origin: Location, val previous: GameMode, val task: BukkitTask)
    private class DeferredRestore(val origin: Location, val previous: GameMode)

    // 元のゲームモードに戻す待ちのプレイヤー
    private val pending = mutableMapOf<UUID, Pending>()
    // 退出・死亡・テレポート失敗により次回参加/リスポーン時に戻すプレイヤー
    private val deferredRestore = mutableMapOf<UUID, DeferredRestore>()

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
            p.task.cancel()
            val player = Bukkit.getPlayer(id) ?: continue
            if (player.teleport(p.origin)) {
                player.gameMode = p.previous
            }
        }
        pending.clear()
        deferredRestore.clear()
        essentials = null
    }

    private fun stp(sender: Player, args: Array<String>): Boolean {
        val error = "§4" + config.getString("error.default")
        val target = args.firstOrNull()?.let { arg ->
            SpectatorTargetSelector.selectVisible(Bukkit.matchPlayer(arg)) { sender.canSee(it) }
        }
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
        deferredRestore.remove(sender.uniqueId)
        val previous = prevPending?.previous ?: sender.gameMode
        val origin = prevPending?.origin ?: sender.location.clone()

        if (!sender.teleport(target.location)) {
            if (prevPending != null) {
                scheduleReturn(sender.uniqueId, origin, previous, 1L, 0)
            }
            sender.sendMessage(error)
            return false
        }
        sender.gameMode = GameMode.SPECTATOR
        val cooldown = (config.getInt("cooldown").toLong()).coerceAtLeast(1L)
        scheduleReturn(sender.uniqueId, origin, previous, cooldown, 0)
        sender.sendMessage((config.getString("result.teleport") ?: "").format(target.displayName().legacyText()))
        return true
    }

    private fun scheduleReturn(uuid: UUID, origin: Location, previous: GameMode, delayTicks: Long, attempt: Int) {
        val task = runLater(delayTicks) {
            val p = pending.remove(uuid) ?: return@runLater
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline || player.isDead) {
                deferredRestore[uuid] = DeferredRestore(p.origin, p.previous)
                return@runLater
            }
            player.leaveVehicle()
            if (player.teleport(p.origin)) {
                player.gameMode = p.previous
                player.sendMessage(config.getString("result.cooldown") ?: "")
            } else if (attempt < MAX_RETURN_RETRIES) {
                scheduleReturn(uuid, p.origin, p.previous, RETRY_DELAY_TICKS, attempt + 1)
            } else {
                // テレポートが失敗し続けた場合は相手の位置に元のゲームモードを残さない
                val safeMode = Bukkit.getServer().defaultGameMode.takeIf { it != GameMode.CREATIVE } ?: GameMode.SPECTATOR
                player.gameMode = safeMode
                deferredRestore[uuid] = DeferredRestore(p.origin, p.previous)
            }
        }
        pending[uuid] = Pending(origin, previous, task)
    }

    // 戻す前に抜けたら、次回の参加時に元の位置とゲームモードに戻す
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val p = pending.remove(uuid) ?: return
        p.task.cancel()
        deferredRestore[uuid] = DeferredRestore(p.origin, p.previous)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val d = deferredRestore.remove(uuid) ?: return
        runLater(1L) {
            val player = Bukkit.getPlayer(uuid) ?: return@runLater
            if (player.teleport(d.origin)) {
                player.gameMode = d.previous
            } else {
                deferredRestore[uuid] = d
            }
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val uuid = event.player.uniqueId
        val d = deferredRestore.remove(uuid) ?: return
        event.respawnLocation = d.origin
        runLater(1L) {
            val player = Bukkit.getPlayer(uuid) ?: return@runLater
            player.gameMode = d.previous
        }
    }

    private companion object {
        const val MAX_RETURN_RETRIES = 5
        const val RETRY_DELAY_TICKS = 20L
    }
}
