package net.torosaba.core.module.lunachat

import net.torosaba.core.module.lunachat.japanize.KanaConverter
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class KanaConverterTest {

    /** 期待値は LunaChat 3 の YukiKanaConverter.conv (Java) を実際に動かして得たもの */
    private val expected = listOf(
        "nihongo" to "にほんご",
        "kyou ha ii tenki" to "きょう は いい てんき",
        "konnichiha" to "こんいちは",
        "konnnichiwa" to "こんにちわ",
        "gakkou" to "がっこう",
        "shinkansen" to "しんかんせん",
        "tokyo" to "ときょ",
        "Hello World" to "Hえっぉ Wおrld",
        "wwww" to "wwww",
        "kitte" to "きって",
        "matcha" to "まtちゃ",
        "ttsu" to "っつ",
        "xtsu" to "っ",
        "xxtsu" to "っっ",
        "n'a" to "んあ",
        "kanna" to "かんあ",
        "sennen" to "せんえん",
        "onna" to "おんあ",
        "nyan" to "にゃん",
        "shinbun" to "しんぶん",
        "fu-ri-" to "ふーりー",
        "kore,sore.are?" to "これ、それ。あれ？",
        "yatta!" to "やった！",
        "[kagi]" to "「かぎ」",
        "<tag>&\"q\"(kakko)" to "＜たg＞＆”q”（かっこ）",
        "vu va vyu" to "ヴ ヴぁ ヴゅ",
        "qwa kwa qa" to "くぁ くぁ くぁ",
        "ltu ltsu" to "っ っ",
        "jya ja jyu" to "じゃ じゃ じゅ",
        "tyotto chotto" to "ちょっと ちょっと",
        "thi dhi twu" to "てぃ でぃ とぅ",
        "wha whi whe who" to "うぁ うぃ うぇ うぉ",
        "lka lke xn" to "ヵ ヶ ん",
        "ccha cchi" to "っちゃ っち",
        "ppu bbo" to "っぷ っぼ",
        "123 abc" to "123 あbc",
        "Minecraft" to "Mいねcらft",
        "dynmap" to "dyんまp",
        "ok" to "おk",
        "lol" to "ぉl",
        "http://example.com" to "http://えぁmpぇ。こm",
    )

    @Test
    fun convertsLikeLunaChat() {
        for ((input, output) in expected) {
            assertEquals(output, KanaConverter.conv(input), "入力: $input")
        }
    }

    @Test
    fun tableIsSameAsLunaChat() {
        // LunaChat の YukiKanaConverter.MAP を名前順に `key=value;` でつないだものの SHA-256 と件数
        val joined = KanaConverter.table.toSortedMap().entries.joinToString("") { "${it.key}=${it.value};" }
        val hash = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(561, KanaConverter.table.size)
        assertEquals("f774bda0fd58efe445b75d552168e1bc62a54bb378c3c7ded54cba25fa17ea4c", hash)
    }

    @Test
    fun emptyAndNonRomaji() {
        assertEquals("", KanaConverter.conv(""))
        assertEquals("日本語", KanaConverter.conv("日本語"))
    }
}
