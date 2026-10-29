// Lift (croxis, LGPL-3.0-or-later) の BukkitConfig / Config の動作を Kotlin で書き直したもの
package net.torosaba.core.module.lift

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.util.logging.Logger

/** config.yml の値。キーは元の Lift と同じ */
internal class LiftSettings(cfg: YamlConfiguration, logger: Logger) {
    val debug = cfg.getBoolean("debug")
    val redstone = cfg.getBoolean("redstone")
    val maxLiftArea = cfg.getInt("maxLiftArea", 16)
    val maxHeight = cfg.getInt("maxHeight", 384)
    val autoPlace = cfg.getBoolean("autoPlace")
    val checkFloor = cfg.getBoolean("checkFloor")
    val liftMobs = cfg.getBoolean("liftMobs", true)
    val preventEntry = cfg.getBoolean("preventEntry")
    val preventLeave = cfg.getBoolean("preventLeave", true)
    val mouseScroll = cfg.getBoolean("mouseScroll", true)
    val secondsUntilTimeout = cfg.getInt("secondsUntilTimeout", 2)

    // 文字列は元と同じく & を § に置き換えるだけ
    val destination = text(cfg, "destination")
    val currentFloor = text(cfg, "messages.currentFloor")
    val oneFloor = text(cfg, "messages.oneFloor")
    val cantEnter = text(cfg, "messages.cantEnter")
    val cantLeave = text(cfg, "messages.cantLeave")
    val unsafe = text(cfg, "messages.unsafe")
    val scrollSelectEnabled = text(cfg, "messages.scrollSelectEnabled")
    val scrollSelectDisabled = text(cfg, "messages.scrollSelectDisabled")

    /** 土台のブロック → 速さ */
    val blockSpeeds: Map<Material, Double>
    val floorMaterials: Set<Material>
    val buttonMaterials: Set<Material>
    val signMaterials: Set<Material>

    init {
        val speeds = LinkedHashMap<Material, Double>()
        val section = cfg.getConfigurationSection("baseBlockSpeeds")
        if (section != null) {
            for (key in section.getKeys(false)) {
                val material = Material.matchMaterial(key)
                if (material == null || !material.isBlock) {
                    logger.warning("baseBlockSpeeds の $key はブロックではないので使いません")
                    continue
                }
                speeds[material] = section.getDouble(key)
            }
        }
        blockSpeeds = speeds
        val blocks = Material.entries.filter { it.isBlock }
        floorMaterials = MaterialPatterns.select(blocks, cfg.getStringList("floorBlocks"))
        buttonMaterials = MaterialPatterns.select(blocks, cfg.getStringList("buttonBlocks"))
        signMaterials = MaterialPatterns.select(blocks, cfg.getStringList("signBlocks"))
        if (debug) {
            logger.info("Floor materials added: $floorMaterials")
            logger.info("Button materials added: $buttonMaterials")
            logger.info("Sign materials added: $signMaterials")
        }
    }

    private fun text(cfg: YamlConfiguration, path: String): String = (cfg.getString(path) ?: "").replace("&", "§")
}

/** floorBlocks などの、`*` を含むブロック名の指定 */
internal object MaterialPatterns {
    /** 元と同じく、大文字にして `*` を `.*?` に置き換えた正規表現で、名前全体と照らし合わせる */
    fun regex(pattern: String): Regex = Regex(pattern.uppercase().replace("*", ".*?"))

    fun matches(name: String, patterns: List<String>): Boolean = patterns.any { regex(it).matches(name) }

    fun select(materials: Collection<Material>, patterns: List<String>): Set<Material> {
        val regexes = patterns.map(::regex)
        return materials.filterTo(LinkedHashSet()) { m -> regexes.any { it.matches(m.name) } }
    }
}
