package net.torosaba.core.module.skills

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import java.util.UUID

/** テレポートで射出したビーコン。地面を滑って進み、600 tick で消える */
internal class Gatecrash(
    private val module: SkillsModule,
    private val owner: UUID,
    start: Location,
    direction: Vector,
) {
    var location: Location = start
        private set
    var life = 0
        private set

    private val direction = direction.clone()
    private var stuck = false

    private val display: ItemDisplay = start.world.spawn(start, ItemDisplay::class.java) { e ->
        e.setItemStack(ItemStack(Material.HEART_OF_THE_SEA))
        val t = e.transformation
        e.transformation = Transformation(t.translation, t.leftRotation, Vector3f(0.5f, 0.5f, 0.5f), t.rightRotation)
        // 落ちたときにワールドへ残さない
        e.isPersistent = false
    }

    private val task: BukkitTask = module.runTimer(0L, 1L) { tick() }

    fun cancel() {
        if (display.isValid) display.remove()
        task.cancel()
    }

    private fun tick() {
        if (life >= TP_SESSION_LIFE) {
            module.states[owner]?.takeIf { it.tpSession === this }?.let {
                it.tpSession = null
                it.tpCooldownFrom = System.currentTimeMillis()
            }
            cancel()
            return
        }
        val world = location.world
        world.spawnParticle(Particle.DUST, location.clone().add(0.0, 0.2, 0.0), 2, 0.1, 0.0, 0.1, Particle.DustOptions(Color.BLUE, 1.0f))
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, location.clone().add(0.0, 0.1, 0.0), 1, 0.05, 0.05, 0.05, 0.01)
        if (!stuck) {
            val decay = if (life < 300) maxOf(0.0, 1.0 - life.toDouble() / 300) else 0.0
            if (decay > 0.001) {
                val next = location.clone().add(direction.clone().multiply(decay))
                if (next.block.type.isSolid) stuck = true
                if (!stuck) {
                    // 足元が空いていれば落ちる
                    if (!next.clone().add(0.0, -1.0, 0.0).block.type.isSolid) next.add(0.0, -0.6, 0.0)
                    if (!next.block.type.isSolid) location = next else stuck = true
                }
            }
        } else if (life % 20 == 0) {
            world.playSound(location, Sound.BLOCK_BEACON_AMBIENT, 0.1f, 2.0f)
        }
        if (display.isValid) {
            val loc = location.clone()
            loc.yaw = life * 15f
            display.teleport(loc)
        }
        life++
    }
}
