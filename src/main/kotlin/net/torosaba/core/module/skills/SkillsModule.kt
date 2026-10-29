package net.torosaba.core.module.skills

import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 旧 Skills。/skill で移動系の能力 (クラス) を選ぶ */
class SkillsModule : Module("skills", "Skills") {
    internal val states = HashMap<UUID, SkillState>()
    private lateinit var grappleKey: NamespacedKey

    override fun onEnable() {
        states.clear()
        grappleKey = NamespacedKey(plugin, "grappling_hook")
        registerGrappleRecipe()
        listen(SkillListener(this))
        command("skill", description = "スキルを選択", usage = "/skill") { sender, _, _ ->
            if (sender is Player) sender.openInventory(ClassMenu().inventory)
            true
        }
        runTimer(0L, 1L) { tick() }
    }

    override fun onDisable() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.openInventory.topInventory.holder is ClassMenu) player.closeInventory()
            // 停止後に体力や効果が残らないよう、能力を持っている人は元に戻す
            if (player.uniqueId in states) resetPlayer(player)
        }
        states.values.forEach { discard(it) }
        states.clear()
        Bukkit.removeRecipe(grappleKey)
    }

    /** 毎 tick 実行する。[block] が false を返したら止める */
    private fun everyTick(block: () -> Boolean) {
        lateinit var task: BukkitTask
        task = runTimer(0L, 1L) { if (!block()) task.cancel() }
    }

    private fun recharged(player: Player) =
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f)

    private fun actionBar(player: Player, text: String) = player.sendActionBar(section.deserialize(text))

    private fun isGrounded(player: Player) = player.location.subtract(0.0, 0.1, 0.0).block.type.isSolid

    // ---- 毎 tick の更新 ----

    private fun tick() {
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            val s = states[player.uniqueId] ?: continue
            val grounded = isGrounded(player)
            when (s.skill) {
                Skill.BLINK -> {
                    if (s.blinkInAir && grounded && !s.blinkCharge && s.blinkLandedAt == null) s.blinkLandedAt = now
                    s.blinkInAir = !grounded
                    val landed = s.blinkLandedAt
                    if (!s.blinkCharge && landed != null && now - landed >= BLINK_COOLDOWN_MS) {
                        s.blinkCharge = true
                        s.blinkLandedAt = null
                        recharged(player)
                    }
                    actionBar(player, blinkBar(s, now))
                }
                Skill.BLAST -> {
                    if (s.blastInAir && grounded && s.blastCharges < 2 && s.blastLandedAt == null) s.blastLandedAt = now
                    s.blastInAir = !grounded
                    val landed = s.blastLandedAt
                    if (landed != null && now - landed >= BLAST_RECHARGE_MS) {
                        s.blastCharges = 2
                        s.blastLandedAt = null
                        recharged(player)
                    }
                    actionBar(player, blastBar(s, now))
                }
                Skill.TELEPORT -> {
                    if (!s.tpCharge && s.tpSession == null) {
                        val from = s.tpCooldownFrom
                        if (from == null) {
                            s.tpCooldownFrom = now
                        } else if (now - from >= TP_RECHARGE_MS) {
                            s.tpCharge = true
                            s.tpCooldownFrom = null
                            recharged(player)
                        }
                    }
                    actionBar(player, teleportBar(s, now, s.tpSession?.life))
                }
                Skill.DASH -> tickDash(player, s, now)
                else -> {}
            }
        }
    }

    private fun tickDash(player: Player, s: SkillState, now: Long) {
        val vel = player.velocity
        val moving = hypot(vel.x, vel.z) > 0.05 || player.isSprinting
        if (s.dashRunning) {
            if (s.dashEnergy > 0) {
                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 5, 4, false, false, true))
                if (moving) {
                    s.dashEnergy = max(0.0, s.dashEnergy - 1.0)
                    if (player.ticksLived % 3 == 0) {
                        val at = player.location.add(0.0, 0.5, 0.0)
                        player.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 2, 0.2, 0.2, 0.2, 0.05)
                        player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 1, 0.2, 0.2, 0.2, 0.02)
                    }
                }
            }
            if (s.dashEnergy <= 0) stopDash(player, s, depleted = true)
        } else if (s.dashEnergy < MAX_ENERGY) {
            // 使い切った直後はしばらく回復しない
            val empty = s.dashEmptyAt
            val inDelay = empty != null && now - empty < DASH_REGEN_DELAY_MS
            if (empty != null && !inDelay) s.dashEmptyAt = null
            if (!inDelay) s.dashEnergy = min(MAX_ENERGY, s.dashEnergy + 0.5)
        }
        val slide = s.dashSlideFrom
        if (slide != null && now - slide >= DASH_SLIDE_RECHARGE_MS) {
            s.dashSlideFrom = null
            s.dashSlideCharge = true
            recharged(player)
        }
        actionBar(player, dashBar(s, now))
    }

    // ---- クラスの選択 ----

    internal fun select(player: Player, skill: Skill?) {
        resetPlayer(player)
        if (skill != null) states[player.uniqueId] = SkillState(skill)
        val inv = player.inventory
        when (skill) {
            Skill.ACROBAT -> player.sendMessage("§bアクロバットを選択しました")
            Skill.SCOUT -> {
                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false))
                inv.addItem(unbreakableGrapple())
                player.sendMessage("§aスカウトを選択しました")
            }
            Skill.GUARDIAN -> {
                player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 24.0
                player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 0, false, false))
                player.sendMessage("§eガーディアンを選択しました")
            }
            Skill.BLINK -> {
                inv.addItem(ItemStack(Material.FEATHER))
                player.sendMessage("§bブリンクを選択しました")
            }
            Skill.BLAST -> {
                inv.addItem(ItemStack(Material.TNT_MINECART))
                player.sendMessage("§eブラストパックを選択しました")
            }
            Skill.TELEPORT -> {
                inv.addItem(ItemStack(Material.ENDER_PEARL))
                player.sendMessage("§dテレポートを選択しました")
            }
            Skill.DASH -> {
                inv.addItem(ItemStack(Material.BLAZE_ROD))
                player.sendMessage("§6ダッシュを選択しました")
            }
            null -> player.sendMessage("§c能力をリセットしました")
        }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
        player.closeInventory()
    }

    internal fun resetPlayer(player: Player) {
        player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
        player.removePotionEffect(PotionEffectType.SPEED)
        player.removePotionEffect(PotionEffectType.REGENERATION)
        states.remove(player.uniqueId)?.let { discard(it) }
    }

    /** ワールドに出しているビーコンやブラストパックを片付ける */
    private fun discard(s: SkillState) {
        s.tpSession?.cancel()
        s.tpSession = null
        s.blastPack?.remove()
        s.blastPack = null
    }

    // ---- 能力 ----

    internal fun acrobatJump(player: Player, s: SkillState) {
        if (s.acrobatCooling) return
        player.velocity = player.location.direction.multiply(0.4).setY(1.0)
        s.acrobatCooling = true
        runLater(60L) { s.acrobatCooling = false }
    }

    internal fun blink(player: Player, s: SkillState) {
        if (!s.blinkCharge) return
        s.blinkCharge = false
        s.blinkLandedAt = null
        s.blinkInAir = true
        val vel = horizontal(player.location.direction).multiply(3.5 / 3)
        player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f)
        var ticks = 0
        everyTick {
            if (ticks++ >= 3 || !player.isOnline) return@everyTick false
            player.velocity = vel
            val at = player.location.add(0.0, 1.0, 0.0)
            player.world.spawnParticle(Particle.CLOUD, at, 5, 0.3, 0.3, 0.3, 0.02)
            player.world.spawnParticle(Particle.CRIT, at, 3, 0.2, 0.2, 0.2, 0.1)
            true
        }
    }

    internal fun blast(player: Player, s: SkillState) {
        val now = System.currentTimeMillis()
        if (s.blastPack != null) {
            if (now - s.blastLastThrow < 100) return
            detonate(s)
            return
        }
        if (s.blastCharges <= 0 || now - s.blastLastThrow < 500) return
        val item = player.world.dropItem(player.eyeLocation, ItemStack(Material.TNT_MINECART))
        item.velocity = player.eyeLocation.direction.multiply(0.6)
        item.pickupDelay = 32767
        s.blastPack = item
        s.blastCharges--
        s.blastLandedAt = null
        s.blastLastThrow = now
        player.playSound(player.location, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f)
        // 5 秒で消える
        runLater(100L) {
            if (s.blastPack === item) {
                if (item.isValid) item.remove()
                s.blastPack = null
            }
        }
    }

    private fun detonate(s: SkillState) {
        val item = s.blastPack
        s.blastPack = null
        if (item == null || !item.isValid) return
        val loc = item.location
        item.remove()
        val world = loc.world
        world.createExplosion(loc, 0f, false, false)
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.2f)
        world.spawnParticle(Particle.EXPLOSION, loc, 3, 0.5, 0.5, 0.5, 0.1)
        world.spawnParticle(Particle.FLAME, loc, 30, 1.0, 1.0, 1.0, 0.1)
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 20, 1.5, 1.5, 1.5, 0.05)

        val powerBase = if (s.blastInAir) 2.0 else 1.35
        for (entity in world.getNearbyEntities(loc, 5.0, 5.0, 5.0)) {
            if (entity !is Player) continue
            val inAir = states[entity.uniqueId]?.blastInAir ?: false
            val v = blastKnockback(loc.toVector(), entity.location.toVector(), entity.location.direction, inAir, powerBase) ?: continue
            if (inAir) entity.velocity = Vector(0, 0, 0)
            entity.velocity = v
        }
    }

    internal fun teleport(player: Player, s: SkillState) {
        if (s.tpSession != null) {
            if (player.isSneaking) fakeTeleport(player, s) else realTeleport(player, s)
            return
        }
        if (!s.tpCharge) return
        val start = player.location.add(0.0, 0.5, 0.0)
        // スニーク中はその場に置く
        val dir = if (player.isSneaking) Vector(0, 0, 0) else horizontal(player.location.direction).multiply(0.25)
        s.tpSession = Gatecrash(this, player.uniqueId, start, dir)
        s.tpCharge = false
        if (player.isSneaking) player.world.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 2.0f)
        else player.world.playSound(player.location, Sound.ENTITY_ENDER_PEARL_THROW, 1.0f, 1.5f)
    }

    private fun realTeleport(player: Player, s: SkillState) {
        val session = s.tpSession ?: return
        s.tpSession = null
        val target = session.location.clone()
        target.setDirection(player.location.direction)
        target.add(0.0, 0.5, 0.0)
        val blue = Particle.DustOptions(Color.BLUE, 2f)
        player.world.spawnParticle(Particle.DUST, player.location.add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, blue)
        player.world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 3.0f, 0.5f)
        player.teleport(target)
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20, 5, false, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, 20, 5, false, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 20, 1, false, false, false))
        target.world.spawnParticle(Particle.DUST, target.clone().add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, blue)
        target.world.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 3.0f, 1.5f)
        session.cancel()
        s.tpCooldownFrom = System.currentTimeMillis()
    }

    private fun fakeTeleport(player: Player, s: SkillState) {
        val session = s.tpSession ?: return
        s.tpSession = null
        val at = session.location.clone()
        val world = at.world
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, Particle.DustOptions(Color.BLUE, 2f))
        world.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 3.0f, 1.5f)
        // その場に水たまりのような跡を残す
        val dark = Particle.DustOptions(Color.fromRGB(0, 0, 139), 2.0f)
        var ticks = 0
        everyTick {
            if (ticks++ > 50) return@everyTick false
            val y = at.y + 0.15
            repeat(8) {
                val angle = Random.nextDouble() * Math.PI * 2
                val radius = Random.nextDouble() * 0.7
                val p = org.bukkit.Location(world, at.x + Math.cos(angle) * radius, y, at.z + Math.sin(angle) * radius)
                world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, dark)
                if (Random.nextDouble() > 0.8) world.spawnParticle(Particle.SPLASH, p, 1, 0.0, 0.0, 0.0, 0.0)
            }
            true
        }
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)
        player.sendMessage("§9§l[Teleport] §bFake Teleport!")
        session.cancel()
        s.tpCooldownFrom = System.currentTimeMillis()
    }

    internal fun toggleDash(player: Player, s: SkillState) {
        if (s.dashRunning) {
            stopDash(player, s, depleted = false)
            return
        }
        val empty = s.dashEmptyAt
        if (empty != null && System.currentTimeMillis() - empty < DASH_REGEN_DELAY_MS) return
        if (s.dashEnergy < 5.0) return
        s.dashRunning = true
        player.playSound(player.location, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 2.0f)
    }

    private fun stopDash(player: Player, s: SkillState, depleted: Boolean) {
        s.dashRunning = false
        player.removePotionEffect(PotionEffectType.SPEED)
        if (depleted) {
            s.dashEmptyAt = System.currentTimeMillis()
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
        } else {
            player.playSound(player.location, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.5f)
        }
    }

    internal fun slide(player: Player, s: SkillState) {
        if (!s.dashSlideCharge) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 0.5f)
            return
        }
        stopDash(player, s, depleted = false)
        s.dashSlideCharge = false
        s.dashSlideFrom = System.currentTimeMillis()
        val dir = horizontal(player.location.direction)
        player.world.playSound(player.location, Sound.BLOCK_SNOW_BREAK, 1.0f, 1.2f)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.5f)
        var ticks = 0
        everyTick {
            if (!player.isOnline || ticks >= 12) {
                // 終わりにブースト
                if (player.isOnline) player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 25, 4, false, false, true))
                return@everyTick false
            }
            player.velocity = dir.clone().multiply(1.2 * slideFactor(ticks)).setY(min(player.velocity.y, 0.1))
            if (ticks % 2 == 0) {
                player.world.spawnParticle(Particle.ELECTRIC_SPARK, player.location.add(0.0, 0.1, 0.0), 8, 0.4, 0.05, 0.4, 0.08)
                player.world.spawnParticle(Particle.CLOUD, player.location.add(0.0, 0.05, 0.0), 2, 0.3, 0.02, 0.3, 0.01)
            }
            ticks++
            true
        }
    }

    // ---- グラップリングフック ----

    private fun registerGrappleRecipe() {
        val result = ItemStack(Material.FISHING_ROD).apply {
            editMeta {
                it.displayName(GRAPPLE_NAME.itemText())
                it.addEnchant(Enchantment.UNBREAKING, 1, true)
                it.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
        }
        // 読み込み直したときに二重登録にならないようにする
        Bukkit.removeRecipe(grappleKey)
        val recipe = ShapedRecipe(grappleKey, result)
            .shape(" S ", "SFS", " S ")
            .setIngredient('S', Material.STRING)
            .setIngredient('F', Material.FISHING_ROD)
        Bukkit.addRecipe(recipe)
    }

    private fun unbreakableGrapple(): ItemStack = ItemStack(Material.FISHING_ROD).apply {
        editMeta {
            it.displayName(GRAPPLE_NAME.itemText())
            it.isUnbreakable = true
            it.addEnchant(Enchantment.UNBREAKING, 10, true)
        }
    }

    internal companion object {
        const val GRAPPLE_NAME = "§bグラップリングフック"
    }
}
