package net.torosaba.core.module.signkeep

/**
 * SNBT (文字列の NBT) の読み取り。看板の `block_entity_data` を取り出すためだけの最小の実装。
 *
 * 結果: 複合タグ → `Map<String, Any?>`、リスト → `List<Any?>`、文字列 → `String`、
 * 数値 → `Long` か `Double`、`1b` のような byte → [SnbtByte]。配列型 (`[I;1,2]`) は中身のリストとして読む。
 * 読めない入力は [IllegalArgumentException]。
 */
internal object Snbt {
    class SnbtByte(val value: Int)

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.atEnd()) { "余分な文字があります: ${p.rest()}" }
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun rest() = s.substring(i, minOf(s.length, i + 20))

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            require(i < s.length) { "入力が途中で終わっています" }
            return when (s[i]) {
                '{' -> compound()
                '[' -> list()
                '"', '\'' -> quoted()
                else -> bare()
            }
        }

        private fun compound(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = if (peek() == '"' || peek() == '\'') quoted() else bareToken()
                skipWs()
                require(peek() == ':') { "':' がありません: ${rest()}" }
                i++
                out[key] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("',' か '}' がありません: ${rest()}")
                }
            }
        }

        private fun list(): List<Any?> {
            i++ // [
            // 配列型の接頭辞 (B; I; L;)
            if (i + 1 < s.length && s[i] in "BIL" && s[i + 1] == ';') i += 2
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("',' か ']' がありません: ${rest()}")
                }
            }
        }

        private fun quoted(): String {
            val quote = s[i++]
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "文字列が閉じていません" }
                val c = s[i++]
                when {
                    c == '\\' -> {
                        require(i < s.length) { "文字列が閉じていません" }
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            's' -> sb.append(' ')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    c == quote -> return sb.toString()
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun bareToken(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_-.+")) i++
            require(i > start) { "値がありません: ${rest()}" }
            return s.substring(start, i)
        }

        private fun bare(): Any {
            val t = bareToken()
            Regex("""^([+-]?\d+)[bB]$""").find(t)?.let { return SnbtByte(it.groupValues[1].toInt()) }
            Regex("""^([+-]?\d+)[sSlL]?$""").find(t)?.let { return it.groupValues[1].toLong() }
            Regex("""^([+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)[fFdD]?$""").find(t)?.let { return it.groupValues[1].toDouble() }
            return when (t) {
                "true" -> SnbtByte(1)
                "false" -> SnbtByte(0)
                else -> t
            }
        }
    }
}
