package net.torosaba.core

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/**
 * TORO サーバーの小さな自作プラグインと、開発の止まったプラグインの機能をまとめたプラグイン。
 * 機能ごとに [Module] に分かれていて、config.yml の modules で 1 つずつ止められる。
 */
class TOROCore : JavaPlugin() {
    lateinit var commandRegistry: CommandRegistry
        private set

    val modules: List<Module> by lazy { Modules.create() }

    override fun onLoad() {
        for (module in modules) {
            // 止めたモジュールは読み込まない (元のプラグインと同じ WorldGuard のフラグを登録してぶつかるなど)
            if (!config.getBoolean("modules.${module.id}", true)) continue
            try {
                module.onServerLoad(this)
            } catch (e: Throwable) {
                logger.log(Level.SEVERE, "モジュール ${module.id} の起動前の準備でエラーが起きました", e)
            }
        }
    }

    override fun onEnable() {
        saveDefaultConfig()
        commandRegistry = CommandRegistry(this)
        enableModules()
    }

    override fun onDisable() {
        disableModules()
    }

    private fun isModuleOn(module: Module): Boolean {
        val path = "modules.${module.id}"
        if (!config.isSet(path)) {
            // 新しく追加したモジュールは、既定で有効にして設定ファイルにも書き足す
            config.set(path, true)
            saveConfig()
        }
        return config.getBoolean(path, true)
    }

    private fun enableModules() {
        val on = mutableListOf<String>()
        for (module in modules) {
            if (!isModuleOn(module)) continue
            try {
                module.enable(this)
                on += module.id
            } catch (e: Throwable) {
                logger.log(Level.SEVERE, "モジュール ${module.id} を有効にできませんでした", e)
                module.disable()
            }
        }
        commandRegistry.sync()
        logger.info("有効なモジュール (${on.size}/${modules.size}): ${on.joinToString(", ")}")
    }

    private fun disableModules() {
        // 1 つの失敗で残りのモジュールが止まらないことがないようにする
        for (module in modules.asReversed()) {
            try {
                module.disable()
            } catch (e: Throwable) {
                logger.log(Level.SEVERE, "モジュール ${module.id} を止めるときにエラーが起きました", e)
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                val target = args.getOrNull(1)
                if (target == null) {
                    disableModules()
                    reloadConfig()
                    enableModules()
                    sender.sendMessage("§aTORO-Core の全モジュールを読み込み直しました")
                } else {
                    val module = modules.find { it.id == target }
                    if (module == null) {
                        sender.sendMessage("§cモジュール $target はありません")
                        return true
                    }
                    module.disable()
                    reloadConfig()
                    if (isModuleOn(module)) {
                        module.enable(this)
                        sender.sendMessage("§aモジュール $target を読み込み直しました")
                    } else {
                        sender.sendMessage("§eモジュール $target は config.yml で無効になっています")
                    }
                    commandRegistry.sync()
                }
            }
            "modules", null -> {
                sender.sendMessage("§6TORO-Core ${pluginMeta.version} のモジュール:")
                for (module in modules) {
                    val state = if (module.enabled) "§a有効" else "§7無効"
                    val legacy = module.legacyName?.let { " §8(旧 $it)" } ?: ""
                    sender.sendMessage(" $state §f${module.id}$legacy")
                }
            }
            else -> return false
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> =
        when (args.size) {
            1 -> listOf("modules", "reload").filter { it.startsWith(args[0]) }
            2 -> if (args[0] == "reload") modules.map { it.id }.filter { it.startsWith(args[1]) } else emptyList()
            else -> emptyList()
        }
}
