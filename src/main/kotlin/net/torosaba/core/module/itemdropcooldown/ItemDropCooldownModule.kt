package net.torosaba.core.module.itemdropcooldown

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import java.util.UUID

/**
 * 旧 ItemDropCooldown。CheckSecond 秒のあいだに MaxDropItem 個より多くアイテムを捨てたプレイヤーは、
 * BlockSecond 秒のあいだアイテムを捨てられなくする。
 */
class ItemDropCooldownModule : Module("item-drop-cooldown", "ItemDropCooldown"), Listener {
    /** 前回の確認から捨てた回数 */
    private val dropCounts = mutableMapOf<UUID, Int>()
    /** 捨てるのを止めているプレイヤー */
    private val blocked = mutableSetOf<UUID>()

    private var maxDrop = 0
    private var blockSecond = 0L
    private var blockMessage: String? = null

    override fun onEnable() {
        val checkTick = (config.getLong("CheckSecond") * 20).coerceAtLeast(1L)
        maxDrop = config.getInt("MaxDropItem")
        blockSecond = config.getLong("BlockSecond")
        blockMessage = config.getString("BlockMessage")

        listen(this)
        runTimer(checkTick, checkTick) {
            for ((uuid, count) in dropCounts) {
                if (count > maxDrop && blocked.add(uuid)) {
                    runLater(blockSecond * 20) { blocked.remove(uuid) }
                }
            }
            dropCounts.clear()
        }
    }

    override fun onDisable() {
        dropCounts.clear()
        blocked.clear()
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        if (player.uniqueId in blocked) {
            event.isCancelled = true
            blockMessage?.takeIf { it.isNotEmpty() }?.let {
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(it.format(blockSecond)))
            }
            return
        }
        dropCounts.merge(player.uniqueId, 1, Int::plus)
    }
}
