package net.torosaba.core.module.hatcolorteam

import net.torosaba.core.Module
import org.bukkit.Color
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.meta.LeatherArmorMeta

/** 旧 HatColorTeam。同じ色の革の帽子をかぶったプレイヤーどうしでは、攻撃 (飛び道具を含む) が通らない */
class HatColorTeamModule : Module("hat-color-team", "HatColorTeam"), Listener {
    override fun onEnable() {
        listen(this)
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val color = hatColor(player) ?: return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        // 他のプラグインが止めた攻撃を通してしまわないよう、止めるときだけ触る
        if (hatColor(attacker) == color) event.isCancelled = true
    }

    private fun hatColor(player: Player): Color? =
        (player.inventory.helmet.itemMeta as? LeatherArmorMeta)?.color
}
