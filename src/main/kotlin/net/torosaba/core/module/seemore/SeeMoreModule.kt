// SeeMore (froobynooby, MIT License, Copyright (c) 2023 froobynooby) を Kotlin に移植したもの
package net.torosaba.core.module.seemore

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionDefault
import org.bukkit.scheduler.BukkitTask
import java.text.DecimalFormat
import java.util.UUID
import kotlin.math.sqrt

/**
 * 旧 SeeMore。プレイヤーごとの描画距離を、クライアントの描画距離の設定に合わせる。
 * 上限はワールドごとの `maximum-view-distance` (-1 ならワールドの描画距離)。
 * 距離を下げるときは `update-delay` tick 待つ (設定をこまめに変えて負荷をかけられないように)。
 */
class SeeMoreModule : Module("see-more", "SeeMore") {
    private val targetView = HashMap<UUID, Int>()
    private val targetSend = HashMap<UUID, Int>()
    private val viewTasks = HashMap<UUID, BukkitTask>()
    private val sendTasks = HashMap<UUID, BukkitTask>()
    private val logTasks = HashMap<UUID, BukkitTask>()
    private val seen = HashSet<UUID>()

    override fun onEnable() {
        permission("seemore.command.seemore", PermissionDefault.OP, "/seemore を使う")
        for (sub in SUBCOMMANDS) {
            permission("seemore.command.$sub", PermissionDefault.OP, "/seemore $sub を使う", mapOf("seemore.command.seemore" to true))
        }
        permission("seemore.command.*", PermissionDefault.OP, "SeeMore のコマンドをすべて使う",
            (listOf("seemore") + SUBCOMMANDS).associate { "seemore.command.$it" to true })

        listen(Updater())
        command("seemore", permission = "seemore.command.seemore", description = "SeeMore のコマンド",
            tab = { sender, _, args ->
                if (args.size == 1) SUBCOMMANDS.filter { sender.hasPermission("seemore.command.$it") } else emptyList()
            },
        ) { sender, _, args -> onCommand(sender, args); true }

        // 再読み込みのときは、いまいるプレイヤーにもすぐ当てる
        updateAllPlayers()
    }

    override fun onDisable() {
        targetView.clear()
        targetSend.clear()
        viewTasks.clear()
        sendTasks.clear()
        logTasks.clear()
        seen.clear()
    }

    // ---- 設定 ----

    private val updateDelay: Long get() = config.getLong("update-delay")
    private val logChanges: Boolean get() = config.getBoolean("log-changes")

    /** ワールドの上限。そのワールドの指定が無ければ default の値 */
    private fun maximumViewDistance(world: World): Int {
        val key = "world-settings.${world.name}.maximum-view-distance"
        return if (config.isSet(key)) config.getInt(key) else config.getInt("world-settings.default.maximum-view-distance", -1)
    }

    // ---- 描画距離の制御 ----

    private fun updateAllPlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            setTarget(player, player.clientViewDistance, testDelay = false, initial = true)
        }
    }

    private fun setTarget(player: Player, clientViewDistance: Int, testDelay: Boolean, initial: Boolean) {
        val world = player.world
        val (view, send) = ViewDistances.targets(clientViewDistance, maximumViewDistance(world), world.viewDistance, world.simulationDistance)
        val id = player.uniqueId
        targetView[id] = view
        targetSend[id] = send

        // 下げるときだけ待つ
        val delay = try {
            if (testDelay && player.viewDistance > view) updateDelay else 0L
        } catch (e: Exception) {
            0L
        }
        update(player, 0L, 0, targetSend, sendTasks) { p, distance -> p.sendViewDistance = distance }
        update(player, delay, 0, targetView, viewTasks) { p, distance ->
            if (p.viewDistance != distance || initial) {
                p.viewDistance = distance
                if (logChanges) logUpdate(p, "Set view distance of ${p.name} to $distance (client view distance is $clientViewDistance).")
            }
        }
    }

    private fun update(
        player: Player,
        delay: Long,
        attempts: Int,
        targets: Map<UUID, Int>,
        tasks: MutableMap<UUID, BukkitTask>,
        apply: (Player, Int) -> Unit,
    ) {
        if (attempts >= MAX_UPDATE_ATTEMPTS) return
        val id = player.uniqueId
        val distance = targets[id] ?: return
        tasks.remove(id)?.cancel()
        if (delay > 0) {
            tasks[id] = runLater(delay) { update(player, 0L, attempts, targets, tasks, apply) }
            return
        }
        if (!player.isOnline) return
        try {
            apply(player, distance)
        } catch (e: Throwable) {
            // 入ったばかりでワールドにまだいないと失敗するので、20 tick 後にやり直す
            tasks[id] = runLater(20L) { update(player, 0L, attempts + 1, targets, tasks, apply) }
        }
    }

    /** 描画距離のスライダーを動かしている間のログを抑えるため、20 tick 遅らせて最後の 1 件だけ出す */
    private fun logUpdate(player: Player, message: String) {
        val id = player.uniqueId
        logTasks.remove(id)?.cancel()
        logTasks[id] = runLater(20L) {
            logger.info(message)
            logTasks.remove(id)
        }
    }

    private fun forget(id: UUID) {
        seen.remove(id)
        targetView.remove(id)
        targetSend.remove(id)
        viewTasks.remove(id)?.cancel()
        sendTasks.remove(id)?.cancel()
    }

    private inner class Updater : Listener {
        @EventHandler
        fun onOptionsChange(event: PlayerClientOptionsChangeEvent) {
            val player = event.player
            val viewDistance = event.viewDistance
            val changed = event.hasViewDistanceChanged()
            val handle = {
                // 入った直後は変化の判定が当てにならないので、初めて見たプレイヤーなら必ず当てる
                val wasSeen = player.uniqueId in seen
                if (changed || !wasSeen) {
                    seen += player.uniqueId
                    setTarget(player, viewDistance, testDelay = wasSeen, initial = !wasSeen)
                }
            }
            if (Bukkit.isPrimaryThread()) handle() else runSync(handle)
        }

        @EventHandler
        fun onQuit(event: PlayerQuitEvent) = forget(event.player.uniqueId)

        @EventHandler
        fun onWorldChange(event: PlayerChangedWorldEvent) {
            setTarget(event.player, event.player.clientViewDistance, testDelay = false, initial = false)
        }
    }

    // ---- コマンド ----

    private fun onCommand(sender: CommandSender, args: Array<String>) {
        val sub = args.firstOrNull()?.lowercase()
        if (sub != null && sub in SUBCOMMANDS) {
            if (!sender.hasPermission("seemore.command.$sub")) {
                sender.sendMessage("§cYou don't have permission to use this command.")
                return
            }
            when (sub) {
                "reload" -> {
                    reloadConfig()
                    updateAllPlayers()
                    sender.sendMessage("§7Plugin reloaded.")
                }
                "average" -> average(sender)
                "players" -> players(sender)
            }
            return
        }
        sender.sendMessage("§7SeeMore (TORO-Core ${plugin.pluginMeta.version})")
        sender.sendMessage("")
        SUBCOMMANDS.filter { sender.hasPermission("seemore.command.$it") }.forEach { sender.sendMessage("/seemore $it") }
    }

    private fun average(sender: CommandSender) {
        val chunks = HashMap<World, Int>()
        val counts = HashMap<World, Int>()
        for (player in Bukkit.getOnlinePlayers()) {
            val vd = try { player.viewDistance } catch (e: Throwable) { continue }
            chunks.merge(player.world, (2 * vd + 1) * (2 * vd + 1), Int::plus)
            counts.merge(player.world, 1, Int::plus)
        }
        val totalPlayers = counts.values.sum()
        if (totalPlayers == 0) {
            sender.sendMessage("§7There are no players online.")
            return
        }
        sender.sendMessage("§7Effective average view distance:")
        sender.sendMessage("§6All worlds: §c${format(ViewDistances.effective(chunks.values.sum(), totalPlayers))}")
        sender.sendMessage("")
        sender.sendMessage("--------------------------")
        for (world in Bukkit.getWorlds()) {
            val vd = counts[world]?.let { ViewDistances.effective(chunks.getValue(world), it) } ?: 0.0
            sender.sendMessage("§6${world.name}: §c${format(vd)}")
        }
        sender.sendMessage("--------------------------")
    }

    private fun players(sender: CommandSender) {
        val byDistance = sortedMapOf<Int, MutableList<String>>(reverseOrder())
        for (player in Bukkit.getOnlinePlayers()) {
            val vd = try { player.viewDistance } catch (e: Throwable) { continue }
            byDistance.getOrPut(vd) { mutableListOf() } += player.name
        }
        if (byDistance.isEmpty()) {
            sender.sendMessage("§7There are no players online.")
            return
        }
        sender.sendMessage("§7Players by view distance:")
        for ((vd, names) in byDistance) {
            sender.sendMessage("§6$vd: " + names.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("§c | ") { "§f$it" })
        }
    }

    private fun format(vd: Double): String = if (vd == 0.0) "-" else DecimalFormat("#.0").format(vd)

    private companion object {
        const val MAX_UPDATE_ATTEMPTS = 10
        val SUBCOMMANDS = listOf("reload", "average", "players")
    }
}

/** 描画距離の計算 (サーバーに触らない部分) */
internal object ViewDistances {
    /** クライアントの距離・設定の上限・ワールドの距離から、(描画距離, 送信距離) を決める */
    fun targets(client: Int, maximum: Int, worldView: Int, simulation: Int): Pair<Int, Int> {
        val capped = minOf(maximum, 32)
        val ceiling = if (capped < 0) worldView else capped
        val view = maxOf(simulation, minOf(ceiling, client))
        val send = maxOf(2, minOf(ceiling, client)) + 1
        return view to send
    }

    /** 読み込んでいるチャンク数を、全員が同じ距離だったときの距離に直す */
    fun effective(chunkCount: Int, playerCount: Int): Double =
        if (playerCount == 0) 0.0 else (sqrt(chunkCount.toDouble() / playerCount) - 1.0) / 2.0
}
