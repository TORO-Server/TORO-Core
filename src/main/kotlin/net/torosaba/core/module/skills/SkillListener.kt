package net.torosaba.core.module.skills

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffectType

/** グラップリングフックかどうか (名前で見分ける。元のプラグインで作ったものも同じ名前) */
internal fun isGrapple(item: ItemStack?): Boolean {
    val meta = item?.takeIf { it.hasItemMeta() }?.itemMeta ?: return false
    val name = meta.displayName() ?: return false
    return section.serialize(name) == SkillsModule.GRAPPLE_NAME
}

internal class SkillListener(private val module: SkillsModule) : Listener {
    private val states get() = module.states

    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        if (e.view.topInventory.holder !is ClassMenu) return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        // 自分のインベントリ側のクリックでは何もしない
        if (e.clickedInventory != e.view.topInventory) return
        val entry = ClassMenu.entryAt(e.slot) ?: return
        module.select(player, entry.skill)
    }

    @EventHandler
    fun onInventoryDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is ClassMenu) e.isCancelled = true
    }

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand == EquipmentSlot.OFF_HAND) return
        if (!e.action.isRightClick) return
        val player = e.player
        val s = states[player.uniqueId] ?: return
        val type = player.inventory.itemInMainHand.type
        when {
            s.skill == Skill.BLINK && type == Material.FEATHER -> module.blink(player, s)
            s.skill == Skill.BLAST && type == Material.TNT_MINECART -> {
                e.isCancelled = true
                module.blast(player, s)
            }
            s.skill == Skill.TELEPORT && type == Material.ENDER_PEARL -> {
                e.isCancelled = true
                module.teleport(player, s)
            }
            s.skill == Skill.DASH && type == Material.BLAZE_ROD -> {
                e.isCancelled = true
                module.toggleDash(player, s)
            }
        }
    }

    @EventHandler
    fun onToggleSneak(e: PlayerToggleSneakEvent) {
        if (!e.isSneaking) return
        val player = e.player
        val s = states[player.uniqueId] ?: return
        when {
            s.skill == Skill.ACROBAT -> module.acrobatJump(player, s)
            s.skill == Skill.DASH && s.dashRunning -> module.slide(player, s)
        }
    }

    @EventHandler
    fun onDamage(e: EntityDamageEvent) {
        val player = e.entity as? Player ?: return
        if (e.cause != EntityDamageEvent.DamageCause.FALL) return
        when (states[player.uniqueId]?.skill) {
            Skill.ACROBAT -> e.isCancelled = true
            Skill.SCOUT -> if (isGrapple(player.inventory.itemInMainHand)) e.damage = e.damage * 0.5
            else -> {}
        }
    }

    @EventHandler
    fun onFish(e: PlayerFishEvent) {
        val player = e.player
        val s = states[player.uniqueId] ?: return
        val inv = player.inventory
        if (s.skill != Skill.SCOUT || !isGrapple(inv.itemInMainHand) || isGrapple(inv.itemInOffHand)) return
        if (e.state != PlayerFishEvent.State.IN_GROUND) return
        // 鈍化・耐火中と、攻撃を受けてから 5 秒間は使えない
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS) || player.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) return
        val tag = s.combatTag
        if (tag != null && System.currentTimeMillis() - tag < 5000) return
        player.velocity = grapplePull(player.location.toVector(), e.hook.location.toVector(), player.velocity)
    }

    @EventHandler
    fun onHit(e: EntityDamageByEntityEvent) {
        val player = e.entity as? Player ?: return
        val s = states[player.uniqueId] ?: return
        if (s.skill == Skill.SCOUT) s.combatTag = System.currentTimeMillis()
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        module.resetPlayer(e.player)
    }
}
