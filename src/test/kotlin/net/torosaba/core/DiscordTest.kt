package net.torosaba.core

import com.google.gson.JsonObject
import net.torosaba.core.module.addperm.AddPermModule
import net.torosaba.core.module.report.WebhookSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordTest {
    private fun fields(payload: JsonObject) =
        payload.getAsJsonArray("embeds")[0].asJsonObject.getAsJsonArray("fields").map { it.asJsonObject }

    private fun fieldValue(payload: JsonObject, name: String) =
        fields(payload).first { it.get("name").asString == name }.get("value").asString

    @Test
    fun escapesMarkdownAndMentions() {
        assertEquals("a\\_b\\_c", Discord.escape("a_b_c"))
        assertEquals("\\*\\*bold\\*\\*", Discord.escape("**bold**"))
        assertEquals("\\[x\\]\\(https\\://example\\.com\\)", Discord.escape("[x](https://example.com)"))
        assertEquals("\\@everyone \\<\\@1\\>", Discord.escape("@everyone <@1>"))
        assertEquals("\\\\", Discord.escape("\\"))
        assertEquals("日本語のまま", Discord.escape("日本語のまま"))
    }

    @Test
    fun lineBreaksCannotStartNewMarkdownLines() {
        assertEquals("a \\# b", Discord.escape("a\n# b"))
        assertFalse(Discord.escape("a\r\nb\u0000c").any { it.isISOControl() })
    }

    @Test
    fun truncatesToTheLimit() {
        assertEquals("abc", Discord.truncate("abc", 3))
        assertEquals("ab…", Discord.truncate("abcd", 3))
        assertEquals(1024, Discord.truncate("x".repeat(5000), 1024).length)
    }

    @Test
    fun truncateDoesNotSplitPairsOrEscapes() {
        // サロゲートペアの途中で切らない
        val emoji = "😀"
        assertEquals("a…", Discord.truncate("a$emoji$emoji", 3))
        // エスケープの途中で切らない
        assertEquals("a…", Discord.truncate(Discord.escape("a___"), 3))
        assertEquals("\\_…", Discord.truncate(Discord.escape("___"), 4))
    }

    @Test
    fun reportPayloadIsSafeForDiscord() {
        val payload = WebhookSender.payload("some_player", "@everyone", "その他", "x_".repeat(2000), "(X: 1.0 Y: 2.0 Z: 3.0)")

        val mentions = payload.getAsJsonObject("allowed_mentions")
        assertEquals(0, mentions.getAsJsonArray("parse").size())

        assertEquals("some\\_player", fieldValue(payload, "通報者"))
        assertEquals("\\@everyone", fieldValue(payload, "対象"))
        // システムが作る座標はそのまま
        assertEquals("(X: 1.0 Y: 2.0 Z: 3.0)", fieldValue(payload, "座標"))
        assertTrue(fieldValue(payload, "詳細").length <= Discord.MAX_FIELD_VALUE)
    }

    @Test
    fun emptyFieldsAreFilled() {
        val payload = WebhookSender.payload("Steve", null, " ", null, "(X: 1.0 Y: 2.0 Z: 3.0)")
        assertEquals("-", fieldValue(payload, "対象"))
        assertEquals("-", fieldValue(payload, "理由"))
        assertFalse(fields(payload).any { it.get("name").asString == "詳細" })
    }

    @Test
    fun jsonIsBuiltByTheLibrary() {
        val text = WebhookSender.payload("Steve", "a\"b\\c", "r", null, "c").toString()
        assertTrue(com.google.gson.JsonParser.parseString(text).isJsonObject)
    }

    @Test
    fun addPermPayloadHasNoMentionsAndFitsTheLimit() {
        val log = AddPermModule.changeLog("some_admin", "<@1>", "WE", "付与")
        assertEquals("some\\_admin が \\<\\@1\\> に WEを付与しました", log)

        val payload = AddPermModule.webhookPayload("x".repeat(5000))
        assertEquals(Discord.MAX_CONTENT, payload.get("content").asString.length)
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size())
        assertEquals("Addbuilder", payload.get("username").asString)
    }
}
