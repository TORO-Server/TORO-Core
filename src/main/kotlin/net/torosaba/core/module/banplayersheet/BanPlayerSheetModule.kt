package net.torosaba.core.module.banplayersheet

import net.torosaba.core.Module
import net.torosaba.core.legacy
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.permissions.PermissionDefault
import java.util.UUID

/**
 * 旧 BanPlayerSheet。
 * Google スプレッドシート (GAS 経由) の BAN リストを複数のサーバーで共有し、
 * 載っているプレイヤーのログインを断る。/sban と /unsban でリストに出し入れする。
 */
class BanPlayerSheetModule : Module("ban-player-sheet", "BanPlayerSheet") {
    private lateinit var api: SheetApi

    private val url: String get() = config.getString("URL") ?: ""
    private fun message(key: String): String = config.getString("Message.$key") ?: ""

    override fun onEnable() {
        api = SheetApi(logger)

        permission("player_sheet.sban", PermissionDefault.OP, "共有 BAN リストにプレイヤーを追加する")
        permission("player_sheet.unsban", PermissionDefault.OP, "共有 BAN リストからプレイヤーを削除する")
        permission(
            "player_sheet.*", PermissionDefault.OP, "BanPlayerSheet のすべての権限",
            mapOf("player_sheet.sban" to true, "player_sheet.unsban" to true),
        )

        listen(LoginListener())

        command("sban", permission = "player_sheet.sban", description = "Spreadsheet Ban", usage = "",
            tab = { _, _, args ->
                when (args.size) {
                    1 -> playerNames()
                    2 -> listOf(message("BanReason"))
                    else -> emptyList()
                }
            }) { sender, _, args -> sban(sender, args); true }

        command("unsban", permission = "player_sheet.unsban", description = "Spreadsheet Unban", usage = "",
            tab = { _, _, args -> if (args.size == 1) playerNames() else emptyList() }
        ) { sender, _, args -> unsban(sender, args); true }
    }

    private inner class LoginListener : Listener {
        // 非同期のイベントなので、ここで通信を待ってよい
        @EventHandler
        fun onLogin(event: AsyncPlayerPreLoginEvent) {
            when (api.isBanned(url, event.uniqueId.toString())) {
                null -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message("ErrorDatabase").legacy())
                true -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message("Kick").legacy())
                false -> {}
            }
        }
    }

    private fun sban(sender: CommandSender, args: Array<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(message("sBan").legacy())
            return
        }
        val name = args[0]
        val reason = args.drop(1).joinToString(" ")
        runAsync {
            val uuid = api.playerUuid(name)
            if (uuid == null) {
                reply(sender, message("PlayerNotFound"))
                return@runAsync
            }
            logger.info("----------sBan----------")
            logger.info("BanPlayerName: $name")
            logger.info("BanPlayerUUID: $uuid")
            logger.info("BanReason: $reason")
            logger.info("----------sBan----------")

            // リストへの書き込みを待たずに、オンラインならすぐキックする
            runSync { Bukkit.getPlayer(UUID.fromString(uuid))?.kick(message("Kick").legacy()) }

            when (api.addBan(url, name, uuid, reason)) {
                null -> reply(sender, message("ErrorDatabase"))
                0 -> reply(sender, message("addBAN"))
                1 -> reply(sender, message("alreadyBAN"))
            }
        }
    }

    private fun unsban(sender: CommandSender, args: Array<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(message("unsBan").legacy())
            return
        }
        val name = args[0]
        runAsync {
            val uuid = api.playerUuid(name)
            if (uuid == null) {
                reply(sender, message("PlayerNotFound"))
                return@runAsync
            }
            logger.info("----------unsBan----------")
            logger.info("BanPlayerName: $name")
            logger.info("BanPlayerUUID: $uuid")
            logger.info("----------unsBan----------")

            when (api.removeBan(url, uuid)) {
                null -> reply(sender, message("ErrorDatabase"))
                0 -> reply(sender, message("rmvBAN"))
                1 -> reply(sender, message("notBAN"))
            }
        }
    }

    /** 非同期処理の中からメッセージを送る */
    private fun reply(sender: CommandSender, text: String) {
        runSync { sender.sendMessage(text.legacy()) }
    }

    private fun playerNames(): List<String> =
        (Bukkit.getOfflinePlayers().mapNotNull { it.name } + Bukkit.getOnlinePlayers().map { it.name }).distinct()
}
