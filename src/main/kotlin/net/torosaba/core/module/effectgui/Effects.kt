package net.torosaba.core.module.effectgui

import org.bukkit.Color
import org.bukkit.potion.PotionEffectType

/**
 * GUI に並べるエフェクト。並び順がそのままスロット番号になる。
 * [key] は config.yml の lang のキー (元のプラグインの古い PotionEffectType 名)。
 */
internal class Effect(
    val type: PotionEffectType,
    val key: String,
    val name: String,
    val lore: String,
    val color: Color,
    val good: Boolean,
)

private fun bgr(b: Int, g: Int, r: Int) = Color.fromBGR(b, g, r)

internal val effects = listOf(
    Effect(PotionEffectType.SPEED, "SPEED", "移動速度上昇", "移動速度が上昇する", bgr(202, 178, 126), true),
    Effect(PotionEffectType.SLOWNESS, "SLOW", "移動速度低下", "移動速度が低下する", bgr(131, 110, 92), false),
    Effect(PotionEffectType.HASTE, "FAST_DIGGING", "採掘速度上昇", "採掘速度と攻撃速度が上昇する", bgr(67, 192, 217), true),
    Effect(PotionEffectType.MINING_FATIGUE, "SLOW_DIGGING", "採掘速度低下", "採掘速度と攻撃速度が低下する", bgr(23, 66, 74), false),
    Effect(PotionEffectType.STRENGTH, "INCREASE_DAMAGE", "攻撃力上昇", "近接攻撃の威力が上昇する", bgr(36, 37, 150), true),
    Effect(PotionEffectType.INSTANT_HEALTH, "HEAL", "即時回復", "エンティティを回復し、アンデッドにはダメージを与える", bgr(36, 37, 252), true),
    Effect(PotionEffectType.INSTANT_DAMAGE, "HARM", "即時ダメージ", "エンティティにダメージを与え、アンデッドは回復する", bgr(9, 10, 68), false),
    Effect(PotionEffectType.JUMP_BOOST, "JUMP", "跳躍力上昇", "ジャンプの高さが上昇し落下ダメージが減少する", bgr(77, 252, 35), true),
    Effect(PotionEffectType.NAUSEA, "CONFUSION", "吐き気", "画面がぐらぐらとゆがむ", bgr(74, 29, 85), false),
    Effect(PotionEffectType.REGENERATION, "REGENERATION", "再生能力", "時間とともに体力が回復する", bgr(174, 94, 209), true),
    Effect(PotionEffectType.RESISTANCE, "DAMAGE_RESISTANCE", "耐性", "ほとんどのダメージが減少する", bgr(58, 69, 153), true),
    Effect(PotionEffectType.FIRE_RESISTANCE, "FIRE_RESISTANCE", "火炎耐性", "炎などに耐性を得る", bgr(59, 157, 232), true),
    Effect(PotionEffectType.WATER_BREATHING, "WATER_BREATHING", "水中呼吸", "溺れなくなる", bgr(156, 84, 47), true),
    Effect(PotionEffectType.INVISIBILITY, "INVISIBILITY", "透明化", "透明になる", bgr(149, 133, 129), true),
    Effect(PotionEffectType.BLINDNESS, "BLINDNESS", "盲目", "視界が黒い霧に包まれ、ダッシュやクリティカルヒットができなくなる", bgr(35, 31, 31), false),
    Effect(PotionEffectType.NIGHT_VISION, "NIGHT_VISION", "暗視", "暗闇でも明るく見えるようになる", bgr(164, 32, 32), true),
    Effect(PotionEffectType.HUNGER, "HUNGER", "空腹", "満腹度消耗値が上昇する", bgr(83, 118, 88), false),
    Effect(PotionEffectType.WEAKNESS, "WEAKNESS", "弱体化", "近接攻撃の威力が低下する", bgr(73, 78, 73), false),
    Effect(PotionEffectType.POISON, "POISON", "毒", "時間とともにダメージを受ける（ただし死なない）", bgr(50, 150, 79), false),
    Effect(PotionEffectType.WITHER, "WITHER", "衰弱", "時間とともにダメージを受ける（死に至る）", bgr(39, 42, 53), false),
    Effect(PotionEffectType.HEALTH_BOOST, "HEALTH_BOOST", "体力増強", "体力の最大値が増加する", bgr(35, 125, 248), true),
    Effect(PotionEffectType.ABSORPTION, "ABSORPTION", "衝撃吸収", "使い捨ての体力が一時的に増加する", bgr(165, 82, 37), true),
    Effect(PotionEffectType.SATURATION, "SATURATION", "満腹度回復", "満腹値と隠し満腹値が上昇する", bgr(35, 36, 248), true),
    Effect(PotionEffectType.GLOWING, "GLOWING", "発光", "エンティティが縁取られ、ブロックを通り越して視認できる", bgr(97, 160, 148), false),
    Effect(PotionEffectType.LEVITATION, "LEVITATION", "浮遊", "エンティティが浮かび上がる", bgr(255, 255, 206), false),
    Effect(PotionEffectType.LUCK, "LUCK", "幸運", "釣りでレアなお宝が出現しやすくなる", bgr(0, 156, 52), true),
    Effect(PotionEffectType.UNLUCK, "UNLUCK", "不運", "釣りでレアなお宝が出現しにくくなる", bgr(77, 164, 192), false),
    Effect(PotionEffectType.SLOW_FALLING, "SLOW_FALLING", "落下速度低下", "落下速度を低下させ、落下ダメージを無効化する", bgr(224, 248, 247), true),
    Effect(PotionEffectType.CONDUIT_POWER, "CONDUIT_POWER", "コンジットパワー", "水中での暗視効果、採掘速度の上昇効果を与え、溺れなくなる", bgr(209, 194, 29), true),
    Effect(PotionEffectType.DOLPHINS_GRACE, "DOLPHINS_GRACE", "イルカの好意", "泳ぐ速度が上昇する", bgr(190, 163, 136), true),
    Effect(PotionEffectType.BAD_OMEN, "BAD_OMEN", "不吉な予感", "村に入ると襲撃が開始される", bgr(56, 97, 11), false),
    Effect(PotionEffectType.HERO_OF_THE_VILLAGE, "HERO_OF_THE_VILLAGE", "村の英雄", "村人の交易が値引きされる", bgr(68, 255, 68), true),
)

/** 時間の選択肢 (tick)。最後の Int.MAX_VALUE は無限 */
internal val timeList = listOf(200, 400, 600, 800, 1000, 1200, 2400, 3600, 4800, 6000, 12000, 18000, 24000, 36000, 48000, 60000, 72000, Int.MAX_VALUE)
internal val timeText = listOf("10秒", "20秒", "30秒", "40秒", "50秒", "1分", "2分", "3分", "4分", "5分", "10分", "15分", "20分", "30分", "40分", "50分", "1時間", "無限")

/** レベルの選択肢 (amplifier。表示は +1) */
internal val levelList = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 19, 29, 39, 49, 59, 69, 79, 89)
