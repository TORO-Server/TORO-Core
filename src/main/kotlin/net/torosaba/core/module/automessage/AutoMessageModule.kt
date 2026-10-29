package net.torosaba.core.module.automessage

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit

/**
 * 旧 Auto_message。config.yml の Messages から 1 つを選んで、tick ごとに全員へ流す。
 * AllSendMessage が true なら、全部を 1 回ずつ流し終えるまで同じメッセージは選ばない。
 */
class AutoMessageModule : Module("auto-message", "Auto_message") {
    // メッセージは § の色コードで書かれている。URL はクリックできるようにする (元の broadcastMessage と同じ)
    private val serializer = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .extractUrls()
        .build()

    override fun onEnable() {
        val messages = config.getStringList("Messages")
        val tick = config.getLong("tick", 6000L).coerceAtLeast(1L)
        val allSend = config.getBoolean("AllSendMessage", true)

        if (messages.isEmpty()) {
            logger.warning("Messages が空なので、メッセージは流しません")
            return
        }
        logger.info("${messages.size} 件のメッセージを $tick tick ごとに流します")

        // まだ流していないメッセージと、流し終えたメッセージ
        var remaining = messages.toMutableList()
        var sent = mutableListOf<String>()

        runTimer(tick, tick) {
            val message = if (allSend) {
                if (remaining.isEmpty()) {
                    remaining = sent
                    sent = mutableListOf()
                }
                remaining.removeAt(remaining.indices.random()).also { sent += it }
            } else {
                messages.random()
            }
            Bukkit.broadcast(serializer.deserialize(message))
        }
    }
}
