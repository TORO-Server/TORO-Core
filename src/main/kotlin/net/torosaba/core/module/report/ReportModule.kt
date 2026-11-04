package net.torosaba.core.module.report

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.permissions.PermissionDefault
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 旧 Report。/report で GUI から通報理由と相手を選び、座標付きで Discord の Webhook に送る。
 * 5 分に 10 回までしか通報できない。/reportblock で特定のプレイヤーの通報を止められる。
 */
class ReportModule : Module("report", "Report") {
    private var webhook: WebhookSender? = null
    private val blocked = mutableSetOf<String>()

    // チャットのイベントは非同期で来る
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val history = mutableMapOf<UUID, ArrayDeque<Long>>()

    private val plain = PlainTextComponentSerializer.plainText()

    override fun onEnable() {
        val url = config.getString("webhook-url", "") ?: ""
        webhook = if (url.isEmpty()) {
            logger.warning("Webhook URL が設定されていません！")
            null
        } else {
            WebhookSender(url, logger)
        }
        blocked.clear()
        blocked += config.getStringList("blockedReports")

        permission("report.block", PermissionDefault.OP, "プレイヤーからの通報を制限する")

        command("report", description = "プレイヤーを通報する") { sender, _, _ ->
            if (sender is Player) report(sender)
            true
        }
        command("reportblock", permission = "report.block", description = "プレイヤーからの通報を制限する") { sender, _, args ->
            if (args.size != 1) {
                sender.sendMessage("§e使用法: /reportblock <プレイヤー名>")
                return@command true
            }
            val target = args[0]
            if (target in blocked) {
                setBlocked(target, false)
                sender.sendMessage("§a$target の通報制限を解除しました。")
            } else {
                setBlocked(target, true)
                sender.sendMessage("§c$target からの通報をブロックしました。")
            }
            true
        }

        listen(ReportListener())
    }

    override fun onDisable() {
        // 開いている通報の GUI を閉じる
        Bukkit.getOnlinePlayers()
            .filter { it.openInventory.topInventory.holder is ReportMenu }
            .forEach { it.closeInventory() }
        sessions.clear()
        history.clear()
    }

    private fun setBlocked(name: String, block: Boolean) {
        if (block) blocked += name else blocked -= name
        config.set("blockedReports", blocked.toList())
        saveConfig()
    }

    /** 5 分間に 10 回まで */
    private fun canReport(uuid: UUID): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = history.getOrPut(uuid) { ArrayDeque() }
        while (timestamps.isNotEmpty() && now - timestamps.first() > 300_000) timestamps.removeFirst()
        if (timestamps.size >= 10) return false
        timestamps.addLast(now)
        return true
    }

    private fun report(p: Player) {
        if (p.name in blocked) {
            p.sendMessage("§cあなたは通報を使用できません。")
            return
        }
        if (!canReport(p.uniqueId)) {
            p.sendMessage("§c連続しての通報は制限しています。")
            return
        }
        val inv = ReportMenu(Menu.REASON).inventory
        inv.setItem(11, item(Material.TNT, "破壊(荒らし行為)"))
        inv.setItem(13, item(Material.GRAY_CONCRETE_POWDER, "嫌がらせ"))
        inv.setItem(15, item(Material.YELLOW_CONCRETE, "その他"))
        sessions[p.uniqueId] = Session()
        p.openInventory(inv)
    }

    private fun openPlayerMenu(p: Player) {
        val inv = ReportMenu(Menu.PLAYER).inventory
        // 53 番目は「ここにいない」に使う。見えない人 (vanish 中など) は出さない
        Bukkit.getOnlinePlayers().filter { p.canSee(it) }.take(53).forEachIndexed { slot, target -> inv.setItem(slot, head(target)) }
        inv.setItem(53, item(Material.YELLOW_CONCRETE, "ここにいない"))
        p.openInventory(inv)
    }

    private fun openDestructionCheckMenu(p: Player) {
        val inv = ReportMenu(Menu.DESTRUCTION).inventory
        inv.setItem(3, item(Material.LIME_CONCRETE, "はい"))
        inv.setItem(5, item(Material.RED_CONCRETE, "いいえ"))
        p.openInventory(inv)
    }

    private fun finishReport(p: Player) {
        val s = sessions.remove(p.uniqueId) ?: return
        val loc = p.location
        val coords = String.format("(X: %.1f Y: %.1f Z: %.1f)", loc.x, loc.y, loc.z)
        val detail = when {
            s.reasonDetail != null -> s.reasonDetail
            s.harassmentDetail != null -> "嫌がらせの詳細: ${s.harassmentDetail}"
            else -> null
        }
        webhook?.let { sender ->
            val reporter = p.name
            runAsync { sender.sendReport(reporter, s.target, s.reason, detail, coords) }
        }
        p.sendMessage("§a通報を送信しました。")
    }

    private inner class ReportListener : Listener {
        @EventHandler
        fun onInventoryClick(e: InventoryClickEvent) {
            val menu = e.view.topInventory.holder as? ReportMenu ?: return
            e.isCancelled = true
            val p = e.whoClicked as? Player ?: return
            val state = sessions[p.uniqueId] ?: return
            // 自分のインベントリのアイテムは選択肢として扱わない
            if (e.clickedInventory != e.view.topInventory) return
            val name = e.currentItem?.itemMeta?.displayName()?.let { plain.serialize(it) } ?: ""
            if (name.isEmpty()) return

            when (menu.menu) {
                Menu.REASON -> {
                    state.reason = name
                    state.isTransitioning = true
                    when (name) {
                        "破壊(荒らし行為)" -> {
                            p.closeInventory()
                            openDestructionCheckMenu(p)
                        }
                        "嫌がらせ" -> {
                            p.closeInventory()
                            state.waitingHarassmentDetail = true
                            p.sendMessage("§eどのような嫌がらせを受けましたか？チャット欄に入力してください。")
                        }
                        "その他" -> {
                            p.closeInventory()
                            state.waitingReasonText = true
                            p.sendMessage("§e通報内容をチャットで入力してください。")
                        }
                        else -> openPlayerMenu(p)
                    }
                }
                Menu.DESTRUCTION -> {
                    if (name == "はい") {
                        state.isTransitioning = true
                        p.closeInventory()
                        openPlayerMenu(p)
                    } else {
                        p.closeInventory()
                        p.sendMessage("§cその場所に移動してから再度通報してください。")
                        sessions.remove(p.uniqueId)
                    }
                }
                Menu.PLAYER -> {
                    state.isTransitioning = true
                    p.closeInventory()
                    if (name == "ここにいない") {
                        state.waitingPlayerName = true
                        p.sendMessage("§e通報するプレイヤーの名前を入力してください。わからない場合は適当な1文字を入力してください。")
                        return
                    }
                    state.target = name
                    finishReport(p)
                }
            }
        }

        @EventHandler
        fun onInventoryDrag(e: InventoryDragEvent) {
            if (e.view.topInventory.holder is ReportMenu) e.isCancelled = true
        }

        @EventHandler
        fun onInventoryClose(e: InventoryCloseEvent) {
            if (e.inventory.holder !is ReportMenu) return
            val state = sessions[e.player.uniqueId] ?: return
            if (state.isTransitioning) {
                state.isTransitioning = false
                return
            }
            sessions.remove(e.player.uniqueId)
        }

        @EventHandler
        fun onQuit(e: PlayerQuitEvent) {
            sessions.remove(e.player.uniqueId)
        }

        @EventHandler
        fun onChat(e: AsyncChatEvent) {
            val p = e.player
            val state = sessions[p.uniqueId] ?: return
            when {
                state.waitingReasonText -> {
                    e.isCancelled = true
                    state.reasonDetail = plain.serialize(e.message())
                    state.waitingReasonText = false
                    runSync { if (p.isOnline) openPlayerMenu(p) }
                }
                state.waitingHarassmentDetail -> {
                    e.isCancelled = true
                    state.harassmentDetail = plain.serialize(e.message())
                    state.waitingHarassmentDetail = false
                    runSync { if (p.isOnline) openPlayerMenu(p) }
                }
                state.waitingPlayerName -> {
                    e.isCancelled = true
                    state.target = plain.serialize(e.message())
                    state.waitingPlayerName = false
                    runSync { if (p.isOnline) finishReport(p) }
                }
            }
        }
    }

    private fun item(m: Material, name: String) = ItemStack(m).apply {
        editMeta { it.displayName(Component.text(name)) }
    }

    private fun head(target: Player) = ItemStack(Material.PLAYER_HEAD).apply {
        editMeta(SkullMeta::class.java) {
            it.owningPlayer = target
            it.displayName(Component.text(target.name))
        }
    }
}

private enum class Menu(val title: String, val size: Int) {
    REASON("通報理由を選択", 27),
    DESTRUCTION("破壊されているのは現在の場所ですか?", 9),
    PLAYER("誰を通報しますか？", 54),
}

/** 通報の GUI。タイトルの比較の代わりに holder で見分ける */
private class ReportMenu(val menu: Menu) : InventoryHolder {
    private val inventory: Inventory = Bukkit.createInventory(this, menu.size, Component.text(menu.title))
    override fun getInventory(): Inventory = inventory
}

/** 通報の途中の状態 */
private class Session {
    @Volatile var reason: String? = null
    @Volatile var reasonDetail: String? = null
    @Volatile var harassmentDetail: String? = null
    @Volatile var target: String? = null
    @Volatile var waitingReasonText = false
    @Volatile var waitingPlayerName = false
    @Volatile var waitingHarassmentDetail = false
    @Volatile var isTransitioning = false
}
