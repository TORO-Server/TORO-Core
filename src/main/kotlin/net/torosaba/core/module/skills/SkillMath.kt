package net.torosaba.core.module.skills

import org.bukkit.util.Vector
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** [start] から [totalMs] 待つときの残り秒数 (0 未満にはしない) */
internal fun remainingSeconds(totalMs: Long, start: Long, now: Long): Double =
    max(0.0, (totalMs - (now - start)) / 1000.0)

private fun sec(value: Double) = String.format(Locale.ROOT, "%.1f", value)

/** 水平方向の単位ベクトル。真上・真下を向いているときは 0 ベクトル */
internal fun horizontal(direction: Vector): Vector {
    val v = direction.clone().setY(0)
    return if (v.lengthSquared() == 0.0) v else v.normalize()
}

/** スライディングの速さの倍率 (12 tick で 0 になる) */
internal fun slideFactor(ticks: Int): Double = 1.0 - (ticks.toDouble() / 12).pow(2)

/** グラップリングフックで引っ張る速度 */
internal fun grapplePull(player: Vector, hook: Vector, velocity: Vector): Vector {
    val speed = min(player.distance(hook) * 0.3, 20.0)
    val pull = hook.clone().subtract(player)
    val dy = hook.y - player.y
    pull.setY(if (dy > 0) dy * 0.2 else 0.4)
    val h = sqrt(pull.x * pull.x + pull.z * pull.z)
    if (h > 0) {
        pull.setX(pull.x / h * speed)
        pull.setZ(pull.z / h * speed)
    }
    return pull.add(velocity.clone().multiply(0.2))
}

/** ブラストパックの爆風で飛ばす速度。範囲 (5 ブロック) の外なら null */
internal fun blastKnockback(center: Vector, target: Vector, look: Vector, targetInAir: Boolean, powerBase: Double): Vector? {
    val dist = target.distance(center)
    if (dist > 5) return null
    val away = target.clone().subtract(center).let { if (it.lengthSquared() == 0.0) it else it.normalize() }
    val lookDir = look.clone().normalize()
    val dir = if (targetInAir) lookDir else away.multiply(0.8).add(lookDir.clone().multiply(0.2)).normalize()
    val v = dir.multiply(powerBase * (1.0 - dist / 5.0))
    v.setY(v.y * 0.35)
    return v
}

// ---- アクションバー ----

internal fun blinkBar(s: SkillState, now: Long): String {
    val landed = s.blinkLandedAt
    val cd = if (!s.blinkCharge && landed != null) " §c[${sec(remainingSeconds(BLINK_COOLDOWN_MS, landed, now))}s]" else ""
    return "§b§l[Blink] " + (if (s.blinkCharge) "§a■" else "§7■") + cd
}

internal fun blastBar(s: SkillState, now: Long): String {
    val boxes = when (s.blastCharges) {
        2 -> "§a■■"
        1 -> "§a■§7■"
        else -> "§7■■"
    }
    val cd = s.blastLandedAt?.let { " §c[${sec(remainingSeconds(BLAST_RECHARGE_MS, it, now))}s]" } ?: ""
    return "§e§l[Blast] $boxes$cd"
}

internal fun teleportBar(s: SkillState, now: Long, sessionLife: Int?): String {
    val sb = StringBuilder("§9§l[Teleport] ").append(if (s.tpCharge) "§a■" else "§7■")
    val from = s.tpCooldownFrom
    if (sessionLife != null) {
        sb.append(" §e[").append(sec((TP_SESSION_LIFE - sessionLife) / 20.0)).append("s]")
    } else if (!s.tpCharge && from != null) {
        sb.append(" §c[").append(sec(remainingSeconds(TP_RECHARGE_MS, from, now))).append("s]")
    }
    return sb.toString()
}

internal fun dashBar(s: SkillState, now: Long): String {
    val sb = StringBuilder(if (s.dashRunning) "§b⚡ Running: " else "§6⚡ Energy: ")
    sb.append("§e${s.dashEnergy.toInt()}%")
    s.dashEmptyAt?.let {
        val rem = (DASH_REGEN_DELAY_MS - (now - it)) / 1000.0
        if (rem > 0) sb.append(" §c[COOLDOWN ${sec(rem)}s]")
    }
    sb.append(" §7| §bSlide: ")
    val slide = s.dashSlideFrom
    if (slide != null) sb.append("§c[${sec(remainingSeconds(DASH_SLIDE_RECHARGE_MS, slide, now))}s]")
    else sb.append(if (s.dashSlideCharge) "§a■" else "§7■")
    return sb.toString()
}
