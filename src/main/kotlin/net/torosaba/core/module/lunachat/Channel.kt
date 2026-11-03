package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import net.torosaba.core.module.lunachat.japanize.JapanizeType
import net.torosaba.core.module.lunachat.japanize.Japanizer
import org.bukkit.Bukkit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * チャンネル (LunaChat の Channel と BukkitChannel)。
 * 名前に `>` を含むものは /tell の 1:1 チャットで、ファイルには保存しない。
 *
 * 発言の処理と設定の変更はメインスレッドで行う。メンバーなどの一覧はチャットのスレッドからも読むので、
 * 読みながら変更してよい入れ物にしてある
 */
internal class Channel(private val lc: LunaChatModule, val name: String) {
    var alias = ""
    var description = ""
    var format: String = if (name.contains(">")) lc.settings.defaultFormatForPrivateMessage else lc.settings.defaultFormat
    val storedMembers = CopyOnWriteArrayList<ChannelMember>()
    val moderator = CopyOnWriteArrayList<ChannelMember>()
    val banned = CopyOnWriteArrayList<ChannelMember>()
    val muted = CopyOnWriteArrayList<ChannelMember>()
    val hided = CopyOnWriteArrayList<ChannelMember>()
    var password = ""
    var visible = true
    var colorCode = ""
    var broadcast = false
    var isWorldRange = false
    var chatRange = 0
    val banExpires = ConcurrentHashMap<ChannelMember, Long>()
    val muteExpires = ConcurrentHashMap<ChannelMember, Long>()
    var allowCC = true

    /** チャンネルごとの Japanize 変換。null なら config.yml の設定 */
    var japanizeType: JapanizeType? = lc.settings.japanizeType

    /** 1:1 チャットの相手 */
    var privateMessageTo: ChannelMember? = null

    private val logger = ChatLogger(lc.dataFolder, if (name.contains(">")) "pm/" + name.replace(">", "-") else name.replace("*", "_"), lc.logWriter)

    private val settings get() = lc.settings
    private val messages get() = lc.messages

    val isPersonalChat: Boolean get() = name.contains(">")
    val isGlobalChannel: Boolean get() = name == settings.globalChannel
    val isBroadcastChannel: Boolean get() = isGlobalChannel || broadcast
    val isForceJoinChannel: Boolean get() = name in settings.forceJoinChannels

    /** メンバー。ブロードキャストチャンネルなら、いまサーバーにいる全員 */
    val members: List<ChannelMember>
        get() = if (isBroadcastChannel) Bukkit.getOnlinePlayers().map { lc.member(it) } else storedMembers

    val onlineNum: Int
        get() = if (isBroadcastChannel) Bukkit.getOnlinePlayers().size else storedMembers.count { it.isOnline }

    val totalNum: Int get() = if (isBroadcastChannel) Bukkit.getOnlinePlayers().size else storedMembers.size

    fun hasModeratorPermission(member: ChannelMember?): Boolean =
        member != null && (member.hasPermission("lunachat-admin.mod-all-channels") || member in moderator)

    fun vars(): ChannelVars = ChannelVars(name, colorCode, privateMessageTo?.vars())

    fun makeFormat(member: ChannelMember, withPlayerLink: Boolean = true): ChatFormat =
        ChatFormat.make(format, member.vars(), vars(), messages, withPlayerLink, lc.manager::getTemplate)

    // ---- 発言 ----

    /** このチャンネルに発言する。メインスレッドで呼ぶ */
    fun chat(player: ChannelMember, message: String) {
        // 発言権限
        val node = "lunachat.speak.$name"
        if (player.isPermissionSet(node) && !player.hasPermission(node)) {
            player.send(messages.error("errmsgPermission", "permission" to node))
            return
        }
        if (player in muted) {
            player.send(messages.error("errmsgMuted"))
            return
        }

        var masked = message

        // 一時的に Japanize しない印
        var skipJapanize = false
        Japanizer.stripMarker(masked, settings.noneJapanizeMarker)?.let {
            skipJapanize = true
            masked = it
        }

        // NG ワード
        val isNg = Japanizer.containsNgWord(masked, settings.ngword)
        masked = Japanizer.maskNgWords(masked, settings.ngword)

        val format = makeFormat(player)

        // 色コードは、チャンネルで許可されていて権限があるときだけ
        masked = if (allowCC && player.hasPermission("lunachat.allowcc")) Colors.replace(masked) else Colors.strip(masked)

        // 2 バイト文字を含むか半角カタカナだけなら、Japanize しない
        val kanaTemp = Colors.strip(masked)
        if (!skipJapanize && Japanizer.isSkipped(kanaTemp)) skipJapanize = true

        val type = japanizeType ?: settings.japanizeType
        val japanize = !skipJapanize && lc.manager.isPlayerJapanize(player.name) && type != JapanizeType.NONE

        if (japanize && settings.japanizeDisplayLine == 1) {
            // 1 行表示: 変換が終わってから、変換結果を含めた 1 行だけを出す
            japanizeLater(player, masked, type, settings.japanizeLine1Format, format)
        } else {
            sendMessage(player, masked, format, true)
            if (japanize) japanizeLater(player, masked, type, settings.japanizeLine2Format, null)
        }

        if (isNg) ngWordAction(player)
    }

    private fun japanizeLater(player: ChannelMember, org: String, type: JapanizeType, jpFormat: String, lineFormat: ChatFormat?) {
        val format = Colors.replace(jpFormat)
        val keywords = if (settings.japanizeIgnorePlayerName) lc.onlinePlayerNames() else emptyList()
        val dictionary = lc.manager.dictionarySnapshot()
        val ngwords = settings.ngword
        lc.async {
            val japanized = Japanizer.japanize(org, type, keywords, dictionary, ngwords)
            val result = Japanizer.format(format, org, japanized)
            lc.sync { sendMessage(player, result, lineFormat, true) }
        }
    }

    private fun ngWordAction(player: ChannelMember) {
        when (settings.ngwordAction) {
            NgWordAction.BAN -> if (!isGlobalChannel) {
                banned.addIfAbsent(player)
                removeMember(player)
                messages.channel("banNGWordMessage", colorCode, name, "player" to player.name)?.let {
                    player.send(it)
                    sendSystemMessage(it, true, "system")
                }
                save()
            }
            NgWordAction.KICK -> if (!isGlobalChannel) {
                removeMember(player)
                messages.channel("kickNGWordMessage", colorCode, name, "player" to player.name)?.let {
                    player.send(it)
                    sendSystemMessage(it, true, "system")
                }
            }
            NgWordAction.MUTE -> {
                muted.addIfAbsent(player)
                save()
                messages.channel("muteNGWordMessage", colorCode, name, "player" to player.name)?.let {
                    player.send(it)
                    sendSystemMessage(it, true, "system")
                }
            }
            NgWordAction.MASK -> {}
        }
    }

    /** dynmap の Web チャットなど、ほかの所からの発言を流す */
    fun chatFromOtherSource(player: String, source: String?, message: String) {
        val displayName = if (!source.isNullOrEmpty()) "$player@$source" else player
        var masked = Japanizer.maskNgWords(message, settings.ngword)
        val member = OtherMember(displayName)
        val format = ChatFormat.make(format, member.vars(), vars(), messages, false, lc.manager::getTemplate)
        if (allowCC) masked = Colors.replace(masked)
        sendMessage(member, masked, format, source != "web")
    }

    /** システムメッセージ (BAN しました など) をメンバーに流す */
    fun sendSystemMessage(message: Component, sendDynmap: Boolean, name: String) {
        val recipients = storedOrBroadcastRecipients().toMutableList()
        addListeners(recipients)

        val dynmap = lc.dynmap
        if (settings.sendBroadcastChannelChatToDynmap && sendDynmap && dynmap != null && isBroadcastChannel && !isWorldRange) {
            dynmap.broadcast(Colors.legacy(message))
        }
        recipients.forEach { it.send(message) }
        val plain = Colors.plain(message)
        if (settings.displayChatOnConsole) lc.consoleLog(plain)
        log(plain, name)
    }

    private fun storedOrBroadcastRecipients(): List<ChannelMember> = members.filter { it !in hided }

    /** opListenAllChannel なら、全チャンネルを聞く権限のある人を足す */
    private fun addListeners(recipients: MutableList<ChannelMember>) {
        if (!settings.opListenAllChannel) return
        for (p in Bukkit.getOnlinePlayers()) {
            val cp = lc.member(p)
            if (cp.hasPermission("lunachat-admin.listen-all-channels") && cp !in recipients) recipients += cp
        }
    }

    /**
     * 発言をメンバーに送る (BukkitChannel#sendMessage)。
     * @param format チャンネルのフォーマット。null なら [message] をそのまま送る (2 行表示の 2 行目)
     */
    fun sendMessage(player: ChannelMember, message: String, format: ChatFormat?, sendDynmap: Boolean) {
        var recipients: MutableList<ChannelMember> =
            if (isBroadcastChannel) {
                Bukkit.getOnlinePlayers().map { lc.member(it) }.filter { it !in hided }.toMutableList()
            } else {
                storedMembers.filter { it.isOnline && it !in hided }.toMutableList()
            }

        var noRecipient = false
        if (isWorldRange && player.isOnline && player.world != null) {
            // ワールドチャット・範囲チャットは、範囲外の人を外す
            recipients = if (chatRange > 0) {
                val origin = player.location
                recipients.filter { r ->
                    val target = r.location
                    origin != null && target != null && origin.world == target.world && origin.distance(target) <= chatRange
                }.toMutableList()
            } else {
                val w = player.world
                recipients.filter { it.world != null && it.world == w }.toMutableList()
            }
            if (messages.has("noRecipientMessage") &&
                (recipients.isEmpty() || (recipients.size == 1 && recipients[0].name == player.name))
            ) {
                noRecipient = true
            }
        }

        addListeners(recipients)

        // 発言者を非表示にしている人を外す
        val hiders = lc.manager.getHidelist(player)
        recipients.removeAll { it in hiders }

        // dynmap へ
        val dynmap = lc.dynmap
        if (settings.sendBroadcastChannelChatToDynmap && sendDynmap && dynmap != null && isBroadcastChannel && !isWorldRange) {
            val text = if (settings.sendFormattedMessageToDynmap && format != null) {
                format.setMessage(message)
                format.toLegacyText()
            } else message
            val p = player.player
            if (p != null) dynmap.chat(p, text) else dynmap.broadcast(text)
        }

        // 送る
        val consoleText: String
        if (format != null) {
            format.setMessage(message)
            val component = format.toComponent()
            recipients.forEach { it.send(component) }
            consoleText = format.toLegacyText()
        } else {
            val component = Colors.component(message)
            recipients.forEach { it.send(component) }
            consoleText = message
        }

        if (settings.displayChatOnConsole) lc.consoleLog(consoleText)

        if (noRecipient) messages.channel("noRecipientMessage", colorCode, name)?.let { player.send(it) }

        log(message, player.displayName)
    }

    private fun log(message: String, name: String) {
        if (settings.loggingChat) logger.log(message, name)
    }

    fun readLog(player: String?, filter: String?, date: String?, reverse: Boolean) =
        logger.read(player, filter, date, reverse)

    // ---- メンバーの出入り ----

    fun addMember(player: ChannelMember) {
        if (player in storedMembers) return
        if (storedMembers.isEmpty() && moderator.isEmpty()) moderator += player
        storedMembers += player
        if (!isPersonalChat) messages.channel("joinMessage", colorCode, name, "player" to player.name)?.let { player.send(it) }
        save()
    }

    fun removeMember(player: ChannelMember) {
        if (player !in storedMembers) return

        // 発言先がこのチャンネルなら、発言先を消す
        val def = lc.manager.getDefaultChannel(player.name)
        if (def != null && def.name == name) lc.manager.removeDefaultChannel(player.name)

        storedMembers.remove(player)
        if (!isPersonalChat) messages.channel("quitMessage", colorCode, name, "player" to player.name)?.let { player.send(it) }

        if (settings.zeroMemberRemove && storedMembers.isEmpty()) {
            lc.manager.removeChannel(name)
            return
        }
        hided.remove(player)
        moderator.remove(player)
        save()
    }

    fun addModerator(player: ChannelMember) {
        if (player in moderator) return
        moderator += player
        if (!isPersonalChat) messages.channel("addModeratorMessage", colorCode, name, "player" to player.name)?.let { player.send(it) }
        save()
    }

    fun removeModerator(player: ChannelMember) {
        if (player !in moderator) return
        moderator.remove(player)
        if (!isPersonalChat) messages.channel("removeModeratorMessage", colorCode, name, "player" to player.name)?.let { player.send(it) }
        save()
    }

    // ---- 情報 ----

    /** /ch info の内容 */
    fun info(forModerator: Boolean): List<Component> {
        val lines = mutableListOf<Component>()
        fun add(legacy: String) {
            lines += Colors.component(legacy)
        }
        add(messages.plain("channelInfoFirstLine"))
        messages.channel("listFormat", "", name, "online" to onlineNum, "total" to totalNum, "topic" to description)
            ?.let { lines += it }
        if (alias.isNotEmpty()) add(messages.plain("channelInfoAlias") + alias)

        val prefix = messages.plain("channelInfoPrefix")
        when {
            isGlobalChannel -> add(messages.plain("channelInfoGlobal"))
            isBroadcastChannel -> add(messages.plain("channelInfoBroadcast"))
            else -> members.chunked(5).ifEmpty { listOf(emptyList()) }.forEach { chunk ->
                add(prefix + chunk.joinToString("") { cp ->
                    var n = cp.name
                    if (cp in moderator) n = "@$n"
                    val color = when {
                        !cp.isOnline -> "§7"
                        cp in hided -> "§3"
                        else -> "§f"
                    }
                    "$color$n,"
                })
            }
        }
        if (!visible) add(messages.plain("channelInfoSecret"))
        if (password.isNotEmpty()) {
            add(if (forModerator) messages.plain("channelInfoPassword") + " " + password else messages.plain("channelInfoPassword"))
        }
        if (isWorldRange && chatRange > 0) add(messages.plain("channelInfoRangeChat", "block" to chatRange))
        else if (isWorldRange) add(messages.plain("channelInfoWorldChat"))

        if (forModerator) {
            add(messages.plain("channelInfoFormat"))
            add("$prefix §f$format")
            if (muted.isNotEmpty()) {
                add(messages.plain("channelInfoMuted"))
                muted.chunked(5).forEach { chunk -> add(prefix + "§f" + chunk.joinToString("") { it.name + "," }) }
            }
            if (banned.isNotEmpty()) {
                add(messages.plain("channelInfoBanned"))
                banned.chunked(5).forEach { chunk -> add(prefix + "§f" + chunk.joinToString("") { it.name + "," }) }
            }
        }
        add(messages.plain("listEndLine"))
        return lines
    }

    /** 期限付きの BAN と Mute が切れていたら解く */
    fun checkExpires() {
        val now = System.currentTimeMillis()
        for ((cp, expire) in banExpires.entries.toList()) {
            if (expire > now) continue
            banExpires.remove(cp)
            if (banned.remove(cp)) {
                save()
                messages.channel("expiredBanMessage", colorCode, name, "player" to cp.name)?.let { sendSystemMessage(it, true, "system") }
                val pardoned = messages.info("cmdmsgPardoned", "channel" to name)
                if (cp.isOnline) cp.send(pardoned)
            }
        }
        for ((cp, expire) in muteExpires.entries.toList()) {
            if (expire > now) continue
            muteExpires.remove(cp)
            if (muted.remove(cp)) {
                save()
                messages.channel("expiredMuteMessage", colorCode, name, "player" to cp.name)?.let { sendSystemMessage(it, true, "system") }
                val unmuted = messages.info("cmdmsgUnmuted", "channel" to name)
                if (cp.isOnline) cp.send(unmuted)
            }
        }
    }

    // ---- 保存 ----

    private fun file(): File = File(File(lc.dataFolder, FOLDER), "$name.yml")

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "alias" to alias,
        "desc" to description,
        "format" to format,
        "members" to storedMembers.map { it.id },
        "banned" to banned.map { it.id },
        "muted" to muted.map { it.id },
        "hided" to hided.map { it.id },
        "moderator" to moderator.map { it.id },
        "password" to password,
        "visible" to visible,
        "color" to colorCode,
        "broadcast" to broadcast,
        "world" to isWorldRange,
        "range" to chatRange,
        "ban_expires" to banExpires.entries.associate { it.key.id to it.value },
        "mute_expires" to muteExpires.entries.associate { it.key.id to it.value },
        "allowcc" to allowCC,
        "japanize" to japanizeType?.toString(),
    )

    /** channels/<name>.yml に保存する。1:1 チャットは保存しない */
    fun save(): Boolean {
        if (isPersonalChat) return false
        return try {
            FlatYaml.save(file(), serialize())
            true
        } catch (e: Exception) {
            lc.logger.warning("チャンネル $name を保存できませんでした: $e")
            false
        }
    }

    fun deleteFile(): Boolean = file().let { it.exists() && it.delete() }

    companion object {
        const val FOLDER = "channels"

        /** LunaChat の Channel#deserialize */
        fun deserialize(lc: LunaChatModule, data: Map<String, Any?>): Channel? {
            val name = data["name"]?.toString() ?: return null
            return Channel(lc, name).apply {
                alias = data["alias"]?.toString() ?: ""
                description = data["desc"]?.toString() ?: ""
                format = data["format"]?.toString() ?: format
                storedMembers += memberList(lc, data["members"])
                banned += memberList(lc, data["banned"])
                muted += memberList(lc, data["muted"])
                hided += memberList(lc, data["hided"])
                moderator += memberList(lc, data["moderator"])
                password = data["password"]?.toString() ?: ""
                visible = data["visible"] as? Boolean ?: true
                colorCode = data["color"]?.toString() ?: ""
                broadcast = data["broadcast"] as? Boolean ?: false
                isWorldRange = data["world"] as? Boolean ?: false
                chatRange = (data["range"] as? Number)?.toInt() ?: 0
                banExpires.putAll(expireMap(lc, data["ban_expires"]))
                muteExpires.putAll(expireMap(lc, data["mute_expires"]))
                allowCC = data["allowcc"] as? Boolean ?: true
                japanizeType = JapanizeType.fromId(data["japanize"]?.toString(), null)
            }
        }

        private fun memberList(lc: LunaChatModule, value: Any?): List<ChannelMember> =
            (value as? List<*>)?.mapNotNull { it?.toString()?.let(lc::memberById) }?.distinct() ?: emptyList()

        private fun expireMap(lc: LunaChatModule, value: Any?): Map<ChannelMember, Long> {
            val map = value as? Map<*, *> ?: return emptyMap()
            val result = HashMap<ChannelMember, Long>()
            for ((k, v) in map) {
                val member = k?.toString()?.let(lc::memberById) ?: continue
                result[member] = (v as? Number)?.toLong() ?: continue
            }
            return result
        }
    }
}
