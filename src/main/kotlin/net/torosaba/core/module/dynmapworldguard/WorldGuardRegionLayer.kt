package net.torosaba.core.module.dynmapworldguard

import com.sk89q.worldedit.world.World
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.BooleanFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import com.sk89q.worldguard.protection.regions.RegionType
import org.bukkit.Bukkit
import org.dynmap.DynmapAPI
import org.dynmap.markers.AreaMarker
import org.dynmap.markers.MarkerSet
import java.util.logging.Logger

/** 地図のレイヤー。dynmap と WorldGuard のクラスはこのインターフェイスの実装の中だけで触る */
internal interface RegionLayer {
    fun stop()
}

/** WorldGuard のフラグ `dynmap-boost`。true の領域は dynmap が優先して描き直す */
internal object BoostFlag {
    const val NAME = "dynmap-boost"

    var flag: BooleanFlag? = null
        private set

    /** WorldGuard が有効になるとフラグを足せなくなるので、サーバーの起動時に呼ぶ */
    fun register(logger: Logger) {
        val registry = WorldGuard.getInstance().flagRegistry
        flag = try {
            BooleanFlag(NAME).also { registry.register(it) }
        } catch (e: FlagConflictException) {
            // 元の Dynmap-WorldGuard が残っていて先に登録したときは、それを使う
            registry.get(NAME) as? BooleanFlag
        } catch (e: IllegalStateException) {
            logger.warning("WorldGuard のフラグ $NAME を登録できませんでした: ${e.message}")
            null
        }
    }
}

/**
 * WorldGuard の領域を dynmap の地図に描く。
 * サーバーが重くならないよう、1 tick に [RegionMapSettings.updatesPerTick] 個ずつ描き、
 * 全部描き終えたら [RegionMapSettings.periodTicks] 後にまた初めから描き直す。
 */
internal class WorldGuardRegionLayer private constructor(
    private val module: DynmapWorldGuardModule,
    private val settings: RegionMapSettings,
    private val set: MarkerSet,
) : RegionLayer {
    /** 前回描いた印。マーカー ID (`<ワールド>_<領域 ID>`) → 印 */
    private var markers = HashMap<String, AreaMarker>()
    private var stopped = false

    private fun start() {
        // 初回はワールドの読み込みを待って 2 秒後
        module.runLater(40) { Pass().step() }
    }

    override fun stop() {
        stopped = true
        set.deleteMarkerSet()
        markers.clear()
    }

    /** 全ワールドの領域を 1 回描き直す */
    private inner class Pass {
        private val drawn = HashMap<String, AreaMarker>()
        private val worlds = ArrayDeque(Bukkit.getWorlds().mapNotNull { world ->
            WorldGuard.getInstance().platform.matcher.getWorldByName(world.name)
        })
        private var world: World? = null
        private val regions = ArrayDeque<ProtectedRegion>()

        fun step() {
            if (stopped) return
            var budget = settings.updatesPerTick
            while (budget > 0) {
                if (regions.isEmpty() && !nextWorld()) {
                    finish()
                    return
                }
                val region = regions.removeLast()
                if (depthOf(region) > settings.maxDepth) continue
                draw(world!!, region)
                budget--
            }
            module.runLater(1) { step() }
        }

        /** 領域のある次のワールドに進む。もう無ければ false */
        private fun nextWorld(): Boolean {
            val container = WorldGuard.getInstance().platform.regionContainer
            while (worlds.isNotEmpty()) {
                val next = worlds.removeFirst()
                val all = container.get(next)?.regions ?: continue
                if (all.isEmpty()) continue
                world = next
                regions.addAll(all.values)
                return true
            }
            return false
        }

        private fun finish() {
            // 今回描かなかった印は、消えた領域のもの
            markers.values.forEach { it.deleteMarker() }
            markers = drawn
            module.runLater(settings.periodTicks) { Pass().step() }
        }

        private fun draw(world: World, region: ProtectedRegion) {
            val owners = region.owners
            val key = RegionKey(
                id = region.id,
                world = world.name,
                ownerNames = owners.players,
                ownerIds = owners.uniqueIds.map { it.toString() },
                ownerGroups = owners.groups,
            )
            if (!settings.isVisible(key)) return
            val (xs, zs) = outline(region) ?: return

            val markerId = "${world.name}_${region.id}"
            val style = settings.styleFor(key)
            val label = style.label ?: RegionMapSettings.labelOf(region.id)
            val marker = markers.remove(markerId)?.also {
                it.setCornerLocations(xs, zs)
                it.label = label
            } ?: set.findAreaMarker(markerId)?.also {
                it.setCornerLocations(xs, zs)
                it.label = label
            } ?: set.createAreaMarker(markerId, label, false, world.name, xs, zs, false) ?: return

            if (settings.use3d) {
                marker.setRangeY(region.maximumPoint.y() + 1.0, region.minimumPoint.y().toDouble())
            }
            val unowned = owners.players.isEmpty() && owners.uniqueIds.isEmpty() && owners.groups.isEmpty()
            marker.setLineStyle(style.strokeWeight, style.strokeOpacity, style.strokeRgb(unowned))
            marker.setFillStyle(style.fillOpacity, style.fillRgb())
            BoostFlag.flag?.let { marker.boostFlag = region.getFlag(it) == true }
            marker.description = settings.formatInfoWindow(infoOf(region, label))
            drawn[markerId] = marker
        }
    }

    companion object {
        private const val MARKER_SET = "worldguard.markerset"

        /** dynmap の印を描く API が使えれば、描き始める。使えなければ null */
        fun start(module: DynmapWorldGuardModule, settings: RegionMapSettings): RegionLayer? {
            val api = Bukkit.getPluginManager().getPlugin("dynmap") as? DynmapAPI ?: return null
            val markerApi = try {
                api.markerAPI
            } catch (e: NullPointerException) {
                null
            } ?: return null
            val set = markerApi.getMarkerSet(MARKER_SET)?.also { it.markerSetLabel = settings.layerName }
                ?: markerApi.createMarkerSet(MARKER_SET, settings.layerName, null, false)
                ?: return null
            if (settings.minZoom > 0) set.minZoom = settings.minZoom
            set.layerPriority = settings.layerPriority
            set.hideByDefault = settings.hideByDefault
            return WorldGuardRegionLayer(module, settings, set).also { it.start() }
        }

        private fun depthOf(region: ProtectedRegion): Int =
            generateSequence(region) { it.parent }.count()

        /** 地図に描く輪郭 (x の並びと z の並び)。描けない種類 (__global__ など) は null */
        private fun outline(region: ProtectedRegion): Pair<DoubleArray, DoubleArray>? = when (region.type) {
            RegionType.CUBOID -> {
                val min = region.minimumPoint
                val max = region.maximumPoint
                // ブロックの端まで囲むので、大きい側は +1 する
                doubleArrayOf(min.x().toDouble(), min.x().toDouble(), max.x() + 1.0, max.x() + 1.0) to
                    doubleArrayOf(min.z().toDouble(), max.z() + 1.0, max.z() + 1.0, min.z().toDouble())
            }
            RegionType.POLYGON -> {
                val points = (region as ProtectedPolygonalRegion).points
                DoubleArray(points.size) { points[it].x().toDouble() } to DoubleArray(points.size) { points[it].z().toDouble() }
            }
            else -> null
        }

        private fun infoOf(region: ProtectedRegion, label: String): RegionInfo {
            val profiles = WorldGuard.getInstance().profileCache
            return RegionInfo(
                label = label,
                playerOwners = region.owners.toPlayersString(profiles),
                groupOwners = region.owners.toGroupsString(),
                playerMembers = region.members.toPlayersString(profiles),
                groupMembers = region.members.toGroupsString(),
                parent = region.parent?.id,
                priority = region.priority,
                flags = region.flags.map { (flag, value) -> flag.name to value.toString() },
            )
        }
    }
}
