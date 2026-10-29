package net.torosaba.core.module.lunachat

import net.torosaba.core.module.lunachat.japanize.JapanizeType
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.EventPriority
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** NG ワードを言った人への処置 */
enum class NgWordAction(val id: String) {
    MASK("mask"), MUTE("mute"), KICK("kick"), BAN("ban");

    companion object {
        fun fromId(id: String?): NgWordAction = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MASK
    }
}

/** LunaChat の config.yml (LunaChatConfig)。キーと既定値は LunaChat と同じ */
class Settings(config: ConfigurationSection, warn: (String) -> Unit = {}) {
    val lang: String = config.getString("lang", "ja")!!
    val enableChannelChat = config.getBoolean("enableChannelChat", true)
    val playerChatEventListenerPriority: EventPriority =
        EventPriority.entries.firstOrNull { it != EventPriority.MONITOR && it.name.equals(config.getString("playerChatEventListenerPriority"), true) }
            ?: EventPriority.HIGHEST
    val noJoinAsGlobal = config.getBoolean("noJoinAsGlobal", true)
    val loggingChat = config.getBoolean("loggingChat", true)
    val displayChatOnConsole = config.getBoolean("displayChatOnConsole", true)
    val globalMarker: String = config.getString("globalMarker", "!")!!
    val zeroMemberRemove = config.getBoolean("zeroMemberRemove", false)
    val showListOnJoin = config.getBoolean("showListOnJoin", false)
    val createChannelOnJoinCommand = config.getBoolean("createChannelOnJoinCommand", false)

    /** 通常のチャットの代わりに使うチャンネル。チャンネルチャットが無効なら使わない (LunaChat issue #58) */
    val globalChannel: String = run {
        val name = if (enableChannelChat) config.getString("globalChannel", "")!! else ""
        if (name.isNotEmpty() && !name.matches(Regex("[0-9a-zA-Z\\-_]{1,20}"))) {
            warn("$name はグローバルチャンネル名に使用できない文字を含んでいます。")
            ""
        } else name
    }
    val forceJoinChannels: List<String> = if (enableChannelChat) config.getStringList("forceJoinChannels") else emptyList()
    val formatConstraint: List<String> =
        if (config.isSet("formatConstraint")) config.getStringList("formatConstraint") else listOf("%username", "%msg")
    val defaultFormat: String = config.getString("defaultFormat", "&f[%color%ch&f]%prefix%username%suffix&a:&f %msg")!!
    val defaultFormatForPrivateMessage: String =
        config.getString("defaultFormatForPrivateMessage", "&7[%player -> %to]&f %msg")!!
    val opListenAllChannel = config.getBoolean("opListenAllChannel", false)
    val minChannelNameLength = config.getInt("minChannelNameLength", 4)
    val maxChannelNameLength = config.getInt("maxChannelNameLength", 20)
    val enableQuickChannelChat = config.getBoolean("enableQuickChannelChat", true)
    val quickChannelChatSeparator: String = config.getString("quickChannelChatSeparator", ":")!!

    val sendBroadcastChannelChatToDynmap = config.getBoolean("sendBroadcastChannelChatToDynmap", true)
    val sendFormattedMessageToDynmap = config.getBoolean("sendFormattedMessageToDynmap", false)
    val dynmapChannel: String = config.getString("dynmapChannel", "")!!

    val ngword: List<Pattern> = config.getStringList("ngword").mapNotNull {
        try {
            Pattern.compile(it)
        } catch (e: PatternSyntaxException) {
            warn("NG ワード $it は正規表現として正しくないので使いません: ${e.description}")
            null
        }
    }
    val ngwordAction = NgWordAction.fromId(config.getString("ngwordAction", "mask"))

    val enableNormalChatMessageFormat = config.getBoolean("enableNormalChatMessageFormat", true)
    val normalChatMessageFormat: String =
        config.getString("normalChatMessageFormat", "&f%prefix%username%suffix&a:&f %msg")!!
    val enableNormalChatColorCode = config.getBoolean("enableNormalChatColorCode", true)
    val enableNormalChatClickable = config.getBoolean("enableNormalChatClickable", false)
    val displayNormalChatOnConsole = config.getBoolean("displayNormalChatOnConsole", true)

    /** 設定が無い・読めないときは変換しない (LunaChat と同じ) */
    val japanizeType: JapanizeType = JapanizeType.fromId(config.getString("japanizeType"), null) ?: JapanizeType.NONE
    val japanizeDisplayLine: Int = config.getInt("japanizeDisplayLine", 2).let { if (it == 1 || it == 2) it else 2 }
    val japanizeLine1Format: String = config.getString("japanizeLine1Format", "%msg &6(%japanize)")!!
    val japanizeLine2Format: String = config.getString("japanizeLine2Format", "&6[JP] %japanize")!!
    val japanizeIgnorePlayerName = config.getBoolean("japanizeIgnorePlayerName", true)
    val noneJapanizeMarker: String = config.getString("noneJapanizeMarker", "$")!!
    val japanizePlayerDefault = config.getBoolean("japanizePlayerDefault", true)

    /** 通常チャットの 2 行表示で、変換結果を出すまで待つ tick (隠し設定) */
    val japanizeWait = config.getInt("japanizeWait", 1)
}
