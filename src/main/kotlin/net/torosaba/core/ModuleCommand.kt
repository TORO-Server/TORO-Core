package net.torosaba.core

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.Plugin
import java.util.logging.Level

/** モジュールが実行時に登録するコマンド。plugin.yml には書かない */
class ModuleCommand internal constructor(
    val module: Module,
    name: String,
    aliases: List<String>,
    description: String,
    usage: String,
    private val executor: (CommandSender, String, Array<String>) -> Boolean,
    private val tab: ((CommandSender, String, Array<String>) -> List<String>)?,
) : Command(name, description, usage, aliases), PluginIdentifiableCommand {

    override fun getPlugin(): Plugin = module.plugin

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (!module.enabled) return true
        if (!testPermission(sender)) return true
        val ok = try {
            executor(sender, commandLabel, args)
        } catch (e: Throwable) {
            module.logger.log(Level.SEVERE, "/$commandLabel の実行中にエラーが起きました", e)
            sender.sendMessage("§cコマンドの実行中にエラーが起きました")
            true
        }
        if (!ok && usage.isNotEmpty()) {
            usage.replace("<command>", commandLabel).lines().forEach { sender.sendMessage(it) }
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
        val complete = tab ?: return super.tabComplete(sender, alias, args)
        if (!module.enabled || !testPermissionSilent(sender)) return emptyList()
        val last = args.lastOrNull() ?: ""
        return complete(sender, alias, args).filter { it.startsWith(last, ignoreCase = true) }
    }
}

/**
 * [ModuleCommand] を CommandMap に出し入れする。
 *
 * 置き換え (override) を指定したコマンドは、同じ名前で登録済みのコマンドを覚えておき、
 * 解除したときに戻す。
 */
class CommandRegistry(private val plugin: TOROCore) {
    private val fallbackPrefix = plugin.name.lowercase()
    private val replaced = mutableMapOf<ModuleCommand, Map<String, Command>>()

    fun register(command: ModuleCommand, override: Boolean) {
        val map = Bukkit.getCommandMap()
        map.register(fallbackPrefix, command)
        if (!override) return
        val known = map.knownCommands
        val previous = mutableMapOf<String, Command>()
        for (label in listOf(command.name) + command.aliases) {
            val key = label.lowercase()
            known[key]?.takeIf { it !== command }?.let { previous[key] = it }
            known[key] = command
        }
        replaced[command] = previous
    }

    fun unregister(command: ModuleCommand) {
        val map = Bukkit.getCommandMap()
        val known = map.knownCommands
        known.entries.removeIf { it.value === command }
        command.unregister(map)
        replaced.remove(command)?.forEach { (label, previous) -> known.putIfAbsent(label, previous) }
    }

    /** 登録の変更をプレイヤーのコマンド補完に反映する */
    fun sync() {
        try {
            Bukkit.getServer().javaClass.getMethod("syncCommands").invoke(Bukkit.getServer())
        } catch (e: ReflectiveOperationException) {
            plugin.logger.log(Level.FINE, "syncCommands を呼べませんでした", e)
        }
        Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
    }
}
