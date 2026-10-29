package net.torosaba.core.module.warnkillpotion

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/** 旧 Warn_KillPotion。即死ポーションを持っているプレイヤーがいたらチャットで知らせる */
class WarnKillPotionModule : Module("warn-kill-potion", "Warn_KillPotion"), Listener {

    // 即死ポーションを持っていると知らせ済みのプレイヤー
    private val holders = mutableSetOf<UUID>()

    override fun onEnable() {
        listen(this)
        runTimer(1, 1) { tick() }
    }

    override fun onDisable() {
        holders.clear()
    }

    private fun tick() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (!hasKillPotion(player)) {
                holders -= player.uniqueId
                continue
            }
            if (player.uniqueId in holders) continue
            val text = config.getString("text") ?: return
            Bukkit.broadcast(section.deserialize(text.format(player.name)))
            holders += player.uniqueId
        }
    }

    // 入ってきたプレイヤーに、今持っている人を知らせる
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.uniqueId !in holders) continue
            val text = config.getString("text") ?: return
            event.player.sendMessage(section.deserialize(text.format(player.name)))
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        holders -= event.player.uniqueId
    }

    private fun hasKillPotion(player: Player): Boolean = player.inventory.contents.any { item ->
        item != null && item.type in potions && (item.itemMeta as? PotionMeta)?.customEffects?.any {
            it.amplifier in killAmplifiers && it.type in killTypes
        } == true
    }

    private companion object {
        val section = LegacyComponentSerializer.legacySection()
        val potions = setOf(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION)
        val killTypes = setOf(PotionEffectType.INSTANT_HEALTH, PotionEffectType.INSTANT_DAMAGE)
        val killAmplifiers = setOf(29, 61, 93, 125)
    }
}
