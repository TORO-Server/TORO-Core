package net.torosaba.core.module.lunachat.bridge

import net.milkbowl.vault.chat.Chat
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.dynmap.DynmapAPI
import org.dynmap.DynmapWebChatEvent

// 連携先のクラスは、そのプラグインが入っているときだけ読み込まれるよう、ここに分ける。
// モジュール本体は下のインターフェースだけを使う

/** プレフィックスとサフィックス (Vault の Chat) */
interface PrefixSource {
    fun prefix(player: Player): String
    fun suffix(player: Player): String
}

/** dynmap の Web チャットに流す */
interface DynmapLink {
    fun chat(player: Player, message: String)
    fun broadcast(message: String)
}

internal object Bridges {
    /** Vault と Chat の提供元があれば使う */
    fun vault(): PrefixSource? {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null
        return try {
            VaultChatBridge.load()
        } catch (e: LinkageError) {
            null
        }
    }

    /**
     * dynmap が入っていれば使う。[onWebChat] は Web チャットの発言 (名前, 送信元, 発言) を受け取り、
     * 処理したら true を返す
     */
    fun dynmap(onWebChat: (name: String, source: String?, message: String) -> Boolean): Pair<DynmapLink, Listener>? {
        val plugin = Bukkit.getPluginManager().getPlugin("dynmap") ?: return null
        return try {
            DynmapBridge.load(plugin, onWebChat)
        } catch (e: LinkageError) {
            null
        }
    }

    /** Multiverse-Core (4 系) のワールドの別名。取れなければ null */
    fun multiverseAlias(world: World): String? {
        val plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core") ?: return null
        return try {
            val manager = plugin.javaClass.getMethod("getMVWorldManager").invoke(plugin) ?: return null
            val mvWorld = manager.javaClass.getMethod("getMVWorld", String::class.java).invoke(manager, world.name)
                ?: return null
            val alias = mvWorld.javaClass.getMethod("getAlias").invoke(mvWorld) as? String
            if (alias.isNullOrEmpty()) world.name else alias
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}

private class VaultChatBridge(private val chat: Chat) : PrefixSource {
    override fun prefix(player: Player): String = chat.getPlayerPrefix(player) ?: ""
    override fun suffix(player: Player): String = chat.getPlayerSuffix(player) ?: ""

    companion object {
        fun load(): VaultChatBridge? =
            Bukkit.getServicesManager().getRegistration(Chat::class.java)?.provider?.let(::VaultChatBridge)
    }
}

private class DynmapBridge(
    private val api: DynmapAPI,
    private val onWebChat: (String, String?, String) -> Boolean,
) : DynmapLink, Listener {
    override fun chat(player: Player, message: String) = api.postPlayerMessageToWeb(player, message)

    override fun broadcast(message: String) {
        api.sendBroadcastToWeb(null, message)
    }

    @EventHandler
    fun onDynmapWebChat(event: DynmapWebChatEvent) {
        if (onWebChat(event.name, event.source, event.message)) {
            event.setProcessed()
            api.sendBroadcastToWeb(null, event.message)
        }
    }

    companion object {
        fun load(plugin: Plugin, onWebChat: (String, String?, String) -> Boolean): Pair<DynmapLink, Listener>? {
            val api = plugin as? DynmapAPI ?: return null
            val bridge = DynmapBridge(api, onWebChat)
            return bridge to bridge
        }
    }
}
