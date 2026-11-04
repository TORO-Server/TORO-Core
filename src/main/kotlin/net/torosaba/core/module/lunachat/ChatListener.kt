package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import net.torosaba.core.module.lunachat.japanize.JapanizeType
import net.torosaba.core.module.lunachat.japanize.Japanizer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.IllegalFormatException

/** 発言をどこへ流すかの判定 (テストできるよう Bukkit に触らない部分) */
object ChatRouting {
    /** 頭にグローバルマーカーがあれば、外した発言を返す。マーカーだけの発言はそのまま (null) */
    fun stripGlobalMarker(message: String, marker: String): String? =
        if (marker.isNotEmpty() && message.startsWith(marker) && message.length > marker.length) {
            message.substring(marker.length)
        } else null

    /** クイックチャンネルチャット `チャンネル名:発言` を (チャンネル名, 発言) に分ける。区切りが無ければ null */
    fun splitQuick(message: String, separator: String): Pair<String, String>? {
        if (separator.isEmpty()) return null
        val index = message.indexOf(separator)
        if (index < 0) return null
        return message.substring(0, index) to message.substring(index + separator.length)
    }

    /**
     * クイックチャンネルチャットの宛先と発言。
     * 発言者が入っていないチャンネルは宛先にしない (null を返して、普通の発言として流す)
     *
     * @param find チャンネル名か別名からチャンネルを引く
     * @param isJoined 発言者がそのチャンネルに入っているか
     */
    fun <C> quickTarget(message: String, separator: String, find: (String) -> C?, isJoined: (C) -> Boolean): Pair<C, String>? {
        val (name, value) = splitQuick(message, separator) ?: return null
        if (value.isBlank()) return null
        val channel = find(name) ?: return null
        return if (isJoined(channel)) channel to value else null
    }

    private val channelNameChars = Regex("[0-9a-zA-Z\\-_]+")

    /** チャンネル名と別名に使える文字だけでできているか */
    fun isChannelNameChars(name: String): Boolean = channelNameChars.matches(name)
}

/**
 * チャットと参加・退出の処理 (LunaChat の BukkitEventListener)。
 *
 * Paper の AsyncChatEvent ではなく、LunaChat と同じ AsyncPlayerChatEvent を使う。
 * Paper はこのイベントの後で AsyncChatEvent を出し、ここで変えた発言 (Japanize の結果を含む) と書式を引き継ぐので、
 * どちらのイベントを聞く DiscordSRV や dynmap にも、LunaChat のときと同じく変換後の発言が届く。
 * チャンネルチャットはイベントを取り消して自分で送る (LunaChat と同じ)
 */
@Suppress("DEPRECATION")
internal class ChatListener(private val lc: LunaChatModule) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChatLowest(event: AsyncPlayerChatEvent) = handle(event, EventPriority.LOWEST)

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChatLow(event: AsyncPlayerChatEvent) = handle(event, EventPriority.LOW)

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onChatNormal(event: AsyncPlayerChatEvent) = handle(event, EventPriority.NORMAL)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChatHigh(event: AsyncPlayerChatEvent) = handle(event, EventPriority.HIGH)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChatHighest(event: AsyncPlayerChatEvent) = handle(event, EventPriority.HIGHEST)

    private fun handle(event: AsyncPlayerChatEvent, priority: EventPriority) {
        if (lc.settings.playerChatEventListenerPriority == priority) process(event)
    }

    private fun process(event: AsyncPlayerChatEvent) {
        val settings = lc.settings
        val manager = lc.manager

        // グローバルマーカーが付いていれば、一時的にグローバル発言にする
        ChatRouting.stripGlobalMarker(event.message, settings.globalMarker)?.let {
            event.message = it
            chatGlobal(event)
            return
        }

        val member = lc.member(event.player)

        // クイックチャンネルチャット
        if (settings.enableQuickChannelChat) {
            ChatRouting.quickTarget(event.message, settings.quickChannelChatSeparator, manager::getChannel) {
                !it.isPersonalChat && member in it.members
            }?.let { (channel, value) ->
                event.isCancelled = true
                lc.sync { channel.playerChat(member, value) }
                return
            }
        }

        val channel = manager.getDefaultChannel(member.name)
        if (channel == null) {
            if (settings.noJoinAsGlobal) chatGlobal(event) else event.isCancelled = true
            return
        }

        val message = event.message
        event.isCancelled = true
        lc.sync { channel.playerChat(member, message) }
    }

    /** グローバル発言にする。グローバルチャンネルが無ければ通常のチャットのまま流す */
    private fun chatGlobal(event: AsyncPlayerChatEvent) {
        val settings = lc.settings
        val member = lc.member(event.player)

        if (settings.globalChannel.isNotEmpty()) {
            val message = event.message
            event.isCancelled = true
            lc.sync {
                val manager = lc.manager
                val global = manager.getChannel(settings.globalChannel) ?: manager.createChannel(settings.globalChannel)
                if (manager.getDefaultChannel(member.name) == null) manager.setDefaultChannel(member.name, global.name)
                global.playerChat(member, message)
            }
            return
        }

        normalChat(event, member)
    }

    /** 通常チャット。チャットのスレッドで、イベントの発言と書式を書き換える */
    private fun normalChat(event: AsyncPlayerChatEvent, member: ChannelMember) {
        val settings = lc.settings
        val player = event.player

        var message = Japanizer.maskNgWords(event.message, settings.ngword)

        if (settings.enableNormalChatColorCode && player.hasPermission("lunachat.allowcc")) {
            message = Colors.replace(message)
        }

        // 発言者を非表示にしている人には届けない
        val hiders = lc.manager.getHidelist(member).mapNotNull { it.player }
        if (hiders.isNotEmpty()) {
            try {
                event.recipients.removeAll(hiders.toSet())
            } catch (e: UnsupportedOperationException) {
                // 受信者を変えられないイベントはそのまま
            }
        }

        var skipJapanize = false
        Japanizer.stripMarker(message, settings.noneJapanizeMarker)?.let {
            skipJapanize = true
            message = it
        }
        val kanaTemp = Colors.strip(message)
        if (!skipJapanize && Japanizer.isSkipped(kanaTemp)) skipJapanize = true

        if (!skipJapanize && lc.manager.isPlayerJapanize(member.name) && settings.japanizeType != JapanizeType.NONE) {
            val keywords = if (settings.japanizeIgnorePlayerName) lc.onlinePlayerNames() else emptyList()
            if (settings.japanizeDisplayLine == 1) {
                // 1 行表示: このスレッドで変換を待ち、発言そのものを書き換える (ほかのプラグインにも変換後の発言が届く)
                val japanized = Japanizer.japanize(
                    kanaTemp, settings.japanizeType, keywords, lc.manager.dictionarySnapshot(), settings.ngword,
                )
                message = Japanizer.format(Colors.replace(settings.japanizeLine1Format), message, japanized)
            } else {
                japanizeSecondLine(event, player, message, keywords)
            }
        }

        if (settings.enableNormalChatClickable) {
            // クリックできるチャット: イベントを取り消して自分で送る。取り消すと raid-watcher のイベントの判定を通らないので、ここで通す
            if (!lc.allowChat(member, message)) {
                event.isCancelled = true
                return
            }
            val formatText = if (settings.enableNormalChatMessageFormat) {
                settings.normalChatMessageFormat
            } else {
                event.format.replace("%1\$s", "%displayname").replace("%2\$s", "%msg")
            }
            val format = ChatFormat.make(formatText, member.vars(), null, lc.messages, true)
            format.setMessage(message)
            val component = format.toComponent()
            event.recipients.forEach { it.sendMessage(component) }
            event.isCancelled = true
            if (settings.displayNormalChatOnConsole) lc.consoleLog(format.toLegacyText())
        } else {
            if (settings.enableNormalChatMessageFormat) {
                val format = ChatFormat.normalChatFormat(settings.normalChatMessageFormat, member.vars(), lc.messages)
                try {
                    event.format = format
                } catch (e: IllegalFormatException) {
                    lc.logger.warning("normalChatMessageFormat を書式にできませんでした: $format")
                }
            }
            event.message = message
        }

        lc.normalChatLogger.log(message, member.name)
    }

    /** 2 行表示: 変換は別スレッドで行い、元の発言の後に変換結果だけを流す */
    private fun japanizeSecondLine(event: AsyncPlayerChatEvent, player: Player, message: String, keywords: Collection<String>) {
        val settings = lc.settings
        val format = Colors.replace(settings.japanizeLine2Format)
        val dictionary = lc.manager.dictionarySnapshot()
        val type = settings.japanizeType
        val ngwords = settings.ngword
        val wait = settings.japanizeWait.toLong().coerceAtLeast(0)
        lc.async {
            val japanized = Japanizer.japanize(message, type, keywords, dictionary, ngwords)
            val result = Japanizer.format(format, message, japanized)
            lc.later(wait) {
                if (event.isCancelled) return@later
                val component: Component = Colors.component(result)
                event.recipients.forEach { it.sendMessage(component) }
                lc.consoleLog(result)
                val dynmap = lc.dynmap
                if (settings.sendBroadcastChannelChatToDynmap && dynmap != null) {
                    if (player.isOnline) dynmap.chat(player, result) else dynmap.broadcast(result)
                }
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val settings = lc.settings
        val manager = lc.manager
        val player = event.player
        lc.cacheName(player)
        val member = lc.member(player)

        // 強制参加チャンネル
        for (name in settings.forceJoinChannels) {
            val channel = manager.getChannel(name) ?: manager.createChannel(name)
            if (member !in channel.members) channel.addMember(member)
            if (manager.getDefaultChannel(member.name) == null) manager.setDefaultChannel(member.name, name)
        }

        // グローバルチャンネル
        if (settings.globalChannel.isNotEmpty()) {
            val global = manager.getChannel(settings.globalChannel) ?: manager.createChannel(settings.globalChannel)
            if (manager.getDefaultChannel(member.name) == null) manager.setDefaultChannel(member.name, global.name)
        }

        if (settings.showListOnJoin) {
            lc.channelListForJoin(member).forEach { member.send(it) }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val pname = player.name
        val quitter = lc.member(player)
        // 両方がいなくなった 1:1 チャットのチャンネルを消す (退出する人はもういないものとして数える)
        val remove = lc.manager.channels.filter { channel ->
            channel.isPersonalChat && channel.name.contains(pname) &&
                channel.members.all { it == quitter || !it.isOnline }
        }
        remove.forEach { lc.manager.removeChannel(it.name) }
    }
}
