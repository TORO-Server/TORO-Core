package net.torosaba.core.module.hidenametag

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Team

/**
 * 旧 HideNametagCMD。/nttoggle で自分のネームタグの表示・非表示を切り替える。
 * 非表示のプレイヤーはメインのスコアボードのチーム marumasa.HideNameTag に入れる。
 * チームはワールドに保存されるので、停止しても消さない (再起動後も非表示のまま)。
 */
class HideNametagModule : Module("hide-nametag", "HideNametagCMD") {
    override fun onEnable() {
        val section = LegacyComponentSerializer.legacySection()
        val hideMessage = section.deserialize(config.getString("hideMessage") ?: "")
        val showMessage = section.deserialize(config.getString("showMessage") ?: "")

        team()

        command("nttoggle", description = "Name tag display switching command") { sender, _, _ ->
            if (sender !is Player) {
                sender.sendMessage("§cプレイヤーだけが使えるコマンドです")
                return@command true
            }
            // /team remove などで消されていても作り直せるよう、毎回取り直す
            val team = team()
            if (team.hasEntry(sender.name)) {
                team.removeEntry(sender.name)
                sender.sendMessage(showMessage)
            } else {
                sender.sendMessage(hideMessage)
                team.addEntry(sender.name)
            }
            true
        }
    }

    private fun team(): Team {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val team = scoreboard.getTeam(TEAM) ?: scoreboard.registerNewTeam(TEAM)
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
        return team
    }

    private companion object {
        const val TEAM = "marumasa.HideNameTag"
    }
}
