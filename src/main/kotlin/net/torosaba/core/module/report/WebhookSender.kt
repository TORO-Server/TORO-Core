package net.torosaba.core.module.report

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.torosaba.core.Discord
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

/** Discord の Webhook に通報を送る。通信を待つので、メインスレッドでは呼ばない */
internal class WebhookSender(private val webhookUrl: String, private val logger: Logger) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun sendReport(reporter: String, target: String?, reason: String?, detail: String?, coords: String) {
        if (webhookUrl.isEmpty()) {
            logger.warning("Webhook URLが設定されていません。送信スキップします。")
            return
        }
        try {
            val request = HttpRequest.newBuilder(URI(webhookUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(reporter, target, reason, detail, coords).toString(), Charsets.UTF_8))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val code = response.statusCode()
            if (code != 204 && code != 200) {
                logger.warning("Webhook送信失敗: HTTP $code")
                if (response.body().isNotEmpty()) logger.warning("Discord Error Response: ${response.body()}")
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Webhook送信中に例外が発生しました", e)
        }
    }

    companion object {
        /** 通報の JSON。プレイヤーが決める文字列は Markdown を無害化し、Discord の上限に収める */
        fun payload(reporter: String, target: String?, reason: String?, detail: String?, coords: String): JsonObject {
            val fields = JsonArray().apply {
                add(field("通報者", Discord.escape(reporter), true))
                add(field("対象", target?.let(Discord::escape), true))
                add(field("座標", coords, true))
                add(field("理由", reason?.let(Discord::escape), false))
                if (!detail.isNullOrEmpty()) add(field("詳細", Discord.escape(detail), false))
            }
            val embed = JsonObject().apply {
                addProperty("title", "新規通報")
                addProperty("color", 16711680) // 赤
                add("fields", fields)
            }
            return JsonObject().apply {
                addProperty("username", "通報")
                add("embeds", JsonArray().apply { add(embed) })
                add("allowed_mentions", Discord.noMentions())
            }
        }

        // 値が空のフィールドは Discord に拒否されるので、何か入れる
        private fun field(name: String, value: String?, inline: Boolean) = JsonObject().apply {
            addProperty("name", Discord.truncate(name, Discord.MAX_FIELD_NAME))
            addProperty("value", Discord.truncate(value?.takeIf { it.isNotBlank() } ?: "-", Discord.MAX_FIELD_VALUE))
            if (inline) addProperty("inline", true)
        }
    }
}
