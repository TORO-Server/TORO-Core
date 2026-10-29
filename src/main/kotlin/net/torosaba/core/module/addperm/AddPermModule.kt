package net.torosaba.core.module.addperm

import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.permissions.PermissionDefault
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/**
 * 旧 AddPerm。/addbuilder などで LuckPerms の builder / driver グループに出し入れし、Discord の Webhook に記録する。
 * LuckPerms が無いときはコマンドを登録しない。
 */
class AddPermModule : Module("add-perm", "AddPerm") {

    private var webhookUrl: String? = null

    override fun onEnable() {
        permission("addbuilder.use", PermissionDefault.OP, "/addbuilder などを使う")

        webhookUrl = config.getString("webhook-url", "")!!.ifEmpty {
            logger.warning("Webhook URL が設定されていません！")
            null
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            logger.warning("LuckPerms が無いので、/addbuilder などのコマンドは使えません")
            return
        }
        val groups = LuckPermsGroups(this)
        for ((name, group, add, description) in listOf(
            Spec("addbuilder", "builder", true, "Add a player to the builder group."),
            Spec("delbuilder", "builder", false, "Delete a player to the buiilder group."),
            Spec("adddriver", "driver", true, "Add a player to the driver group."),
            Spec("deldriver", "driver", false, "Delete a player to the driver group."),
        )) {
            command(name, permission = "addbuilder.use", description = description, usage = "/$name <MCID>") { sender, label, args ->
                groups.execute(sender, label, args, group, add)
                true
            }
        }
    }

    override fun onDisable() {
        webhookUrl = null
    }

    private data class Spec(val name: String, val group: String, val add: Boolean, val description: String)

    /** Floodgate (統合版) のプレイヤー名から UUID を引く。Floodgate が無ければ null */
    internal fun floodgateUuid(name: String): CompletableFuture<UUID?> {
        val floodgate = Bukkit.getPluginManager().getPlugin("floodgate") ?: return CompletableFuture.completedFuture(null)
        return try {
            // softdepend に入っていないので、Floodgate のクラスローダーから API を探す
            val api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, floodgate.javaClass.classLoader)
            val instance = api.getMethod("getInstance").invoke(null) ?: return CompletableFuture.completedFuture(null)
            @Suppress("UNCHECKED_CAST")
            val future = api.getMethod("getUuidFor", String::class.java).invoke(instance, name) as CompletableFuture<UUID?>
            future.exceptionally { null }
        } catch (e: ReflectiveOperationException) {
            logger.log(Level.WARNING, "Floodgate から UUID を取得できませんでした", e)
            CompletableFuture.completedFuture(null)
        }
    }

    /** Discord の Webhook に 1 行送る。非同期のスレッドから呼ぶ */
    internal fun sendWebhook(content: String) {
        val url = webhookUrl
        if (url == null) {
            logger.warning("Webhook URLが設定されていません。送信スキップします。")
            return
        }
        try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val json = """{"username":"Addbuilder","content":"${content.jsonEscape()}"}"""
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code != 204) logger.warning("Webhook送信失敗: HTTP $code")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Webhook を送れませんでした", e)
        }
    }

    private fun String.jsonEscape(): String = buildString {
        for (c in this@jsonEscape) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
