package net.torosaba.core.module.tpslog

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.DecimalFormat
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 旧 Tps_log。tick ごとに 300 tick にかかった時間から TPS を測り、
 * WarnTPS 以下ならサーバーログと Discord の Webhook に、そのときのプレイヤー一覧と一緒に出す。
 */
class TpsLogModule : Module("tps-log", "Tps_log") {
    private lateinit var settings: Settings
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    private class PlayerData(val name: String, val world: String, val location: String, val uuid: String)

    override fun onEnable() {
        settings = Settings(config)
        val tick = settings.tick.coerceAtLeast(1L)
        runTimer(tick, tick) {
            val start = System.currentTimeMillis()
            runLater(MEASURE_TICKS) { check(System.currentTimeMillis() - start) }
        }
    }

    private fun check(elapsedMillis: Long) {
        val tps = MEASURE_TICKS * 1000.0 / elapsedMillis.coerceAtLeast(1L)
        if (tps > settings.warnTps) return

        val textTps = textTps(tps)
        // プレイヤーの情報はメインスレッドで集めておく
        val players = if (settings.playerList) Bukkit.getOnlinePlayers().map { player ->
            val loc = player.location
            PlayerData(
                name = PlainTextComponentSerializer.plainText().serialize(player.displayName()),
                world = player.world.name,
                location = "${loc.blockX} ${loc.blockY} ${loc.blockZ}",
                uuid = player.uniqueId.toString().replace("-", ""),
            )
        } else null

        if (settings.discord) {
            val json = discordJson(textTps, players)
            runAsync { post(settings.url, json) }
        }
        if (settings.serverLog) log(textTps, players)
    }

    private fun textTps(tps: Double): String {
        val s = settings
        var value = tps
        val load = when {
            tps > s.valueVeryLow -> s.textVeryLow.also { value = s.maxTps.toDouble() }
            tps > s.valueLow -> s.textLow
            tps > s.valueMedium -> s.textMedium
            tps > s.valueHigh -> s.textHigh
            else -> s.textVeryHigh
        }
        val format = DecimalFormat("##0.00")
        return "${format.format(value)} / ${format.format(s.maxTps)} ${s.loadText}$load"
    }

    private fun log(textTps: String, players: List<PlayerData>?) {
        logger.warning("----------Warn TPS----------")
        logger.warning(textTps)
        if (players != null) {
            logger.warning("${settings.playerListText} : ${players.size}")
            for (player in players) {
                val line = buildList {
                    add(player.name)
                    if (settings.world) add(player.world)
                    if (settings.location) add(player.location)
                    if (settings.uuid) add(player.uuid)
                }.joinToString("  ")
                logger.warning(line)
            }
        }
        logger.warning("----------------------------")
    }

    private fun discordJson(textTps: String, players: List<PlayerData>?): String {
        val timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(TIMESTAMP)
        val embed = StringBuilder()
        embed.append("\"title\":${json(textTps)},\"color\":${settings.embedColor},\"timestamp\":${json(timestamp)}")
        if (players != null) {
            if (players.isNotEmpty()) {
                val fields = buildList {
                    add(settings.playerNameText to players.map { it.name })
                    if (settings.world) add(settings.playerWorldText to players.map { it.world })
                    if (settings.location) add(settings.playerLocationText to players.map { it.location })
                    if (settings.uuid) add(settings.playerUUIDText to players.map { it.uuid })
                }.joinToString(",") { (name, values) ->
                    "{\"name\":${json(name)},\"value\":${json(values.joinToString("\n"))},\"inline\":true}"
                }
                embed.append(",\"fields\":[$fields]")
            }
            embed.append(",\"description\":${json("${settings.playerListText} : ${players.size}")}")
        }
        return "{\"embeds\":[{$embed}]}"
    }

    private fun post(url: String, body: String) {
        if (url.isBlank()) {
            logger.warning("WebhookURL が空なので、Discord には送りません")
            return
        }
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 ")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warning("Discord への送信に失敗しました (HTTP ${response.statusCode()}): ${response.body()}")
            }
        } catch (e: Exception) {
            logger.warning("Discord への送信に失敗しました: $e")
        }
    }

    private companion object {
        const val MEASURE_TICKS = 300L
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")

        /** JSON の文字列にする */
        fun json(text: String): String = buildString {
            append('"')
            for (c in text) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}
