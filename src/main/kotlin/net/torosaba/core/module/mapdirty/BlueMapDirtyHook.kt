package net.torosaba.core.module.mapdirty

import com.flowpowered.math.vector.Vector2i
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.BlueMapMap
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 窓の確定に合わせて、変更のあった範囲の再描画を BlueMap に頼む。
 *
 * BlueMap の更新はリージョンファイル (32x32 チャンク) 単位でしか頼めないので、
 * 窓のチャンク集合をリージョンへ丸めてから scheduleMapUpdateTask に渡す。
 * BlueMap 側には同じマップへの重複を弾く仕組みがあり、すでにタスクがある場合は false が返るだけなので、
 * 変更が続く間なんども頼んでも依頼が際限なく積み上がることはない。
 *
 * BlueMap が入っていないときはこのクラス自体を読み込まない (呼び出し側で分けてある)。
 * BlueMap は TORO-Core より後から有効になることもあるので、onEnable で知らせを受ける。
 */
class BlueMapDirtyHook(
    private val logger: Logger,
    private val force: Boolean,
) {
    @Volatile
    private var api: BlueMapAPI? = null

    private val onEnable = Consumer<BlueMapAPI> { api = it }
    private val onDisable = Consumer<BlueMapAPI> { if (api === it) api = null }

    fun start() {
        BlueMapAPI.onEnable(onEnable)
        BlueMapAPI.onDisable(onDisable)
        BlueMapAPI.getInstance().ifPresent { api = it }
    }

    fun stop() {
        BlueMapAPI.unregisterListener(onEnable)
        BlueMapAPI.unregisterListener(onDisable)
        api = null
    }

    /** 窓 1 つ分の変更を BlueMap へ渡す。BlueMap がまだ有効でないときは何もしない */
    fun update(snapshot: WindowSnapshot) {
        val current = api ?: return
        for ((world, bySource) in snapshot.worlds) {
            val chunks = HashSet<Long>()
            for (set in bySource.values) chunks += set
            if (chunks.isEmpty()) continue
            val maps = mapsOf(current, world)
            if (maps.isEmpty()) continue
            val regions = ChunkMath.regionsOf(chunks).map { Vector2i(ChunkMath.unpackX(it), ChunkMath.unpackZ(it)) }
            for (map in maps) {
                try {
                    current.renderManager.scheduleMapUpdateTask(map, regions, force)
                } catch (e: Exception) {
                    logger.log(Level.FINE, "BlueMap への再描画の依頼に失敗しました (map ${map.id})", e)
                }
            }
        }
    }

    /**
     * Bukkit のワールド名に対応する BlueMap のマップ。
     * BlueMap のワールド ID は通常はワールド名そのものだが、保存フォルダの末尾の名前でも確かめる。
     */
    private fun mapsOf(api: BlueMapAPI, worldName: String): List<BlueMapMap> =
        api.maps.filter { map ->
            map.world.id == worldName || map.world.saveFolder.fileName?.toString() == worldName
        }
}
