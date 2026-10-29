package net.torosaba.core.module.armorstand

import net.torosaba.core.Module
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent

/** 旧 Armor_stand。防具立てがプレイヤーの攻撃以外 (爆発・ブロック・モブなど) で壊れないようにする */
class ArmorStandModule : Module("armor-stand", "Armor_stand"), Listener {
    override fun onEnable() {
        listen(this)
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (event.entity.type == EntityType.ARMOR_STAND && event.damager.type != EntityType.PLAYER) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onBlockDamage(event: EntityDamageByBlockEvent) {
        if (event.entity.type == EntityType.ARMOR_STAND) event.isCancelled = true
    }
}
