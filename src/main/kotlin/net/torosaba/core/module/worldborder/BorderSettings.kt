// WorldBorder (Brettflan, BSD-2-Clause License) の Config の読み書きを移植したもの
package net.torosaba.core.module.worldborder

import net.torosaba.core.colorCodes
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.logging.Logger

/**
 * config.yml の値。キーと書式は元の WorldBorder と同じ。
 * fill/trim 関係のキー (fill-autosave-frequency など) は使わないが、読み書きしても消さない。
 */
internal class BorderSettings {
    var message = DEFAULT_MESSAGE
    val messageFormatted: String get() = message.colorCodes()
    var shapeRound = true
    var debug = false
    var whooshEffect = true
    var portalRedirection = true
    var knockBack = 3.0
    var timerTicks = 5
    var remountTicks = 0
    var dynmapEnabled = true
    var dynmapLayerLabel = "WorldBorder"
    var dynmapMessage = "The border of the world."
    var dynmapHideByDefault = false
    var dynmapPriority = 0
    var killPlayer = false
    var denyEnderpearl = true
    var preventBlockPlace = false
    var preventMobSpawn = false

    /** ワールド名 → 境界 (書いた順) */
    val borders = LinkedHashMap<String, BorderData>()
    val bypass = LinkedHashSet<UUID>()

    fun load(cfg: YamlConfiguration, logger: Logger) {
        val cfgVersion = cfg.getInt("cfg-version", CURRENT_CFG_VERSION)

        shapeRound = cfg.getBoolean("round-border", true)
        debug = cfg.getBoolean("debug-mode", false)
        whooshEffect = cfg.getBoolean("whoosh-effect", true)
        portalRedirection = cfg.getBoolean("portal-redirection", true)
        knockBack = cfg.getDouble("knock-back-dist", 3.0)
        timerTicks = cfg.getInt("timer-delay-ticks", 5)
        remountTicks = cfg.getInt("remount-delay-ticks", 0)
        dynmapEnabled = cfg.getBoolean("dynmap-border-enabled", true)
        dynmapLayerLabel = cfg.getString("dynmap-border-layer-label") ?: "WorldBorder"
        dynmapMessage = cfg.getString("dynmap-border-message") ?: "The border of the world."
        dynmapHideByDefault = cfg.getBoolean("dynmap-border-hideByDefault", false)
        dynmapPriority = cfg.getInt("dynmap-border-priority", 0)
        killPlayer = cfg.getBoolean("player-killed-bad-spawn", false)
        denyEnderpearl = cfg.getBoolean("deny-enderpearl", true)
        preventBlockPlace = cfg.getBoolean("prevent-block-place", false)
        preventMobSpawn = cfg.getBoolean("prevent-mob-spawn", false)

        bypass.clear()
        for (s in cfg.getStringList("bypass-list-uuids")) {
            try {
                bypass += UUID.fromString(s)
            } catch (e: IllegalArgumentException) {
                logger.warning("bypass-list-uuids の UUID を読めませんでした: $s")
            }
        }

        val msg = cfg.getString("message")
        message = when {
            msg.isNullOrEmpty() -> DEFAULT_MESSAGE
            // 色コードに対応する前の設定は、先頭に赤を付ける
            cfgVersion < 8 && !msg.startsWith("&") -> "&c$msg"
            else -> msg
        }
        // 昔は既定で false だったが、今はほぼ全員が欲しい動作なので true にする (元と同じ)
        if (cfgVersion < 10) denyEnderpearl = true
        // 名前で持っていた古いバイパスの一覧は捨てる
        if (cfgVersion < 11) cfg.set("bypass-list", null)

        borders.clear()
        val worlds = cfg.getConfigurationSection("worlds")
        if (worlds != null) {
            for (key in worlds.getKeys(false)) {
                val section = worlds.getConfigurationSection(key) ?: continue
                // "." は設定のパスの区切りなので、ワールド名の "." は "<" にして保存してある
                val worldName = if (cfgVersion > 3) key.replace("<", ".") else key
                // 楕円に対応する前の設定 (radius だけ) も読む
                val legacyRadius = if (section.isSet("radius") && !section.isSet("radiusX")) section.getInt("radius") else null
                val border = BorderData(
                    x = section.getDouble("x", 0.0),
                    z = section.getDouble("z", 0.0),
                    radiusX = legacyRadius ?: section.getInt("radiusX", 0),
                    radiusZ = legacyRadius ?: section.getInt("radiusZ", 0),
                    shapeRound = section.get("shape-round") as? Boolean,
                    wrapping = section.getBoolean("wrapping", false),
                )
                borders[worldName] = border
                logger.info("[CONFIG] World \"$worldName\" has border ${border.describe()}")
            }
        }
        logger.info("[CONFIG] Using ${BorderData.shapeName(shapeRound)} border, knockback of $knockBack blocks, and timer delay of $timerTicks.")
    }

    /** 今の値を cfg に書く (ほかのキーは残す) */
    fun writeTo(cfg: YamlConfiguration) {
        if (cfg.getInt("cfg-version", 0) < CURRENT_CFG_VERSION) cfg.set("cfg-version", CURRENT_CFG_VERSION)
        cfg.set("message", message)
        cfg.set("round-border", shapeRound)
        cfg.set("debug-mode", debug)
        cfg.set("whoosh-effect", whooshEffect)
        cfg.set("portal-redirection", portalRedirection)
        cfg.set("knock-back-dist", knockBack)
        cfg.set("timer-delay-ticks", timerTicks)
        cfg.set("remount-delay-ticks", remountTicks)
        cfg.set("dynmap-border-enabled", dynmapEnabled)
        cfg.set("dynmap-border-layer-label", dynmapLayerLabel)
        cfg.set("dynmap-border-message", dynmapMessage)
        cfg.set("dynmap-border-hideByDefault", dynmapHideByDefault)
        cfg.set("dynmap-border-priority", dynmapPriority)
        cfg.set("player-killed-bad-spawn", killPlayer)
        cfg.set("deny-enderpearl", denyEnderpearl)
        cfg.set("bypass-list-uuids", bypass.map { it.toString() })
        cfg.set("prevent-block-place", preventBlockPlace)
        cfg.set("prevent-mob-spawn", preventMobSpawn)

        cfg.set("worlds", null)
        for ((world, border) in borders) {
            val path = "worlds." + world.replace(".", "<")
            cfg.set("$path.x", border.x)
            cfg.set("$path.z", border.z)
            cfg.set("$path.radiusX", border.radiusX)
            cfg.set("$path.radiusZ", border.radiusZ)
            cfg.set("$path.wrapping", border.wrapping)
            if (border.shapeRound != null) cfg.set("$path.shape-round", border.shapeRound)
        }
    }

    fun describe(world: String): String {
        val border = borders[world] ?: return "No border was found for the world \"$world\"."
        return "World \"$world\" has border ${border.describe()}"
    }

    companion object {
        const val DEFAULT_MESSAGE = "&cYou have reached the edge of this world."

        /** 元の WorldBorder (1.9.10 の派生版) が書く版数 */
        const val CURRENT_CFG_VERSION = 12
    }
}
