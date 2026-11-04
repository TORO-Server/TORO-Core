package net.torosaba.core.module.inventorybackup

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreCauseTest {
    private val id = UUID.randomUUID()

    @Test
    fun clearCommands() {
        assertEquals("clear", RestoreCause.clearCommandLabel("/clear"))
        assertEquals("clear", RestoreCause.clearCommandLabel("clear @s"))
        assertEquals("minecraft:clear", RestoreCause.clearCommandLabel("/minecraft:clear Steve"))
        assertEquals("ci", RestoreCause.clearCommandLabel("/CI Steve"))
        assertEquals("essentials:clearinventory", RestoreCause.clearCommandLabel("/essentials:clearinventory"))
        assertEquals("clear", RestoreCause.clearCommandLabel("execute as @a run clear @s"))

        assertNull(RestoreCause.clearCommandLabel("/tell Steve clear"))
        assertNull(RestoreCause.clearCommandLabel("/clearlag"))
        assertNull(RestoreCause.clearCommandLabel("/execute as @a run say clear"))
        assertNull(RestoreCause.clearCommandLabel(""))
    }

    @Test
    fun deathOnlyCountsWhenNothingWasDropped() {
        assertTrue(RestoreCause.isDeathLoss(keepInventory = false, droppedItems = false))
        // 地面に落ちたアイテムは失われていないので案内しない
        assertFalse(RestoreCause.isDeathLoss(keepInventory = false, droppedItems = true))
        // 持ち物を失っていない
        assertFalse(RestoreCause.isDeathLoss(keepInventory = true, droppedItems = false))
    }

    @Test
    fun creativeClearIsOfferedWithTheFirstSnapshot() {
        val tracker = CreativeClearTracker<String>()
        var calls = 0
        tracker.onChange(id) { calls++; "before" }
        tracker.onChange(id) { calls++; "after one slot" }
        assertEquals(1, calls)
        assertEquals("before", tracker.onClose(id, cleared = true))
        // 案内は 1 回だけ
        assertNull(tracker.onClose(id, cleared = true))
    }

    @Test
    fun itemsMovedOutAreNotOffered() {
        val tracker = CreativeClearTracker<String>()
        tracker.onChange(id) { "before" }
        tracker.onMovedOut(id)
        assertNull(tracker.onClose(id, cleared = true))
    }

    @Test
    fun notClearedIsNotOffered() {
        val tracker = CreativeClearTracker<String>()
        tracker.onChange(id) { "before" }
        assertNull(tracker.onClose(id, cleared = false))
    }

    @Test
    fun closingWithoutCreativeChangesIsNotOffered() {
        // サバイバルで空になったインベントリを閉じただけでは案内しない
        val tracker = CreativeClearTracker<String>()
        tracker.onMovedOut(id)
        assertNull(tracker.onClose(id, cleared = true))
    }

    @Test
    fun movedOutBeforeTheFirstChangeDoesNotCarryOver() {
        val tracker = CreativeClearTracker<String>()
        tracker.onMovedOut(id)
        tracker.onChange(id) { "before" }
        assertEquals("before", tracker.onClose(id, cleared = true))
    }
}
