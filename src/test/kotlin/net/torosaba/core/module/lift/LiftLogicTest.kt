package net.torosaba.core.module.lift

import net.torosaba.core.module.lift.ShaftScan.Cell
import net.torosaba.core.module.lift.ShaftScan.Column
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiftLogicTest {
    // ---- ブロック名の指定 (* の扱い) ----

    @Test
    fun `ワイルドカードはブロック名の全体と照らし合わせる`() {
        val floor = listOf("GLASS", "*_STAINED_GLASS")
        assertTrue(MaterialPatterns.matches("GLASS", floor))
        assertTrue(MaterialPatterns.matches("RED_STAINED_GLASS", floor))
        assertTrue(MaterialPatterns.matches("LIGHT_BLUE_STAINED_GLASS", floor))
        assertFalse(MaterialPatterns.matches("RED_STAINED_GLASS_PANE", floor))
        assertFalse(MaterialPatterns.matches("TINTED_GLASS", floor))

        val buttons = listOf("*BUTTON")
        assertTrue(MaterialPatterns.matches("STONE_BUTTON", buttons))
        assertTrue(MaterialPatterns.matches("POLISHED_BLACKSTONE_BUTTON", buttons))
        assertFalse(MaterialPatterns.matches("STONE_PRESSURE_PLATE", buttons))

        val signs = listOf("*WALL_SIGN")
        assertTrue(MaterialPatterns.matches("OAK_WALL_SIGN", signs))
        assertTrue(MaterialPatterns.matches("CRIMSON_WALL_SIGN", signs))
        assertFalse(MaterialPatterns.matches("OAK_SIGN", signs))
        assertFalse(MaterialPatterns.matches("OAK_WALL_HANGING_SIGN", signs))

        // 小文字で書いても大文字にして比べる
        assertTrue(MaterialPatterns.matches("GLASS", listOf("glass")))
    }

    // ---- 土台と階 ----

    @Test
    fun `つながった土台を集め、上限で打ち切る`() {
        val base = (0..2).flatMap { x -> (0..2).map { z -> Column(x, z) } }.toSet()
        assertEquals(base, ShaftScan.baseColumns(Column(1, 1), 16) { it in base })
        assertEquals(4, ShaftScan.baseColumns(Column(1, 1), 4) { it in base }.size)
        // 斜めはつながらない
        val diagonal = setOf(Column(0, 0), Column(1, 1))
        assertEquals(setOf(Column(0, 0)), ShaftScan.baseColumns(Column(0, 0), 16) { it in diagonal })
    }

    /** 土台 y=0 の 2x1 の昇降路。ガラスの床は 3, 8, 13、ボタンは 5, 10, 15 (立つ高さ 4, 9, 14) */
    private val columns = listOf(Column(0, 0), Column(1, 0))
    private val buttons = setOf(Triple(0, 5, 0), Triple(1, 10, 0), Triple(0, 15, 0))

    private fun cell(blocked: Set<Triple<Int, Int, Int>> = emptySet()) = { x: Int, y: Int, z: Int ->
        when (Triple(x, y, z)) {
            in blocked -> Cell.BLOCKED
            in buttons -> Cell.BUTTON
            else -> Cell.OPEN
        }
    }

    @Test
    fun `ボタンのある高さが下から順に階になる`() {
        val result = ShaftScan.floors(columns, 0, 384, 320, cell())
        assertEquals(listOf(5, 10, 15), result.buttonYs)
        assertTrue(result.blockages.isEmpty())
    }

    @Test
    fun `どこかの列がふさがっていれば、それより上の階は使わない`() {
        val result = ShaftScan.floors(columns, 0, 384, 320, cell(setOf(Triple(1, 12, 0))))
        assertEquals(listOf(5, 10), result.buttonYs)
        assertEquals(listOf(Triple(1, 12, 0)), result.blockages)
    }

    @Test
    fun `maxHeight より上とワールドの外は調べない`() {
        assertEquals(listOf(5, 10), ShaftScan.floors(columns, 0, 12, 320, cell()).buttonYs)
        assertEquals(listOf(5), ShaftScan.floors(columns, 0, 384, 10, cell()).buttonYs)
    }

    @Test
    fun `checkFloor で床がおかしい階から上は使わない`() {
        // 床の層 y=8 (2 階のボタン 10 の 2 つ下) が床に使えない
        val result = ShaftScan.floors(columns, 0, 384, 320, cell(), floorOk = { it != 8 })
        assertEquals(listOf(5, 15), result.buttonYs)
        val strict = ShaftScan.floors(listOf(Column(0, 0)), 0, 384, 320, cell(), floorOk = { it != 3 })
        assertEquals(emptyList(), strict.buttonYs)
    }

    // ---- 看板 ----

    @Test
    fun `名前だけの看板は階の名前として読む`() {
        val sign = LiftSign("Current Floor", "§1Dest", listOf("ロビー", "", "", ""))
        assertEquals("ロビー", sign.currentName)
        assertEquals(0, sign.destinationFloor)
        assertFalse(sign.isEmpty)
    }

    @Test
    fun `今の書式の看板を読む`() {
        val sign = LiftSign("現在の階", "§a行先", listOf("現在の階: 2§r", "2F 売り場§r", "§a行先: 3§r", "屋上§r"))
        assertEquals(2, sign.currentFloor)
        assertEquals(3, sign.destinationFloor)
        assertEquals("2F 売り場§r", sign.currentName)
        // 色コードが付け直されて §r が落ちていても読める
        val roundTrip = LiftSign("現在の階", "§a行先", listOf("現在の階: 2", "2F", "§a行先: 12", "屋上"))
        assertEquals(12, roundTrip.destinationFloor)
    }

    @Test
    fun `行先を書き込む`() {
        val sign = LiftSign("Current Floor", "§1Dest", listOf("", "", "", ""))
        assertTrue(sign.isEmpty)
        sign.setCurrentFloor(1)
        sign.setDestinationFloor(4)
        sign.setDestinationName("Top")
        assertEquals(listOf("Current Floor: 1§r", "", "§1Dest: 4§r", "Top§r"), sign.lines)
        assertEquals(1, sign.currentFloor)
        assertEquals(4, sign.destinationFloor)
    }

    @Test
    fun `古い書式の看板は今の書式に直す`() {
        val sign = LiftSign("Current Floor", "Dest", listOf("Current Floor", "3", "Dest: 1", "Lobby"))
        assertEquals(3, sign.currentFloor)
        assertEquals(1, sign.destinationFloor)
    }

    @Test
    fun `行先は今いる階を飛ばして端で回り込む`() {
        // 5 階建ての 2 階にいる
        assertEquals(3, FloorCycle.next(1, 2, 5, 1))
        assertEquals(1, FloorCycle.next(0, 2, 5, 1))
        assertEquals(1, FloorCycle.next(5, 2, 5, 1))
        assertEquals(1, FloorCycle.next(3, 2, 5, -1))
        assertEquals(5, FloorCycle.next(1, 2, 5, -1))
        // 1 階にいて上の端を越えたら 2 階へ、最上階にいて下の端を越えたらその下へ
        assertEquals(2, FloorCycle.next(5, 1, 5, 1))
        assertEquals(4, FloorCycle.next(1, 5, 5, -1))
        // 2 階建て
        assertEquals(2, FloorCycle.next(0, 1, 2, 1))
        assertEquals(1, FloorCycle.next(1, 2, 2, 1))
    }
}
