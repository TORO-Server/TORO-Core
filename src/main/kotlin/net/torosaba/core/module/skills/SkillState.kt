package net.torosaba.core.module.skills

import org.bukkit.entity.Item

/** 選べるクラス。何も選んでいないプレイヤーは状態を持たない */
enum class Skill {
    ACROBAT, SCOUT, GUARDIAN, BLINK, BLAST, TELEPORT, DASH
}

internal const val MAX_ENERGY = 100.0
internal const val BLINK_COOLDOWN_MS = 2000L
internal const val BLAST_RECHARGE_MS = 4000L
internal const val TP_RECHARGE_MS = 3500L
internal const val DASH_REGEN_DELAY_MS = 3000L
internal const val DASH_SLIDE_RECHARGE_MS = 5000L

/** テレポートのビーコンが消えるまでの tick 数 */
internal const val TP_SESSION_LIFE = 600

/** プレイヤー 1 人分の能力の状態。クラスを選び直すと作り直す */
internal class SkillState(val skill: Skill) {
    // アクロバット・スカウト
    var acrobatCooling = false
    var combatTag: Long? = null

    // ブリンク
    var blinkCharge = true
    var blinkInAir = false
    var blinkLandedAt: Long? = null

    // ブラストパック
    var blastCharges = 2
    var blastLastThrow = 0L
    var blastInAir = false
    var blastLandedAt: Long? = null
    var blastPack: Item? = null

    // テレポート
    var tpSession: Gatecrash? = null
    var tpCharge = true
    var tpCooldownFrom: Long? = null

    // ダッシュ
    var dashEnergy = MAX_ENERGY
    var dashRunning = false
    var dashSlideCharge = true
    var dashSlideFrom: Long? = null
    var dashEmptyAt: Long? = null
}
