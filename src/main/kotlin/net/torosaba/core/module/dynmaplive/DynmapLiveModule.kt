package net.torosaba.core.module.dynmaplive

import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import java.net.URI

/**
 * 地図 (map.torosaba.net) にプレイヤーの動きをすぐ出すため、全員の位置と体力を toro-webmap に送り続ける。
 *
 * 地図に誰を出すか (隠れている人、透明化しているときなど) は、これまでどおり dynmap が決める。
 * toro-webmap は dynmap が地図に出しているプレイヤーの位置だけをブラウザに流すので、ここでは全員分を送る。
 * 送り先は Docker の中のネットワークだけで、token で確かめる。
 */
class DynmapLiveModule : Module("dynmap-live") {
    private var client: LiveClient? = null
    private var last = ""
    private var lastSentAt = 0L

    override fun onEnable() {
        val endpoint = config.getString("endpoint").orEmpty()
        val token = config.getString("token").orEmpty()
        if (endpoint.isBlank() || token.isBlank()) {
            logger.warning("config.yml の endpoint か token が空なので、地図には送りません")
            return
        }
        val c = LiveClient(URI(endpoint), token, logger)
        client = c
        c.start()
        val interval = config.getLong("interval-ticks", 5).coerceAtLeast(1)
        val heartbeat = config.getLong("heartbeat-seconds", 5) * 1000
        runTimer(interval, interval) {
            val now = System.currentTimeMillis()
            val json = snapshot(now)
            // 変わっていないときは送らない (数秒に 1 度は、つながっているか確かめるために送る)
            val body = json.substringAfter(",\"players\":")
            if (body == last && now - lastSentAt < heartbeat) return@runTimer
            if (c.send(json)) {
                last = body
                lastSentAt = now
            }
        }
    }

    override fun onDisable() {
        client?.close()
        client = null
        last = ""
    }

    /** オンラインの全員の位置と体力。座標は dynmap と同じくブロックの座標 (小数) */
    private fun snapshot(now: Long): String = buildString {
        append("{\"t\":\"players\",\"ts\":").append(now).append(",\"players\":[")
        var first = true
        for (p in Bukkit.getOnlinePlayers()) {
            val loc = p.location
            if (!first) append(',')
            first = false
            append("{\"account\":").append(json(p.name))
            append(",\"world\":").append(json(loc.world?.name ?: ""))
            append(",\"x\":").append(round(loc.x))
            append(",\"y\":").append(round(loc.y))
            append(",\"z\":").append(round(loc.z))
            append(",\"health\":").append(round(p.health))
            append(",\"armor\":").append(round(p.getAttribute(Attribute.ARMOR)?.value ?: 0.0))
            append('}')
        }
        append("]}")
    }

    private fun round(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
