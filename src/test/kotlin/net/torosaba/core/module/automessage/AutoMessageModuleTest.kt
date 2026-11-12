package net.torosaba.core.module.automessage

import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoMessageModuleTest {
    private fun getSerializer(): LegacyComponentSerializer {
        val module = AutoMessageModule()
        val field = AutoMessageModule::class.java.getDeclaredField("serializer")
        field.isAccessible = true
        return field.get(module) as LegacyComponentSerializer
    }

    @Test
    fun `URLにのみ下線と水色スタイルおよびOPEN_URLクリックイベントが付与される`() {
        val serializer = getSerializer()
        val root = serializer.deserialize("§eTips : map -> https://map.torosaba.net/")

        assertEquals("Tips : map -> ", root.content())
        assertEquals(NamedTextColor.YELLOW, root.color())
        assertFalse(root.hasDecoration(TextDecoration.UNDERLINED))
        assertNull(root.clickEvent())

        assertEquals(1, root.children().size)
        val child = root.children()[0]
        assertTrue(child is TextComponent)
        assertEquals("https://map.torosaba.net/", child.content())
        assertEquals(NamedTextColor.AQUA, child.color())
        assertTrue(child.hasDecoration(TextDecoration.UNDERLINED))

        val clickEvent = child.clickEvent()
        assertNotNull(clickEvent)
        assertEquals(ClickEvent.Action.OPEN_URL, clickEvent.action())
        val payload = clickEvent.payload()
        assertTrue(payload is ClickEvent.Payload.Text)
        assertEquals("https://map.torosaba.net/", payload.value())
        assertEquals(ClickEvent.openUrl("https://map.torosaba.net/"), clickEvent)
    }

    @Test
    fun `URLの前後のテキストはURL用スタイルが波及しない`() {
        val serializer = getSerializer()
        val root = serializer.deserialize("§e前 https://torosaba.net/ 後")

        assertEquals("前 ", root.content())
        assertEquals(NamedTextColor.YELLOW, root.color())
        assertFalse(root.hasDecoration(TextDecoration.UNDERLINED))
        assertNull(root.clickEvent())

        assertEquals(2, root.children().size)
        val urlChild = root.children()[0]
        assertTrue(urlChild is TextComponent)
        assertEquals("https://torosaba.net/", urlChild.content())
        assertEquals(NamedTextColor.AQUA, urlChild.color())
        assertTrue(urlChild.hasDecoration(TextDecoration.UNDERLINED))
        val clickEvent = urlChild.clickEvent()
        assertNotNull(clickEvent)
        assertEquals(ClickEvent.Action.OPEN_URL, clickEvent.action())
        val payload = clickEvent.payload()
        assertTrue(payload is ClickEvent.Payload.Text)
        assertEquals("https://torosaba.net/", payload.value())

        val afterChild = root.children()[1]
        assertTrue(afterChild is TextComponent)
        assertEquals(" 後", afterChild.content())
        // 非URL部分に下線やAQUA色は付与されない
        assertFalse(afterChild.hasDecoration(TextDecoration.UNDERLINED))
        assertNull(afterChild.clickEvent())
    }

    @Test
    fun `URLを含まないメッセージは従来の装飾のみを維持する`() {
        val serializer = getSerializer()
        val root = serializer.deserialize("§a通常メッセージ")

        assertEquals("通常メッセージ", root.content())
        assertEquals(NamedTextColor.GREEN, root.color())
        assertFalse(root.hasDecoration(TextDecoration.UNDERLINED))
        assertNull(root.clickEvent())
        assertTrue(root.children().isEmpty())
    }
}
