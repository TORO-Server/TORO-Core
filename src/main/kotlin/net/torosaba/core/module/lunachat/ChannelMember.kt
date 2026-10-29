package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** メンバーの名前やプレフィックスを引くための口 */
internal interface MemberContext {
    /** uuidcache.yml にある名前 */
    fun cachedName(uuid: UUID): String?
    fun prefix(player: Player): String
    fun suffix(player: Player): String

    /** %world に入れるワールド名 (Multiverse の別名があればそれ) */
    fun worldName(world: World): String
}

/**
 * チャンネルのメンバー。LunaChat と同じく、文字列表現 ([id]) で同じかどうかを決める。
 * プレイヤーは `$` + UUID、コンソールなどは名前
 */
internal sealed class ChannelMember {
    abstract val id: String
    abstract val name: String
    abstract val displayName: String
    abstract val isOnline: Boolean
    open val player: Player? get() = null
    open val prefix: String get() = ""
    open val suffix: String get() = ""
    open val worldName: String get() = ""
    open val world: World? get() = null
    open val location: Location? get() = null

    abstract fun send(message: Component)

    /** `§` の色コード付きの文字列を送る。空なら何もしない */
    fun send(legacy: String) {
        if (legacy.isNotEmpty()) send(Colors.component(legacy))
    }

    abstract fun hasPermission(node: String): Boolean
    abstract fun isPermissionSet(node: String): Boolean

    /** このメンバーとして発言する */
    open fun chat(message: String) {}

    fun vars(): MemberVars = MemberVars(name, displayName, { prefix }, { suffix }, worldName, "")

    override fun equals(other: Any?): Boolean = other is ChannelMember && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id
}

internal class PlayerMember(val uuid: UUID, private val ctx: MemberContext) : ChannelMember() {
    override val id: String = "$$uuid"
    override val player: Player? get() = Bukkit.getPlayer(uuid)
    override val isOnline: Boolean get() = player != null
    override val name: String
        get() = ctx.cachedName(uuid) ?: player?.name ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
    override val displayName: String get() = player?.let { Colors.legacy(it.displayName()) } ?: name
    override val prefix: String get() = player?.let { ctx.prefix(it) } ?: ""
    override val suffix: String get() = player?.let { ctx.suffix(it) } ?: ""
    override val world: World? get() = player?.world
    override val worldName: String get() = world?.let { ctx.worldName(it) } ?: ""
    override val location: Location? get() = player?.location

    override fun send(message: Component) {
        player?.sendMessage(message)
    }

    override fun hasPermission(node: String): Boolean = player?.hasPermission(node) ?: false
    override fun isPermissionSet(node: String): Boolean = player?.isPermissionSet(node) ?: false

    override fun chat(message: String) {
        player?.chat(message)
    }
}

/** コンソールやコマンドブロック */
internal class SenderMember(val sender: CommandSender) : ChannelMember() {
    override val id: String get() = sender.name
    override val name: String get() = sender.name
    override val displayName: String get() = sender.name
    override val isOnline: Boolean get() = true
    override val worldName: String
        get() = if (sender is BlockCommandSender) sender.block.world.name else "-"
    override val world: World? get() = (sender as? BlockCommandSender)?.block?.world
    override val location: Location? get() = (sender as? BlockCommandSender)?.block?.location

    override fun send(message: Component) = sender.sendMessage(message)
    override fun hasPermission(node: String): Boolean = sender.hasPermission(node)
    override fun isPermissionSet(node: String): Boolean = sender.isPermissionSet(node)

    override fun chat(message: String) {
        if (sender is ConsoleCommandSender) Bukkit.broadcast(Colors.component("<$name>$message"))
    }
}

/** dynmap の Web チャットの発言者や、UUID の分からない古いデータの名前だけのメンバー */
internal class OtherMember(override val name: String, override val displayName: String = name) : ChannelMember() {
    override val id: String get() = name
    override val isOnline: Boolean get() = Bukkit.getPlayerExact(name) != null
    override fun send(message: Component) {
        Bukkit.getPlayerExact(name)?.sendMessage(message)
    }

    override fun hasPermission(node: String): Boolean = false
    override fun isPermissionSet(node: String): Boolean = false
}
