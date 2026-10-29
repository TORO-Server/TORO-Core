package net.torosaba.core.module.lunachat

import net.torosaba.core.module.lunachat.japanize.JapanizeType
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRoutingTest {

    @Test
    fun globalMarker() {
        assertEquals("hello", ChatRouting.stripGlobalMarker("!hello", "!"))
        assertNull(ChatRouting.stripGlobalMarker("!", "!"))
        assertNull(ChatRouting.stripGlobalMarker("hello", "!"))
        assertNull(ChatRouting.stripGlobalMarker("!hello", ""))
    }

    @Test
    fun quickChannelChat() {
        assertEquals("m" to "hello", ChatRouting.splitQuick("m:hello", ":"))
        assertEquals("a" to "b:c", ChatRouting.splitQuick("a:b:c", ":"))
        assertEquals("" to "x", ChatRouting.splitQuick(":x", ":"))
        assertEquals("main" to "", ChatRouting.splitQuick("main:", ":"))
        assertEquals("http" to "//example.com", ChatRouting.splitQuick("http://example.com", ":"))
        assertNull(ChatRouting.splitQuick("hello", ":"))
        assertNull(ChatRouting.splitQuick("a:b", ""))
        // 区切りは正規表現ではなく文字として扱う
        assertEquals("a" to "b", ChatRouting.splitQuick("a.b", "."))
    }

    @Test
    fun torosProductionSettings() {
        val yaml = YamlConfiguration.loadConfiguration(
            """
            lang: en
            playerChatEventListenerPriority: 'HIGH'
            japanizeType: GoogleIME
            japanizeDisplayLine: 1
            japanizeLine1Format: '%msg &6(%japanize)'
            enableNormalChatMessageFormat: true
            normalChatMessageFormat: '&f%displayname&a:&f %msg'
            globalMarker: '!'
            globalChannel: ''
            quickChannelChatSeparator: ':'
            noneJapanizeMarker: '$'
            ngword: []
            ngwordAction: mask
            """.trimIndent().reader(),
        )
        val s = Settings(yaml)
        assertEquals(EventPriority.HIGH, s.playerChatEventListenerPriority)
        assertEquals(JapanizeType.GOOGLE_IME, s.japanizeType)
        assertEquals(1, s.japanizeDisplayLine)
        assertEquals("", s.globalChannel)
        assertTrue(s.ngword.isEmpty())
        assertEquals(NgWordAction.MASK, s.ngwordAction)
        assertTrue(s.japanizePlayerDefault)
        assertTrue(s.noJoinAsGlobal)
        // 書かれていないキーは LunaChat の既定値
        assertEquals(listOf("%username", "%msg"), s.formatConstraint)
        assertEquals("&6[JP] %japanize", s.japanizeLine2Format)
        assertFalse(s.enableNormalChatClickable)
    }

    @Test
    fun invalidSettingsFallBack() {
        val warnings = mutableListOf<String>()
        val yaml = YamlConfiguration.loadConfiguration(
            """
            japanizeDisplayLine: 3
            globalChannel: 'bad name!'
            ngword: ['(']
            playerChatEventListenerPriority: MONITOR
            """.trimIndent().reader(),
        )
        val s = Settings(yaml) { warnings += it }
        assertEquals(2, s.japanizeDisplayLine)
        assertEquals("", s.globalChannel)
        assertTrue(s.ngword.isEmpty())
        assertEquals(JapanizeType.NONE, s.japanizeType)
        assertEquals(EventPriority.HIGHEST, s.playerChatEventListenerPriority)
        assertEquals(2, warnings.size)
    }
}
