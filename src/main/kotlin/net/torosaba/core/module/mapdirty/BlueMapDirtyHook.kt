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
 *
 * 診断ログの方針 (段階5の 3 回の無言失敗を受けたもの):
 * update() の無言終了は 2 経路しかない (API 未取得 / 対応マップなし) ため、
 * 両方を初回のみ WARNING で記録し、利用可能なマップの一覧も添える。
 * scheduleMapUpdateTask の戻り値 (false = 重複タスクとして弾かれた) も INFO で記録する。
 * 挙動の変更はなく、追加されるのはログのみである。
 */
class BlueMapDirtyHook(
    private val logger: Logger,
    private val force: Boolean,
) {
    @Volatile
    private var api: BlueMapAPI? = null

    /** API 未取得の WARNING は初回のみ (窓は 10 秒間隔のため毎回の記録はしない) */
    @Volatile
    private var warnedApiNull = false

    /** 対応マップなしの WARNING はワールドごとに初回のみ */
    private val warnedNoMap = HashSet<String>()

    private val onEnable = Consumer<BlueMapAPI> {
        api = it
        warnedApiNull = false
        logger.info("BlueMap API が有効になりました")
    }
    private val onDisable = Consumer<BlueMapAPI> { if (api === it) api = null }

    fun start() {
        BlueMapAPI.onEnable(onEnable)
        BlueMapAPI.onDisable(onDisable)
        BlueMapAPI.getInstance().ifPresentOrElse(
            {
                api = it
                logger.info("BlueMap API を取得しました")
            },
            { logger.info("BlueMap API は開始時に未取得です (有効化を待ちます)") },
        )
    }

    fun stop() {
        BlueMapAPI.unregisterListener(onEnable)
        BlueMapAPI.unregisterListener(onDisable)
        api = null
        warnedApiNull = false
        warnedNoMap.clear()
    }

    /** 窓 1 つ分の変更を BlueMap へ渡す。BlueMap がまだ有効でないときは何もしない */
    fun update(snapshot: WindowSnapshot) {
        val current = api ?: run {
            if (!warnedApiNull) {
                warnedApiNull = true
                logger.warning("BlueMap への再描画の依頼をしませんでした: BlueMap API が未取得です")
            }
            return
        }
        for ((world, bySource) in snapshot.worlds) {
            val chunks = HashSet<Long>()
            for (set in bySource.values) chunks += set
            if (chunks.isEmpty()) continue
            val maps = mapsOf(current, world)
            if (maps.isEmpty()) {
                if (warnedNoMap.add(world)) {
                    val available = current.maps.joinToString(", ") { "${it.id}[world=${it.world.id}]" }
                    logger.warning(
                        "BlueMap への再描画の依頼をしませんでした: ワールド '$world' に対応するマップがありません " +
                            "(利用可能なマップ: $available)",
                    )
                }
                continue
            }
            val regions = ChunkMath.regionsOf(chunks).map { Vector2i(ChunkMath.unpackX(it), ChunkMath.unpackZ(it)) }
            // 窓がフックへ届いたことを運用ログでも確かめられるようにする (窓は 10 秒間隔なので量は問題にならない)
            logger.info("BlueMap へ再描画を依頼します (world $world、${chunks.size} チャンク、${regions.size} リージョン、map ${maps.size} 件)")
            for (map in maps) {
                try {
                    val scheduled = current.renderManager.scheduleMapUpdateTask(map, regions, force)
                    logger.info("BlueMap へ再描画を依頼しました (map ${map.id}、${regions.size} リージョン、scheduled=$scheduled)")
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "BlueMap への再描画の依頼に失敗しました (map ${map.id})", e)
                }
            }
        }
    }

    /**
     * Bukkit のワールド名に対応する BlueMap のマップ。
     * 照合の条件は [matchesWorld] を見ること。
     */
    private fun mapsOf(api: BlueMapAPI, worldName: String): List<BlueMapMap> =
        api.maps.filter { map ->
            matchesWorld(map.world.id, map.world.saveFolder.fileName?.toString(), worldName)
        }
}

/**
 * BlueMap のマップが Bukkit のワールドに対応するかの照合。
 *
 * ワールド ID の完全一致のほか、BlueMap が使う「ワールド名#ディメンション」の形の ID
 * (例: main#minecraft:overworld) では「#」より前の部分の一致でも対応するとみなす。
 * それでも決まらないときの予備として、保存フォルダの末尾の名前でも確かめる。
 */
internal fun matchesWorld(worldId: String, saveFolderName: String?, worldName: String): Boolean =
    worldId == worldName ||
        worldId.substringBefore('#') == worldName ||
        saveFolderName == worldName
