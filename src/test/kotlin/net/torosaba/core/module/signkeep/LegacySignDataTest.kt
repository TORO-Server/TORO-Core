package net.torosaba.core.module.signkeep

import com.google.gson.JsonParser
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.DyeColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacySignDataTest {
    private fun legacy(snbt: String) = LegacySignData.fromData(Snbt.parse(snbt) as Map<*, *>)

    @Test
    fun snbtParsesCompoundListStringAndNumbers() {
        val m = Snbt.parse("""{a:[1,2L,3.5d],b:'x\'y',c:"q\"r",d:1b,e:{f:true},g:[I;1,2],h:[]}""") as Map<*, *>
        assertEquals(listOf(1L, 2L, 3.5), m["a"])
        assertEquals("x'y", m["b"])
        assertEquals("q\"r", m["c"])
        assertEquals(1, (m["d"] as Snbt.SnbtByte).value)
        assertEquals(1, ((m["e"] as Map<*, *>)["f"] as Snbt.SnbtByte).value)
        assertEquals(listOf(1L, 2L), m["g"])
        assertEquals(emptyList<Any>(), m["h"])
        assertFailsWith<IllegalArgumentException> { Snbt.parse("{a:") }
        assertFailsWith<IllegalArgumentException> { Snbt.parse("{a:1} x") }
    }

    @Test
    fun readsPlainTextLines() {
        val sign = assertNotNull(legacy("""{id:"minecraft:sign",front_text:{messages:["TORO","Hello","",""],color:"red",has_glowing_text:1b}}"""))
        val front = assertNotNull(sign.front)
        assertEquals(listOf("TORO", "Hello", "", ""), front.lines.map { SignTextFilter.plain(it) })
        assertEquals(DyeColor.RED, front.color)
        assertTrue(front.glowing)
        assertNull(sign.back)
        assertTrue(front.hasText())
    }

    @Test
    fun readsStyledCompoundLines() {
        val sign = legacy("""{id:"minecraft:sign",front_text:{messages:[{text:"A",color:"gold",bold:1b},{text:"B"},{text:""},{text:""}]}}""")
        val front = assertNotNull(sign?.front)
        assertEquals("A", SignTextFilter.plain(front.lines[0]))
        assertEquals(NamedTextColor.GOLD, front.lines[0].color())
        assertTrue(front.lines[0].hasDecoration(TextDecoration.BOLD))
    }

    @Test
    fun clickEventInDataIsNeverWritten() {
        val sign = legacy(
            """{id:"minecraft:sign",front_text:{messages:[{text:"go",click_event:{action:"run_command",command:"op x"}},"","",""]}}""",
        )
        val out = SignTextFilter.sanitize(assertNotNull(sign?.front).lines[0])
        assertEquals("go", SignTextFilter.plain(out))
        assertNull(out.clickEvent())
    }

    @Test
    fun notSignOrNoDataGivesNull() {
        assertNull(legacy("""{id:"minecraft:chest"}"""))
        assertNull(legacy("""{id:"minecraft:sign"}"""))
        assertNull(LegacySignData.fromItemJson(JsonParser.parseString("""{"id":"minecraft:oak_sign","components":{}}""").asJsonObject))
        assertNull(
            LegacySignData.fromItemJson(
                JsonParser.parseString("""{"id":"minecraft:oak_sign","components":{"minecraft:sign_text_front":{"messages":["a","","",""]}}}""").asJsonObject,
            ),
        )
    }

    @Test
    fun readsItemJsonLikeServerSerializeItemAsJson() {
        // 実測した Paper 26.3-245 の serializeItemAsJson の出力形式 (block_entity_data は SNBT の文字列)
        val json = """{"id":"minecraft:oak_sign","count":1,"components":{"minecraft:block_entity_data":"{front_text:{messages:[\"TORO\",\"Hello\",\"\",\"\"]},id:\"minecraft:sign\"}"},"DataVersion":5023}"""
        val sign = assertNotNull(LegacySignData.fromItemJson(JsonParser.parseString(json).asJsonObject))
        val front = assertNotNull(sign.front)
        assertEquals("TORO", SignTextFilter.plain(front.lines[0]))
        assertEquals("Hello", SignTextFilter.plain(front.lines[1]))
    }
}
