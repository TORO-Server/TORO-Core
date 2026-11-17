package net.torosaba.core.module.signkeep

import io.papermc.paper.event.player.PlayerOpenSignEvent.Cause
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignKeepTest {
    @Test
    fun presetSignPlacedIsKept() {
        assertTrue(SignKeepModule.shouldKeep(Cause.PLACE, hasText = true))
    }

    @Test
    fun emptySignStillOpensEditor() {
        // MarumaSign などが編集画面へ文字を入れる経路を妨げない
        assertFalse(SignKeepModule.shouldKeep(Cause.PLACE, hasText = false))
    }

    @Test
    fun otherCausesAreNotTouched() {
        for (cause in listOf(Cause.INTERACT, Cause.PLUGIN, Cause.UNKNOWN)) {
            assertFalse(SignKeepModule.shouldKeep(cause, hasText = true), cause.name)
            assertFalse(SignKeepModule.shouldKeep(cause, hasText = false), cause.name)
        }
    }

    @Test
    fun hasTextLooksAtPlainText() {
        val empty = List(4) { Component.empty() }
        assertFalse(SignKeepModule.hasText(empty))
        assertTrue(SignKeepModule.hasText(listOf(Component.empty(), Component.text("TORO"), Component.empty(), Component.empty())))
        assertFalse(SignKeepModule.hasText(emptyList()))
    }

    @Test
    fun displayElementsAreKept() {
        val line = Component.text("Hello", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text(" world", NamedTextColor.BLUE))
        val out = SignTextFilter.sanitize(line)
        assertEquals("Hello world", SignTextFilter.plain(out))
        assertEquals(NamedTextColor.RED, out.color())
        assertTrue(out.hasDecoration(TextDecoration.BOLD))
        assertEquals(NamedTextColor.BLUE, out.children().single().color())
    }

    @Test
    fun executableElementsAreRemoved() {
        val line = Component.text("click me")
            .clickEvent(ClickEvent.runCommand("/op someone"))
            .hoverEvent(HoverEvent.showText(Component.text("tip")))
            .insertion("/say hi")
            .append(Component.text("child").clickEvent(ClickEvent.suggestCommand("/stop")))
        val removed = linkedSetOf<String>()
        val out = SignTextFilter.sanitize(line, removed)
        assertNull(out.clickEvent())
        assertNull(out.hoverEvent())
        assertNull(out.insertion())
        assertNull(out.children().single().clickEvent())
        assertEquals("click mechild", SignTextFilter.plain(out))
        assertEquals(setOf("clickEvent", "hoverEvent", "insertion"), removed)
    }

    @Test
    fun serverResolvedComponentsAreDropped() {
        for (c in listOf(
            Component.translatable("chat.type.text"),
            Component.selector("@a"),
            Component.score("Steve", "obj"),
            Component.keybind("key.jump"),
            Component.storageNBT().nbtPath("x").storage(net.kyori.adventure.key.Key.key("a:b")).build(),
        )) {
            val removed = linkedSetOf<String>()
            val out = SignTextFilter.sanitize(Component.text("a").append(c), removed)
            assertEquals("a", SignTextFilter.plain(out), c.javaClass.simpleName)
            assertTrue(removed.isNotEmpty(), c.javaClass.simpleName)
        }
    }
}

