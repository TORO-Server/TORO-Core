package net.torosaba.core.module.addperm

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.Node
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** LuckPerms の API を使う部分。LuckPerms があるときだけ作る */
internal class LuckPermsGroups(private val module: AddPermModule) {

    fun execute(sender: CommandSender, label: String, args: Array<String>, group: String, add: Boolean) {
        if (args.size != 1) {
            sender.sendMessage("使用方法: /$label <MCID>")
            return
        }
        val targetName = args[0]
        val luckPerms = try {
            LuckPermsProvider.get()
        } catch (_: IllegalStateException) {
            sender.sendMessage("LuckPerms API のロードに失敗しました。")
            return
        }

        resolveUuid(luckPerms, targetName).thenCompose { uuid ->
            if (uuid == null) {
                reply(sender, "指定されたプレイヤーは存在しません。")
                return@thenCompose CompletableFuture.completedFuture(null)
            }
            luckPerms.userManager.loadUser(uuid).thenAcceptAsync { user ->
                if (user == null) {
                    reply(sender, "指定されたプレイヤーのデータを取得できませんでした。")
                    return@thenAcceptAsync
                }
                val groupNode = "group.$group"
                if (add) user.data().add(Node.builder(groupNode).build())
                else user.data().clear { it.key == groupNode }
                luckPerms.userManager.saveUser(user)

                val senderName = (sender as? Player)?.name ?: "Console"
                val action = if (add) "付与" else "剥奪"
                val role = if (group == "builder") "WE" else "Driver"
                module.sendWebhook(AddPermModule.changeLog(senderName, targetName, role, action))
                reply(sender, "$targetName に ${role}を${action}しました。")
            }
        }.exceptionally { e ->
            module.logger.log(Level.SEVERE, "/$label の実行中にエラーが起きました", e)
            null
        }
    }

    // オンライン → このサーバーに来たことがある → LuckPerms に記録がある → Floodgate の順に探す
    private fun resolveUuid(luckPerms: LuckPerms, name: String): CompletableFuture<UUID?> {
        Bukkit.getPlayerExact(name)?.let { return CompletableFuture.completedFuture(it.uniqueId) }
        Bukkit.getOfflinePlayerIfCached(name)?.takeIf { it.hasPlayedBefore() }?.let {
            return CompletableFuture.completedFuture(it.uniqueId)
        }
        return luckPerms.userManager.lookupUniqueId(name)
            .exceptionally { null }
            .thenCompose { uuid -> if (uuid != null) CompletableFuture.completedFuture(uuid) else module.floodgateUuid(name) }
    }

    private fun reply(sender: CommandSender, message: String) {
        module.runSync { sender.sendMessage(message) }
    }
}
