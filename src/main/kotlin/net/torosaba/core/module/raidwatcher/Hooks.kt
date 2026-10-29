package net.torosaba.core.module.raidwatcher

import com.earth2me.essentials.Essentials
import com.earth2me.essentials.utils.DateUtil
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.util.Tristate
import org.bukkit.Bukkit
import java.util.UUID

// Essentials と LuckPerms のクラスは、そのプラグインが入っているときだけ読み込まれるよう、ここに分ける

internal class EssentialsHook {
    private val ess = Bukkit.getPluginManager().getPlugin("Essentials") as Essentials

    /** "10s" "1m" などを、今からその時間がたった時刻 (ミリ秒) にする */
    fun parseDateDiff(time: String): Long = DateUtil.parseDateDiff(time, true)

    fun mute(uuid: UUID, reason: String, time: String) {
        val user = ess.getUser(uuid) ?: return
        user.setMuted(true)
        user.setMuteReason(reason)
        user.setMuteTimeout(parseDateDiff(time))
    }
}

internal class LuckPermsHook {
    /** まだサーバーにいないプレイヤーの権限を調べる。決まっていなければ null。通信を待つことがある */
    fun check(uuid: UUID, permission: String): Boolean? {
        val users = LuckPermsProvider.get().userManager
        val user = users.getUser(uuid) ?: users.loadUser(uuid).join()
        return when (user.cachedData.permissionData.checkPermission(permission)) {
            Tristate.TRUE -> true
            Tristate.FALSE -> false
            else -> null
        }
    }
}
