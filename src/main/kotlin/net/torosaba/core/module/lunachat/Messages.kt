package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import org.bukkit.configuration.ConfigurationSection

/**
 * LunaChat のメッセージ (messages_ja.yml)。キーと `%channel%` などのキーワードは LunaChat と同じ。
 *
 * - `cmdmsg*` には infoPrefix、`errmsg*` には errorPrefix を頭に付ける
 * - 値が空のメッセージは送らない (空文字列を返す)
 */
class Messages(private val resources: ConfigurationSection) : Hovers {

    private fun get(key: String): String? = resources.getString(key)?.takeIf { it.isNotEmpty() }

    private fun fill(text: String, args: Array<out Pair<String, Any>>): String {
        var result = text
        for ((key, value) in args) result = result.replace("%$key%", value.toString())
        return result
    }

    /** 頭に何も付けないメッセージ */
    fun plain(key: String, vararg args: Pair<String, Any>): String =
        get(key)?.let { Colors.replace(fill(it, args)) } ?: ""

    /** 実行結果のメッセージ (infoPrefix 付き) */
    fun info(key: String, vararg args: Pair<String, Any>): String =
        get(key)?.let { Colors.replace((resources.getString("infoPrefix") ?: "") + fill(it, args)) } ?: ""

    /** エラーメッセージ (errorPrefix 付き) */
    fun error(key: String, vararg args: Pair<String, Any>): String =
        get(key)?.let { Colors.replace((resources.getString("errorPrefix") ?: "") + fill(it, args)) } ?: ""

    /** 使い方 (`%label%` をコマンドのラベルにする) */
    fun usage(key: String, label: String): String = plain(key, "label" to label)

    /**
     * チャンネル名 (`%channel%`) をクリックするとそのチャンネルに入れるメッセージ。
     * メッセージが空なら null
     */
    fun channel(key: String, color: String, channel: String, vararg args: Pair<String, Any>): Component? {
        val text = get(key) ?: return null
        val format = ChatFormat.channelMessage(text, channel, this)
        format.replace("%color%", color)
        format.replace("%channel%", channel)
        for ((k, v) in args) format.replace("%$k%", v.toString())
        return format.toComponent()
    }

    fun has(key: String): Boolean = get(key) != null

    override fun channel(name: String): String = plain("hoverChannelName", "channel" to name)

    override fun player(name: String): String = plain("hoverPlayerName", "player" to name)
}
