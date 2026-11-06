package net.torosaba.core.module.mapdirty

import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.StructureGrowEvent

/**
 * Bukkit のブロック変更イベントをチャンク単位で集める。Dynmap の render-triggers に相当する集合。
 * MONITOR で見るだけ (変更しない) ので、ほかのプラグインが止めた変更は数えない。
 * ハンドラでは集合に足すだけで、ファイルへの書き出しはしない。
 * BlockPhysicsEvent は回数が桁違いに多く、地図の見た目もほぼ変わらないので入れない。
 */
class BukkitDirtyListener(private val window: DirtyWindow) : Listener {

    private fun add(block: Block) {
        window.add(block.world.name, block.x, block.z, DirtySource.BUKKIT)
    }

    private fun add(worldName: String, blockX: Int, blockZ: Int) {
        window.add(worldName, blockX, blockZ, DirtySource.BUKKIT)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        add(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        add(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        add(event.block)
        event.blockList().forEach { add(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().forEach { add(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        add(event.block)
        val direction = event.direction
        for (moved in event.blocks) {
            add(moved)
            // 動いた先のチャンクも変わる
            add(moved.world.name, moved.x + direction.modX, moved.z + direction.modZ)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        add(event.block)
        val direction = event.direction
        for (moved in event.blocks) {
            add(moved)
            add(moved.world.name, moved.x + direction.modX, moved.z + direction.modZ)
        }
    }

    /** 液体の流れ (BlockFromToEvent は流れた先が変わる) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        add(event.block)
        add(event.toBlock)
    }

    /** 雪や氷ができる、コンクリートが固まるなど */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        add(event.block)
    }

    /** 作物やサボテンなどの成長 (苗木の木化は StructureGrowEvent のほう) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        add(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        event.blocks.forEach { state ->
            val loc = state.location
            add(state.world.name, loc.blockX, loc.blockZ)
        }
    }

    /** 草や菌糸、炎などの広がり */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        add(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        add(event.block)
    }

    /** 雪や氷が溶ける、耕地が乾くなど */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        add(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        add(event.block)
    }

    /** エンダーマンがブロックを持つ、落下ブロックが着地する、羊が草を食べるなど */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        add(event.block)
    }
}
