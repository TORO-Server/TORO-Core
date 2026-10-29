package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import java.text.SimpleDateFormat
import java.util.Date

/** フォーマットに入れる発言者の情報 */
class MemberVars(
    val name: String,
    val displayName: String,
    prefix: () -> String = { "" },
    suffix: () -> String = { "" },
    val world: String = "",
    val server: String = "",
) {
    // Vault への問い合わせは、フォーマットで使うときだけにする
    val prefix: String by lazy(prefix)
    val suffix: String by lazy(suffix)
}

/** フォーマットに入れるチャンネルの情報。[to] は 1:1 チャットの相手 */
class ChannelVars(val name: String, val color: String, val to: MemberVars? = null)

/** クリックできる部分のマウスオーバーの文 */
interface Hovers {
    fun channel(name: String): String
    fun player(name: String): String
}

/**
 * LunaChat の ClickableFormat。フォーマットのキーワード (`%ch` `%displayname` など) を置き換え、
 * チャンネル名やプレイヤー名をクリックできる Component にする。
 *
 * クリックできる部分は、置き換えの途中では `＜type=... text="..." hover="..." command="..."＞` の形で文字列に埋め込む。
 * 発言 (`%msg`) は最後に別に差し込むので、発言にこの形の文字列があってもクリックできる部分にはならない。
 */
class ChatFormat(format: String) {
    private val buf = StringBuilder(format)
    private var message: String = ""

    /** [keyword] をすべて [value] に置き換える。置き換えた後の文字列は、もう一度は調べない */
    fun replace(keyword: String, value: String) {
        if (keyword.isEmpty()) return
        var index = buf.indexOf(keyword)
        while (index > -1) {
            buf.replace(index, index + keyword.length, value)
            index = buf.indexOf(keyword, index + value.length)
        }
    }

    fun contains(keyword: String): Boolean = buf.indexOf(keyword) > -1

    /** `%msg` に発言を入れる */
    fun setMessage(message: String) {
        replace("%msg", MESSAGE_TOKEN)
        this.message = message
    }

    /** そのままの文字列 (クリック部分は文字だけにし、色コード候補は `§` にする) */
    fun toLegacyText(): String {
        val text = placeholder.replace(Colors.replace(buf.toString())) { it.groupValues[2] }
        return text.replace(MESSAGE_TOKEN, message)
    }

    override fun toString(): String = buf.toString()

    /** チャット欄に出す Component にする */
    fun toComponent(): Component {
        val text = Colors.replace(buf.toString())
        val root = Component.text()
        // クリック部分の色は、直前の部分の色に合わせる (LunaChat issue #202)
        var lastColor: TextColor? = null
        var lastIndex = 0
        for (match in placeholder.findAll(text)) {
            if (lastIndex < match.range.first) {
                lastColor = appendLiteral(root, text.substring(lastIndex, match.range.first))
            }
            val (type, label, hover, command) = match.destructured
            val click = Component.text().append(Colors.component(label))
            if (hover.isNotEmpty()) click.hoverEvent(HoverEvent.showText(Colors.component(hover)))
            click.clickEvent(if (type == "RUN_COMMAND") ClickEvent.runCommand(command) else ClickEvent.suggestCommand(command))
            if (lastColor != null) click.color(lastColor)
            root.append(click.build())
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) appendLiteral(root, text.substring(lastIndex))
        return root.build()
    }

    /** 文字の部分を足して、最後の色 (無ければ白) を返す。色の状態は発言の前後で引き継ぐ */
    private fun appendLiteral(root: TextComponent.Builder, literal: String): TextColor {
        var state = ""
        val pieces = literal.split(MESSAGE_TOKEN)
        pieces.forEachIndexed { i, piece ->
            if (i > 0) {
                val withMessage = state + message
                root.append(Colors.component(withMessage))
                state = activeCodes(withMessage)
            }
            if (piece.isNotEmpty()) {
                val text = state + piece
                root.append(Colors.component(text))
                state = activeCodes(text)
            }
        }
        return colorOf(state) ?: NamedTextColor.WHITE
    }

    companion object {
        /** 発言を入れる場所の目印 (私用領域の文字) */
        private const val MESSAGE_TOKEN = ""

        private val placeholder =
            Regex("＜type=(SUGGEST_COMMAND|RUN_COMMAND) text=\"([^\"]*)\" hover=\"([^\"]*)\" command=\"([^\"]*)\"＞")

        private fun runCommand(text: String, hover: String, command: String) =
            "＜type=RUN_COMMAND text=\"$text\" hover=\"$hover\" command=\"$command\"＞"

        private fun suggestCommand(text: String, hover: String, command: String) =
            "＜type=SUGGEST_COMMAND text=\"$text\" hover=\"$hover\" command=\"$command\"＞"

        /**
         * フォーマットのキーワードを置き換える。
         *
         * @param withPlayerLink プレイヤー名をクリックすると /tell を入力できるようにするか
         * @param templates テンプレート (`%0`〜`%9`) を番号から引く
         */
        fun make(
            format: String,
            member: MemberVars?,
            channel: ChannelVars?,
            hovers: Hovers,
            withPlayerLink: Boolean = true,
            templates: (String) -> String? = { null },
            now: Date = Date(),
        ): ChatFormat {
            val msg = ChatFormat(format)

            if (channel != null) {
                // テンプレートを最初に置き換える (最初に見つかった 1 つだけ)
                for (i in 0..9) {
                    val key = "%$i"
                    if (!msg.contains(key)) continue
                    val template = templates("$i") ?: continue
                    msg.replace(key, template)
                    break
                }
                msg.replace("%ch", runCommand(channel.name, hovers.channel(channel.name), "/lunachat join ${channel.name}"))
                msg.replace("%color", channel.color)
                channel.to?.let { to ->
                    msg.replace("%to", suggestCommand(to.displayName, hovers.player(to.name), "/tell ${to.name}"))
                    msg.replace("%recieverserver", to.server)
                }
            }

            if (msg.contains("%date")) msg.replace("%date", SimpleDateFormat("yyyy/MM/dd").format(now))
            if (msg.contains("%time")) msg.replace("%time", SimpleDateFormat("HH:mm:ss").format(now))

            if (member != null) {
                if (withPlayerLink) {
                    val link = suggestCommand(member.displayName, hovers.player(member.name), "/tell ${member.name}")
                    msg.replace("%displayname", link)
                    msg.replace("%username", link)
                    msg.replace("%player", suggestCommand(member.name, hovers.player(member.name), "/tell ${member.name}"))
                } else {
                    msg.replace("%displayname", member.displayName)
                    msg.replace("%username", member.displayName)
                    msg.replace("%player", member.name)
                }
                if (msg.contains("%prefix") || msg.contains("%suffix")) {
                    msg.replace("%prefix", member.prefix)
                    msg.replace("%suffix", member.suffix)
                }
                msg.replace("%world", member.world)
                msg.replace("%server", member.server)
            }
            return msg
        }

        /** メッセージ (参加しました など) の `%channel%` を、クリックするとそのチャンネルに入れるようにする */
        fun channelMessage(format: String, channelName: String, hovers: Hovers): ChatFormat {
            val msg = ChatFormat(format)
            val stripped = Colors.strip(channelName)
            msg.replace("%channel%", runCommand(channelName, hovers.channel(stripped), "/lunachat join $stripped"))
            return msg
        }

        /**
         * 通常チャット (AsyncPlayerChatEvent) の setFormat 用にする。
         * `%displayName` `%username` は表示名 (`%1$s`)、`%msg` は発言 (`%2$s`) になる。
         * 入れた値に `%` があると String.format が壊れるので `%%` にしておく
         */
        fun normalChatFormat(format: String, member: MemberVars, hovers: Hovers, now: Date = Date()): String {
            val escaped = MemberVars(
                name = member.name.escapeFormat(),
                displayName = member.displayName.escapeFormat(),
                prefix = { member.prefix.escapeFormat() },
                suffix = { member.suffix.escapeFormat() },
                world = member.world.escapeFormat(),
                server = member.server.escapeFormat(),
            )
            val f = format.replace("%displayName", "%1\$s").replace("%username", "%1\$s").replace("%msg", "%2\$s")
            return make(f, escaped, null, hovers, withPlayerLink = false, now = now).toLegacyText()
        }

        private fun String.escapeFormat() = replace("%", "%%")

        /** 文字列の最後で効いている色と装飾のコード */
        fun activeCodes(text: String): String {
            var color = ""
            val formats = StringBuilder()
            var i = 0
            while (i < text.length) {
                if (text[i] != '§' || i + 1 >= text.length) {
                    i++
                    continue
                }
                val code = text[i + 1].lowercaseChar()
                when {
                    code == 'x' && isHexSequence(text, i) -> {
                        color = text.substring(i, i + 14)
                        formats.clear()
                        i += 14
                    }
                    code in '0'..'9' || code in 'a'..'f' -> {
                        color = "§$code"
                        formats.clear()
                        i += 2
                    }
                    code in 'k'..'o' -> {
                        formats.append('§').append(code)
                        i += 2
                    }
                    code == 'r' -> {
                        color = ""
                        formats.clear()
                        i += 2
                    }
                    else -> i++
                }
            }
            return color + formats
        }

        private fun isHexSequence(text: String, start: Int): Boolean {
            if (start + 14 > text.length) return false
            for (k in 0 until 6) {
                val p = start + 2 + k * 2
                if (text[p] != '§' || text[p + 1].digitToIntOrNull(16) == null) return false
            }
            return true
        }

        /** [activeCodes] の色を TextColor にする */
        private fun colorOf(codes: String): TextColor? {
            if (codes.length < 2 || codes[0] != '§') return null
            val code = codes[1].lowercaseChar()
            if (code == 'x' && codes.length >= 14) {
                val hex = (0 until 6).map { codes[3 + it * 2] }.joinToString("")
                return TextColor.fromHexString("#$hex")
            }
            return legacyColors[code]
        }

        private val legacyColors = mapOf(
            '0' to NamedTextColor.BLACK, '1' to NamedTextColor.DARK_BLUE, '2' to NamedTextColor.DARK_GREEN,
            '3' to NamedTextColor.DARK_AQUA, '4' to NamedTextColor.DARK_RED, '5' to NamedTextColor.DARK_PURPLE,
            '6' to NamedTextColor.GOLD, '7' to NamedTextColor.GRAY, '8' to NamedTextColor.DARK_GRAY,
            '9' to NamedTextColor.BLUE, 'a' to NamedTextColor.GREEN, 'b' to NamedTextColor.AQUA,
            'c' to NamedTextColor.RED, 'd' to NamedTextColor.LIGHT_PURPLE, 'e' to NamedTextColor.YELLOW,
            'f' to NamedTextColor.WHITE,
        )
    }
}
