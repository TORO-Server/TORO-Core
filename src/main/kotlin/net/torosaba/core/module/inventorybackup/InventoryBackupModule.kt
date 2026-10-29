package net.torosaba.core.module.inventorybackup

import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import java.util.UUID

/**
 * 旧 InventoryBackup。
 * インベントリを閉じたときに中身が空になっていたら、クリックで直前の中身に戻せるメッセージを出す。
 */
class InventoryBackupModule : Module("inventory-backup", "InventoryBackup"), Listener {

    // インベントリを開いた / 閉じたときの中身
    private val backups = mutableMapOf<UUID, Array<ItemStack?>>()

    // 復元メッセージで案内した中身 (ハッシュコード → 中身)
    private val restorable = mutableMapOf<UUID, MutableMap<Int, Array<ItemStack?>>>()

    override fun onEnable() {
        permission("inventory_backup.restore", PermissionDefault.TRUE, "インベントリの復元を許可する権限")
        command(
            "restore",
            permission = "inventory_backup.restore",
            description = "インベントリを復元するコマンド",
        ) { sender, _, args -> restore(sender as? Player ?: return@command false, args) }
        listen(this)
    }

    override fun onDisable() {
        backups.clear()
        restorable.clear()
    }

    private fun restore(player: Player, args: Array<String>): Boolean {
        if (args.size != 1) return false
        val hash = args[0].toIntOrNull() ?: return false
        val items = restorable[player.uniqueId]?.get(hash) ?: return false
        player.inventory.contents = items
        return true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player
        val contents = player.inventory.contents
        val backup = backups[player.uniqueId]
        if (contents.allNull() && !backup.allNull()) sendRestoreMessage(player, backup!!)
        backups[player.uniqueId] = contents.cloneAll()
    }

    @EventHandler
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player
        backups[player.uniqueId] = player.inventory.contents.cloneAll()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        backups.remove(event.player.uniqueId)
        restorable.remove(event.player.uniqueId)
    }

    private fun sendRestoreMessage(player: HumanEntity, items: Array<ItemStack?>) {
        val hash = items.contentHashCode()
        restorable.getOrPut(player.uniqueId) { mutableMapOf() }[hash] = items

        val command = restoreCommand().format(hash)
        val text = section.deserialize(config.getString("chat-text") ?: "")
            .hoverEvent(HoverEvent.showText(section.deserialize(config.getString("hover-text") ?: "")))
            .clickEvent(ClickEvent.runCommand(command))
        player.sendMessage(text)
    }

    // 元のプラグインの名前空間 (/inventorybackup:restore) は TORO-Core の名前空間に読み替える
    private fun restoreCommand(): String {
        val command = config.getString("restore-command") ?: "/restore %d"
        return command.replaceFirst("/inventorybackup:", "/${plugin.name.lowercase()}:", ignoreCase = true)
    }

    private companion object {
        val section = LegacyComponentSerializer.legacySection()

        // 中身が 1 つも無いか (配列自体が無いときも true)
        fun Array<ItemStack?>?.allNull(): Boolean = this == null || all { it == null || it.isEmpty }

        fun Array<ItemStack?>.cloneAll(): Array<ItemStack?> = Array(size) { this[it]?.clone() }
    }
}
