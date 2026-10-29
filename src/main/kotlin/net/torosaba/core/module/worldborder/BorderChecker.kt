// WorldBorder (Brettflan, BSD-2-Clause License) の BorderCheckTask と安全な位置探しを移植したもの
package net.torosaba.core.module.worldborder

import org.bukkit.Bukkit
import org.bukkit.Effect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min

/** プレイヤーが境界の外にいないか調べて、内側へ戻す */
internal class BorderChecker(private val module: WorldBorderModule) {
    private val settings get() = module.settings

    /**
     * 戻している最中のプレイヤー。テレポートのイベントが古い位置のまま届くことがあり、
     * そのたびに戻すと止まらなくなるので、処理中は飛ばす
     */
    private val handling = HashSet<UUID>()

    fun checkAll() {
        if (settings.knockBack == 0.0) return
        for (player in Bukkit.getOnlinePlayers().toList()) check(player, null, returnLocationOnly = false, notify = true)
    }

    /**
     * [target] (null なら今の位置) が境界の外なら、戻す先を決める。
     * [returnLocationOnly] のときは動かさずに戻す先を返す (テレポートのイベントで行き先を差し替えるとき)。
     */
    fun check(player: Player, target: Location?, returnLocationOnly: Boolean, notify: Boolean): Location? {
        if (!player.isOnline) return null
        val loc = target ?: player.location
        val world = loc.world ?: return null
        val border = settings.borders[world.name] ?: return null
        if (border.insideBorder(loc.x, loc.z, settings.shapeRound)) return null
        if (player.uniqueId in settings.bypass || player.uniqueId in handling) return null

        handling += player.uniqueId
        val newLoc = newLocation(player, loc, border, notify)
        if (newLoc == null) {
            // 安全な場所が無く、プレイヤーを倒した
            handling -= player.uniqueId
            return null
        }

        // 乗り物に乗ったままだとテレポートできないので降ろし、乗り物も一緒に動かす
        var handlingVehicle = false
        val ride = player.vehicle
        if (ride != null) {
            player.leaveVehicle()
            val vertOffset = if (ride is LivingEntity) 0.0 else ride.location.y - loc.y
            val rideLoc = newLoc.clone().apply { y = newLoc.y + vertOffset }
            if (settings.debug) module.logger.warning("Player was riding a \"$ride\".")
            ride.velocity = Vector(0, 0, 0)
            ride.teleport(rideLoc, TeleportCause.PLUGIN)
            if (settings.remountTicks > 0) {
                remountLater(ride, player, settings.remountTicks.toLong())
                handlingVehicle = true
            }
        }

        // プレイヤーに何か乗っているとテレポートできないので降ろす
        val passengers = player.passengers
        if (passengers.isNotEmpty()) {
            player.eject()
            for (rider in passengers) {
                rider.teleport(newLoc, TeleportCause.PLUGIN)
                if (settings.debug) module.logger.warning("Player had a passenger riding on them: ${rider.type}")
            }
            player.sendMessage("Your passenger" + (if (passengers.size > 1) "s have" else " has") + " been ejected.")
        }

        showWhooshEffect(loc)

        if (!returnLocationOnly) player.teleport(newLoc, TeleportCause.PLUGIN)
        if (!handlingVehicle) handling -= player.uniqueId
        return if (returnLocationOnly) newLoc else null
    }

    private fun newLocation(player: Player, loc: Location, border: BorderData, notify: Boolean): Location? {
        val coord = BorderData.COORD
        if (settings.debug) {
            module.logger.warning((if (notify) "Border crossing" else "Check was run") + " in \"${loc.world.name}\". Border ${border.describe()}")
            module.logger.warning("Player position X: ${coord.format(loc.x)} Y: ${coord.format(loc.y)} Z: ${coord.format(loc.z)}")
        }

        var newLoc = correctedPosition(loc, border, player.isFlying)
        if (newLoc == null) {
            // ネザーなどで安全な場所が見つからないとき
            if (settings.debug) module.logger.warning("Target new location unviable, using spawn or killing player.")
            if (settings.killPlayer) {
                player.health = 0.0
                return null
            }
            newLoc = player.world.spawnLocation
        }

        if (settings.debug) {
            module.logger.warning("New position in world \"${newLoc.world.name}\" at X: ${coord.format(newLoc.x)} Y: ${coord.format(newLoc.y)} Z: ${coord.format(newLoc.z)}")
        }
        if (notify) player.sendMessage(settings.messageFormatted)
        return newLoc
    }

    private fun correctedPosition(loc: Location, border: BorderData, flying: Boolean): Location? {
        val world = loc.world
        val (x, z) = border.correctedXZ(loc.x, loc.z, settings.shapeRound, settings.knockBack)
        val bx = Location.locToBlock(x)
        val bz = Location.locToBlock(z)
        // 調べる場所のチャンクを読み込んでおく
        world.getChunkAt(bx shr 4, bz shr 4)
        val y = safeY(world, bx, Location.locToBlock(loc.y), bz, flying) ?: return null
        return Location(world, floor(x) + 0.5, y.toDouble(), floor(z) + 0.5, loc.yaw, loc.pitch)
    }

    /** いちばん近い安全な高さを、上下に広げながら探す (Acru の Nether プラグインの方法) */
    private fun safeY(world: World, x: Int, startY: Int, z: Int, flying: Boolean): Int? {
        val isNether = world.environment == World.Environment.NETHER
        val limBot = world.minHeight
        // ネザーは天井の上に出さない
        var limTop = if (isNether) 125 else world.maxHeight - 2
        val highestBlockBoundary = min(world.getHighestBlockYAt(x, z) + 1, limTop)

        if (flying && startY > limTop && !isNether) return startY

        var y = startY
        if (y > limTop) y = if (isNether || flying) limTop else highestBlockBoundary
        if (y < limBot) y = limBot

        // ネザー以外で飛んでいなければ、いちばん高いブロックより上は調べなくてよい
        if (!isNether && !flying) limTop = highestBlockBoundary

        var y1 = y
        var y2 = y
        while (y1 > limBot || y2 < limTop) {
            if (y1 > limBot && isSafeSpot(world, x, y1, z, flying)) return y1
            if (y2 < limTop && y2 != y1 && isSafeSpot(world, x, y2, z, flying)) return y2
            y1--
            y2++
        }
        return null
    }

    /** 息ができるブロックが 2 つ重なり、その下が (飛んでいなければ) 足場になるか */
    private fun isSafeSpot(world: World, x: Int, y: Int, z: Int, flying: Boolean): Boolean {
        val safe = isSafeOpen(world.getBlockAt(x, y, z).type) && isSafeOpen(world.getBlockAt(x, y + 1, z).type)
        if (!safe || flying) return safe
        val below = world.getBlockAt(x, y - 1, z).type
        return (!isSafeOpen(below) || below == Material.WATER) && below !in PAINFUL
    }

    private fun showWhooshEffect(loc: Location) {
        if (!settings.whooshEffect) return
        val world = loc.world
        world.playEffect(loc, Effect.ENDER_SIGNAL, 0)
        world.playEffect(loc, Effect.ENDER_SIGNAL, 0)
        // 元は Effect.SMOKE を 3 回 (今は非推奨なので煙の粒子で代える)
        world.spawnParticle(Particle.SMOKE, loc.clone().add(0.0, 1.0, 0.0), 24, 0.3, 0.5, 0.3, 0.02)
        world.playEffect(loc, Effect.GHAST_SHOOT, 0)
    }

    /** 降ろしたプレイヤーを、少し待ってから乗り物に戻す */
    private fun remountLater(vehicle: Entity, player: Player, delay: Long) {
        module.runLater(delay) {
            handling -= player.uniqueId
            if (vehicle.isValid && player.isOnline) vehicle.addPassenger(player)
        }
    }

    fun forget(player: Player) {
        handling -= player.uniqueId
    }

    private companion object {
        /** 足元にあると困るブロック */
        val PAINFUL = setOf(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.END_PORTAL,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        )

        /** 中に立っても危なくない、通り抜けられるブロック (元の一覧に近い基準) */
        val DANGEROUS_OPEN = setOf(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.END_PORTAL, Material.NETHER_PORTAL, Material.END_GATEWAY,
            Material.BUBBLE_COLUMN,
        )

        fun isSafeOpen(type: Material): Boolean =
            type.isAir || type == Material.WATER || (type !in DANGEROUS_OPEN && !type.isSolid)
    }
}
