package net.torosaba.core.module.mapdirty

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 窓の記録を TORO-Renderer の watch へ WebSocket で送る (dynmap-live と同じ流儀: token で確かめ、
 * 切れたら少しずつ間を空けてつなぎ直す)。
 *
 * dynmap-live との違いは、受け手が Godot 製の TORO-Renderer で、接続時の HTTP ヘッダーを読めないこと。
 * そのため token はヘッダーではなく、つながった直後の 1 通 ({"t":"auth",...}) で見せる。
 * 認証が済むまでは dirty を送らない。
 *
 * 送れなかった分は [DirtyFeedBuffer] に貯めておき、つながったときにまとめて送る (捨てない)。
 * 1 通に入れるチャンクには上限があり、超えた分は次の機会に送る。
 */
class DirtyFeedClient(private val uri: URI, private val token: String, private val logger: Logger) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "TORO-Core map-dirty feed").apply { isDaemon = true } }
    private val buffer = DirtyFeedBuffer()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var authed = false
    @Volatile private var sending = false
    private var retry = 1L
    private var warned = false

    fun start() = connect()

    /** 窓の記録を送信用に貯める。つながっていればそのまま送り始める */
    fun offer(snapshot: WindowSnapshot) {
        buffer.offer(snapshot)
        trySend()
    }

    /** 貯まっている変更の数 (ログ用) */
    fun queuedChunks(): Int = buffer.size()

    fun close() {
        closed = true
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "")
        socket = null
        scheduler.shutdownNow()
    }

    private fun connect() {
        if (closed) return
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(uri, Listener())
            .orTimeout(10, TimeUnit.SECONDS)
            .whenComplete { ws, e ->
                if (e != null) {
                    if (!warned) logger.warning("TORO-Renderer ($uri) につなげません: ${e.cause?.message ?: e.message}。つながるまで試し続けます")
                    warned = true
                    later()
                } else {
                    socket = ws
                    retry = 1
                    if (warned) logger.info("TORO-Renderer ($uri) につながりました")
                    warned = false
                    authenticate(ws)
                }
            }
    }

    private fun authenticate(ws: WebSocket) {
        val auth = "{\"t\":\"auth\",\"token\":${jsonString(token)}}"
        ws.sendText(auth, true).whenComplete { _, e ->
            if (e != null) {
                drop(ws, e)
            } else {
                authed = true
                trySend()
            }
        }
    }

    private fun trySend() {
        val ws = socket ?: return
        if (!authed || sending) return
        val snapshot = buffer.take(MAX_CHUNKS_PER_MESSAGE) ?: return
        sending = true
        ws.sendText(snapshot.toFeedJson(), true).whenComplete { _, e ->
            sending = false
            if (e != null) {
                // 送れなかった分は貯めに戻す (重なりは集合なので潰れてよい)
                buffer.offer(snapshot)
                drop(ws, e)
            } else {
                trySend()
            }
        }
    }

    private fun later() {
        if (closed) return
        scheduler.schedule({ connect() }, retry, TimeUnit.SECONDS)
        retry = (retry * 2).coerceAtMost(60)
    }

    private fun drop(ws: WebSocket, e: Throwable?) {
        if (socket !== ws) return
        socket = null
        authed = false
        if (e != null) logger.log(Level.FINE, "TORO-Renderer への接続が切れました", e)
        later()
    }

    private inner class Listener : WebSocket.Listener {
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (closed) return CompletableFuture.completedFuture(null)
            if (!warned) logger.warning("TORO-Renderer との接続が閉じられました ($statusCode $reason)。つなぎ直します")
            warned = true
            drop(ws, null)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(ws: WebSocket, error: Throwable) {
            drop(ws, error)
        }
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private companion object {
        /** 1 通に入れるチャンクの上限。これを超える分は次の機会に送る */
        const val MAX_CHUNKS_PER_MESSAGE = 200_000
    }
}
