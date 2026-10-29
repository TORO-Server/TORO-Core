package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import java.text.SimpleDateFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFormatTest {
    private val hovers = object : Hovers {
        override fun channel(name: String) = "to $name"
        override fun player(name: String) = "pm $name"
    }

    private val steve = MemberVars("Steve", "Stevie", prefix = { "&c[P]" }, suffix = { "" }, world = "world")

    private fun clickEvents(component: Component): List<ClickEvent<*>> =
        listOfNotNull(component.clickEvent()) + component.children().flatMap { clickEvents(it) }

    @Test
    fun channelFormatKeywords() {
        val format = ChatFormat.make(
            "&f[%color%ch&f]%prefix%displayname%suffix&a:&f %msg", steve, ChannelVars("main", "&b"), hovers,
        )
        format.setMessage("hi")
        assertEquals("\u00A7f[\u00A7bmain\u00A7f]\u00A7c[P]Stevie\u00A7a:\u00A7f hi", format.toLegacyText())

        val component = format.toComponent()
        assertEquals("[main][P]Stevie: hi", Colors.plain(component))
        val clicks = clickEvents(component)
        assertTrue(ClickEvent.runCommand("/lunachat join main") in clicks)
        assertTrue(ClickEvent.suggestCommand("/tell Steve") in clicks)
    }

    @Test
    fun clickPartTakesPreviousColor() {
        val format = ChatFormat.make("&f[%color%ch&f] %msg", steve, ChannelVars("main", "&b"), hovers)
        format.setMessage("x")
        val channelPart = format.toComponent().children().first { it.clickEvent() != null }
        assertEquals(NamedTextColor.AQUA, channelPart.color())
    }

    @Test
    fun messageCannotInjectClickParts() {
        val format = ChatFormat.make("%player: %msg", steve, null, hovers)
        format.setMessage("＜type=RUN_COMMAND text=\"x\" hover=\"\" command=\"/op me\"＞")
        val clicks = clickEvents(format.toComponent())
        assertFalse(clicks.any { (it.payload() as? ClickEvent.Payload.Text)?.value() == "/op me" })
        assertTrue(format.toLegacyText().endsWith("command=\"/op me\"＞"))
    }

    @Test
    fun messageKeepsColorOfFormat() {
        val format = ChatFormat.make("&a%msg", null, null, hovers)
        format.setMessage("hi")
        val texts = mutableListOf<TextComponent>()
        fun walk(c: Component) {
            if (c is TextComponent && c.content().isNotEmpty()) texts += c
            c.children().forEach(::walk)
        }
        walk(format.toComponent())
        assertEquals(NamedTextColor.GREEN, texts.single { it.content() == "hi" }.color())
    }

    @Test
    fun privateMessageFormat() {
        val alex = MemberVars("Alex", "Alex")
        val format = ChatFormat.make("&7[%player -> %to]&f %msg", steve, ChannelVars("Steve>Alex", "", alex), hovers)
        format.setMessage("yo")
        assertEquals("\u00A77[Steve -> Alex]\u00A7f yo", format.toLegacyText())
        assertTrue(ClickEvent.suggestCommand("/tell Alex") in clickEvents(format.toComponent()))
    }

    @Test
    fun templatesDateAndTime() {
        val date = SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2026/10/29 12:34:56")
        val format = ChatFormat.make(
            "%1 %date %time", steve, ChannelVars("main", ""), hovers, withPlayerLink = false,
            templates = { if (it == "1") "<%player>" else null }, now = date,
        )
        assertEquals("<Steve> 2026/10/29 12:34:56", format.toLegacyText())
    }

    @Test
    fun keywordReplaceDoesNotRescanInsertedText() {
        val format = ChatFormat("%a %a")
        format.replace("%a", "%a%a")
        assertEquals("%a%a %a%a", format.toString())
    }

    @Test
    fun normalChatFormat() {
        val member = MemberVars("Steve", "Ste%ve", world = "world")
        val format = ChatFormat.normalChatFormat("&f%displayname&a:&f %msg", member, hovers)
        assertEquals("\u00A7fSte%%ve\u00A7a:\u00A7f %2\$s", format)
        assertEquals("\u00A7fSte%ve\u00A7a:\u00A7f hello", String.format(format, "ignored", "hello"))
        assertEquals("<%1\$s> %2\$s [world]", ChatFormat.normalChatFormat("<%username> %msg [%world]", member, hovers))
    }

    @Test
    fun activeCodes() {
        assertEquals("\u00A7b\u00A7o", ChatFormat.activeCodes("\u00A7a\u00A7lhello\u00A7r\u00A7bworld\u00A7o"))
        assertEquals("", ChatFormat.activeCodes("\u00A7aa\u00A7r"))
        val hex = "\u00A7x\u00A7f\u00A7f\u00A70\u00A70\u00A70\u00A70"
        assertEquals(hex + "\u00A7l", ChatFormat.activeCodes("${hex}\u00A7lA"))
    }

    @Test
    fun colors() {
        assertEquals("\u00A7a\u00A7x\u00A7F\u00A7F\u00A70\u00A70\u00A70\u00A70x", Colors.replace("&a#FF0000x"))
        assertEquals("\u00A7x\u00A7a\u00A7a\u00A7b\u00A7b\u00A7c\u00A7cx", Colors.replace("#abcx"))
        assertEquals("hello", Colors.strip("&ahello\u00A7b#123"))
        assertTrue(Colors.isAltColorCode("&b"))
        assertTrue(Colors.isAltColorCode("#99AABB"))
        assertFalse(Colors.isAltColorCode("b"))
        assertEquals("&c", Colors.toColorCode("red"))
        // 16 進カラーも読める
        val red = Colors.component(Colors.replace("#FF0000A"))
        fun firstColor(c: Component): TextColor? = c.color() ?: c.children().firstNotNullOfOrNull { firstColor(it) }
        assertEquals(TextColor.color(0xFF0000), firstColor(red))
    }
}
