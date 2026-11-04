package net.torosaba.core.module.skills

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillItemRuleTest {
    private fun blocks(
        clickedTop: Boolean = false,
        shiftToTop: Boolean = false,
        cursor: Boolean = false,
        current: Boolean = false,
        swapped: Boolean = false,
        cursorIsBundle: Boolean = false,
        currentIsBundle: Boolean = false,
    ) = SkillItemRule.blocksClick(clickedTop, shiftToTop, cursor, current, swapped, cursorIsBundle, currentIsBundle)

    @Test
    fun movingIntoTheTopInventoryIsBlocked() {
        assertTrue(blocks(clickedTop = true, cursor = true))
        assertTrue(blocks(clickedTop = true, swapped = true))
        assertTrue(blocks(shiftToTop = true, current = true))
    }

    @Test
    fun bundlesAreBlocked() {
        assertTrue(blocks(cursor = true, currentIsBundle = true))
        assertTrue(blocks(current = true, cursorIsBundle = true))
    }

    @Test
    fun otherClicksAreAllowed() {
        // 自分のインベントリの中で動かす
        assertFalse(blocks(cursor = true))
        assertFalse(blocks(current = true))
        // ほかのアイテムを容器へ移す
        assertFalse(blocks(clickedTop = true))
        assertFalse(blocks(shiftToTop = true))
        // 上の欄にあるものを取る
        assertFalse(blocks(clickedTop = true, current = true))
    }
}
