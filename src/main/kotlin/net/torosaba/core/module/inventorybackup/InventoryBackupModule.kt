package net.torosaba.core.module.inventorybackup

import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.command.CommandSender
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import java.util.UUID

/**
 * 旧 InventoryBackup。
 * インベントリの中身が消えたときに、クリックで直前の中身に戻せるメッセージを出す。
 *
 * アイテムがほかの場所に残っていないと言い切れる、次の場合だけ案内する。
 * - クリアのコマンド (/clear など) で空になった
 * - 死んで、アイテムを 1 つも落とさずに失った
 * - クリエイティブのインベントリで空にした (ゴミ箱など。容器へ移したり捨てたりしていないとき)
 */
class InventoryBackupModule : Module("inventory-backup", "InventoryBackup"), Listener {

    // 復元メッセージで案内した中身 (ハッシュコード → 中身)
    private val restorable = mutableMapOf<UUID, MutableMap<Int, Array<ItemStack?>>>()

    // クリエイティブのインベントリで最初に中身が変わる直前の中身
    private val creative = CreativeClearTracker<Array<ItemStack?>>()

    // 死んだときに失った中身。リスポーンしたら案内する
    private val deathLoss = mutableMapOf<UUID, Array<ItemStack?>>()

    // クリアのコマンドの直前の中身。次の tick に空になっていたら案内する
    private val clearWatch = mutableMapOf<UUID, Array<ItemStack?>>()

    override fun onEnable() {
        permission(RESTORE_PERMISSION, PermissionDefault.OP, "インベントリの復元を許可する権限")
        command(
            "restore",
            permission = RESTORE_PERMISSION,
            description = "インベントリを復元するコマンド",
        ) { sender, _, args -> restore(sender as? Player ?: return@command false, args) }
        listen(this)
    }

    override fun onDisable() {
        restorable.clear()
        creative.clear()
        deathLoss.clear()
        clearWatch.clear()
    }

    private fun restore(player: Player, args: Array<String>): Boolean {
        if (args.size != 1) return false
        val hash = args[0].toIntOrNull() ?: return false
        val offers = restorable[player.uniqueId] ?: return false
        if (hash !in offers) return false
        if (!player.inventory.contents.allNull()) {
            player.sendMessage("§cインベントリが空でないため復元できません")
            return false
        }
        player.inventory.contents = offers.remove(hash)!!.cloneAll()
        return true
    }

    // ---- クリエイティブのインベントリ ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreative(event: InventoryCreativeEvent) {
        val player = event.whoClicked
        // イベントの時点では、まだ中身は変わっていない
        creative.onChange(player.uniqueId) { player.inventory.contents.cloneAll() }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player
        val cleared = player.gameMode == GameMode.CREATIVE && event.view.isOwnInventory() && player.inventory.contents.allNull()
        val before = creative.onClose(player.uniqueId, cleared) ?: return
        if (!before.allNull()) sendRestoreMessage(player, before)
    }

    // ---- アイテムが移ったことの記録 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) = onInventoryAction(event.whoClicked, event.view)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) = onInventoryAction(event.whoClicked, event.view)

    private fun onInventoryAction(player: HumanEntity, view: InventoryView) {
        clearWatch.remove(player.uniqueId)
        if (!view.isOwnInventory()) creative.onMovedOut(player.uniqueId)
        // 他人のインベントリを開いて編集した場合 (/invsee など) は、操作した本人だけでなく持ち主の分も記録する。
        // 記録が無いと、持ち出されたアイテムが本人の復元案内に含まれて複製される
        for (inventory in listOf(view.topInventory, view.bottomInventory)) {
            val holder = inventory.holder as? HumanEntity ?: continue
            if (holder.uniqueId != player.uniqueId) creative.onMovedOut(holder.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        clearWatch.remove(event.player.uniqueId)
        creative.onMovedOut(event.player.uniqueId)
    }

    // ---- 死亡 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        if (!RestoreCause.isDeathLoss(event.keepInventory, event.drops.isNotEmpty())) return
        val player = event.entity
        val items = player.inventory.contents
        if (!items.allNull()) deathLoss[player.uniqueId] = items.cloneAll()
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val uuid = event.player.uniqueId
        val items = deathLoss.remove(uuid) ?: return
        // リスポーンが終わってから確かめる
        runLater(1L) {
            val player = Bukkit.getPlayer(uuid) ?: return@runLater
            if (player.inventory.contents.allNull()) sendRestoreMessage(player, items)
        }
    }

    // ---- クリアのコマンド ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) = watchClear(event.player, event.message)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) = watchClear(event.sender, event.command)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRemoteCommand(event: RemoteServerCommandEvent) = watchClear(event.sender, event.command)

    /** クリアのコマンドなら、いまの中身を覚えて、次の tick に空になった人へ案内する */
    private fun watchClear(sender: CommandSender, line: String) {
        val label = RestoreCause.clearCommandLabel(line) ?: return
        // 権限が無くて実行されないコマンドでは案内しない
        val command = Bukkit.getCommandMap().getCommand(label) ?: return
        if (!command.testPermissionSilent(sender)) return

        if (clearWatch.isEmpty()) runLater(1L) { settleClearWatch() }
        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(RESTORE_PERMISSION)) continue
            val items = player.inventory.contents
            if (!items.allNull()) clearWatch.putIfAbsent(player.uniqueId, items.cloneAll())
        }
    }

    private fun settleClearWatch() {
        val watched = clearWatch.toMap()
        clearWatch.clear()
        for ((uuid, items) in watched) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (player.inventory.contents.allNull()) sendRestoreMessage(player, items)
        }
    }

    // ---- 案内 ----

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        restorable.remove(uuid)
        creative.forget(uuid)
        deathLoss.remove(uuid)
        clearWatch.remove(uuid)
    }

    private fun sendRestoreMessage(player: HumanEntity, items: Array<ItemStack?>) {
        if (!player.hasPermission(RESTORE_PERMISSION)) return
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
        const val RESTORE_PERMISSION = "inventory_backup.restore"

        val section = LegacyComponentSerializer.legacySection()

        // 中身が 1 つも無いか (配列自体が無いときも true)
        fun Array<ItemStack?>?.allNull(): Boolean = this == null || all { it == null || it.isEmpty }

        fun Array<ItemStack?>.cloneAll(): Array<ItemStack?> = Array(size) { this[it]?.clone() }

        // 容器などを開かず、自分のインベントリだけを見ているか
        fun InventoryView.isOwnInventory(): Boolean = topInventory.type == InventoryType.CRAFTING
    }
}

/** 復元を案内してよい、アイテムの消え方の判定 (テストできるよう Bukkit に触らない部分) */
internal object RestoreCause {
    // バニラの clear と Essentials の clearinventory (別名を含む)
    private val clearCommands = setOf(
        "clear", "eclear", "clearinventory", "eclearinventory", "clearinvent", "eclearinvent", "ci", "eci", "clean", "eclean",
    )

    /** コマンドの行がクリアのコマンドなら、そのラベル (`minecraft:clear` など) を返す。`execute ... run` の先も見る */
    fun clearCommandLabel(line: String): String? {
        val command = line.trim().removePrefix("/").let { if (" run " in it) it.substringAfterLast(" run ").trim() else it }
        val label = command.substringBefore(' ').lowercase()
        return label.takeIf { it.substringAfterLast(':') in clearCommands }
    }

    /**
     * 死んだときに、アイテムがどこにも残らず失われたか。
     * 地面に落ちたものは失われていないので、1 つでも落としたら false
     */
    fun isDeathLoss(keepInventory: Boolean, droppedItems: Boolean): Boolean = !keepInventory && !droppedItems
}

/**
 * クリエイティブのインベントリでアイテムを消したかを、閉じるまで追いかける。
 * 最初に中身が変わる直前の写しを持ち、その間に捨てたり容器へ移したりしていなければ、閉じたときに写しを返す
 */
internal class CreativeClearTracker<T : Any> {
    private class Session<T>(val before: T) {
        var movedOut = false
    }

    private val sessions = HashMap<UUID, Session<T>>()

    /** 中身が変わる直前に呼ぶ。[snapshot] はそのときの最初の 1 回だけ使う */
    fun onChange(id: UUID, snapshot: () -> T) {
        sessions.getOrPut(id) { Session(snapshot()) }
    }

    /** アイテムを捨てた、または容器を操作したときに呼ぶ */
    fun onMovedOut(id: UUID) {
        sessions[id]?.movedOut = true
    }

    /** インベントリを閉じたときに呼ぶ。[cleared] (クリエイティブで自分のインベントリが空) なら案内する中身を返す */
    fun onClose(id: UUID, cleared: Boolean): T? {
        val session = sessions.remove(id) ?: return null
        return session.before.takeIf { cleared && !session.movedOut }
    }

    fun forget(id: UUID) {
        sessions.remove(id)
    }

    fun clear() = sessions.clear()
}
