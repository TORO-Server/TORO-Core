package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** LunaChat の色コードの扱い (`&a` と、Web カラー `#99AABB` `#9AB`) */
object Colors {
    private val web6 = Regex("#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])")
    private val web3 = Regex("#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])")
    private val alt = Regex("&([0-9a-fk-orA-FK-OR])")
    private val stripWeb6 = Regex("#[0-9a-fA-F]{6}")
    private val stripWeb3 = Regex("#[0-9a-fA-F]{3}")
    private val stripAlt = Regex("&([0-9a-fk-orxA-FK-ORX])")
    private val stripSection = Regex("§([0-9a-fk-orxA-FK-ORX])")
    private val altCode = Regex("(&[0-9a-fk-orA-FK-OR]|#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6})")

    /** `§x§r§r§g§g§b§b` 形式の 16 進カラーも読める、`§` の色コードの変換器 */
    val serializer: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    /** カラーコード候補 (`&a` `#99AABB` `#9AB`) を `§` のカラーコードにする */
    fun replace(source: String): String =
        source.replace(web6, "§x§\$1§\$2§\$3§\$4§\$5§\$6")
            .replace(web3, "§x§\$1§\$1§\$2§\$2§\$3§\$3")
            .replace(alt, "§\$1")

    /** カラーコードとカラーコード候補を取り除く */
    fun strip(source: String): String = stripAlt(source).replace(stripSection, "")

    /** カラーコード候補 (`&a` `#99AABB`) だけを取り除く */
    fun stripAlt(source: String): String =
        source.replace(stripWeb6, "").replace(stripWeb3, "").replace(stripAlt, "")

    /** `&a` `#9AB` `#99AABB` のどれかか */
    fun isAltColorCode(code: String): Boolean = altCode.matches(code)

    private val names = mapOf(
        "BLACK" to '0', "DARK_BLUE" to '1', "DARK_GREEN" to '2', "DARK_AQUA" to '3',
        "DARK_RED" to '4', "DARK_PURPLE" to '5', "GOLD" to '6', "GRAY" to '7',
        "DARK_GRAY" to '8', "BLUE" to '9', "GREEN" to 'a', "AQUA" to 'b',
        "RED" to 'c', "LIGHT_PURPLE" to 'd', "YELLOW" to 'e', "WHITE" to 'f',
        "MAGIC" to 'k', "BOLD" to 'l', "STRIKETHROUGH" to 'm', "UNDERLINE" to 'n',
        "ITALIC" to 'o', "RESET" to 'r',
    )

    /** RED や GREEN などの色の名前か */
    fun isValidColor(color: String): Boolean = color.uppercase() in names

    /** 色の名前を `&a` などにする。知らない名前は白 */
    fun toColorCode(color: String): String = "&" + (names[color.uppercase()] ?: 'f')

    /** `§` の色コードを含む文字列を Component にする */
    fun component(legacy: String): Component = serializer.deserialize(legacy)

    fun legacy(component: Component): String = serializer.serialize(component)

    fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
}
