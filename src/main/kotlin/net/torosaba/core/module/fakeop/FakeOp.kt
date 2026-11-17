package net.torosaba.core.module.fakeop

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * 「クライアントにだけ OP の権限レベルを伝える」ための共有部品。
 *
 * サーバー側の実際の権限は変えない (isOp は false のまま、ops.json にも書かない、OP 専用コマンドは
 * サーバーが拒否する)。変わるのは、クライアントが手元で行う判定だけである
 * (例: F3+F4 のゲームモード切替画面、クリエイティブの「オペレーター用アイテム」タブ)。
 *
 * 仕組み: Paper の `Player#sendOpLevel(byte)` が、バニラの権限通知
 * (`ClientboundEntityEventPacket` のイベント ID 24〜28 = レベル 0〜4) をそのプレイヤーにだけ送る。
 * 通知はサーバーが入室・リスポーン・ディメンション移動・/op・/deop のたびに実際の値で送り直すので、
 * 偽の値はそのたびに上書きされる。[FakeOpModule] が該当のイベントで送り直す。
 *
 * 使い方: 利用するモジュールが [register] で「どのプレイヤーに、どのレベルを」を登録する。
 * 送信と再送はこの部品だけが行う (モジュールごとに実装を持たない)。
 */
object FakeOp {
    /** クライアント側の判定が「ゲームマスター」(F3+F4 / オペレータータブ) を満たす最小のレベル */
    const val GAMEMASTER_LEVEL = 2

    private const val MAX_LEVEL = 4

    internal class Request(val owner: String, val level: Int, val target: (Player) -> Boolean)

    private val requests = ConcurrentHashMap<String, Request>()

    /** FakeOpModule が有効なあいだだけ true。無効のときは何も送らない */
    @Volatile
    internal var active = false

    /** 偽のレベルを送ったプレイヤー。取り消すときに実際のレベルへ戻すために覚えておく */
    private val applied = ConcurrentHashMap.newKeySet<java.util.UUID>()

    /**
     * [owner] (モジュールの名前など) の要求を登録する。同じ [owner] の要求は置き換える。
     * [level] は 1〜4 に収める。0 以下は偽装にならないので登録しない。
     */
    fun register(owner: String, level: Int, target: (Player) -> Boolean) {
        val clamped = level.coerceAtMost(MAX_LEVEL)
        if (clamped <= 0) {
            requests.remove(owner)
            return
        }
        requests[owner] = Request(owner, clamped, target)
    }

    /** 登録を外す。偽のレベルを受けていたプレイヤーには、実際のレベルを送り直す */
    fun unregister(owner: String, online: Collection<Player>) {
        if (requests.remove(owner) == null) return
        online.forEach { refresh(it) }
    }

    /** いまのプレイヤーに送るべき偽のレベル。登録された要求の最大値。無ければ 0 */
    fun levelFor(player: Player): Int = effectiveLevel(requests.values, player)

    /**
     * 現在の登録に従って [player] のクライアントの表示を整える。
     * - 偽のレベルが要るなら送る (本物の OP には送らない。レベル 4 を下げてしまうため)
     * - 以前送っていて要らなくなったなら、実際のレベルを送り直す
     */
    fun refresh(player: Player) {
        if (!active) {
            if (applied.remove(player.uniqueId)) restore(player)
            return
        }
        val level = if (player.isOp) 0 else levelFor(player)
        if (level > 0) {
            player.sendOpLevel(level.toByte())
            applied.add(player.uniqueId)
        } else if (applied.remove(player.uniqueId)) {
            restore(player)
        }
    }

    /** 偽のレベルを受けた記録を捨てる (退室時) */
    internal fun forget(player: Player) {
        applied.remove(player.uniqueId)
    }

    internal fun hasApplied(player: Player): Boolean = player.uniqueId in applied

    /**
     * 実際のレベル 0 を送り直す。OP になった人には、/op の時点でサーバーが実際のレベルを送っているので、
     * ここでは何もしない (OP のレベルは ops.json の値で 4 とは限らないため、こちらで決めない)
     */
    private fun restore(player: Player) {
        if (!player.isOp) player.sendOpLevel(0)
    }

    internal fun effectiveLevel(requests: Collection<Request>, player: Player): Int =
        requests.filter { it.target(player) }.maxOfOrNull { it.level } ?: 0

    /** 送った記録だけを捨てる。他のモジュールが登録した要求は残す (fake-op だけを読み込み直しても登録が消えないように) */
    internal fun clearApplied() {
        applied.clear()
    }

    internal fun clear() {
        requests.clear()
        applied.clear()
    }
}
