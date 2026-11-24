package net.torosaba.core.module.teleportspectator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpectatorTargetSelectorTest {
    private data class Candidate(val name: String, val visible: Boolean)

    @Test
    fun `見えないプレイヤーは選ばれず後続の見えるプレイヤーが選ばれる`() {
        val hidden = Candidate("AdminVanish", visible = false)
        val shown = Candidate("AdminShown", visible = true)
        assertEquals(shown, SpectatorTargetSelector.selectVisible(listOf(hidden, shown)) { it.visible })
        assertNull(SpectatorTargetSelector.selectVisible(listOf(hidden)) { it.visible })
        assertNull(SpectatorTargetSelector.selectVisible(emptyList<Candidate>()) { it.visible })
    }
}
