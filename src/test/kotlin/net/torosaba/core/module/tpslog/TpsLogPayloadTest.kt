package net.torosaba.core.module.tpslog

import com.google.gson.JsonParser
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TpsLogPayloadTest {
    private fun settings(yaml: String) = Settings(YamlConfiguration().apply { loadFromString(yaml) })

    private fun payload(yaml: String) =
        JsonParser.parseString(TpsLogModule.payload(settings(yaml), "20.00 / 20.00 Load : VeryLow", null, "2026-01-01T00:00Z")).asJsonObject

    @Test
    fun `MentionUserId を読める`() {
        assertEquals("12345", settings("MentionUserId: \"12345\"").mentionUserId)
        assertEquals("", settings("").mentionUserId)
    }

    @Test
    fun `MentionUserId があれば content にメンションを付ける`() {
        val payload = payload("MentionUserId: \"12345\"")
        assertEquals("<@12345>", payload.get("content").asString)
        val parse = payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").map { it.asString }
        assertEquals(listOf("users"), parse)
        // 埋め込みは従来どおり
        val embed = payload.getAsJsonArray("embeds")[0].asJsonObject
        assertEquals("20.00 / 20.00 Load : VeryLow", embed.get("title").asString)
        assertEquals("2026-01-01T00:00Z", embed.get("timestamp").asString)
    }

    @Test
    fun `MentionUserId が無ければメンションを付けない`() {
        val payload = payload("")
        assertFalse(payload.has("content"))
        assertFalse(payload.has("allowed_mentions"))
        assertTrue(payload.has("embeds"))
    }

    @Test
    fun `MentionUserId が空白ならメンションを付けない`() {
        val payload = payload("MentionUserId: \"  \"")
        assertFalse(payload.has("content"))
        assertFalse(payload.has("allowed_mentions"))
        assertTrue(payload.has("embeds"))
    }
}
