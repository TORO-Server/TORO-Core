package net.torosaba.core.module.banplayersheet

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

/**
 * BAN リストのスプレッドシート (Google Apps Script) と Mojang API への通信。
 * どれも通信を待つので、メインスレッドでは呼ばない。
 */
internal class SheetApi(private val logger: Logger) {
    // GAS は応答をリダイレクトで返すので、リダイレクトをたどる
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** JSON を POST して、応答を JSON として返す。通信できないか JSON でなければ null */
    fun post(url: String, body: JsonObject): JsonObject? = try {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json; utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), Charsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        JsonParser.parseString(response.body()).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        logger.log(Level.WARNING, "BAN リストにアクセスできませんでした", e)
        null
    }

    /** 応答が成功 ("type" が "Error" でない) なら result を読む。読めなければ null */
    private inline fun <T> result(response: JsonObject?, read: (JsonElement) -> T): T? = runCatching {
        response?.takeIf { it.get("type")?.asString != "Error" }?.get("result")?.let(read)
    }.getOrNull()

    fun isBanned(url: String, uuid: String): Boolean? =
        result(post(url, JsonObject().apply {
            addProperty("type", "isBAN")
            addProperty("uuid", uuid)
        })) { it.asBoolean }

    /** 0: 追加した 1: すでに BAN されている null: 失敗 */
    fun addBan(url: String, name: String, uuid: String, reason: String): Int? =
        result(post(url, JsonObject().apply {
            addProperty("name", name)
            addProperty("uuid", uuid)
            addProperty("ban_reason", reason)
            addProperty("type", "addBAN")
        })) { it.asInt }

    /** 0: 削除した 1: BAN されていない null: 失敗 */
    fun removeBan(url: String, uuid: String): Int? =
        result(post(url, JsonObject().apply {
            addProperty("type", "rmvBAN")
            addProperty("uuid", uuid)
        })) { it.asInt }

    /** Mojang API でプレイヤー名から UUID (ハイフン付き) を調べる。見つからなければ null */
    fun playerUuid(name: String): String? = try {
        val encoded = URLEncoder.encode(name, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("https://api.mojang.com/users/profiles/minecraft/$encoded"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (response.statusCode() != 200) null
        else JsonParser.parseString(response.body()).asJsonObject.get("id")?.asString?.replaceFirst(
            Regex("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)"),
            "$1-$2-$3-$4-$5",
        )
    } catch (e: Exception) {
        logger.log(Level.WARNING, "$name の UUID を調べられませんでした", e)
        null
    }
}
