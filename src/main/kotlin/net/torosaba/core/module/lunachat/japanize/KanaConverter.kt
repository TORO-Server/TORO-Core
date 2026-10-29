package net.torosaba.core.module.lunachat.japanize

/**
 * ローマ字をかな文字にする。
 *
 * LunaChat の YukiKanaConverter (作者 YukiLeafX, LGPL-3.0) の変換表をそのまま移したもの。
 * 元は「ローマ字の降順に並べた表で StringUtils.replaceEach する」実装で、これは
 * 「左から順に、その位置で一致するいちばん長いローマ字に置き換える」のと同じ結果になる。
 *
 * @see <a href="https://support.microsoft.com/ja-jp/help/883232">参考</a>
 */
object KanaConverter {

    /** ローマ字 → かな */
    val table: Map<String, String> = buildTable()

    private val maxKeyLength = table.keys.maxOf { it.length }

    /** ローマ字をかな文字にする。表にない文字はそのまま残す */
    fun conv(romaji: String): String {
        val out = StringBuilder(romaji.length)
        var i = 0
        while (i < romaji.length) {
            var matched = false
            var len = minOf(maxKeyLength, romaji.length - i)
            while (len > 0) {
                val kana = table[romaji.substring(i, i + len)]
                if (kana != null) {
                    out.append(kana)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                out.append(romaji[i])
                i++
            }
        }
        return out.toString()
    }

    private fun buildTable(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        fun put(kana: String, vararg romaji: String) {
            for (r in romaji) {
                // 元の実装 (Guava の ImmutableSortedMap) は同じキーがあると作れないので、重複はないはず
                check(map.put(r, kana) == null) { "重複したローマ字: $r" }
            }
        }

        // ひらがな
        put("あ", "a")
        put("い", "i", "yi")
        put("う", "u", "wu", "whu")
        put("え", "e")
        put("お", "o")

        put("うぁ", "wha")
        put("うぃ", "whi", "wi")
        put("うぇ", "whe", "we")
        put("うぉ", "who")

        put("ゐ", "wyi")
        put("ゑ", "wye")

        put("ぁ", "la", "xa")
        put("ぃ", "li", "xi", "lyi", "xyi")
        put("ぅ", "lu", "xu")
        put("ぇ", "le", "xe", "lye", "xye")
        put("ぉ", "lo", "xo")

        put("いぇ", "ye")

        put("か", "ka", "ca")
        put("き", "ki")
        put("く", "ku", "cu", "qu")
        put("け", "ke")
        put("こ", "ko", "co")

        put("きゃ", "kya")
        put("きぃ", "kyi")
        put("きゅ", "kyu")
        put("きぇ", "kye")
        put("きょ", "kyo")

        put("くゃ", "qya")
        put("くゅ", "qyu")
        put("くょ", "qyo")

        put("くぁ", "qwa", "qa", "kwa")
        put("くぃ", "qwi", "qi", "qyi")
        put("くぅ", "qwu")
        put("くぇ", "qwe", "qe", "qye")
        put("くぉ", "qwo", "qo", "kwo")

        put("が", "ga")
        put("ぎ", "gi")
        put("ぐ", "gu")
        put("げ", "ge")
        put("ご", "go")

        put("ぎゃ", "gya")
        put("ぎぃ", "gyi")
        put("ぎゅ", "gyu")
        put("ぎぇ", "gye")
        put("ぎょ", "gyo")

        put("ぐぁ", "gwa")
        put("ぐぃ", "gwi")
        put("ぐぅ", "gwu")
        put("ぐぇ", "gwe")
        put("ぐぉ", "gwo")

        put("ヵ", "lka", "xka")
        put("ヶ", "lke", "xke")

        put("さ", "sa")
        put("し", "si", "ci", "shi")
        put("す", "su")
        put("せ", "se", "ce")
        put("そ", "so")

        put("しゃ", "sya", "sha")
        put("しぃ", "syi")
        put("しゅ", "syu", "shu")
        put("しぇ", "sye", "she")
        put("しょ", "syo", "sho")

        put("すぁ", "swa")
        put("すぃ", "swi")
        put("すぅ", "swu")
        put("すぇ", "swe")
        put("すぉ", "swo")

        put("ざ", "za")
        put("じ", "zi", "ji")
        put("ず", "zu")
        put("ぜ", "ze")
        put("ぞ", "zo")

        put("じゃ", "zya", "ja", "jya")
        put("じぃ", "zyi", "jyi")
        put("じゅ", "zyu", "ju", "jyu")
        put("じぇ", "zye", "je", "jye")
        put("じょ", "zyo", "jo", "jyo")

        put("た", "ta")
        put("ち", "ti", "chi")
        put("つ", "tu", "tsu")
        put("て", "te")
        put("と", "to")

        put("ちゃ", "tya", "cha", "cya")
        put("ちぃ", "tyi", "cyi")
        put("ちゅ", "tyu", "chu", "cyu")
        put("ちぇ", "tye", "che", "cye")
        put("ちょ", "tyo", "cho", "cyo")

        put("つぁ", "tsa")
        put("つぃ", "tsi")
        put("つぇ", "tse")
        put("つぉ", "tso")

        put("てゃ", "tha")
        put("てぃ", "thi")
        put("てゅ", "thu")
        put("てぇ", "the")
        put("てょ", "tho")

        put("とぁ", "twa")
        put("とぃ", "twi")
        put("とぅ", "twu")
        put("とぇ", "twe")
        put("とぉ", "two")

        put("だ", "da")
        put("ぢ", "di")
        put("づ", "du")
        put("で", "de")
        put("ど", "do")

        put("ぢゃ", "dya")
        put("ぢぃ", "dyi")
        put("ぢゅ", "dyu")
        put("ぢぇ", "dye")
        put("ぢょ", "dyo")

        put("でゃ", "dha")
        put("でぃ", "dhi")
        put("でゅ", "dhu")
        put("でぇ", "dhe")
        put("でょ", "dho")

        put("どぁ", "dwa")
        put("どぃ", "dwi")
        put("どぅ", "dwu")
        put("どぇ", "dwe")
        put("どぉ", "dwo")

        put("っ", "ltu", "xtu", "ltsu", "xtsu")

        put("な", "na")
        put("に", "ni")
        put("ぬ", "nu")
        put("ね", "ne")
        put("の", "no")

        put("にゃ", "nya")
        put("にぃ", "nyi")
        put("にゅ", "nyu")
        put("にぇ", "nye")
        put("にょ", "nyo")

        put("は", "ha")
        put("ひ", "hi")
        put("ふ", "hu", "fu")
        put("へ", "he")
        put("ほ", "ho")

        put("ひゃ", "hya")
        put("ひぃ", "hyi")
        put("ひゅ", "hyu")
        put("ひぇ", "hye")
        put("ひょ", "hyo")

        put("ふぁ", "fwa", "fa")
        put("ふぃ", "fwi", "fi", "fyi")
        put("ふぅ", "fwu")
        put("ふぇ", "fwe", "fe", "fye")
        put("ふぉ", "fwo", "fo")

        put("ふゃ", "fya")
        put("ふゅ", "fyu")
        put("ふょ", "fyo")

        put("ば", "ba")
        put("び", "bi")
        put("ぶ", "bu")
        put("べ", "be")
        put("ぼ", "bo")

        put("びゃ", "bya")
        put("びぃ", "byi")
        put("びゅ", "byu")
        put("びぇ", "bye")
        put("びょ", "byo")

        put("ヴぁ", "va")
        put("ヴぃ", "vi")
        put("ヴ", "vu")
        put("ヴぇ", "ve")
        put("ヴぉ", "vo")

        put("ヴゃ", "vya")
        put("ヴぃ", "vyi")
        put("ヴゅ", "vyu")
        put("ヴぇ", "vye")
        put("ヴょ", "vyo")

        put("ぱ", "pa")
        put("ぴ", "pi")
        put("ぷ", "pu")
        put("ぺ", "pe")
        put("ぽ", "po")

        put("ぴゃ", "pya")
        put("ぴぃ", "pyi")
        put("ぴゅ", "pyu")
        put("ぴぇ", "pye")
        put("ぴょ", "pyo")

        put("ま", "ma")
        put("み", "mi")
        put("む", "mu")
        put("め", "me")
        put("も", "mo")

        put("みゃ", "mya")
        put("みぃ", "myi")
        put("みゅ", "myu")
        put("みぇ", "mye")
        put("みょ", "myo")

        put("や", "ya")
        put("ゆ", "yu")
        put("よ", "yo")

        put("ゃ", "lya", "xya")
        put("ゅ", "lyu", "xyu")
        put("ょ", "lyo", "xyo")

        put("ら", "ra")
        put("り", "ri")
        put("る", "ru")
        put("れ", "re")
        put("ろ", "ro")

        put("りゃ", "rya")
        put("りぃ", "ryi")
        put("りゅ", "ryu")
        put("りぇ", "rye")
        put("りょ", "ryo")

        put("わ", "wa")
        put("を", "wo")

        put("ゎ", "lwa", "xwa")

        put("ん", "n", "nn", "n'", "xn")

        // 促音 (先頭の子音を重ねたもの)。母音と n から始まるものは除く
        for ((romaji, kana) in map.entries.toList()) {
            if (romaji[0] !in "aiueon") put("っ$kana", romaji[0] + romaji)
        }

        // 記号
        put("ー", "-")
        put("、", ",")
        put("。", ".")
        put("？", "?")
        put("！", "!")
        put("「", "[")
        put("」", "]")
        put("＜", "<")
        put("＞", ">")
        put("＆", "&")
        put("”", "\"")
        put("（", "(")
        put("）", ")")

        return map
    }
}
