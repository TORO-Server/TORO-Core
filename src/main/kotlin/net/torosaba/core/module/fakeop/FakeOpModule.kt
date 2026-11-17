package net.torosaba.core.module.fakeop

import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * クライアントだけに OP の権限レベルを伝える共有部品 ([FakeOp]) の、送り直しを受け持つモジュール。
 *
 * 登録は [FakeOp.register] で行う。このモジュール自体は誰にも偽のレベルを送らない。
 * 送り直しのきっかけ (バニラはこれらで実際のレベルを送り直し、偽の値を上書きする):
 * - 入室 (PlayerJoinEvent)
 * - リスポーン (PlayerRespawnEvent)
 * - ディメンション (ワールド) 移動 (PlayerChangedWorldEvent)
 * - ゲームモードの変更 (PlayerGameModeChangeEvent)。バニラは送り直さないが、念のため同じ tick の後ろで送る
 *
 * サーバーの内部状態 (isOp、ops.json、コマンドの権限) は一切変えない。
 */
class FakeOpModule : Module("fake-op"), Listener {
    private var log = true
    private var resendOn = DEFAULT_EVENTS

    override fun onEnable() {
        log = config.getBoolean("log", true)
        resendOn = config.getStringList("resend-on").ifEmpty { DEFAULT_EVENTS }.map { it.lowercase() }.toSet()
        FakeOp.active = true
        listen(this)
        // 有効になる前から入っている人 (/torocore reload など) にも反映する
        runLater(1L) { Bukkit.getOnlinePlayers().forEach { FakeOp.refresh(it) } }
    }

    override fun onDisable() {
        FakeOp.active = false
        // 偽のレベルを受けていた人に実際のレベルを送り直す (active=false のとき refresh は実際のレベルに戻す)
        Bukkit.getOnlinePlayers().forEach { FakeOp.refresh(it) }
        FakeOp.clearApplied()
    }

    /** バニラの送信より後ろで送る。同じ tick 内の順序に依存しないよう、1 tick 遅らせる */
    private fun later(player: org.bukkit.entity.Player, reason: String) {
        if (reason !in resendOn) return
        runLater(1L) {
            if (!player.isOnline) return@runLater
            FakeOp.refresh(player)
            if (log && FakeOp.hasApplied(player)) {
                logger.info("偽の権限レベル ${FakeOp.levelFor(player)} を送りました (player=${player.name}, きっかけ=$reason)")
            }
        }
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) = later(e.player, "join")

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) = later(e.player, "respawn")

    @EventHandler
    fun onWorld(e: PlayerChangedWorldEvent) = later(e.player, "world-change")

    @EventHandler
    fun onGameMode(e: PlayerGameModeChangeEvent) = later(e.player, "gamemode")

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) = FakeOp.forget(e.player)

    private companion object {
        val DEFAULT_EVENTS = setOf("join", "respawn", "world-change", "gamemode")
    }
}
