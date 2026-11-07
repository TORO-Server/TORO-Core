package net.torosaba.core.module.mapdirty

import net.torosaba.core.Module
import org.bukkit.Bukkit
import java.net.URI
import java.util.logging.Level

/**
 * ブロックの変更をチャンク単位で集めて、地図の更新につなげる。
 *
 * Bukkit のイベントで拾える変更と、イベントの出ない WorldEdit の直接書き換えの両方を拾い、
 * 窓 (既定 10 秒) でまとめる。窓が確定するたびに、次の 3 つを行う。
 * - 記録 (map-dirty.jsonl への追記)。watch の Anvil スキャンとの突き合わせ用で、常に行う
 * - TORO-Renderer の watch への送信 (feed-endpoint と feed-token が設定されているとき)
 * - BlueMap への再描画の依頼 (bluemap が有効で、BlueMap が入っているとき)
 *
 * 既定ではモジュール自体が無効 (TORO-Core の config.yml で modules.map-dirty が false)。
 */
class MapDirtyModule : Module("map-dirty") {
    private lateinit var window: DirtyWindow
    private lateinit var writer: DirtyLogWriter
    private var worldEditHook: WorldEditDirtyHook? = null
    private var feed: DirtyFeedClient? = null
    private var blueMapHook: BlueMapDirtyHook? = null

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

        val endpoint = config.getString("feed-endpoint").orEmpty()
        val token = config.getString("feed-token").orEmpty()
        if (endpoint.isNotBlank() && token.isNotBlank()) {
            feed = try {
                DirtyFeedClient(URI(endpoint), token, logger).also { it.start() }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "TORO-Renderer への送信を始められませんでした (endpoint: $endpoint)", e)
                null
            }
        }

        if (config.getBoolean("bluemap", true) &&
            Bukkit.getPluginManager().getPlugin("BlueMap")?.isEnabled == true
        ) {
            blueMapHook = try {
                BlueMapDirtyHook(logger, config.getBoolean("bluemap-force", true)).also { it.start() }
            } catch (e: LinkageError) {
                logger.log(Level.WARNING, "BlueMap と連携できませんでした。BlueMap の更新はファイル検知に任せます", e)
                null
            }
        }

        // 1 秒ごとに、WorldEdit セッションの確定と窓の締めを確かめる (どちらも軽い確認だけ)
        runTimerAsync(20, 20) {
            worldEditHook?.sweep(System.currentTimeMillis())
            window.takeIfDue()?.let { write(it) }
        }
        logger.info(
            "ブロック変更の記録を開始しました (窓 ${windowSeconds} 秒、WorldEdit ${if (worldEditHook != null) "あり" else "なし"}、" +
                "Renderer 送信 ${if (feed != null) "あり" else "なし"}、BlueMap ${if (blueMapHook != null) "あり" else "なし"})"
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
        try {
            blueMapHook?.stop()
        } catch (e: LinkageError) {
            logger.log(Level.WARNING, "BlueMap 連携の停止でエラーが起きました", e)
        }
        blueMapHook = null
        feed?.close()
        feed = null
    }

    private fun write(snapshot: WindowSnapshot) {
        try {
            writer.append(snapshot.toJson())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "ブロック変更の記録を書けませんでした", e)
        }
        feed?.offer(snapshot)
        try {
            blueMapHook?.update(snapshot)
        } catch (e: LinkageError) {
            logger.log(Level.WARNING, "BlueMap 連携でエラーが起きました", e)
        }
    }
}
