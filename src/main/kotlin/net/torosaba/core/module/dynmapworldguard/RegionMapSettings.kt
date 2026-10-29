package net.torosaba.core.module.dynmapworldguard

import org.bukkit.configuration.ConfigurationSection

/** 領域の線と塗りの見た目。色は `#RRGGBB` */
internal data class AreaStyle(
    val strokeColor: String,
    val unownedStrokeColor: String,
    val strokeOpacity: Double,
    val strokeWeight: Int,
    val fillColor: String,
    val fillOpacity: Double,
    /** 地図に出す名前を、領域の ID の代わりにこれにする */
    val label: String? = null,
) {
    fun strokeRgb(unowned: Boolean) = parseColor(if (unowned) unownedStrokeColor else strokeColor)
    fun fillRgb() = parseColor(fillColor)

    /** [section] にあるキーだけをこの見た目から上書きしたもの */
    fun overriddenBy(section: ConfigurationSection) = AreaStyle(
        strokeColor = section.getString("strokeColor") ?: strokeColor,
        unownedStrokeColor = section.getString("unownedStrokeColor") ?: unownedStrokeColor,
        strokeOpacity = if (section.isSet("strokeOpacity")) section.getDouble("strokeOpacity") else strokeOpacity,
        strokeWeight = if (section.isSet("strokeWeight")) section.getInt("strokeWeight") else strokeWeight,
        fillColor = section.getString("fillColor") ?: fillColor,
        fillOpacity = if (section.isSet("fillOpacity")) section.getDouble("fillOpacity") else fillOpacity,
        label = section.getString("label"),
    )

    companion object {
        val DEFAULT = AreaStyle("#FF0000", "#00FF00", 0.8, 3, "#FF0000", 0.35)

        /** 読めない色は赤にする (元のプラグインと同じ) */
        fun parseColor(color: String): Int =
            color.removePrefix("#").toIntOrNull(16) ?: 0xFF0000
    }
}

/** 地図に描くか、どの見た目で描くかを決めるのに使う、領域の情報 */
internal data class RegionKey(
    val id: String,
    val world: String,
    val ownerNames: Collection<String> = emptyList(),
    val ownerIds: Collection<String> = emptyList(),
    val ownerGroups: Collection<String> = emptyList(),
)

/** 吹き出し (infowindow) の `%...%` に入れる値 */
internal data class RegionInfo(
    val label: String,
    val playerOwners: String,
    val groupOwners: String,
    val playerMembers: String,
    val groupMembers: String,
    val parent: String?,
    val priority: Int,
    val flags: List<Pair<String, String>>,
)

/** config.yml の中身。キーは元の Dynmap-WorldGuard と同じ */
internal class RegionMapSettings(config: ConfigurationSection) {
    /** 領域を読み直す間隔 (tick)。15 秒より短くはしない */
    val periodTicks = config.getInt("update.period", 300).coerceAtLeast(15) * 20L
    val layerName = config.getString("layer.name") ?: "WorldGuard"
    val hideByDefault = config.getBoolean("layer.hidebydefault", false)
    val layerPriority = config.getInt("layer.layerprio", 10)
    val minZoom = config.getInt("layer.minzoom", 0)
    val use3d = config.getBoolean("use3dregions", false)
    val infoWindow = config.getString("infowindow") ?: DEFAULT_INFOWINDOW
    val maxDepth = config.getInt("maxdepth", 16)
    val updatesPerTick = config.getInt("updates-per-tick", 20).coerceAtLeast(1)

    val defaultStyle = config.getConfigurationSection("regionstyle")
        ?.let { AreaStyle.DEFAULT.overriddenBy(it).copy(label = null) } ?: AreaStyle.DEFAULT

    /** 領域ごとの見た目。キーは `<領域 ID>` か `<ワールド>/<領域 ID>` */
    private val customStyles = HashMap<String, AreaStyle>()

    /** `前|後` の形のキー。ID が「前」で始まり「後」で終わる領域に使う */
    private val wildcardStyles = LinkedHashMap<String, AreaStyle>()

    /** 持ち主 (プレイヤー名・UUID・グループ) ごとの見た目。キーは小文字 */
    private val ownerStyles = HashMap<String, AreaStyle>()

    private val visible = config.getStringList("visibleregions").toSet()
    private val hidden = config.getStringList("hiddenregions").toSet()

    init {
        config.getConfigurationSection("custstyle")?.let { section ->
            for (key in section.getKeys(false)) {
                val style = defaultStyle.overriddenBy(section.getConfigurationSection(key) ?: continue)
                if ('|' in key) wildcardStyles[key] = style else customStyles[key] = style
            }
        }
        config.getConfigurationSection("ownerstyle")?.let { section ->
            for (key in section.getKeys(false)) {
                ownerStyles[key.lowercase()] = defaultStyle.overriddenBy(section.getConfigurationSection(key) ?: continue)
            }
        }
    }

    fun isVisible(region: RegionKey): Boolean {
        val names = listOf(region.id, "world:${region.world}", "${region.world}/${region.id}")
        if (visible.isNotEmpty() && names.none { it in visible }) return false
        return names.none { it in hidden }
    }

    fun styleFor(region: RegionKey): AreaStyle =
        customStyles["${region.world}/${region.id}"]
            ?: customStyles[region.id]
            ?: wildcardStyles.entries.firstOrNull { matchesWildcard(it.key, region.id) }?.value
            ?: region.ownerNames.firstNotNullOfOrNull { ownerStyles[it.lowercase()] }
            ?: region.ownerIds.firstNotNullOfOrNull { ownerStyles[it.lowercase()] }
            ?: region.ownerGroups.firstNotNullOfOrNull { ownerStyles[it.lowercase()] }
            ?: defaultStyle

    fun formatInfoWindow(info: RegionInfo): String =
        "<div class=\"regioninfo\">$infoWindow</div>"
            .replace("%regionname%", info.label)
            .replace("%playerowners%", info.playerOwners)
            .replace("%groupowners%", info.groupOwners)
            .replace("%playermembers%", info.playerMembers)
            .replace("%groupmembers%", info.groupMembers)
            .replace("%parent%", info.parent ?: "")
            .replace("%priority%", info.priority.toString())
            .replace("%flags%", info.flags.joinToString("") { (name, value) -> "$name: $value<br/>" })

    companion object {
        const val DEFAULT_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> " +
            "Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br />" +
            "<span style=\"font-weight:bold;\">%flags%</span></div>"

        private fun matchesWildcard(pattern: String, id: String): Boolean {
            val (prefix, suffix) = pattern.split('|', limit = 2)
            return id.startsWith(prefix) && id.endsWith(suffix)
        }

        /** 地図に出す名前。ID の先頭を大文字にする */
        fun labelOf(id: String): String = id.replaceFirstChar { it.uppercaseChar() }
    }
}
