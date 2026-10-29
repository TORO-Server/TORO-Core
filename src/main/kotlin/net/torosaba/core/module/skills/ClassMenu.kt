package net.torosaba.core.module.skills

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

internal val section = LegacyComponentSerializer.legacySection()

/** `§` の色コード付き文字列を、アイテム名・説明用に斜体なしの Component にする */
internal fun String.itemText(): Component =
    section.deserialize(this).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

internal fun createGuiItem(material: Material, name: String, vararg lore: String): ItemStack =
    ItemStack(material).apply {
        editMeta {
            it.displayName(name.itemText())
            it.lore(lore.map { line -> line.itemText() })
        }
    }

/** /skill で開くクラス選択画面 */
internal class ClassMenu : InventoryHolder {
    /** skill が null のものは「全て解除」 */
    class Entry(val slot: Int, val skill: Skill?, val item: ItemStack)

    private val inventory = Bukkit.createInventory(this, 27, Component.text(TITLE))

    init {
        ENTRIES.forEach { inventory.setItem(it.slot, it.item.clone()) }
    }

    override fun getInventory(): Inventory = inventory

    companion object {
        const val TITLE = "クラス選択"

        val ENTRIES: List<Entry> by lazy {
            listOf(
                Entry(1, Skill.ACROBAT, createGuiItem(Material.FEATHER, "§bアクロバット",
                    "§f・§7スニークで§f2段ジャンプ§7が可能",
                    "§f・§7高い所から落ちても§c落下ダメージを完全に無効化§7する")),
                Entry(3, Skill.SCOUT, createGuiItem(Material.FISHING_ROD, "§aスカウト",
                    "§f・§7専用の§bグラップリングフック§7をクラフト・使用可能",
                    "§f・§7常に§f俊敏 I§7 が付与され、落下ダメージも§f50%軽減§7される")),
                Entry(5, Skill.GUARDIAN, createGuiItem(Material.SHIELD, "§eガーディアン",
                    "§f・§7最大体力が§fハート2個分§7増加する",
                    "§f・§7常に§f再生 I§7 が付与され、驚異的な耐久力を発揮する")),
                Entry(10, Skill.BLINK, createGuiItem(Material.FEATHER, "§b§lブリンク",
                    "§7使用アイテム: 羽",
                    "§f・§7右クリックで視線方向に§f瞬時に突進§7する",
                    "§f・§7着地から §f2秒後 §7に再び使用可能になる")),
                Entry(12, Skill.BLAST, createGuiItem(Material.TNT_MINECART, "§e§lブラストパック",
                    "§7使用アイテム: TNTトロッコ",
                    "§f・§7右クリックで投げ、設置後に再度右クリックで§f起爆§7する",
                    "§f・§7爆風で敵を吹き飛ばし、自分は空中で§f加速§7できる",
                    "§f・§7着地後 §f4秒 §7で §f2チャージ §7回復")),
                Entry(14, Skill.TELEPORT, createGuiItem(Material.ENDER_PEARL, "§d§lテレポート",
                    "§7使用アイテム: エンダーパール",
                    "§f・§7右クリックでビーコンを射出し、再発動でその位置へ§f転送§7する",
                    "§f・§7スニークで設置、またはフェイクテレポートも可能",
                    "§f・§7リチャージ: §f3.5秒")),
                Entry(16, Skill.DASH, createGuiItem(Material.BLAZE_ROD, "§6§lダッシュ",
                    "§7使用アイテム: ブレイズロッド",
                    "§f・§7右クリックでエネルギーを消費する§f高速走行§7をON/OFF",
                    "§f・§7走行中にスニークで§fスライディング§7を発動",
                    "§f・§7スライディング終了時に§fブースト§7が発生")),
                Entry(22, null, createGuiItem(Material.BARRIER, "§c§l全て解除", "§7能力をリセット")),
            )
        }

        fun entryAt(slot: Int): Entry? = ENTRIES.find { it.slot == slot }
    }
}
