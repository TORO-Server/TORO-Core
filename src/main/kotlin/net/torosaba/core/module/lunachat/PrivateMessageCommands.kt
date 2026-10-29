package net.torosaba.core.module.lunachat

/**
 * /tell /reply と /japanize (LunaChat の LunaChatMessageCommand, LunaChatReplyCommand, LunaChatJapanizeCommand)。
 * 1:1 チャットは `送信者>受信者` という名前の、保存しないチャンネルで送る
 */
internal class PrivateMessageCommands(private val lc: LunaChatModule) {
    private val messages get() = lc.messages

    fun tell(sender: ChannelMember, label: String, args: Array<String>) {
        if (args.isEmpty()) {
            sender.send(messages.usage("usageMessage", label))
            return
        }
        sendTellMessage(sender, args[0], args.drop(1).joinToString(" ").trim())
    }

    fun reply(sender: ChannelMember, label: String, args: Array<String>) {
        val invitedName = lc.privateMessageMap[sender.name]
        if (args.isEmpty()) {
            sender.send(
                if (invitedName == null) messages.info("cmdmsgReplyInviterNone", "inviter" to sender.name)
                else messages.info("cmdmsgReplyInviter", "inviter" to sender.name, "invited" to invitedName),
            )
            return
        }
        if (invitedName == null) {
            sender.send(messages.error("errmsgNotfoundPM"))
            return
        }
        sendTellMessage(sender, invitedName, args.joinToString(" ").trim())
    }

    private fun sendTellMessage(inviter: ChannelMember, invitedName: String, message: String) {
        val invited = lc.memberByName(invitedName)
        if (invited == null || !invited.isOnline) {
            inviter.send(messages.error("errmsgNotfoundPlayer", "player" to invitedName))
            return
        }
        if (inviter.name == invited.name) {
            inviter.send(messages.error("errmsgCannotSendPMSelf"))
            return
        }

        val manager = lc.manager
        val cname = inviter.name + ">" + invited.name
        val channel = manager.getChannel(cname) ?: manager.createChannel(cname).apply {
            visible = false
            addMember(inviter)
            addMember(invited)
            privateMessageTo = invited
        }

        if (message.isNotBlank()) channel.chat(inviter, message)

        lc.privateMessageMap[invited.name] = inviter.name
        lc.privateMessageMap[inviter.name] = invited.name
    }

    fun tellTab(args: Array<String>): List<String> =
        if (args.size == 1) lc.onlinePlayerNames().toList() else emptyList()

    fun japanize(sender: ChannelMember, label: String, args: Array<String>) {
        val manager = lc.manager
        if (args.size == 1 && (args[0].equals("on", true) || args[0].equals("off", true))) {
            manager.setPlayersJapanize(sender.name, args[0].equals("on", true))
            sender.send(messages.info("cmdmsgPlayerJapanize", "value" to args[0]))
            return
        }
        if (args.size == 2 && (args[1].equals("on", true) || args[1].equals("off", true))) {
            if (!sender.hasPermission(PERM_JAPANIZE_OTHER)) {
                sender.send(messages.error("errmsgNotPermission", "permission" to PERM_JAPANIZE_OTHER))
                return
            }
            val target = lc.memberByName(args[0])
            if (target == null || !target.isOnline) {
                sender.send(messages.error("errmsgNotfoundPlayer", "player" to args[0]))
                return
            }
            manager.setPlayersJapanize(target.name, args[1].equals("on", true))
            target.send(messages.info("cmdmsgPlayerJapanize", "value" to args[1]))
            sender.send(messages.info("cmdmsgPlayerJapanizeOther", "player" to args[0], "value" to args[1]))
            return
        }
        sender.send(messages.usage("usageJapanize", label))
        if (sender.hasPermission(PERM_JAPANIZE_OTHER)) sender.send(messages.usage("usageJapanizeOther", label))
    }

    fun japanizeTab(sender: ChannelMember, args: Array<String>): List<String> = when (args.size) {
        1 -> listOf("on", "off") + if (sender.hasPermission(PERM_JAPANIZE_OTHER)) lc.onlinePlayerNames() else emptyList()
        2 -> if (sender.hasPermission(PERM_JAPANIZE_OTHER)) listOf("on", "off") else emptyList()
        else -> emptyList()
    }

    companion object {
        const val PERM_JAPANIZE_OTHER = "lunachat-admin.japanize-other"
    }
}
