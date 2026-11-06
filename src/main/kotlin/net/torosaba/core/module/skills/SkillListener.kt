package net.torosaba.core.module.skills

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Allay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
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

/** クラスの道具がプレイヤーのインベントリの外へ出るクリックかの判定 (テストできるよう Bukkit に触らない部分) */
internal object SkillItemRule {
    /**
     * @param clickedTop 上の欄 (容器やクラフト欄) をクリックした
     * @param shiftToTop 下の欄をシフトクリックして、上の容器へ送ろうとしている
     * @param cursor カーソルの持っているものがクラスの道具
     * @param current クリックした場所のものがクラスの道具
     * @param swapped 数字キーや F キーで、クリックした場所と入れ替わるものがクラスの道具
     * @param cursorIsBundle カーソルの持っているものがバンドル
     * @param currentIsBundle クリックした場所のものがバンドル
     */
    fun blocksClick(
        clickedTop: Boolean,
        shiftToTop: Boolean,
        cursor: Boolean,
        current: Boolean,
        swapped: Boolean,
        cursorIsBundle: Boolean,
        currentIsBundle: Boolean,
    ): Boolean = when {
        clickedTop -> cursor || swapped
        shiftToTop -> current
        // バンドルにも入れない
        else -> (cursor && currentIsBundle) || (current && cursorIsBundle)
    }
}

internal class SkillListener(private val module: SkillsModule) : Listener {
    private val states get() = module.states

    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        if (e.view.topInventory.holder !is ClassMenu) {
            if (movesSkillItemOut(e)) e.isCancelled = true
            return
        }
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        // 自分のインベントリ側のクリックでは何もしない
        if (e.clickedInventory != e.view.topInventory) return
        val entry = ClassMenu.entryAt(e.slot) ?: return
        module.select(player, entry.skill)
    }

    private fun movesSkillItemOut(e: InventoryClickEvent): Boolean {
        val top = e.view.topInventory
        val inventory = e.whoClicked.inventory
        val swappedItem = when (e.click) {
            ClickType.NUMBER_KEY -> inventory.getItem(e.hotbarButton)
            ClickType.SWAP_OFFHAND -> inventory.itemInOffHand
            else -> null
        }
        return SkillItemRule.blocksClick(
            clickedTop = e.rawSlot in 0 until top.size,
            // 自分のクラフト欄 (上の欄が CRAFTING) へはシフトクリックでは入らない
            shiftToTop = e.isShiftClick && e.clickedInventory == e.view.bottomInventory && top.type != InventoryType.CRAFTING,
            cursor = module.isSkillItem(e.cursor),
            current = module.isSkillItem(e.currentItem),
            swapped = module.isSkillItem(swappedItem),
            cursorIsBundle = isBundle(e.cursor),
            currentIsBundle = isBundle(e.currentItem),
        )
    }

    private fun isBundle(item: ItemStack?) = item != null && item.type.name.endsWith("BUNDLE")

    @EventHandler
    fun onInventoryDrag(e: InventoryDragEvent) {
        val top = e.view.topInventory
        when {
            top.holder is ClassMenu -> e.isCancelled = true
            module.isSkillItem(e.oldCursor) && e.rawSlots.any { it < top.size } -> e.isCancelled = true
        }
    }

    // ---- クラスの道具は、持ち主の手元から離さない ----

    @EventHandler(ignoreCancelled = true)
    fun onDrop(e: PlayerDropItemEvent) {
        if (module.isSkillItem(e.itemDrop.itemStack)) e.isCancelled = true
    }

    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        e.drops.removeIf { module.isSkillItem(it) }
    }

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        val uuid = e.player.uniqueId
        val skill = states[uuid]?.skill ?: return
        // リスポーンが終わってから渡す。死んで落とさなかった分を渡し直す
        module.runLater(1L) {
            val player = Bukkit.getPlayer(uuid) ?: return@runLater
            if (states[uuid]?.skill == skill) module.giveSkillItem(player, skill)
        }
    }

    // 額縁・アレイ・防具立てには渡さない
    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(e: PlayerInteractEntityEvent) {
        if (e.rightClicked !is ItemFrame && e.rightClicked !is Allay) return
        val inventory = e.player.inventory
        val held = if (e.hand == EquipmentSlot.OFF_HAND) inventory.itemInOffHand else inventory.itemInMainHand
        if (module.isSkillItem(held)) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onArmorStand(e: PlayerArmorStandManipulateEvent) {
        if (module.isSkillItem(e.playerItem)) e.isCancelled = true
    }

    private fun holdsItems(block: Block?) =
        block != null && (block.type == Material.DECORATED_POT || block.type.name.endsWith("_SHELF"))

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (!e.action.isRightClick) return
        val player = e.player
        val inventory = player.inventory
        if (e.hand == EquipmentSlot.OFF_HAND) {
            // オフハンドでは能力を使えない。道具を普通のアイテムとして使わせない
            if (module.isSkillItem(inventory.itemInOffHand)) e.isCancelled = true
            return
        }
        val s = states[player.uniqueId]
        val owner = module.skillItemOwner(inventory.itemInMainHand)
        if (owner != null) {
            // クラスが変わった後などに残っていた道具は回収する
            if (owner != s?.skill?.name) {
                e.isCancelled = true
                inventory.setItemInMainHand(null)
                return
            }
            if (holdsItems(e.clickedBlock)) {
                e.isCancelled = true
                return
            }
        }
        if (s == null) return
        val type = inventory.itemInMainHand.type
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
