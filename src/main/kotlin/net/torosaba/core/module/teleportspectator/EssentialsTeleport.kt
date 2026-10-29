package net.torosaba.core.module.teleportspectator

import com.earth2me.essentials.Essentials
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/** Essentials の API を使う部分。Essentials があるときだけ作る */
internal class EssentialsTeleport {
    private val essentials = Bukkit.getPluginManager().getPlugin("Essentials") as Essentials

    /** 相手がテレポートを受け付けているか (/tptoggle) */
    fun isTeleportEnabled(target: Player): Boolean = essentials.getUser(target).isTeleportEnabled
}
