package net.torosaba.core.module.effectgui

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.TooltipDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.torosaba.core.Module
import net.torosaba.core.legacy
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import java.util.UUID

/**
 * 旧 Effect_GUI。/effectgui の GUI からエフェクトを自分に付ける。
 * 時間・レベル・可視性・パーティクルの設定はプレイヤーごとに、サーバーの再起動まで覚えておく。
 */
class EffectGuiModule : Module("effect-gui", "Effect_GUI") {
    private val states = mutableMapOf<UUID, PlayerState>()

    override fun onEnable() {
        permission("effectgui.use", org.bukkit.permissions.PermissionDefault.TRUE, "エフェクトGUIを使う")
        command("effectgui", permission = "effectgui.use", description = "エフェクトのGUIを表示する") { sender, _, _ ->
            if (sender !is Player) {
                sender.sendMessage("§cプレイヤーのみ実行できます")
                return@command true
            }
            sender.openInventory(state(sender).mainGui.inventory)
            true
        }
        listen(GuiListener())
    }

    override fun onDisable() {
        Bukkit.getOnlinePlayers()
            .filter { it.openInventory.topInventory.holder is EffectGui }
            .forEach { it.closeInventory() }
        states.clear()
    }

    private fun state(player: Player) = states.getOrPut(player.uniqueId) { PlayerState() }

    private inner class GuiListener : Listener {
        @EventHandler
        fun onClick(e: InventoryClickEvent) {
            val gui = e.view.topInventory.holder as? EffectGui ?: return
            e.isCancelled = true
            val player = e.whoClicked as? Player ?: return
            if (e.clickedInventory != e.view.topInventory) return
            val state = state(player)
            when (gui.kind) {
                Kind.MAIN -> clickMain(player, state, e.slot)
                Kind.TIME -> clickTime(player, state, e.slot)
                Kind.LEVEL -> clickLevel(player, state, e.slot)
            }
        }

        @EventHandler
        fun onDrag(e: InventoryDragEvent) {
            if (e.view.topInventory.holder is EffectGui) e.isCancelled = true
        }
    }

    private fun clickMain(player: Player, state: PlayerState, slot: Int) {
        effects.getOrNull(slot)?.let {
            addEffect(player, state, it)
            return
        }
        val inv = state.mainGui.inventory
        when (slot) {
            45 -> {
                state.ambient = !state.ambient
                setAmbient(inv, state.ambient)
            }
            46 -> {
                state.particles = !state.particles
                setParticles(inv, state.particles)
            }
            49 -> removeAllEffects(player)
            52 -> player.openInventory(state.timeGui.inventory)
            53 -> player.openInventory(state.levelGui.inventory)
        }
    }

    private fun clickTime(player: Player, state: PlayerState, slot: Int) {
        if (slot < 18) {
            val inv = state.timeGui.inventory
            timeList.indexOf(state.time).takeIf { it >= 0 }?.let { inv.setItem(it, timeItem(it, false)) }
            inv.setItem(slot, timeItem(slot, true))
            state.time = timeList[slot]
        } else if (slot == 18) {
            player.openInventory(state.mainGui.inventory)
        }
    }

    private fun clickLevel(player: Player, state: PlayerState, slot: Int) {
        if (slot < 18) {
            val inv = state.levelGui.inventory
            levelList.indexOf(state.level).takeIf { it >= 0 }?.let { inv.setItem(it, levelItem(it, false)) }
            inv.setItem(slot, levelItem(slot, true))
            state.level = levelList[slot]
        } else if (slot == 18) {
            player.openInventory(state.mainGui.inventory)
        }
    }

    private fun addEffect(player: Player, state: PlayerState, effect: Effect) {
        // 「無限」は本当に無限にする
        val duration = if (state.time == Int.MAX_VALUE) PotionEffect.INFINITE_DURATION else state.time
        player.addPotionEffect(PotionEffect(effect.type, duration, state.level, state.ambient, state.particles))

        val key = config.getString("lang.${effect.key}") ?: effect.type.translationKey()
        player.sendMessage(
            Component.translatable(key, NamedTextColor.GOLD)
                .append(Component.text(config.getString("addText") ?: "", NamedTextColor.YELLOW))
        )
    }

    private fun removeAllEffects(player: Player) {
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.sendMessage(Component.text(config.getString("removeText") ?: "", NamedTextColor.YELLOW))
        player.closeInventory()
    }

    // ---- GUI ----

    private inner class PlayerState {
        var time = Int.MAX_VALUE
        var level = 4
        var ambient = true
        var particles = false

        // GUI はプレイヤーごとに作って使い回す (ボタンの表示を覚えておくため)
        val mainGui by lazy { EffectGui(Kind.MAIN).also { fillMain(it.inventory, this) } }
        val timeGui by lazy { EffectGui(Kind.TIME).also { fillTime(it.inventory, this) } }
        val levelGui by lazy { EffectGui(Kind.LEVEL).also { fillLevel(it.inventory, this) } }
    }

    private fun fillMain(inv: Inventory, state: PlayerState) {
        effects.forEachIndexed { slot, effect ->
            inv.setItem(slot, potion(effect.color, "&aエフェクト: ${effect.name}",
                listOf("&a${effect.lore}", if (effect.good) "&9プラス効果" else "&cマイナス効果")))
        }
        setAmbient(inv, state.ambient)
        setParticles(inv, state.particles)
        inv.setItem(49, item(Material.MILK_BUCKET, "&aリセット", listOf("&aエフェクトをリセットする")))
        inv.setItem(52, item(Material.REDSTONE, "&a時間", listOf("&aエフェクトの時間設定")))
        inv.setItem(53, item(Material.GLOWSTONE_DUST, "&aレベル", listOf("&aエフェクトのレベル設定")))
    }

    // ambient が true のとき「可視性 OFF」
    private fun setAmbient(inv: Inventory, ambient: Boolean) {
        inv.setItem(45, if (ambient) item(Material.ENDER_PEARL, "&a可視性", listOf("&9OFF"))
        else item(Material.ENDER_EYE, "&a可視性", listOf("&cON")))
    }

    private fun setParticles(inv: Inventory, particles: Boolean) {
        inv.setItem(46, if (particles) item(Material.MAGMA_CREAM, "&aパーティクル表示", listOf("&cON"))
        else item(Material.SLIME_BALL, "&aパーティクル表示", listOf("&9OFF")))
    }

    private fun fillTime(inv: Inventory, state: PlayerState) {
        for (slot in 0 until 18) inv.setItem(slot, timeItem(slot, timeList[slot] == state.time))
        inv.setItem(18, backButton())
    }

    private fun fillLevel(inv: Inventory, state: PlayerState) {
        for (slot in 0 until 18) inv.setItem(slot, levelItem(slot, levelList[slot] == state.level))
        inv.setItem(18, backButton())
    }

    private fun timeItem(index: Int, selected: Boolean) =
        item(if (selected) Material.REDSTONE else Material.GUNPOWDER, "&a時間: ${timeText[index]}")

    private fun levelItem(index: Int, selected: Boolean) =
        item(if (selected) Material.GLOWSTONE_DUST else Material.GUNPOWDER, "&aレベル: ${levelList[index] + 1}")

    private fun backButton() = potion(Color.fromRGB(43, 128, 255), "&a戻る", emptyList())

    private fun potion(color: Color, name: String, lore: List<String>) = ItemStack(Material.POTION).apply {
        editMeta(PotionMeta::class.java) {
            it.color = color
            it.displayName(name.text())
            if (lore.isNotEmpty()) it.lore(lore.map { line -> line.text() })
        }
        // ポーションの効果の行 (「効果なし」) を隠す
        setData(
            DataComponentTypes.TOOLTIP_DISPLAY,
            TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.POTION_CONTENTS).build(),
        )
    }

    private fun item(material: Material, name: String, lore: List<String> = emptyList()) = ItemStack(material).apply {
        editMeta {
            it.displayName(name.text())
            if (lore.isNotEmpty()) it.lore(lore.map { line -> line.text() })
        }
    }

    // 元の setDisplayName と同じく斜体にしない
    private fun String.text(): Component = legacy().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
}

private enum class Kind(val title: String, val size: Int) {
    MAIN("EffectMenu", 54),
    TIME("EffectTime", 27),
    LEVEL("EffectLevel", 27),
}

/** エフェクトの GUI。holder で見分ける */
private class EffectGui(val kind: Kind) : InventoryHolder {
    private val inventory: Inventory = Bukkit.createInventory(this, kind.size, Component.text(kind.title))
    override fun getInventory(): Inventory = inventory
}
