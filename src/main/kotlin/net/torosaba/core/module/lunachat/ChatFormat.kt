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
 * クリックできる部分の中身は [links] に持ち、文字列には私用領域の文字の目印 (`LINK_START` 番号 `LINK_END`) だけを埋め込む。
 * フォーマットと置き換える値からは目印の文字を取り除くので、目印を作れるのはこのクラスだけになる。
 * 発言 (`%msg`) は最後に別に差し込むので、発言の中身はクリックできる部分にならない。
 */
class ChatFormat(format: String) {
    private class Link(val run: Boolean, val text: String, val hover: String, val command: String) {
        fun clickEvent(): ClickEvent<*> = if (run) ClickEvent.runCommand(command) else ClickEvent.suggestCommand(command)
    }

    private val buf = StringBuilder(format.withoutMarkers())
    private val links = ArrayList<Link>()
    private var message: String = ""

    /** [keyword] をすべて [value] に置き換える。置き換えた後の文字列は、もう一度は調べない */
    fun replace(keyword: String, value: String) = replaceRaw(keyword, value.withoutMarkers())

    /** 目印を取り除かずに置き換える。[value] はこのクラスで作った目印だけにする */
    private fun replaceRaw(keyword: String, value: String) {
        if (keyword.isEmpty()) return
        var index = buf.indexOf(keyword)
        while (index > -1) {
            buf.replace(index, index + keyword.length, value)
            index = buf.indexOf(keyword, index + value.length)
        }
    }

    /** [keyword] をクリックできる部分にする */
    private fun replaceLink(keyword: String, link: Link) {
        if (!contains(keyword)) return
        links += link
        replaceRaw(keyword, "$LINK_START${links.lastIndex}$LINK_END")
    }

    fun contains(keyword: String): Boolean = buf.indexOf(keyword) > -1

    /** `%msg` に発言を入れる */
    fun setMessage(message: String) {
        replaceRaw("%msg", MESSAGE_TOKEN)
        this.message = message
    }

    /** そのままの文字列 (クリック部分は文字だけにし、色コード候補は `§` にする) */
    fun toLegacyText(): String {
        val text = linkToken.replace(Colors.replace(buf.toString())) { Colors.replace(linkOf(it).text) }
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
        for (match in linkToken.findAll(text)) {
            if (lastIndex < match.range.first) {
                lastColor = appendLiteral(root, text.substring(lastIndex, match.range.first))
            }
            val link = linkOf(match)
            val click = Component.text().append(Colors.component(Colors.replace(link.text)))
            if (link.hover.isNotEmpty()) click.hoverEvent(HoverEvent.showText(Colors.component(Colors.replace(link.hover))))
            click.clickEvent(link.clickEvent())
            if (lastColor != null) click.color(lastColor)
            root.append(click.build())
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) appendLiteral(root, text.substring(lastIndex))
        return root.build()
    }

    private fun linkOf(match: MatchResult): Link = links[match.groupValues[1].toInt()]

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

        /** クリックできる部分の目印 (私用領域の文字)。間に [links] の番号が入る */
        private const val LINK_START = ""
        private const val LINK_END = ""

        private val markers = charArrayOf('', '', '')
        private val linkToken = Regex("$LINK_START([0-9]+)$LINK_END")

        /** 外から来た文字列が内部の目印にならないよう、目印の文字を取り除く */
        private fun String.withoutMarkers(): String = if (indexOfAny(markers) < 0) this else filterNot { it in markers }

        private fun runCommand(text: String, hover: String, command: String) = Link(true, text, hover, command)

        private fun suggestCommand(text: String, hover: String, command: String) = Link(false, text, hover, command)

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
                msg.replaceLink("%ch", runCommand(channel.name, hovers.channel(channel.name), "/lunachat join ${channel.name}"))
                msg.replace("%color", channel.color)
                channel.to?.let { to ->
                    msg.replaceLink("%to", suggestCommand(to.displayName, hovers.player(to.name), "/tell ${to.name}"))
                    msg.replace("%recieverserver", to.server)
                }
            }

            if (msg.contains("%date")) msg.replace("%date", SimpleDateFormat("yyyy/MM/dd").format(now))
            if (msg.contains("%time")) msg.replace("%time", SimpleDateFormat("HH:mm:ss").format(now))

            if (member != null) {
                if (withPlayerLink) {
                    val link = suggestCommand(member.displayName, hovers.player(member.name), "/tell ${member.name}")
                    msg.replaceLink("%displayname", link)
                    msg.replaceLink("%username", link)
                    msg.replaceLink("%player", suggestCommand(member.name, hovers.player(member.name), "/tell ${member.name}"))
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
            msg.replaceLink("%channel%", runCommand(channelName, hovers.channel(stripped), "/lunachat join $stripped"))
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
