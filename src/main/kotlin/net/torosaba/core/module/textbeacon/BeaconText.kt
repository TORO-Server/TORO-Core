package net.torosaba.core.module.textbeacon

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/** 看板の行と `§` 付き文字列の変換。`§x§r§r§g§g§b§b` の RGB 色もそのまま残す */
internal val signText: LegacyComponentSerializer = LegacyComponentSerializer.builder()
    .character(LegacyComponentSerializer.SECTION_CHAR)
    .hexColors()
    .useUnusualXRepeatedCharacterHexFormat()
    .build()

internal fun Component?.signLine(): String = this?.let(signText::serialize) ?: ""

private val HEX_COLOR = Regex("(?i)&#([0-9a-f]{6})")
private const val COLOR_CHARS = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx"

/** Bukkit の ChatColor.translateAlternateColorCodes と同じ置き換え */
internal fun translateColorCodes(alt: Char, text: String): String {
    val b = text.toCharArray()
    for (i in 0 until b.size - 1) {
        if (b[i] == alt && COLOR_CHARS.indexOf(b[i + 1]) > -1) {
            b[i] = '§'
            b[i + 1] = b[i + 1].lowercaseChar()
        }
    }
    return String(b)
}

/** `&a` と `&#RRGGBB` を `§` の色コードにする */
internal fun colorize(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    val hex = HEX_COLOR.replace(text) { m -> "§x" + m.groupValues[1].lowercase().map { "§$it" }.joinToString("") }
    return translateColorCodes('&', hex)
}

/** 看板の 2〜4 行目からメッセージを作る */
internal fun buildMessage(lines: List<String>): String =
    (1..3).mapNotNull { lines.getOrNull(it) }
        .filter { it.isNotBlank() }
        .joinToString("\n") { colorize(it) }

/** /tb の内容を看板の 2〜4 行目に分ける (`\n` で改行) */
internal fun splitMessageForSign(content: String): List<String> {
    val src = colorize(content).replace("\\n", "\n").split("\n")
    return List(3) { src.getOrElse(it) { "" } }
}
