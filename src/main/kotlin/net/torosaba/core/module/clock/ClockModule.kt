package net.torosaba.core.module.clock

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import java.time.LocalDateTime
import java.util.IllegalFormatException

/** 旧 ClockPlugin。毎時 Minute 分になったら時報を流す */
class ClockModule : Module("clock", "ClockPlugin") {
    override fun onEnable() {
        val format = config.getString("Message") ?: "時報 %d/%d/%d/%d:%s になりました"
        val minute = config.getInt("Minute", 0)

        // 同じ分のあいだに 2 回流さないためのフラグ
        var ready = true

        // 30 秒ごとに時刻を見る
        runTimer(0L, 600L) {
            val now = LocalDateTime.now()
            if (now.minute != minute) {
                ready = true
                return@runTimer
            }
            if (!ready) return@runTimer
            ready = false

            val text = try {
                format.format(now.year, now.monthValue, now.dayOfMonth, now.hour, "%02d".format(now.minute))
            } catch (e: IllegalFormatException) {
                logger.warning("Message の書式が正しくありません: ${e.message}")
                return@runTimer
            }
            Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§e$text"))
        }
    }
}
