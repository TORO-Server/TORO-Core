// WorldBorder (Brettflan, BSD-2-Clause License) の DynMapFeatures を移植したもの
package net.torosaba.core.module.worldborder

import org.bukkit.Bukkit
import org.dynmap.DynmapAPI
import org.dynmap.markers.AreaMarker
import org.dynmap.markers.CircleMarker
import org.dynmap.markers.MarkerAPI
import org.dynmap.markers.MarkerSet

/** 境界を地図に描く先。dynmap のクラスはこのインターフェイスの実装 ([DynmapBorders]) の中だけで触る */
internal interface BorderMarkers {
    fun showAll()
    fun show(world: String, border: BorderData)
    fun remove(world: String)
    fun removeAll()
}

/** dynmap に境界の線を描く。dynmap が無いときはこのクラスを読み込まない */
internal class DynmapBorders private constructor(
    private val module: WorldBorderModule,
    private val markerApi: MarkerAPI,
) : BorderMarkers {
    private val settings get() = module.settings
    private var markerSet: MarkerSet? = null
    private val roundBorders = HashMap<String, CircleMarker>()
    private val squareBorders = HashMap<String, AreaMarker>()

    override fun showAll() {
        removeAll()
        if (!settings.dynmapEnabled) {
            // 無効のときはレイヤーごと消す
            markerSet?.deleteMarkerSet()
            markerSet = null
            return
        }
        val set = markerApi.getMarkerSet(MARKER_SET)?.also { it.markerSetLabel = settings.dynmapLayerLabel }
            ?: markerApi.createMarkerSet(MARKER_SET, settings.dynmapLayerLabel, null, false)
            ?: return
        set.setLayerPriority(settings.dynmapPriority)
        set.setHideByDefault(settings.dynmapHideByDefault)
        markerSet = set
        for ((world, border) in settings.borders) show(world, border)
    }

    override fun show(world: String, border: BorderData) {
        if (!settings.dynmapEnabled) return
        val set = markerSet ?: return
        if (border.isRound(settings.shapeRound)) showRound(set, world, border) else showSquare(set, world, border)
    }

    private fun showRound(set: MarkerSet, world: String, border: BorderData) {
        if (world in squareBorders) remove(world)
        val y = Bukkit.getWorld(world)?.maxHeight?.toDouble() ?: 255.0
        val marker = roundBorders[world]
        if (marker != null) {
            marker.setCenter(world, border.x, y, border.z)
            marker.setRadius(border.radiusX.toDouble(), border.radiusZ.toDouble())
            return
        }
        // 前回消し損ねた同じ ID の印があると作れないので、先に消す
        set.findCircleMarker(markerId(world))?.deleteMarker()
        set.findAreaMarker(markerId(world))?.deleteMarker()
        val created = set.createCircleMarker(
            markerId(world), settings.dynmapMessage, false, world,
            border.x, y, border.z, border.radiusX.toDouble(), border.radiusZ.toDouble(), true,
        ) ?: return
        created.setLineStyle(LINE_WEIGHT, LINE_OPACITY, LINE_COLOR)
        created.setFillStyle(0.0, 0x000000)
        roundBorders[world] = created
    }

    private fun showSquare(set: MarkerSet, world: String, border: BorderData) {
        if (world in roundBorders) remove(world)
        val xs = doubleArrayOf(border.x - border.radiusX, border.x + border.radiusX)
        val zs = doubleArrayOf(border.z - border.radiusZ, border.z + border.radiusZ)
        val marker = squareBorders[world]
        if (marker != null) {
            marker.setCornerLocations(xs, zs)
            return
        }
        set.findCircleMarker(markerId(world))?.deleteMarker()
        set.findAreaMarker(markerId(world))?.deleteMarker()
        val created = set.createAreaMarker(markerId(world), settings.dynmapMessage, false, world, xs, zs, true) ?: return
        created.setLineStyle(LINE_WEIGHT, LINE_OPACITY, LINE_COLOR)
        created.setFillStyle(0.0, 0x000000)
        squareBorders[world] = created
    }

    override fun removeAll() {
        roundBorders.values.forEach { it.deleteMarker() }
        roundBorders.clear()
        squareBorders.values.forEach { it.deleteMarker() }
        squareBorders.clear()
    }

    override fun remove(world: String) {
        roundBorders.remove(world)?.deleteMarker()
        squareBorders.remove(world)?.deleteMarker()
    }

    companion object {
        private const val MARKER_SET = "worldborder.markerset"
        private const val LINE_WEIGHT = 3
        private const val LINE_OPACITY = 1.0
        private const val LINE_COLOR = 0xFF0000

        private fun markerId(world: String) = "worldborder_$world"

        /** dynmap が有効なら作る。印を描けないときは null */
        fun create(module: WorldBorderModule): DynmapBorders? {
            val dynmap = Bukkit.getPluginManager().getPlugin("dynmap") ?: return null
            if (!dynmap.isEnabled) return null
            val api = dynmap as? DynmapAPI ?: return null
            val markerApi = try {
                api.markerAPI
            } catch (e: NullPointerException) {
                null
            } ?: return null
            return DynmapBorders(module, markerApi)
        }
    }
}
