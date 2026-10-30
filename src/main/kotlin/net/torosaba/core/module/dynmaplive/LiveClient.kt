package net.torosaba.core.module.dynmaplive

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * toro-map-web への WebSocket。切れたら少しずつ間を空けてつなぎ直す。
 * 送るのは最新の 1 通だけでよいので、前の送信が終わっていないときは今回の分を捨てる。
 */
class LiveClient(private val uri: URI, private val token: String, private val logger: Logger) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "TORO-Core dynmap-live").apply { isDaemon = true } }
    private val sending = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var retry = 1L
    private var warned = false

    fun start() = connect()

    /** 送れたら true。つながっていないとき、前の送信がまだのときは false */
    fun send(text: String): Boolean {
        val ws = socket ?: return false
        if (!sending.compareAndSet(false, true)) return false
        ws.sendText(text, true).whenComplete { _, e ->
            sending.set(false)
            if (e != null) drop(ws, e)
        }
        return true
    }

    fun close() {
        closed = true
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "")
        socket = null
        scheduler.shutdownNow()
    }

    private fun connect() {
        if (closed) return
        http.newWebSocketBuilder()
            .header("X-Toro-Token", token)
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(uri, Listener())
            // WebSocket でない返事が来たときなどに、待ったままにならないようにする
            .orTimeout(10, TimeUnit.SECONDS)
            .whenComplete { ws, e ->
                if (e != null) {
                    if (!warned) logger.warning("地図 ($uri) につなげません: ${e.cause?.message ?: e.message}。つながるまで試し続けます")
                    warned = true
                    later()
                } else {
                    socket = ws
                    retry = 1
                    if (warned) logger.info("地図 ($uri) につながりました")
                    warned = false
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
        if (e != null) logger.log(Level.FINE, "地図への接続が切れました", e)
        later()
    }

    private inner class Listener : WebSocket.Listener {
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            // 自分で閉じたとき (モジュールを止めたとき) は知らせない
            if (closed) return CompletableFuture.completedFuture(null)
            if (!warned) logger.warning("地図との接続が閉じられました ($statusCode $reason)。つなぎ直します")
            warned = true
            drop(ws, null)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(ws: WebSocket, error: Throwable) {
            drop(ws, error)
        }
    }
}
