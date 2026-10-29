package net.torosaba.core.module.dynmapworldguard

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegionMapSettingsTest {
    private fun settings(yaml: String) = RegionMapSettings(YamlConfiguration().apply { loadFromString(yaml) })

    @Test
    fun `本番の設定を読める`() {
        val s = settings(
            """
            update:
              period: 300
            layer:
              name: WorldGuard
              hidebydefault: true
              layerprio: 0
              minzoom: 0
            use3dregions: false
            regionstyle:
              strokeColor: '#FF0000'
              strokeOpacity: 0.8
              strokeWeight: 3
              fillColor: '#FF0000'
              fillOpacity: 0.35
              unownedStrokeColor: '#00FF00'
            visibleregions: []
            hiddenregions: []
            ownerstyle:
              kingoftheworld:
                strokecolor: '#C0C0C0'
            maxdepth: 16
            updates-per-tick: 20
            """.trimIndent(),
        )
        assertEquals(6000L, s.periodTicks)
        assertTrue(s.hideByDefault)
        assertEquals(0, s.layerPriority)
        assertEquals(AreaStyle.DEFAULT, s.defaultStyle)
        // キーの大文字小文字が違う (strokecolor) ので、元のプラグインと同じく既定の色のまま
        assertEquals(0xFF0000, s.styleFor(RegionKey("home", "world", ownerNames = listOf("KingOfTheWorld"))).strokeRgb(false))
    }

    @Test
    fun `間隔は 15 秒より短くしない`() {
        assertEquals(15 * 20L, settings("update:\n  period: 1").periodTicks)
    }

    @Test
    fun `見た目は 領域 ワイルドカード 持ち主 既定 の順に決まる`() {
        val s = settings(
            """
            regionstyle:
              fillColor: '#0000FF'
            custstyle:
              world_nether/spawn:
                strokeColor: '#111111'
              spawn:
                strokeColor: '#222222'
                label: Spawn Area
              'shop|_a':
                strokeColor: '#333333'
            ownerstyle:
              Alice:
                strokeColor: '#444444'
              admins:
                strokeColor: '#555555'
            """.trimIndent(),
        )
        assertEquals(0x111111, s.styleFor(RegionKey("spawn", "world_nether")).strokeRgb(false))
        val spawn = s.styleFor(RegionKey("spawn", "world"))
        assertEquals(0x222222, spawn.strokeRgb(false))
        assertEquals("Spawn Area", spawn.label)
        // 上書きしていない値は regionstyle から
        assertEquals(0x0000FF, spawn.fillRgb())
        assertEquals(0x333333, s.styleFor(RegionKey("shop1_a", "world")).strokeRgb(false))
        assertEquals(0xFF0000, s.styleFor(RegionKey("shop1_b", "world")).strokeRgb(false))
        assertEquals(0x444444, s.styleFor(RegionKey("house", "world", ownerNames = listOf("alice"))).strokeRgb(false))
        assertEquals(0x555555, s.styleFor(RegionKey("house", "world", ownerGroups = listOf("Admins"))).strokeRgb(false))
        assertEquals(0x00FF00, s.styleFor(RegionKey("house", "world")).strokeRgb(true))
    }

    @Test
    fun `描く領域を絞る`() {
        val s = settings("visibleregions: ['world:world', 'world_nether/fort']\nhiddenregions: ['secret']")
        assertTrue(s.isVisible(RegionKey("anything", "world")))
        assertTrue(s.isVisible(RegionKey("fort", "world_nether")))
        assertFalse(s.isVisible(RegionKey("other", "world_nether")))
        assertFalse(s.isVisible(RegionKey("secret", "world")))
        assertTrue(settings("").isVisible(RegionKey("x", "world")))
    }

    @Test
    fun `吹き出しに値を入れる`() {
        val s = settings("infowindow: '%regionname% (%parent%) - %priority% / %playerowners% / %flags%'")
        val html = s.formatInfoWindow(
            RegionInfo("Spawn", "alice, bob", "", "", "", null, 5, listOf("pvp" to "DENY", "build" to "ALLOW")),
        )
        assertEquals("<div class=\"regioninfo\">Spawn () - 5 / alice, bob / pvp: DENY<br/>build: ALLOW<br/></div>", html)
    }

    @Test
    fun `地図に出す名前は先頭を大文字にする`() {
        assertEquals("Spawn", RegionMapSettings.labelOf("spawn"))
        assertEquals("1st", RegionMapSettings.labelOf("1st"))
    }

    @Test
    fun `読めない色は赤にする`() {
        assertEquals(0xFF0000, AreaStyle.parseColor("red"))
        assertEquals(0x00FF00, AreaStyle.parseColor("#00FF00"))
    }
}
