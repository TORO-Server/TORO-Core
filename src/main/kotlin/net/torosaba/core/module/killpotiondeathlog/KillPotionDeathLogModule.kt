package net.torosaba.core.module.killpotiondeathlog

import net.torosaba.core.Module
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * 旧 EssKillPotionBack (KillPotionDeathLog)。
 * 即死ポーションを受けたエンティティの体力を 0 にして、普通に死んだ扱いにする (死亡ログと /back が残る)。
 */
class KillPotionDeathLogModule : Module("kill-potion-death-log", "EssKillPotionBack"), Listener {

    override fun onEnable() {
        listen(this)
    }

    // 飲むポーション
    @EventHandler
    fun onPlayerItemConsume(event: PlayerItemConsumeEvent) {
        val meta = event.item.itemMeta as? PotionMeta ?: return
        if (meta.customEffects.none { it.isKillPotion() }) return
        val player = event.player
        if (!player.isDead) player.health = 0.0
    }

    // スプラッシュポーション
    @EventHandler
    fun onPotionSplash(event: PotionSplashEvent) {
        if (event.entity.effects.none { it.isKillPotion() }) return
        kill(event.affectedEntities)
    }

    // 残留ポーション
    @EventHandler
    fun onAreaEffectCloudApply(event: AreaEffectCloudApplyEvent) {
        if (event.entity.customEffects.none { it.isKillPotion() }) return
        kill(event.affectedEntities)
    }

    private fun kill(entities: Collection<LivingEntity>) {
        for (entity in entities) {
            if (!entity.isDead) entity.health = 0.0
        }
    }

    private companion object {
        val killAmplifiers = setOf(29, 61, 93, 125)

        // 即死ポーションになる即時回復の効果か
        fun PotionEffect.isKillPotion(): Boolean = amplifier in killAmplifiers && type == PotionEffectType.INSTANT_HEALTH
    }
}
