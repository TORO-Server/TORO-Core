package net.torosaba.core.module.textbeacon

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextBeaconTest {
    @Test
    fun colorCodes() {
        assertEquals("§aHello §lWorld", colorize("&aHello &LWorld"))
        assertEquals("&zなし&", colorize("&zなし&"))
        assertEquals("§x§5§5§f§f§f§fRGB", colorize("&#55FFffRGB"))
        assertEquals("", colorize(null))
    }

    @Test
    fun messageFromSign() {
        assertEquals("§a1行目\n3行目", buildMessage(listOf("[TextBeacon]", "&a1行目", "  ", "3行目")))
        assertEquals("", buildMessage(listOf("[TextBeacon]")))
    }

    @Test
    fun splitForSign() {
        assertEquals(listOf("§aA", "B", ""), splitMessageForSign("&aA\\nB"))
        assertEquals(listOf("1", "2", "3"), splitMessageForSign("1\\n2\\n3\\n4"))
    }

    @Test
    fun signLineKeepsHexColors() {
        val s = colorize("&#123ABCあい")
        assertEquals("§x§1§2§3§a§b§cあい", s)
        assertEquals(s, signText.serialize(signText.deserialize(s)))
    }

    @Test
    fun packIsUnique() {
        assertNotEquals(pack(0, -1, 0), pack(0, 1, 0))
        assertNotEquals(pack(-1, 64, 0), pack(1, 64, 0))
        assertNotEquals(pack(0, 64, -1), pack(0, 64, 1))
        assertEquals(pack(10, -64, -20), pack(10, -64, -20))
    }

    @Test
    fun connectedGlass() {
        // 3x1x1 の棒と、離れた 1 ブロック
        val glass = setOf(Triple(0, 0, 0), Triple(1, 0, 0), Triple(2, 0, 0), Triple(5, 0, 0))
        val found = assertNotNull(collectConnected(intArrayOf(0, 0, 0)) { x, y, z -> Triple(x, y, z) in glass })
        assertEquals(3, found.size)
        assertTrue(isWithinCube(found))

        // 7 ブロックの棒は大きすぎる (6 までは通る)
        val six = collectConnected(intArrayOf(0, 0, 0)) { x, y, z -> y == 0 && z == 0 && x in 0..5 }
        assertEquals(6, six?.size)
        assertNull(collectConnected(intArrayOf(0, 0, 0)) { x, y, z -> y == 0 && z == 0 && x in 0..6 })
    }

    @Test
    fun registryRoundTrip() {
        val reg = BeaconRegistry()
        val id = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val b = Beacon(id, BlockKey("world", 1, 64, -3), "§aやあ\n2行目", owner, "Steve")
        b.blocks += BlockKey("world", 2, 64, -3)
        b.blocks += BlockKey("world", 2, 65, -3)
        reg.add(b)
        reg.muted += owner

        assertEquals(id, reg.trigger("world", 2, 65, -3))
        assertNull(reg.trigger("world_nether", 2, 65, -3))

        val text = reg.save().saveToString()
        val loaded = BeaconRegistry()
        val warnings = mutableListOf<String>()
        loaded.load(YamlConfiguration().apply { loadFromString(text) }) { warnings += it }
        assertTrue(warnings.isEmpty())
        val l = assertNotNull(loaded.beacons[id])
        assertEquals("§aやあ\n2行目", l.message)
        assertEquals(owner, l.owner)
        assertEquals("Steve", l.ownerName)
        assertEquals(b.sign, l.sign)
        assertEquals(b.blocks, l.blocks)
        assertEquals(id, loaded.signs[b.sign])
        assertTrue(owner in loaded.muted)

        assertNotNull(loaded.remove(id))
        assertNull(loaded.trigger("world", 2, 64, -3))
        assertTrue(loaded.signs.isEmpty())
    }

    @Test
    fun loadsOriginalDataFormat() {
        val yaml = YamlConfiguration()
        yaml.loadFromString(
            """
            settings:
              mutedPlayers:
              - not-a-uuid
            beacons:
              0b6f7a52-6d1c-4f4e-9d7a-1f0c2d3e4f50:
                world: world
                msg: hi
                sign: {x: 0, y: 70, z: 0}
                blocks:
                - 1,70,0
                - bad
              broken-id:
                world: world
            """.trimIndent()
        )
        val reg = BeaconRegistry()
        val warnings = mutableListOf<String>()
        reg.load(yaml) { warnings += it }
        assertEquals(1, reg.beacons.size)
        val b = reg.beacons.values.single()
        assertNull(b.owner)
        assertEquals(listOf(BlockKey("world", 1, 70, 0)), b.blocks)
        assertEquals(3, warnings.size)
    }
}
