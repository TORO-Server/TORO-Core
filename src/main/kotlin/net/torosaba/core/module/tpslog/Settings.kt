package net.torosaba.core.module.tpslog

import org.bukkit.configuration.file.YamlConfiguration

/** config.yml の値 (キーは元の Tps_log と同じ) */
internal class Settings(config: YamlConfiguration) {
    val valueVeryLow = config.getInt("status.Value.VeryLow")
    val valueLow = config.getInt("status.Value.Low")
    val valueMedium = config.getInt("status.Value.Medium")
    val valueHigh = config.getInt("status.Value.High")

    val textVeryLow = config.getString("status.Text.VeryLow") ?: ""
    val textLow = config.getString("status.Text.Low") ?: ""
    val textMedium = config.getString("status.Text.Medium") ?: ""
    val textHigh = config.getString("status.Text.High") ?: ""
    val textVeryHigh = config.getString("status.Text.VeryHigh") ?: ""
    val loadText = config.getString("status.LoadText") ?: ""

    val maxTps = config.getInt("MaxTPS")
    val warnTps = config.getInt("WarnTPS")
    val url = config.getString("WebhookURL") ?: ""
    val embedColor = config.getInt("EmbedColor")
    val discord = config.getBoolean("Discord")
    val serverLog = config.getBoolean("ServerLog")

    val playerListText = config.getString("playerListText") ?: ""
    val playerNameText = config.getString("playerNameText") ?: ""
    val playerWorldText = config.getString("playerWorldText") ?: ""
    val playerLocationText = config.getString("playerLocationText") ?: ""
    val playerUUIDText = config.getString("playerUUIDText") ?: ""

    val playerList = config.getBoolean("playerList")
    val world = config.getBoolean("World")
    val location = config.getBoolean("Location")
    val uuid = config.getBoolean("UUID")

    val tick = config.getLong("tick")
}
