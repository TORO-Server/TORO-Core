package net.torosaba.core.module.infinitydispense

import net.torosaba.core.Module
import net.torosaba.core.legacyText
import org.bukkit.Location
import org.bukkit.Nameable
import org.bukkit.block.Container
import org.bukkit.block.Dispenser
import org.bukkit.block.Dropper
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent

/** 旧 InfinityDispense。config の name と同じ名前のディスペンサーとドロッパーはアイテムを消費しない */
class InfinityDispenseModule : Module("infinity-dispense", "InfinityDispense"), Listener {

    // 自分で発射し直している最中のブロック (この間の発射イベントはそのまま通す)
    private val redispensing = mutableSetOf<Location>()

    override fun onEnable() {
        listen(this)
    }

    override fun onDisable() {
        redispensing.clear()
    }

    @EventHandler
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        if (block.location in redispensing) return

        val state = block.getState(false)
        if (state !is Nameable || state !is Container) return
        if (state.customName()?.legacyText() != config.getString("name")) return

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
