package net.torosaba.core.module.skills

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.util.Vector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillMathTest {
    @Test
    fun remainingSecondsNeverNegative() {
        assertEquals(1.5, remainingSeconds(2000, 1000, 1500))
        assertEquals(0.0, remainingSeconds(2000, 1000, 9000))
    }

    @Test
    fun horizontalIgnoresPitch() {
        val h = horizontal(Vector(3.0, 5.0, 4.0))
        assertEquals(0.0, h.y)
        assertEquals(1.0, h.length(), 1e-9)
        // 真上を向いていても NaN にならない
        val up = horizontal(Vector(0.0, 1.0, 0.0))
        assertEquals(0.0, up.length())
    }

    @Test
    fun slideSlowsDownToZero() {
        assertEquals(1.0, slideFactor(0))
        assertEquals(0.75, slideFactor(6))
        assertEquals(0.0, slideFactor(12))
    }

    @Test
    fun grapplePullsTowardsHook() {
        val v = grapplePull(Vector(0, 64, 0), Vector(10, 64, 0), Vector(0, 0, 0))
        assertEquals(3.0, v.x, 1e-9) // 距離 10 × 0.3
        assertEquals(0.0, v.z, 1e-9)
        assertEquals(0.4, v.y, 1e-9) // 同じ高さなら少し浮かせる
        val up = grapplePull(Vector(0, 64, 0), Vector(0, 74, 0), Vector(0, 0, 0))
        assertEquals(2.0, up.y, 1e-9)
        // 速さは 20 まで
        val far = grapplePull(Vector(0, 0, 0), Vector(1000, 0, 0), Vector(0, 0, 0))
        assertEquals(20.0, far.x, 1e-9)
    }

    @Test
    fun blastKnockbackRange() {
        val center = Vector(0, 64, 0)
        assertNull(blastKnockback(center, Vector(6, 64, 0), Vector(1, 0, 0), false, 1.35))
        val v = assertNotNull(blastKnockback(center, Vector(2, 64, 0), Vector(1, 0, 0), false, 1.35))
        assertEquals(1.35 * 0.6, v.x, 1e-9)
        // 爆心にいても例外にならない
        val zero = assertNotNull(blastKnockback(center, center.clone(), Vector(0, 0, 1), false, 2.0))
        assertTrue(zero.z.isFinite() && zero.z > 0)
        // 空中にいる人は視線方向へ飛ぶ
        val air = assertNotNull(blastKnockback(center, Vector(1, 64, 0), Vector(0, 1, 0), true, 2.0))
        assertEquals(0.0, air.x, 1e-9)
        assertEquals(2.0 * 0.8 * 0.35, air.y, 1e-9)
    }

    @Test
    fun actionBars() {
        val s = SkillState(Skill.BLINK)
        assertEquals("§b§l[Blink] §a■", blinkBar(s, 0))
        s.blinkCharge = false
        s.blinkLandedAt = 1000
        assertEquals("§b§l[Blink] §7■ §c[1.5s]", blinkBar(s, 1500))

        val b = SkillState(Skill.BLAST)
        b.blastCharges = 1
        b.blastLandedAt = 0
        assertEquals("§e§l[Blast] §a■§7■ §c[3.0s]", blastBar(b, 1000))

        val t = SkillState(Skill.TELEPORT)
        t.tpCharge = false
        assertEquals("§9§l[Teleport] §7■ §e[29.5s]", teleportBar(t, 0, 10))
        t.tpCooldownFrom = 0
        assertEquals("§9§l[Teleport] §7■ §c[2.5s]", teleportBar(t, 1000, null))

        val d = SkillState(Skill.DASH)
        d.dashEnergy = 42.7
        d.dashEmptyAt = 0
        d.dashSlideFrom = 0
        assertEquals("§6⚡ Energy: §e42% §c[COOLDOWN 2.0s] §7| §bSlide: §c[4.0s]", dashBar(d, 1000))
        d.dashRunning = true
        d.dashEmptyAt = null
        d.dashSlideFrom = null
        assertEquals("§b⚡ Running: §e42% §7| §bSlide: §a■", dashBar(d, 1000))
    }

    @Test
    fun grappleNameMatchesLegacyItems() {
        // 元のプラグインで名前を付けたアイテム (斜体なし・水色) と同じ文字列になる
        val old = Component.text("グラップリングフック", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
        assertEquals(SkillsModule.GRAPPLE_NAME, section.serialize(old))
        assertEquals(SkillsModule.GRAPPLE_NAME, section.serialize(SkillsModule.GRAPPLE_NAME.itemText()))
        assertEquals(TextDecoration.State.FALSE, SkillsModule.GRAPPLE_NAME.itemText().decoration(TextDecoration.ITALIC))
    }
}
