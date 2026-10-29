package net.torosaba.core.module.worldborder

import org.bukkit.configuration.file.YamlConfiguration
import java.util.logging.Logger
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BorderDataTest {
    private val eps = 1e-9

    @Test
    fun `四角の境界は端を含んで内側`() {
        val b = BorderData(0.0, 0.0, 100, 50)
        assertTrue(b.insideBorder(0.0, 0.0, false))
        assertTrue(b.insideBorder(100.0, 50.0, false))
        assertTrue(b.insideBorder(-100.0, -50.0, false))
        assertFalse(b.insideBorder(100.1, 0.0, false))
        assertFalse(b.insideBorder(0.0, -50.1, false))
        // 角は四角なら内側、円なら外側
        assertTrue(b.insideBorder(99.0, 49.0, false))
        assertFalse(b.insideBorder(99.0, 49.0, true))
    }

    @Test
    fun `円の境界`() {
        val b = BorderData(10.0, -20.0, 100, 100)
        assertTrue(b.insideBorder(10.0, -20.0, true))
        assertTrue(b.insideBorder(10.0 + 99.9, -20.0, true))
        assertFalse(b.insideBorder(10.0 + 100.0, -20.0, true))
        // 45 度の向き: 半径 70.7 あたりが境目
        assertTrue(b.insideBorder(10.0 + 70.0, -20.0 + 70.0, true))
        assertFalse(b.insideBorder(10.0 + 71.0, -20.0 + 71.0, true))
    }

    @Test
    fun `楕円の境界`() {
        val b = BorderData(0.0, 0.0, 200, 100)
        assertTrue(b.insideBorder(199.0, 0.0, true))
        assertFalse(b.insideBorder(0.0, 101.0, true))
        assertTrue(b.insideBorder(0.0, 99.0, true))
        // (x/200)^2 + (z/100)^2 = 0.5^2 + 0.9^2 > 1
        assertFalse(b.insideBorder(100.0, 90.0, true))
        assertTrue(b.insideBorder(100.0, 80.0, true))
    }

    @Test
    fun `ワールドごとの形の指定が既定より優先される`() {
        val round = BorderData(0.0, 0.0, 100, 100, shapeRound = true)
        assertFalse(round.insideBorder(99.0, 99.0, false))
        val square = BorderData(0.0, 0.0, 100, 100, shapeRound = false)
        assertTrue(square.insideBorder(99.0, 99.0, true))
    }

    @Test
    fun `四角の押し戻し先は境界から knockback だけ内側`() {
        val b = BorderData(0.0, 0.0, 100, 100)
        val (x, z) = b.correctedXZ(110.0, 20.0, false, 3.0)
        assertEquals(97.0, x, eps)
        assertEquals(20.0, z, eps)
        val (x2, z2) = b.correctedXZ(-150.0, -101.0, false, 3.0)
        assertEquals(-97.0, x2, eps)
        assertEquals(-97.0, z2, eps)
        assertTrue(b.insideBorder(x2, z2, false))
    }

    @Test
    fun `円の押し戻し先は中心へ向かって半径 - knockback`() {
        val b = BorderData(50.0, 50.0, 100, 100)
        val (x, z) = b.correctedXZ(250.0, 50.0, true, 3.0)
        assertEquals(147.0, x, eps)
        assertEquals(50.0, z, eps)
        val (x2, z2) = b.correctedXZ(50.0 + 100.0, 50.0 + 100.0, true, 3.0)
        assertEquals(97.0, hypot(x2 - 50.0, z2 - 50.0), 1e-6)
        assertEquals(x2, z2, eps)
        assertTrue(b.insideBorder(x2, z2, true))
    }

    @Test
    fun `回り込みのときは反対側の端へ`() {
        val square = BorderData(0.0, 0.0, 100, 100, wrapping = true)
        val (x, z) = square.correctedXZ(101.0, 0.0, false, 3.0)
        assertEquals(-97.0, x, eps)
        assertEquals(0.0, z, eps)
        val round = BorderData(0.0, 0.0, 100, 100, wrapping = true)
        val (x2, z2) = round.correctedXZ(0.0, -120.0, true, 3.0)
        assertEquals(0.0, x2, eps)
        assertEquals(97.0, z2, eps)
    }

    @Test
    fun `2 つの角から境界を作る`() {
        val b = BorderData.fromCorners(-100.0, 300.0, 100.0, -100.0, null, false)
        assertEquals(0.0, b.x, eps)
        assertEquals(100.0, b.z, eps)
        assertEquals(100, b.radiusX)
        assertEquals(200, b.radiusZ)
    }

    @Test
    fun `元の WorldBorder の設定をそのまま読み書きできる`() {
        val yaml = """
            cfg-version: 12
            message: '&cYou have reached the edge of this world.'
            round-border: false
            knock-back-dist: 3.0
            timer-delay-ticks: 5
            dynmap-border-layer-label: WorldBorder
            deny-enderpearl: true
            fill-autosave-frequency: 30
            no-players-toggle: false
            bypass-list-uuids:
            - 069a79f4-44e9-4726-a5be-fca90e38aaf5
            worlds:
              world:
                x: 0.5
                z: -10.0
                radiusX: 5000
                radiusZ: 4000
                wrapping: false
              my<world:
                radius: 300
                shape-round: true
        """.trimIndent()
        val cfg = YamlConfiguration().apply { loadFromString(yaml) }
        val settings = BorderSettings().apply { load(cfg, Logger.getAnonymousLogger()) }

        assertFalse(settings.shapeRound)
        assertEquals(BorderData(0.5, -10.0, 5000, 4000, null, false), settings.borders["world"])
        // "<" は "." に戻し、古い radius のキーも読む
        assertEquals(BorderData(0.0, 0.0, 300, 300, true, false), settings.borders["my.world"])
        assertEquals(1, settings.bypass.size)
        assertEquals("§cYou have reached the edge of this world.", settings.messageFormatted)

        settings.borders.remove("world")
        settings.writeTo(cfg)
        val saved = YamlConfiguration().apply { loadFromString(cfg.saveToString()) }
        assertNull(saved.getConfigurationSection("worlds.world"))
        assertEquals(300, saved.getInt("worlds.my<world.radiusX"))
        assertEquals(true, saved.get("worlds.my<world.shape-round"))
        // 使わないキーも消さない
        assertEquals(30, saved.getInt("fill-autosave-frequency"))
        assertTrue(saved.isSet("no-players-toggle"))
        assertEquals(12, saved.getInt("cfg-version"))
    }
}
