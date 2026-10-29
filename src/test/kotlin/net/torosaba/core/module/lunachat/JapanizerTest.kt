package net.torosaba.core.module.lunachat

import net.torosaba.core.module.lunachat.japanize.GoogleIme
import net.torosaba.core.module.lunachat.japanize.JapanizeType
import net.torosaba.core.module.lunachat.japanize.Japanizer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JapanizerTest {
    private fun kana(org: String, keywords: List<String> = emptyList(), dictionary: Map<String, String> = emptyMap()) =
        Japanizer.japanize(org, JapanizeType.KANA, keywords, dictionary, emptyList())

    @Test
    fun skipRules() {
        // 2 バイト文字を含む・半角カタカナだけなら変換しない
        assertTrue(Japanizer.isSkipped("こんにちは"))
        assertTrue(Japanizer.isSkipped("hello 世界"))
        assertTrue(Japanizer.isSkipped("ｱｲｳ ｴｵ"))
        assertFalse(Japanizer.isSkipped("hello"))
        assertFalse(Japanizer.isSkipped("123 !?"))
        assertFalse(Japanizer.isSkipped(""))
    }

    @Test
    fun noneJapanizeMarker() {
        assertEquals("hello", Japanizer.stripMarker("\$hello", "$"))
        assertNull(Japanizer.stripMarker("hello", "$"))
        assertNull(Japanizer.stripMarker("\$hello", ""))
        assertEquals("", Japanizer.stripMarker("$", "$"))
    }

    @Test
    fun kanaOnly() {
        assertEquals("きょう は いい てんき", kana("kyou ha ii tenki"))
        assertEquals("にほんご", kana("&anihongo"))
        assertEquals("にほんご", kana("§anihongo"))
    }

    @Test
    fun urlIsRemoved() {
        assertEquals("みて   ください", kana("mite http://example.com/a?b=c kudasai"))
    }

    @Test
    fun playerNamesAndDictionaryAreKept() {
        assertEquals("へっぉ marumasa", kana("hello marumasa", keywords = listOf("marumasa")))
        // 名前を除かなければ、名前もかなになる
        assertEquals("へっぉ まるまさ", kana("hello marumasa"))
        assertEquals("マイクラ たのしい", kana("minecraft tanosii", dictionary = mapOf("minecraft" to "マイクラ")))
        // プレイヤー名を先に、次に辞書
        assertEquals("ded ちゅう", kana("ded tyuu", keywords = listOf("ded"), dictionary = mapOf("ded" to "デッド")))
    }

    @Test
    fun imeRunsAfterKanaAndBeforeUnlock() {
        val result = Japanizer.japanize(
            "marumasa desu", JapanizeType.GOOGLE_IME, listOf("marumasa"), emptyMap(), emptyList(),
        ) { "<$it>" }
        assertEquals("<marumasa です>", result)
    }

    @Test
    fun imeFailureFallsBackToKana() {
        val result = Japanizer.japanize("nihongo", JapanizeType.GOOGLE_IME, emptyList(), emptyMap(), emptyList()) { null }
        assertEquals("にほんご", result)
    }

    @Test
    fun noneTypeReturnsAsIs() {
        assertEquals("nihongo", Japanizer.japanize("nihongo", JapanizeType.NONE, emptyList(), emptyMap(), emptyList()))
    }

    @Test
    fun ngWordsAreMaskedAfterConversion() {
        val ng = listOf(Pattern.compile("ばか"))
        assertEquals("** です", Japanizer.japanize("baka desu", JapanizeType.KANA, emptyList(), emptyMap(), ng))
        // LunaChat と同じく、最初の一致の長さで全部の一致を置き換える
        assertEquals("**x**", Japanizer.maskNgWords("abxab", listOf(Pattern.compile("ab"))))
        assertTrue(Japanizer.containsNgWord("abc", listOf(Pattern.compile("b"))))
    }

    @Test
    fun displayFormat() {
        assertEquals(
            "nihongo §6(日本語)",
            Japanizer.format(Colors.replace("%msg &6(%japanize)"), "nihongo", "日本語"),
        )
        assertEquals("§6[JP] 日本語", Japanizer.format(Colors.replace("&6[JP] %japanize"), "nihongo", "日本語"))
    }

    @Test
    fun googleImeJson() {
        val json = """[["にほんご",["日本語","にほんご","ニホンゴ"]],["です",["です","デス"]]]"""
        assertEquals("日本語です", GoogleIme.parseJson(json))
    }

    /** 実際に Google に問い合わせる。LUNACHAT_NETWORK_TEST=1 のときだけ */
    @Test
    @EnabledIfEnvironmentVariable(named = "LUNACHAT_NETWORK_TEST", matches = "1")
    fun googleImeOnline() {
        val result = Japanizer.japanize("nihongo desu", JapanizeType.GOOGLE_IME, emptyList(), emptyMap(), emptyList())
        assertEquals("日本語 です", result)
    }

    @Test
    fun japanizeTypeIds() {
        assertEquals(JapanizeType.GOOGLE_IME, JapanizeType.fromId("GoogleIME", null))
        assertEquals(JapanizeType.KANA, JapanizeType.fromId("kana", null))
        assertNull(JapanizeType.fromId("null", null))
        assertEquals("googleime", JapanizeType.GOOGLE_IME.toString())
    }
}
