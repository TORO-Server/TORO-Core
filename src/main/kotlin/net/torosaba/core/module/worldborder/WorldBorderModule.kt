// WorldBorder (Brettflan, BSD-2-Clause License, Copyright (c) 2020 Brett Flannigan) を Kotlin に移植したもの
package net.torosaba.core.module.worldborder

import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.permissions.PermissionDefault
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Level

/**
 * 旧 WorldBorder。ワールドごとの境界 (円/四角) の外に出たプレイヤーを内側へ戻す。
 * 境界の定義は config.yml の worlds に、元のプラグインと同じ書式で持つ。
 * ワールドの生成 (fill) と削除 (trim) は移植していない。
 */
class WorldBorderModule : Module("world-border", "WorldBorder") {
    internal val settings = BorderSettings()
    internal lateinit var checker: BorderChecker
        private set
    internal var markers: BorderMarkers? = null
        private set
    private var timer: BukkitTask? = null

    override fun onEnable() {
        registerPermissions()
        settings.load(config, logger)
        checker = BorderChecker(this)
        listen(BorderListener(this))
        startTimer()
        setupDynmap()
        WorldBorderCommand(this).register()
    }

    override fun onDisable() {
        markers?.removeAll()
        markers = null
        timer = null
    }

    /** `/wb reload`。config.yml を読み直す */
    internal fun reload() {
        reloadConfig()
        settings.load(config, logger)
        startTimer()
        markers?.showAll()
    }

    internal fun save() {
        settings.writeTo(config)
        saveConfig()
    }

    /** 境界を見回るタスクを (間隔を変えたときは) 動かし直す */
    internal fun startTimer() {
        timer?.cancel()
        val ticks = settings.timerTicks.toLong().coerceAtLeast(1L)
        timer = runTimer(ticks, ticks) { checker.checkAll() }
    }

    /** dynmap が有効なら境界を描く。dynmap のクラスはここから先でしか読み込まない */
    internal fun setupDynmap() {
        if (markers != null) return
        val dynmap = Bukkit.getPluginManager().getPlugin("dynmap") ?: return
        if (!dynmap.isEnabled) return
        try {
            markers = DynmapBorders.create(this)
        } catch (e: LinkageError) {
            logger.log(Level.WARNING, "dynmap と連携できませんでした。境界は地図に描きません", e)
            return
        }
        if (markers == null) {
            logger.info("dynmap はありますが、印を描く API を使えないので境界は描きません")
            return
        }
        markers?.showAll()
        logger.info("dynmap に境界を描きます")
    }

    // ---- 境界の変更 (保存と地図の更新もする) ----

    internal fun setBorder(world: String, border: BorderData, log: Boolean = true) {
        settings.borders[world] = border
        if (log) logger.info("Border set. " + settings.describe(world))
        save()
        markers?.show(world, border)
    }

    internal fun removeBorder(world: String) {
        settings.borders.remove(world)
        logger.info("Removed border for world \"$world\".")
        save()
        markers?.remove(world)
    }

    internal fun removeAllBorders() {
        settings.borders.clear()
        logger.info("Removed all borders for all worlds.")
        save()
        markers?.removeAll()
    }

    private fun registerPermissions() {
        val descriptions = linkedMapOf(
            "bypass" to "Can enable bypass mode to go beyond the border",
            "bypasslist" to "Can get list of players with border bypass enabled",
            "clear" to "Can remove any border",
            "debug" to "Can enable/disable debug output to console",
            "delay" to "Can set the frequency at which the plugin checks for border crossings",
            "denypearl" to "Can enable/disable direct cancellation of ender pearls thrown past border",
            "dynmap" to "Can enable/disable DynMap border display integration",
            "dynmapmsg" to "Can set the label text for borders shown in DynMap",
            "fill" to "Can fill in (generate) any missing map chunks out to the border",
            "fillautosave" to "Can set the world save interval for the Fill process",
            "getmsg" to "Can view the border crossing message",
            "help" to "Can view the command reference help pages",
            "knockback" to "Can set the knockback distance for border crossings",
            "list" to "Can view a list of all borders",
            "portal" to "Can enable/disable portal redirection to be inside border",
            "preventblockplace" to "Can prevent placement of blocks outside the border",
            "preventmobspawn" to "Can prevent spawning of mobs outside the border",
            "radius" to "Can set the radius of an existing border",
            "reload" to "Can force the plugin to reload from the config file",
            "remount" to "Can set the delay before remounting a player to their vehicle after knockback",
            "set" to "Can set borders for any world",
            "setmsg" to "Can set the border crossing message",
            "shape" to "Can set the default shape (round or square) for all borders",
            "trim" to "Can trim (remove) any excess map chunks outside of the border",
            "whoosh" to "Can enable/disable \"whoosh\" knockback effect",
            "wrap" to "Can set border crossings to wrap around to the other side of the world",
            "wshape" to "Can set an overriding border shape for a single world",
        )
        for ((name, description) in descriptions) permission("worldborder.$name", PermissionDefault.OP, description)
        permission("worldborder.*", PermissionDefault.OP, "Grants all WorldBorder permissions",
            descriptions.keys.associate { "worldborder.$it" to true })
    }
}
