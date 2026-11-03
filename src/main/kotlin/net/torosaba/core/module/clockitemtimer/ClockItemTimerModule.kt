package net.torosaba.core.module.clockitemtimer

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 旧 ClockItemTimer。時計を右クリックでタイマーの開始・一時停止・再開、左クリックでリセット。
 * 経過時間はアクションバーに出す。何も付いていない時計は、使ったときに説明の lore を付ける。
 */
class ClockItemTimerModule : Module("clock-item-timer", "ClockItemTimer"), Listener {
    private val section = LegacyComponentSerializer.legacySection()

    private lateinit var lore: List<String>
    private lateinit var messages: Messages
    private val timers = mutableMapOf<UUID, PlayerTimer>()

    private class Messages(
        val start: String,
        val stop: String,
        val restart: String,
        val reset: String,
        val copy: String,
        val timer: String,
    )

    /** プレイヤー 1 人分のタイマー。毎 tick 経過時間をアクションバーに出す */
    private inner class PlayerTimer(val player: Player) {
        var ticks = 0L
        var paused = false
        private val task: BukkitTask = runTimer(1L, 1L) {
            player.sendActionBar(section.deserialize(format(ticks)))
            if (!paused && ticks < 20L * 86400L * 365L) ticks++
        }

        fun remove() {
            task.cancel()
            player.sendActionBar(Component.empty())
        }
    }

    override fun onEnable() {
        lore = config.getStringList("lore")
        messages = Messages(
            start = config.getString("message.start") ?: "",
            stop = config.getString("message.stop") ?: "",
            restart = config.getString("message.restart") ?: "",
            reset = config.getString("message.reset") ?: "",
            copy = config.getString("message.copy") ?: "",
            timer = config.getString("message.timer") ?: "%01d:%02d:%02d.%02d",
        )
        listen(this)
    }

    override fun onDisable() {
        timers.values.forEach { it.remove() }
        timers.clear()
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (item.type != Material.CLOCK) return
        val meta = item.itemMeta ?: return

        // 何も付いていない時計なら、タイマー用の lore を付ける
        if (meta == ItemStack.of(Material.CLOCK).itemMeta) {
            meta.lore(lore.map(section::deserialize))
            item.itemMeta = meta
        }

        // lore が設定と同じ時計だけをタイマーとして扱う (§ の書き方の違いは揃えてから比べる)
        val itemLore = meta.lore()?.map(section::serialize) ?: return
        if (itemLore != lore.map { section.serialize(section.deserialize(it)) }) return

        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> reset(event.player)
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> startStop(event.player)
            else -> {}
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        timers.remove(event.player.uniqueId)?.remove()
    }

    private fun startStop(player: Player) {
        val timer = timers[player.uniqueId]
        if (timer == null) {
            timers[player.uniqueId] = PlayerTimer(player)
            player.sendMessage(section.deserialize(messages.start))
            return
        }
        if (timer.paused) {
            player.sendMessage(timeMessage(messages.restart, timer))
            timer.paused = false
        } else {
            player.sendMessage(timeMessage(messages.stop, timer))
            timer.paused = true
        }
    }

    private fun reset(player: Player) {
        val timer = timers.remove(player.uniqueId) ?: return
        player.sendMessage(timeMessage(messages.reset, timer))
        timer.remove()
    }

    /** 経過時間入りのメッセージ。クリックで時間をコピーできる */
    private fun timeMessage(text: String, timer: PlayerTimer): Component {
        val time = format(timer.ticks)
        return section.deserialize(text.format(time))
            .clickEvent(ClickEvent.copyToClipboard(time))
            .hoverEvent(HoverEvent.showText(section.deserialize(messages.copy)))
    }

    private fun format(ticks: Long): String {
        val safeTicks = ticks.coerceAtLeast(0L)
        val seconds = safeTicks / 20
        return try {
            messages.timer.format(seconds / 3600, seconds % 3600 / 60, seconds % 60, safeTicks % 20 * 5)
        } catch (_: Exception) {
            "%01d:%02d:%02d.%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60, safeTicks % 20 * 5)
        }
    }
}
