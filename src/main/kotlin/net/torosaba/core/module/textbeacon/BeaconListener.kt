package net.torosaba.core.module.textbeacon

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.block.sign.SignSide
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType

internal const val SIGN_HEADER = "[TextBeacon]"

/** 看板の表面に見出しと 3 行のメッセージを書く */
internal fun SignSide.writeBeacon(lines: List<String>) {
    line(0, Component.text(SIGN_HEADER))
    lines.forEachIndexed { i, s -> line(i + 1, signText.deserialize(s)) }
}

/** 設置した看板が TextBeacon の看板か (メッセージ入りのアイテムか、1 行目が見出し) */
internal fun isBeaconSign(itemMessage: String?, firstLine: String): Boolean =
    !itemMessage.isNullOrBlank() || SIGN_HEADER.equals(firstLine, ignoreCase = true)

internal class BeaconListener(private val module: TextBeaconModule) : Listener {
    private val registry get() = module.registry

    // ---- 看板の設置 ----

    // 保護プラグインなどが取り消した編集では登録しない
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSign(e: SignChangeEvent) {
        if (!e.line(0).signLine().equals(SIGN_HEADER, ignoreCase = true)) return
        if (!e.player.hasPermission("textbeacon.create")) {
            e.player.sendMessage("§cこの操作を行う権限がありません (textbeacon.create)")
            return
        }
        // 登録済みの看板を書き直したときは二重に登録しない
        if (BlockKey(e.block.location) in registry.signs) return
        val lines = (0..3).map { e.line(it).signLine() }
        module.tryRegisterBeacon(e.player, e.block.location, buildMessage(lines))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        val sign = e.blockPlaced.state as? Sign ?: return
        val itemMessage = itemMessage(e.itemInHand)
        val front = sign.getSide(Side.FRONT)
        // 普通の看板には何もしない (権限のメッセージも出さない)
        if (!isBeaconSign(itemMessage, front.line(0).signLine())) return
        if (!e.player.hasPermission("textbeacon.create")) {
            e.player.sendMessage("§cこの操作を行う権限がありません (textbeacon.create)")
            return
        }

        if (!itemMessage.isNullOrBlank()) {
            // 看板の中身はサーバーが書く (OP 以外はアイテムの看板データが反映されないため)
            e.isCancelled = true
            if (e.player.gameMode != GameMode.CREATIVE) {
                e.itemInHand.amount -= 1
            }
            placeBeaconSign(e, sign.location, itemMessage)
            return
        }

        if (BlockKey(sign.location) in registry.signs) return
        module.tryRegisterBeacon(e.player, sign.location, buildMessage(front.lines().map { it.signLine() }))
    }

    private fun itemMessage(item: ItemStack?): String? {
        val meta = item?.itemMeta as? BlockStateMeta ?: return null
        return meta.persistentDataContainer.get(module.itemMessageKey, PersistentDataType.STRING)
    }

    private fun placeBeaconSign(e: BlockPlaceEvent, location: Location, msg: String) {
        val data = e.blockPlaced.blockData
        val player = e.player
        module.runSync {
            val block = location.block
            block.setBlockData(data, false)
            val sign = block.state as? Sign ?: return@runSync
            sign.getSide(Side.FRONT).writeBeacon(splitMessageForSign(msg))
            sign.update(true, false)
            player.closeInventory()
            if (!module.tryRegisterBeacon(player, location, msg)) block.type = Material.AIR
        }
    }

    // ---- 看板の破壊 ----

    @EventHandler
    fun onBreak(e: BlockBreakEvent) {
        if (e.block.state !is Sign) return
        val key = BlockKey(e.block.location)
        val id = registry.signs[key] ?: return
        val b = registry.beacons[id]
        if (b == null) {
            registry.signs.remove(key)
            return
        }
        val isOwner = b.owner != null && b.owner == e.player.uniqueId
        if (!e.player.hasPermission("textbeacon.remove") && !isOwner) {
            e.isCancelled = true
            e.player.sendMessage("§c他人のBeaconを削除することはできません (textbeacon.remove)")
            return
        }
        if (!module.removeBeacon(id, false)) return
        e.player.sendMessage("§eTextBeaconを削除しました")
    }

    // ---- 入場の検知 (ブロックをまたいだときだけ) ----

    @EventHandler
    fun onMove(e: PlayerMoveEvent) {
        if (!e.hasChangedBlock()) return
        val p = e.player
        if (!p.hasPermission("textbeacon.use") || p.uniqueId in registry.muted) {
            module.inside.remove(p.uniqueId)
            return
        }
        val beaconId = module.triggeredBeacon(p, e.to)
        val set = module.inside.getOrPut(p.uniqueId) { HashSet() }
        if (beaconId == null) {
            set.clear()
            return
        }
        if (beaconId in set) return
        // 別の Beacon に移ったときも通知する
        set.clear()
        val b = registry.beacons[beaconId] ?: return
        p.sendMessage(b.message)
        set += beaconId
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        module.inside.remove(e.player.uniqueId)
        module.debugViewers.remove(e.player.uniqueId)
    }
}
