package net.torosaba.core.module.dispenserfallingblock

import net.torosaba.core.Module
import org.bukkit.block.Dispenser
import org.bukkit.block.data.Directional
import org.bukkit.entity.FallingBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent

/** 旧 Dispenser_FallingBlock。BlockList のブロックをディスペンサーから落下ブロックとして出す */
class DispenserFallingBlockModule : Module("dispenser-falling-block", "Dispenser_FallingBlock"), Listener {

    override fun onEnable() {
        listen(this)
    }

    @EventHandler
    fun onDispense(event: BlockDispenseEvent) {
        val type = event.item.type
        if (config.getBoolean("Log")) logger.info(type.toString())

        if (config.getStringList("BlockList").none { it.equals(type.name, ignoreCase = true) }) return
        if (!type.isBlock) return
        val block = event.block
        val dispenser = block.getState(false) as? Dispenser ?: return

        // 向いている方向の 1 マス先の中心
        val facing = (block.blockData as? Directional)?.facing
        val location = block.location.add(0.5, 0.0, 0.5)
        if (facing != null) location.add(facing.modX.toDouble(), facing.modY.toDouble(), facing.modZ.toDouble())

        val targetBlock = location.block
        if (!targetBlock.isPassable && !targetBlock.isEmpty) return

        if (config.getBoolean("Consume")) {
            val inventory = dispenser.inventory
            val slots = inventory.contents.indices.filter { inventory.getItem(it)?.type == type }
            if (slots.isEmpty()) return
            val slot = slots.first()
            val item = inventory.getItem(slot) ?: return
            if (item.amount <= 1) {
                inventory.setItem(slot, null)
            } else {
                item.amount -= 1
                inventory.setItem(slot, item)
            }
        }
        block.world.spawn(location, FallingBlock::class.java) { it.blockData = type.createBlockData() }
        event.isCancelled = true
    }
}
