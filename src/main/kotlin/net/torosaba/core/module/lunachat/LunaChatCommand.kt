package net.torosaba.core.module.lunachat

import net.torosaba.core.module.lunachat.japanize.JapanizeType

/** ヘルプでの分類 */
internal enum class CommandType(val desc: String) { USER("user"), MODERATOR("moderator"), ADMIN("admin") }

internal class SubCommand(
    val name: String,
    val permission: String,
    val type: CommandType,
    val usageKeys: List<String>,
    val run: (sender: ChannelMember, label: String, args: Array<String>) -> Unit,
)

/** /lunachat (/ch /lc) と、そのサブコマンド (LunaChat の LunaChatCommand と各 *Command) */
internal class LunaChatCommand(private val lc: LunaChatModule) {
    private val manager get() = lc.manager
    private val settings get() = lc.settings
    private val messages get() = lc.messages

    private fun ChannelMember.info(key: String, vararg args: Pair<String, Any>) = send(messages.info(key, *args))
    private fun ChannelMember.error(key: String, vararg args: Pair<String, Any>) = send(messages.error(key, *args))
    private fun ChannelMember.plain(key: String, vararg args: Pair<String, Any>) = send(messages.plain(key, *args))

    private val join = SubCommand("join", "lunachat.join", CommandType.USER, listOf("usageJoin"), ::join)

    private val help = SubCommand("help", "lunachat.help", CommandType.USER, listOf("usageHelp"), ::help)

    /** チャンネルチャットが無効でも使えるもの */
    private val commonCommands = listOf(
        SubCommand("hide", "lunachat.hide", CommandType.USER, listOf("usageHide", "usageHidePlayer"), ::hide),
        SubCommand("unhide", "lunachat.unhide", CommandType.USER, listOf("usageUnhide", "usageUnhidePlayer"), ::unhide),
        SubCommand("dictionary", "lunachat-admin.dictionary", CommandType.ADMIN, listOf("usageDictionary"), ::dictionary),
        SubCommand("dic", "lunachat-admin.dictionary", CommandType.ADMIN, listOf("usageDic"), ::dictionary),
        SubCommand("reload", "lunachat-admin.reload", CommandType.ADMIN, listOf("usageReload"), ::reload),
    )

    /** ヘルプに並べる順 */
    private val commands = listOf(
        join,
        SubCommand("leave", "lunachat.leave", CommandType.USER, listOf("usageLeave"), ::leave),
        SubCommand("list", "lunachat.list", CommandType.USER, listOf("usageList"), ::list),
        SubCommand("invite", "lunachat.invite", CommandType.MODERATOR, listOf("usageInvite"), ::invite),
        SubCommand("accept", "lunachat.accept", CommandType.USER, listOf("usageAccept"), ::accept),
        SubCommand("deny", "lunachat.deny", CommandType.USER, listOf("usageDeny"), ::deny),
        SubCommand("kick", "lunachat.kick", CommandType.MODERATOR, listOf("usageKick"), ::kick),
        SubCommand("ban", "lunachat.ban", CommandType.MODERATOR, listOf("usageBan", "usageBan2"), ::ban),
        SubCommand("pardon", "lunachat.pardon", CommandType.MODERATOR, listOf("usagePardon"), ::pardon),
        SubCommand("mute", "lunachat.mute", CommandType.MODERATOR, listOf("usageMute", "usageMute2"), ::mute),
        SubCommand("unmute", "lunachat.unmute", CommandType.MODERATOR, listOf("usageUnmute"), ::unmute),
        SubCommand("info", "lunachat.info", CommandType.USER, listOf("usageInfo"), ::info),
        SubCommand("log", "lunachat.log", CommandType.USER, listOf("usageLog"), ::log),
        SubCommand("create", "lunachat.create", CommandType.MODERATOR, listOf("usageCreate"), ::create),
        SubCommand("remove", "lunachat.remove", CommandType.MODERATOR, listOf("usageRemove"), ::remove),
        SubCommand("format", "lunachat.format", CommandType.MODERATOR, listOf("usageFormat"), ::format),
        SubCommand("moderator", "lunachat.moderator", CommandType.MODERATOR, listOf("usageModerator"), ::moderator),
        SubCommand("mod", "lunachat.moderator", CommandType.MODERATOR, listOf("usageMod"), ::moderator),
        SubCommand("option", "lunachat.option", CommandType.MODERATOR, listOf("usageOption"), ::option),
        SubCommand("template", "lunachat-admin.template", CommandType.ADMIN, listOf("usageTemplate"), ::template),
        SubCommand("set", "lunachat-admin.set", CommandType.ADMIN, listOf("usageSet1"), ::set),
        help,
    )

    fun execute(sender: ChannelMember, label: String, args: Array<String>) {
        if (args.isNotEmpty()) {
            commonCommands.firstOrNull { it.name.equals(args[0], true) }?.let {
                runWithPermission(it, sender, label, args)
                return
            }
        }

        if (!settings.enableChannelChat && !sender.hasPermission("lunachat-admin")) {
            sender.error("errmsgChannelChatDisabled")
            return
        }

        if (args.isEmpty()) {
            help(sender, label, args)
            return
        }

        val command = commands.firstOrNull { it.name.equals(args[0], true) } ?: join
        runWithPermission(command, sender, label, args)
    }

    private fun runWithPermission(command: SubCommand, sender: ChannelMember, label: String, args: Array<String>) {
        if (!sender.hasPermission(command.permission)) {
            sender.error("errmsgPermission", "permission" to command.permission)
            return
        }
        command.run(sender, label, args)
    }

    private fun sendUsage(command: SubCommand, sender: ChannelMember, label: String) =
        command.usageKeys.forEach { sender.send(messages.usage(it, label)) }

    // ---- join / leave / list / info ----

    private fun join(sender: ChannelMember, label: String, args: Array<String>) {
        val channelName: String
        val message: String
        if (!args[0].equals("join", true)) {
            channelName = args[0]
            message = args.drop(1).joinToString("") { "$it " }
        } else if (args.size >= 2) {
            channelName = args[1]
            message = args.drop(2).joinToString("") { "$it " }
        } else {
            sender.error("errmsgCommand")
            return
        }

        val channel = manager.getChannel(channelName)
        if (channel == null) {
            if (settings.globalChannel.isEmpty() && channelName == settings.globalMarker) {
                // グローバルチャンネルが無く、マーカーを指定したら、発言先を通常チャットに戻す
                if (manager.getDefaultChannel(sender.name) != null) {
                    manager.removeDefaultChannel(sender.name)
                    sender.info("cmdmsgSet", "channel" to "Global")
                }
                if (message.isNotBlank()) sender.chat(message.trim())
                return
            }
            if (settings.createChannelOnJoinCommand) {
                if (!checkChannelName(sender, channelName)) return
                val created = manager.createChannel(channelName)
                created.addMember(sender)
                sender.info("cmdmsgCreate", "channel" to channelName)
            } else {
                sender.error("errmsgNotExist")
            }
            return
        }

        val name = channel.name
        val node = "lunachat.join.$name"
        if (sender.isPermissionSet(node) && !sender.hasPermission(node)) {
            sender.error("errmsgPermission", "permission" to node)
            return
        }
        if (sender in channel.banned) {
            sender.error("errmsgBanned")
            return
        }
        if (channel.isPersonalChat) {
            sender.error("errmsgCannotJoinPersonal")
            return
        }

        if (sender in channel.members) {
            if (message.isNotEmpty() && canSpeak(sender, name)) {
                channel.chat(sender, message)
                return
            }
            setDefault(sender, channel)
        } else {
            if (name == settings.globalChannel && message.isNotEmpty() && canSpeak(sender, name)) {
                channel.chat(sender, message)
                return
            }
            if (channel.password.isNotEmpty()) {
                val pass = message.trim()
                if (pass.isEmpty()) {
                    sender.error("errmsgPassword1")
                } else if (channel.password != pass) {
                    sender.error("errmsgPasswordNotmatch")
                }
                if (pass != channel.password) {
                    sender.error("errmsgPassword2")
                    sender.error("errmsgPassword3")
                    return
                }
            }
            if (name != settings.globalChannel) {
                channel.addMember(sender)
                sender.info("cmdmsgJoin", "channel" to name)
            }
            setDefault(sender, channel)
        }

        if (channel.description.isNotBlank()) sender.info("cmdmsgSetTopic", "topic" to channel.description.trim())
        if (sender in channel.hided) sender.info("cmdmsgSetHide")
    }

    private fun setDefault(sender: ChannelMember, channel: Channel) {
        val def = manager.getDefaultChannel(sender.name)
        if (def == null || def.name != channel.name) {
            manager.setDefaultChannel(sender.name, channel.name)
            sender.info("cmdmsgSet", "channel" to channel.name)
        }
    }

    /** 発言の権限が外されていないか */
    private fun canSpeak(sender: ChannelMember, channelName: String): Boolean {
        val node = "lunachat.speak.$channelName"
        return !sender.isPermissionSet(node) || sender.hasPermission(node)
    }

    private fun checkChannelName(sender: ChannelMember, name: String): Boolean {
        if (!name.matches(Regex("[0-9a-zA-Z\\-_]+"))) {
            sender.error("errmsgCannotUseForChannel", "channel" to name)
            return false
        }
        if (name.length < settings.minChannelNameLength) {
            sender.error("errmsgCannotUseForChannelTooShort", "channel" to name, "min" to settings.minChannelNameLength)
            return false
        }
        if (name.length > settings.maxChannelNameLength) {
            sender.error("errmsgCannotUseForChannelTooLong", "channel" to name, "max" to settings.maxChannelNameLength)
            return false
        }
        return true
    }

    private fun leave(sender: ChannelMember, label: String, args: Array<String>) {
        val channelName = args.getOrNull(1) ?: manager.getDefaultChannel(sender.name)?.name
        val channel = manager.getChannel(channelName)
        if (channel == null) {
            sender.error("errmsgNotExist")
            return
        }
        val node = "lunachat.leave.${channel.name}"
        if (sender.isPermissionSet(node) && !sender.hasPermission(node)) {
            sender.error("errmsgPermission", "permission" to node)
            return
        }
        when {
            channel.isGlobalChannel -> sender.error("errmsgCannotLeaveGlobal", "channel" to channel.name)
            channel.isForceJoinChannel -> sender.error("errmsgCannotLeaveForceJoin", "channel" to channel.name)
            sender !in channel.members -> sender.error("errmsgNomember")
            else -> {
                channel.removeMember(sender)
                sender.info("cmdmsgLeave", "channel" to channel.name)
            }
        }
    }

    private fun list(sender: ChannelMember, label: String, args: Array<String>) {
        val page = args.getOrNull(1)?.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull() ?: 1
        lc.channelList(sender, page).forEach { sender.send(it) }
    }

    private fun info(sender: ChannelMember, label: String, args: Array<String>) {
        val name = args.getOrNull(1) ?: manager.getDefaultChannel(sender.name)?.name
        val channel = manager.getChannel(name)
        if (channel == null) {
            sender.error("errmsgNotExist")
            return
        }
        if (sender in channel.banned) {
            sender.error("errmsgBanned")
            return
        }
        channel.info(channel.hasModeratorPermission(sender)).forEach { sender.send(it) }
    }

    private fun log(sender: ChannelMember, label: String, args: Array<String>) {
        var cname: String? = null
        var index = 1
        if (args.size >= 2 && !args[1].contains("=")) {
            cname = args[1]
            index = 2
        }
        var player: String? = null
        var filter: String? = null
        var date: String? = null
        var reverse = false
        for (arg in args.drop(index)) {
            when {
                arg.startsWith("p=") -> player = arg.substring(2)
                arg.startsWith("f=") -> filter = arg.substring(2)
                arg.startsWith("d=") -> date = arg.substring(2)
                arg == "r=" || arg == "r" -> reverse = true
            }
        }
        if (cname == null) cname = manager.getDefaultChannel(sender.name)?.name

        val node = "lunachat.log.$cname"
        if (sender.isPermissionSet(node) && !sender.hasPermission(node)) {
            sender.error("errmsgPermission", "permission" to node)
            return
        }

        val logs: List<String>
        if (settings.globalChannel.isEmpty() && (cname == null || cname == settings.globalMarker)) {
            logs = lc.normalChatLogger.read(player, filter, date, reverse).takeLast(100)
            cname = "GlobalChat"
        } else {
            val channel = manager.getChannel(cname)
            if (channel == null) {
                sender.error("errmsgNotExist")
                return
            }
            if (sender in channel.banned) {
                sender.error("errmsgBanned")
                return
            }
            if (sender !in channel.members) {
                sender.error("errmsgNomember")
                return
            }
            logs = channel.readLog(player, filter, date, reverse).takeLast(100)
        }

        sender.plain("logDisplayFirstLine", "channel" to (cname ?: ""))
        for (line in logs) {
            val parts = line.split(",")
            sender.plain(
                "logDisplayFormat",
                "date" to parts[0], "player" to parts.getOrElse(2) { "" }, "message" to parts.getOrElse(1) { "" },
            )
        }
        sender.plain("logDisplayEndLine")
    }

    // ---- 招待 ----

    private fun invite(sender: ChannelMember, label: String, args: Array<String>) {
        if (args.size >= 3 && args[2].equals("force", true)) {
            forceInvite(sender, args)
            return
        }
        val channel = manager.getDefaultChannel(sender.name)
        if (channel == null) {
            sender.error("errmsgNoJoin")
            return
        }
        val invitedName = args.getOrNull(1) ?: run {
            sender.error("errmsgCommand")
            return
        }
        if (!channel.hasModeratorPermission(sender)) {
            sender.error("errmsgNotModerator")
            return
        }
        val invited = lc.memberByName(invitedName)
        if (invited == null || !invited.isOnline) {
            sender.error("errmsgNotfoundPlayer", "player" to invitedName)
            return
        }
        if (invited in channel.members) {
            sender.error("errmsgInvitedAlreadyExist", "player" to invitedName)
            return
        }
        lc.inviteMap[invited.name] = channel.name
        lc.inviterMap[invited.name] = sender.name
        sender.info("cmdmsgInvite", "player" to invitedName, "channel" to channel.name)
        invited.info("cmdmsgInvited1", "player" to sender.name, "channel" to channel.name)
        invited.info("cmdmsgInvited2")
    }

    private fun forceInvite(sender: ChannelMember, args: Array<String>) {
        val node = "lunachat-admin.force-invite"
        if (!sender.hasPermission(node)) {
            sender.error("errmsgPermission", "permission" to node)
            return
        }
        val cname = args.getOrNull(3) ?: manager.getDefaultChannel(sender.name)?.name
        val channel = manager.getChannel(cname)
        if (channel == null) {
            sender.error("errmsgNotExist")
            return
        }
        val invitedName = args[1]
        val invited = lc.memberByName(invitedName)
        if (invited == null || !invited.isOnline) {
            sender.error("errmsgNotfoundPlayer", "player" to invitedName)
            return
        }
        if (invited in channel.members) {
            sender.error("errmsgInvitedAlreadyExist", "player" to invitedName)
            return
        }
        channel.addMember(invited)
        manager.setDefaultChannel(invited.name, channel.name)
        sender.info("cmdmsgInvite", "player" to invitedName, "channel" to channel.name)
        invited.info("cmdmsgJoin", "channel" to channel.name)
    }

    private fun accept(sender: ChannelMember, label: String, args: Array<String>) {
        val channelName = lc.inviteMap[sender.name]
        if (channelName == null) {
            sender.error("errmsgNotInvited")
            return
        }
        val channel = manager.getChannel(channelName)
        if (channel == null) {
            sender.error("errmsgNotfoundChannel")
            return
        }
        lc.inviteMap.remove(sender.name)
        lc.inviterMap.remove(sender.name)
        if (sender in channel.members) {
            sender.error("errmsgInvitedAlreadyJoin")
            return
        }
        if (sender in channel.banned) {
            sender.error("errmsgBanned")
            return
        }
        channel.addMember(sender)
        sender.info("cmdmsgJoin", "channel" to channel.name)
        manager.setDefaultChannel(sender.name, channel.name)
        sender.info("cmdmsgSet", "channel" to channel.name)
    }

    private fun deny(sender: ChannelMember, label: String, args: Array<String>) {
        if (!lc.inviteMap.containsKey(sender.name)) {
            sender.error("errmsgNotInvited")
            return
        }
        val inviterName = lc.inviterMap[sender.name]
        lc.inviteMap.remove(sender.name)
        lc.inviterMap.remove(sender.name)
        sender.info("cmdmsgDeny")
        val inviter = inviterName?.let { lc.memberByName(it) }
        if (inviter != null && inviter.isOnline) inviter.info("cmdmsgDenyed")
    }

    // ---- キック・BAN・Mute ----

    /** 対象のプレイヤー名と、チャンネル (指定が無ければ発言先) を引く。モデレーターでなければ null */
    private fun moderatedTarget(sender: ChannelMember, args: Array<String>, channelArg: String?): Pair<String, Channel>? {
        val targetName = args.getOrNull(1) ?: run {
            sender.error("errmsgCommand")
            return null
        }
        val channel = if (channelArg != null) manager.getChannel(channelArg) else manager.getDefaultChannel(sender.name)
        if (channel == null) {
            sender.error("errmsgNoJoin")
            return null
        }
        if (!channel.hasModeratorPermission(sender)) {
            sender.error("errmsgNotModerator")
            return null
        }
        return targetName to channel
    }

    private fun kick(sender: ChannelMember, label: String, args: Array<String>) {
        val (name, channel) = moderatedTarget(sender, args, args.getOrNull(2)) ?: return
        if (channel.isGlobalChannel) {
            sender.error("errmsgCannotKickGlobal", "channel" to channel.name)
            return
        }
        val kicked = lc.memberByName(name)
        if (kicked == null || kicked !in channel.members) {
            sender.error("errmsgNomemberOther")
            return
        }
        channel.removeMember(kicked)
        channel.save()
        sender.info("cmdmsgKick", "player" to name, "channel" to channel.name)
        messages.channel("kickMessage", channel.colorCode, channel.name, "player" to kicked.name)
            ?.let { channel.sendSystemMessage(it, true, "system") }
        if (kicked.isOnline) kicked.info("cmdmsgKicked", "channel" to channel.name)
    }

    /**
     * 3 つ目の引数が期限 (分) か、チャンネル名か。
     * LunaChat 3 は常にチャンネル名として扱うため期限付きにできなかったので、数字でチャンネルも無ければ期限とみなす
     */
    private fun splitExpireArg(arg: String?): Pair<String?, String?> =
        if (arg != null && arg.matches(Regex("[0-9]+")) && manager.getChannel(arg) == null) null to arg else arg to null

    private fun parseExpire(sender: ChannelMember, minutes: String?, errorKey: String): Int? {
        if (minutes == null) return -1
        val value = minutes.toIntOrNull()
        if (value == null || value < 1 || value > 43200) {
            sender.error(errorKey)
            return null
        }
        return value
    }

    private fun ban(sender: ChannelMember, label: String, args: Array<String>) {
        val (channelArg, minutesArg) = splitExpireArg(args.getOrNull(2))
        val (name, channel) = moderatedTarget(sender, args, channelArg) ?: return
        if (channel.isGlobalChannel) {
            sender.error("errmsgCannotBANGlobal", "channel" to channel.name)
            return
        }
        val kicked = lc.memberByName(name)
        if (kicked == null || kicked !in channel.members) {
            sender.error("errmsgNomemberOther")
            return
        }
        if (kicked in channel.banned) {
            sender.error("errmsgAlreadyBanned")
            return
        }
        val minutes = parseExpire(sender, minutesArg, "errmsgInvalidBanExpireParameter") ?: return

        channel.banned += kicked
        if (minutes != -1) channel.banExpires[kicked] = System.currentTimeMillis() + minutes * 60L * 1000L
        channel.removeMember(kicked)
        channel.save()

        val notice = if (minutes != -1) {
            sender.info("cmdmsgBanWithExpire", "player" to name, "channel" to channel.name, "minutes" to minutes)
            messages.channel("banWithExpireMessage", channel.colorCode, channel.name, "player" to kicked.name, "minutes" to minutes)
        } else {
            sender.info("cmdmsgBan", "player" to name, "channel" to channel.name)
            messages.channel("banMessage", channel.colorCode, channel.name, "player" to kicked.name)
        }
        notice?.let { channel.sendSystemMessage(it, true, "system") }
        if (kicked.isOnline) kicked.info("cmdmsgBanned", "channel" to channel.name)
    }

    private fun pardon(sender: ChannelMember, label: String, args: Array<String>) {
        val (name, channel) = moderatedTarget(sender, args, args.getOrNull(2)) ?: return
        val kicked = lc.memberByName(name)
        if (kicked == null || kicked !in channel.banned) {
            sender.error("errmsgNotBanned")
            return
        }
        channel.banned.remove(kicked)
        channel.banExpires.remove(kicked)
        channel.save()
        sender.info("cmdmsgPardon", "player" to name, "channel" to channel.name)
        messages.channel("pardonMessage", channel.colorCode, channel.name, "player" to kicked.name)
            ?.let { channel.sendSystemMessage(it, true, "system") }
        if (kicked.isOnline) kicked.info("cmdmsgPardoned", "channel" to channel.name)
    }

    private fun mute(sender: ChannelMember, label: String, args: Array<String>) {
        val (channelArg, minutesArg) = splitExpireArg(args.getOrNull(2))
        val (name, channel) = moderatedTarget(sender, args, channelArg) ?: return
        val kicked = lc.memberByName(name)
        if (kicked == null || kicked !in channel.members) {
            sender.error("errmsgNomemberOther")
            return
        }
        if (kicked in channel.muted) {
            sender.error("errmsgAlreadyMuted")
            return
        }
        val minutes = parseExpire(sender, minutesArg, "errmsgInvalidMuteExpireParameter") ?: return

        channel.muted += kicked
        if (minutes != -1) channel.muteExpires[kicked] = System.currentTimeMillis() + minutes * 60L * 1000L
        channel.save()

        val notice = if (minutes != -1) {
            sender.info("cmdmsgMuteWithExpire", "player" to name, "channel" to channel.name, "minutes" to minutes)
            messages.channel("muteWithExpireMessage", channel.colorCode, channel.name, "player" to kicked.name, "minutes" to minutes)
        } else {
            sender.info("cmdmsgMute", "player" to name, "channel" to channel.name)
            messages.channel("muteMessage", channel.colorCode, channel.name, "player" to kicked.name)
        }
        notice?.let { channel.sendSystemMessage(it, true, "system") }
        kicked.info("cmdmsgMuted", "channel" to channel.name)
    }

    private fun unmute(sender: ChannelMember, label: String, args: Array<String>) {
        val (name, channel) = moderatedTarget(sender, args, args.getOrNull(2)) ?: return
        val kicked = lc.memberByName(name)
        if (kicked == null || kicked !in channel.muted) {
            sender.error("errmsgNotMuted")
            return
        }
        channel.muted.remove(kicked)
        channel.muteExpires.remove(kicked)
        channel.save()
        sender.info("cmdmsgUnmute", "player" to name, "channel" to channel.name)
        messages.channel("unmuteMessage", channel.colorCode, channel.name, "player" to kicked.name)
            ?.let { channel.sendSystemMessage(it, true, "system") }
        if (kicked.isOnline) kicked.info("cmdmsgUnmuted", "channel" to channel.name)
    }

    // ---- 非表示 ----

    private fun hide(sender: ChannelMember, label: String, args: Array<String>) = hideOrUnhide(sender, args, hide = true)

    private fun unhide(sender: ChannelMember, label: String, args: Array<String>) = hideOrUnhide(sender, args, hide = false)

    private fun hideOrUnhide(sender: ChannelMember, args: Array<String>, hide: Boolean) {
        var target: String? = null
        var isPlayerCommand = false
        var isChannelCommand = false
        if (args.size <= 1) {
            target = manager.getDefaultChannel(sender.name)?.name
        } else if (hide && args[1] == "list") {
            sender.plain("hideChannelFirstLine")
            val prefix = messages.plain("listPlainPrefix")
            manager.channels.filter { sender in it.hided }.forEach { sender.send(prefix + it.name) }
            sender.plain("hidePlayerFirstLine")
            manager.getHideinfo(sender).forEach { sender.send(prefix + it.displayName) }
            sender.plain("listEndLine")
            return
        } else if (args.size >= 3 && args[1].equals("player", true)) {
            isPlayerCommand = true
            target = args[2]
        } else if (args.size >= 3 && args[1].equals("channel", true)) {
            isChannelCommand = true
            target = args[2]
        } else {
            target = args[1]
        }

        val channel = manager.getChannel(target)
        if (!isPlayerCommand && channel != null) {
            isChannelCommand = true
        } else if (isChannelCommand || target == null || lc.memberByName(target) == null) {
            sender.error("errmsgNotExistChannelAndPlayer")
            return
        }

        if (isChannelCommand && channel != null) {
            if (hide) {
                if (sender in channel.hided) {
                    sender.error("errmsgAlreadyHided")
                    return
                }
                if (sender !in channel.members) {
                    sender.error("errmsgNomember")
                    return
                }
                channel.hided += sender
                channel.save()
                sender.info("cmdmsgHided", "channel" to channel.name)
            } else {
                if (sender !in channel.hided) {
                    sender.error("errmsgAlreadyUnhided")
                    return
                }
                channel.hided.remove(sender)
                channel.save()
                sender.info("cmdmsgUnhided", "channel" to channel.name)
            }
            return
        }

        val hided = lc.memberByName(target!!) ?: return
        val hiding = sender in manager.getHidelist(hided)
        if (hide) {
            if (hiding) {
                sender.error("errmsgAlreadyHidedPlayer")
                return
            }
            if (hided == sender) {
                sender.error("errmsgCannotHideSelf")
                return
            }
            manager.addHidelist(sender, hided)
            sender.info("cmdmsgHidedPlayer", "player" to hided.displayName)
        } else {
            if (!hiding) {
                sender.error("errmsgAlreadyUnhidedPlayer")
                return
            }
            manager.removeHidelist(sender, hided)
            sender.info("cmdmsgUnhidedPlayer", "channel" to hided.displayName)
        }
    }

    // ---- チャンネルの作成と設定 ----

    private fun create(sender: ChannelMember, label: String, args: Array<String>) {
        val name = args.getOrNull(1) ?: run {
            sender.error("errmsgCommand")
            return
        }
        if (manager.getChannel(name) != null) {
            sender.error("errmsgExist")
            return
        }
        if (!checkChannelName(sender, name)) return
        val channel = manager.createChannel(name)
        channel.description = args.getOrNull(2) ?: ""
        channel.save()
        sender.info("cmdmsgCreate", "channel" to name)
    }

    private fun remove(sender: ChannelMember, label: String, args: Array<String>) {
        val name = args.getOrNull(1) ?: manager.getDefaultChannel(sender.name)?.name
        val channel = manager.getChannel(name)
        if (channel == null) {
            sender.error("errmsgNotExist")
            return
        }
        if (!channel.hasModeratorPermission(sender)) {
            sender.error("errmsgNotModerator")
            return
        }
        if (channel.isGlobalChannel) {
            sender.error("errmsgCannotRemoveGlobal", "channel" to channel.name)
            return
        }
        if (manager.removeChannel(channel.name)) sender.info("cmdmsgRemove", "channel" to name!!)
    }

    /**
     * 対象のチャンネルと残りの引数。2 つ目の引数がチャンネル名で、さらに引数が続くならそのチャンネル、
     * そうでなければ発言先 (LunaChat 3 は常に発言先になっていた)
     */
    private fun channelAndRest(sender: ChannelMember, args: Array<String>): Pair<Channel, List<String>>? {
        if (args.size < 2) {
            sender.error("errmsgCommand")
            return null
        }
        val specified = if (args.size >= 3) manager.getChannel(args[1]) else null
        val channel = specified ?: manager.getDefaultChannel(sender.name)
        val rest = args.drop(if (specified != null) 2 else 1)
        if (channel == null) {
            sender.error("errmsgNotExist")
            return null
        }
        if (!channel.hasModeratorPermission(sender)) {
            sender.error("errmsgNotModerator")
            return null
        }
        return channel to rest
    }

    private fun format(sender: ChannelMember, label: String, args: Array<String>) {
        val (channel, rest) = channelAndRest(sender, args) ?: return
        val format = rest.joinToString(" ").trim()

        // テンプレートを展開してから、必須のキーワードがあるか調べる
        var temp = format
        for (i in 0..9) {
            val template = manager.getTemplate("$i")
            if (temp.contains("%$i") && template != null) {
                temp = temp.replace("%$i", template)
                break
            }
        }
        settings.formatConstraint.firstOrNull { !temp.contains(it) }?.let {
            sender.error("errmsgFormatConstraint", "key" to it)
            return
        }
        channel.format = format
        sender.info("cmdmsgFormat", "format" to format)
        channel.save()
    }

    private fun moderator(sender: ChannelMember, label: String, args: Array<String>) {
        val (channel, rest) = channelAndRest(sender, args) ?: return
        if (channel.isGlobalChannel) {
            sender.error("errmsgCannotModeratorGlobal", "channel" to channel.name)
            return
        }
        for (mod in rest) {
            if (mod.startsWith("-")) {
                val name = mod.substring(1)
                lc.memberByName(name)?.let { channel.removeModerator(it) }
                sender.info("cmdmsgModeratorMinus", "player" to name, "channel" to channel.name)
            } else {
                val cp = lc.memberByName(mod)
                if (cp == null) {
                    sender.error("errmsgNotfoundPlayer", "player" to mod)
                    continue
                }
                channel.addModerator(cp)
                sender.info("cmdmsgModerator", "player" to mod, "channel" to channel.name)
            }
        }
    }

    private fun option(sender: ChannelMember, label: String, args: Array<String>) {
        val (channel, rest) = channelAndRest(sender, args) ?: return

        val options = LinkedHashMap<String, String>()
        for (t in rest) {
            val index = t.indexOf('=')
            if (index == -1) continue
            options[t.substring(0, index)] = t.substring(index + 1)
        }

        var setOption = false
        fun allowed(key: String): Boolean {
            val node = "lunachat.option.$key"
            if (sender.hasPermission(node)) return true
            sender.error("errmsgNotPermission", "permission" to node)
            return false
        }
        fun set(key: String, value: String) {
            sender.info("cmdmsgOption", "key" to key, "value" to value)
            setOption = true
        }

        options["description"]?.let { desc ->
            if (!allowed("description")) return@let
            if (desc.length > MAX_LENGTH_DESCRIPTION) {
                sender.error("errmsgToolongDescription", "max" to MAX_LENGTH_DESCRIPTION)
            } else {
                channel.description = desc
                set("description", desc)
            }
        }

        options["alias"]?.let { alias ->
            if (!allowed("alias")) return@let
            val other = manager.getChannel(alias)
            when {
                alias.length > MAX_LENGTH_ALIAS -> sender.error("errmsgToolongAlias", "max" to MAX_LENGTH_ALIAS)
                other != null -> sender.error("errmsgDuplicatedAlias", "aliase" to alias, "channel" to other.name)
                else -> {
                    channel.alias = alias
                    set("alias", alias)
                }
            }
        }

        options["color"]?.let { value ->
            if (!allowed("color")) return@let
            val code = if (Colors.isValidColor(value)) Colors.toColorCode(value) else value
            // 色コードがメッセージの中で色にならないよう、全角にして入れてから戻す
            val forMsg = value.replace("&", "＆").replace("#", "＃")
            if (Colors.isAltColorCode(code)) {
                channel.colorCode = code
                sender.send(messages.info("cmdmsgOption", "key" to "color", "value" to forMsg).replace("＃", "#").replace("＆", "&"))
                setOption = true
            } else {
                sender.send(messages.error("errmsgInvalidColorCode", "value" to forMsg).replace("＃", "#").replace("＆", "&"))
            }
        }

        options["broadcast"]?.let { value ->
            if (!allowed("broadcast")) return@let
            when {
                value.isEmpty() || value.equals("false", true) ->
                    if (channel.isGlobalChannel) {
                        sender.error("errmsgCannotOffGlobalBroadcast")
                    } else {
                        channel.broadcast = false
                        set("broadcast", "false")
                    }
                value.equals("true", true) -> {
                    channel.broadcast = true
                    set("broadcast", "true")
                }
                else -> sender.error("errmsgInvalidBooleanOption", "key" to "broadcast")
            }
        }

        options["range"]?.let { value ->
            if (!allowed("range")) return@let
            when {
                value.isEmpty() -> {
                    channel.isWorldRange = false
                    channel.chatRange = 0
                    set("range", "off")
                }
                value.equals("world", true) -> {
                    channel.isWorldRange = true
                    channel.chatRange = 0
                    set("range", "world")
                }
                value.matches(Regex("[0-9]+")) && value.toIntOrNull() != null -> {
                    channel.isWorldRange = true
                    channel.chatRange = value.toInt()
                    set("range", value)
                }
                else -> sender.error("errmsgInvalidRangeOption")
            }
        }

        options["allowcc"]?.let { value ->
            if (!allowed("allowcc")) return@let
            when {
                value.isEmpty() || value.equals("false", true) -> {
                    channel.allowCC = false
                    set("allowcc", "false")
                }
                value.equals("true", true) -> {
                    channel.allowCC = true
                    set("allowcc", "true")
                }
                else -> sender.error("errmsgInvalidBooleanOption", "key" to "allowcc")
            }
        }

        options["japanize"]?.let { value ->
            if (!allowed("japanize")) return@let
            if (value.isEmpty()) {
                channel.japanizeType = null
                set("japanize", "default")
            } else {
                val type = JapanizeType.fromId(value, null)
                if (type == null) {
                    sender.error("errmsgInvalidJapanizeOption", "key" to "japanize", "value" to value)
                } else {
                    channel.japanizeType = type
                    set("japanize", type.toString())
                }
            }
        }

        if (!channel.isGlobalChannel) {
            options["password"]?.let { password ->
                if (!allowed("password")) return@let
                if (password.length > MAX_LENGTH_PASSWORD) {
                    sender.error("errmsgToolongPassword", "max" to MAX_LENGTH_PASSWORD)
                } else {
                    channel.password = password
                    set("password", password)
                }
            }
            options["visible"]?.let { value ->
                if (!allowed("visible")) return@let
                when {
                    value.equals("false", true) -> {
                        channel.visible = false
                        set("visible", "false")
                    }
                    value.equals("true", true) -> {
                        channel.visible = true
                        set("visible", "true")
                    }
                    else -> sender.error("errmsgInvalidBooleanOption", "key" to "visible")
                }
            }
        }

        if (!setOption) sender.error("errmsgInvalidOptions") else channel.save()
    }

    // ---- 管理者向け ----

    private fun template(sender: ChannelMember, label: String, args: Array<String>) {
        if (args.size <= 1) {
            sender.error("errmsgCommand")
            return
        }
        if (!args[1].matches(Regex("[0-9]"))) {
            sender.error("errmsgInvalidTemplateNumber")
            sender.send(messages.usage("usageTemplate", label))
            return
        }
        val id = args[1]
        val format = args.drop(2).joinToString(" ").trim()
        if (format.isEmpty()) {
            manager.removeTemplate(id)
            sender.info("cmdmsgTemplateRemove", "index" to id)
        } else {
            manager.setTemplate(id, format)
            sender.info("cmdmsgTemplate", "index" to id, "value" to format)
        }
    }

    private fun set(sender: ChannelMember, label: String, args: Array<String>) {
        if (args.size >= 3 && args[1].equals("default", true)) {
            val target = lc.memberByName(args[2])
            val targetName = target?.name ?: args[2]
            val channel = if (args.size >= 4) manager.getChannel(args[3]) else manager.getDefaultChannel(sender.name)
            if (channel == null) {
                sender.error("errmsgNotExistOrNotSpecified")
                return
            }
            manager.setDefaultChannel(targetName, channel.name)
            sender.info("cmdmsgSetDefault", "player" to targetName, "channel" to channel.name)
            target?.info("cmdmsgSet", "channel" to channel.name)
            return
        }
        sender.send(messages.usage("usageSet1", label))
    }

    private fun dictionary(sender: ChannelMember, label: String, args: Array<String>) {
        val usageKey = if (args[0].equals("dic", true)) "usageDic" else "usageDictionary"
        fun usage() {
            sender.error("errmsgCommand")
            sender.send(messages.usage(usageKey, label))
        }
        when (args.getOrNull(1)?.lowercase()) {
            "add" -> {
                if (args.size <= 3) return usage()
                manager.setDictionary(args[2], args[3])
                sender.info("cmdmsgDictionaryAdd", "key" to args[2], "value" to args[3])
            }
            "remove" -> {
                if (args.size <= 2) return usage()
                manager.removeDictionary(args[2])
                sender.info("cmdmsgDictionaryRemove", "key" to args[2])
            }
            "view" -> manager.dictionarySnapshot().forEach { (k, v) -> sender.send("$k§7 -> §f$v") }
            else -> usage()
        }
    }

    private fun reload(sender: ChannelMember, label: String, args: Array<String>) {
        lc.reloadAll()
        sender.info("cmdmsgReload")
    }

    private fun help(sender: ChannelMember, label: String, args: Array<String>) {
        var type = CommandType.USER
        var page = 1
        val a1 = args.getOrNull(1)
        when {
            a1 != null && (a1.equals("mod", true) || a1.equals("moderator", true)) -> type = CommandType.MODERATOR
            a1 != null && a1.equals("admin", true) -> type = CommandType.ADMIN
            a1 != null && a1.matches(Regex("[1-9]")) -> page = a1.toInt()
        }
        args.getOrNull(2)?.takeIf { it.matches(Regex("[1-9]")) }?.let { page = it.toInt() }

        val list = commands.filter { it.type == type && sender.hasPermission(it.permission) }
        val lastPage = (list.size - 1) / PAGE_ITEM_NUM + 1
        sender.plain("usageTop", "type" to type.desc, "num" to page, "max" to lastPage)
        list.drop((page - 1) * PAGE_ITEM_NUM).take(PAGE_ITEM_NUM).forEach { sendUsage(it, sender, label) }
        sender.plain("usageFoot")
        if (page < lastPage) {
            val t = if (type != CommandType.USER) type.desc else ""
            sender.plain("usageNoticeNextPage", "label" to label, "type" to t, "next" to page + 1)
        }
    }

    // ---- 補完 ----

    fun tabComplete(sender: ChannelMember, args: Array<String>): List<String> {
        val a0 = args.getOrNull(0)?.lowercase() ?: return emptyList()
        val last = args.last().lowercase()
        fun playerNames() = lc.onlinePlayerNames().filter { it.lowercase().startsWith(last) }
        fun joinable() = canJoinNames(sender).filter { it.lowercase().startsWith(last) }
        return when (args.size) {
            1 -> (commands + commonCommands).filter { it.name.startsWith(a0) && sender.hasPermission(it.permission) }.map { it.name }
            2 -> when (a0) {
                "join", "info" -> joinable()
                "ban", "pardon", "kick", "mute", "unmute" -> playerNames()
                "hide", "unhide" -> joinable() + playerNames() + listOf("player", "channel").filter { it.startsWith(last) }
                "remove" -> manager.channels
                    .filter { it.hasModeratorPermission(sender) && !it.isPersonalChat && !it.isGlobalChannel }
                    .map { it.name }.filter { it.lowercase().startsWith(last) }
                "dic", "dictionary" -> listOf("add", "remove", "view").filter { it.startsWith(last) }
                "set" -> listOf("default").filter { it.startsWith(last) }
                else -> emptyList()
            }
            3 -> when {
                a0 in listOf("ban", "pardon", "kick", "mute", "unmute") -> joinable()
                (a0 == "hide" || a0 == "unhide") && args[1].equals("player", true) -> playerNames()
                (a0 == "hide" || a0 == "unhide") && args[1].equals("channel", true) -> joinable()
                (a0 == "dic" || a0 == "dictionary") && args[1].equals("remove", true) ->
                    manager.dictionarySnapshot().keys.filter { it.lowercase().startsWith(last) }
                a0 == "set" && args[1].equals("default", true) -> playerNames()
                else -> emptyList()
            }
            4 -> if (a0 == "set" && args[1].equals("default", true)) joinable() else emptyList()
            else -> emptyList()
        }
    }

    /** 補完に出す、入れるチャンネル */
    private fun canJoinNames(sender: ChannelMember): List<String> = manager.channels.filter {
        sender !in it.banned && !it.isPersonalChat && (sender in it.members || it.isGlobalChannel || it.visible)
    }.map { it.name }

    companion object {
        private const val PAGE_ITEM_NUM = 6
        private const val MAX_LENGTH_DESCRIPTION = 30
        private const val MAX_LENGTH_ALIAS = 15
        private const val MAX_LENGTH_PASSWORD = 15
    }
}
