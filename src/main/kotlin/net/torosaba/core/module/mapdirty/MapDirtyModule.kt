package net.torosaba.core.module.mapdirty

import net.torosaba.core.Module
import org.bukkit.Bukkit
import java.util.logging.Level

/**
 * ブロックの変更をチャンク単位で記録する (この段階では記録のみで、地図の描画には使わない)。
 * TORO-Renderer の watch (リージョンファイルの保存時刻を 60 秒周期で見る検知) が
 * 変更を取りこぼしていないかを、後からこの記録と突き合わせて確かめるためのもの。
 *
 * Bukkit のイベントで拾える変更と、イベントの出ない WorldEdit の直接書き換えの両方を拾い、
 * 窓 (既定 10 秒) でまとめてモジュールのフォルダの map-dirty.jsonl に書く。
 * 既定では無効 (TORO-Core の config.yml で modules.map-dirty が false)。
 */
class MapDirtyModule : Module("map-dirty") {
    private lateinit var window: DirtyWindow
    private lateinit var writer: DirtyLogWriter
    private var worldEditHook: WorldEditDirtyHook? = null

    override fun onEnable() {
        val windowSeconds = config.getLong("window-seconds", 10).coerceIn(1, 3600)
        val maxBytes = config.getLong("max-file-size-mb", 16).coerceIn(1, 1024) * 1024 * 1024
        val generations = config.getInt("generations", 7).coerceIn(1, 30)
        window = DirtyWindow(windowSeconds * 1000)
        writer = DirtyLogWriter(dataFolder, "map-dirty", maxBytes, generations)
        listen(BukkitDirtyListener(window))

        if (Bukkit.getPluginManager().getPlugin("WorldEdit")?.isEnabled == true) {
            worldEditHook = try {
                WorldEditDirtyHook(window).also { it.start() }
            } catch (e: LinkageError) {
                logger.log(Level.WARNING, "WorldEdit と連携できませんでした。WorldEdit の変更は記録しません", e)
                null
            }
        }

        // 1 秒ごとに、WorldEdit セッションの確定と窓の締めを確かめる (どちらも軽い確認だけ)
        runTimerAsync(20, 20) {
            worldEditHook?.sweep(System.currentTimeMillis())
            window.takeIfDue()?.let { write(it) }
        }
        logger.info(
            "ブロック変更の記録を開始しました (窓 ${windowSeconds} 秒、WorldEdit ${if (worldEditHook != null) "あり" else "なし"})"
        )
    }

    override fun onDisable() {
        try {
            worldEditHook?.stop()
        } catch (e: LinkageError) {
            logger.log(Level.WARNING, "WorldEdit 連携の停止でエラーが起きました", e)
        }
        worldEditHook = null
        // 残っている窓は、非同期タスクがもう動かないのでここで直接書く
        if (::window.isInitialized) window.flush()?.let { write(it) }
    }

    private fun write(snapshot: WindowSnapshot) {
        try {
            writer.append(snapshot.toJson())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "ブロック変更の記録を書けませんでした", e)
        }
    }
}
