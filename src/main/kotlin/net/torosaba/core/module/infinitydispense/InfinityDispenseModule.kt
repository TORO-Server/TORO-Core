package net.torosaba.core.module.infinitydispense

import net.torosaba.core.Module
import net.torosaba.core.legacyText
import org.bukkit.Location
import org.bukkit.Nameable
import org.bukkit.NamespacedKey
import org.bukkit.block.Container
import org.bukkit.block.Dispenser
import org.bukkit.block.Dropper
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.permissions.PermissionDefault
import org.bukkit.persistence.PersistentDataType

/** 旧 InfinityDispense。config の name と同じ名前のディスペンサーとドロッパーはアイテムを消費しない。旧プラグインは名前だけで判定していたため、タグの無い既存の設置物も対象とする */
class InfinityDispenseModule : Module("infinity-dispense", "InfinityDispense"), Listener {

    private lateinit var tagKey: NamespacedKey

    // 自分で発射し直している最中のブロック (この間の発射イベントはそのまま通す)
    private val redispensing = mutableSetOf<Location>()

    override fun onEnable() {
        tagKey = NamespacedKey(plugin, "infinity_dispenser")
        permission("infinitydispense.create", PermissionDefault.TRUE, "無限ディスペンサーを設置する権限")
        listen(this)
    }

    override fun onDisable() {
        redispensing.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val state = event.blockPlaced.state as? Container ?: return
        if (state !is Nameable) return
        val targetName = config.getString("name") ?: return
        if (state.customName()?.legacyText() != targetName) return
        if (!event.player.hasPermission("infinitydispense.create")) {
            event.player.sendMessage("§c無限ディスペンサーを設置する権限がありません")
            return
        }
        state.persistentDataContainer.set(tagKey, PersistentDataType.BYTE, 1)
        state.update()
    }

    @EventHandler
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        if (block.location in redispensing) return

        val state = block.getState(false)
        if (state !is Nameable || state !is Container) return
        if (state.customName()?.legacyText() != config.getString("name")) return
        // 旧プラグインは名前だけで判定していたため、タグの無い既存の設置物も無限として扱う

        event.isCancelled = true
        // 次の tick に中身を写してから発射し、発射後に中身を戻す
        runLater(0) {
            val container = block.getState(false) as? Container ?: return@runLater
            val inventory = container.inventory
            val items = inventory.contents.map { it?.clone() }.toTypedArray()
            redispensing += block.location
            try {
                when (container) {
                    is Dispenser -> container.dispense()
                    is Dropper -> container.drop()
                }
            } finally {
                redispensing -= block.location
                inventory.contents = items
            }
        }
    }
}
