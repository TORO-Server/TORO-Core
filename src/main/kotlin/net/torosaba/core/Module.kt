package net.torosaba.core

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.InputStreamReader
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 統合前は 1 つのプラグインだった機能のまとまり。
 *
 * 設定とデータは `plugins/TORO-Core/<id>/` に置く。初回の有効化で、そのフォルダがまだ無く
 * 元のプラグインのフォルダ (`plugins/<legacyName>/`) があれば、中身をそのまま写す。
 * 設定のキーは元のプラグインと同じにしてあるので、写した設定はそのまま読める。
 */
abstract class Module(
    /** 設定のフォルダ名と modules の切り替えに使う名前 (例: auto-message) */
    val id: String,
    /** 元のプラグインの名前 (データフォルダ名)。移す元が無いときは null */
    val legacyName: String? = null,
) {
    lateinit var plugin: TOROCore
        private set

    lateinit var logger: Logger
        private set

    /** `config.yml`。モジュールの JAR 内に `modules/<id>/config.yml` があれば、それを既定値にする */
    lateinit var config: YamlConfiguration
        private set

    val dataFolder: File get() = File(plugin.dataFolder, id)
    private val configFile: File get() = File(dataFolder, "config.yml")

    private val listeners = mutableListOf<Listener>()
    private val tasks = mutableListOf<BukkitTask>()
    private val commands = mutableListOf<ModuleCommand>()
    private val permissions = mutableListOf<Permission>()

    var enabled = false
        private set

    /**
     * サーバーの起動時 (プラグインの onLoad) に、モジュールが有効かどうかに関係なく 1 度だけ呼ぶ。
     * ほかのプラグインが有効になる前にしかできないこと (WorldGuard のフラグの登録など) に使う。
     * [plugin] や [config] はまだ使えない
     */
    open fun onServerLoad(plugin: TOROCore) {}

    open fun onEnable() {}
    open fun onDisable() {}

    internal fun enable(plugin: TOROCore) {
        this.plugin = plugin
        logger = Logger.getLogger("TORO-Core/$id").also { it.parent = plugin.logger }
        migrateLegacyFolder()
        reloadConfig()
        onEnable()
        enabled = true
    }

    internal fun disable() {
        if (!enabled) return
        enabled = false
        try {
            onDisable()
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "停止中にエラーが起きました", e)
        }
        listeners.forEach { HandlerList.unregisterAll(it) }
        listeners.clear()
        synchronized(tasks) {
            tasks.forEach { it.cancel() }
            tasks.clear()
        }
        commands.forEach { plugin.commandRegistry.unregister(it) }
        commands.clear()
        permissions.forEach { Bukkit.getPluginManager().removePermission(it) }
        permissions.clear()
    }

    // ---- 設定 ----

    fun reloadConfig() {
        saveResource("config.yml")
        config = if (configFile.isFile) YamlConfiguration.loadConfiguration(configFile) else YamlConfiguration()
        resource("config.yml")?.let { config.setDefaults(it) }
    }

    fun saveConfig() {
        dataFolder.mkdirs()
        config.save(configFile)
    }

    /** JAR 内の `modules/<id>/<path>` を読む */
    fun resource(path: String): YamlConfiguration? =
        plugin.getResource("modules/$id/$path")?.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    /** JAR 内の `modules/<id>/<path>` を、まだ無ければデータフォルダに書き出す */
    fun saveResource(path: String) {
        val out = File(dataFolder, path)
        if (out.exists()) return
        val input = plugin.getResource("modules/$id/$path") ?: return
        out.parentFile.mkdirs()
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun migrateLegacyFolder() {
        val name = legacyName ?: return
        if (dataFolder.exists()) return
        val legacy = File(plugin.dataFolder.parentFile, name)
        if (!legacy.isDirectory) return
        legacy.copyRecursively(dataFolder)
        logger.info("${legacy.path} の設定とデータを ${dataFolder.path} に写しました")
    }

    // ---- 登録 (停止時にまとめて解除する) ----

    fun listen(listener: Listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin)
        listeners += listener
    }

    fun runLater(delay: Long, block: () -> Unit): BukkitTask =
        Bukkit.getScheduler().runTaskLater(plugin, Runnable(block), delay).also { track(it) }

    fun runTimer(delay: Long, period: Long, block: () -> Unit): BukkitTask =
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable(block), delay, period).also { track(it) }

    fun runAsync(block: () -> Unit): BukkitTask =
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable(block)).also { track(it) }

    fun runTimerAsync(delay: Long, period: Long, block: () -> Unit): BukkitTask =
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable(block), delay, period).also { track(it) }

    /** メインスレッドで実行する (非同期処理の結果を戻すときに使う) */
    fun runSync(block: () -> Unit): BukkitTask =
        Bukkit.getScheduler().runTask(plugin, Runnable(block)).also { track(it) }

    // runSync / runAsync は非同期スレッドからも呼ばれるので、tasks の操作はロックする
    private fun track(task: BukkitTask) = synchronized(tasks) {
        // 終わったタスクは持ち続けない
        val scheduler = Bukkit.getScheduler()
        tasks.removeAll { it.isCancelled || (!scheduler.isQueued(it.taskId) && !scheduler.isCurrentlyRunning(it.taskId)) }
        tasks += task
    }

    /**
     * コマンドを登録する。
     *
     * [override] を true にすると、同じ名前のコマンド (Essentials の /tell など) がすでにあっても、
     * このコマンドで置き換える。停止時には元のコマンドに戻す。
     */
    fun command(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        description: String = "",
        usage: String = "/$name",
        override: Boolean = false,
        tab: ((sender: CommandSender, alias: String, args: Array<String>) -> List<String>)? = null,
        executor: (sender: CommandSender, label: String, args: Array<String>) -> Boolean,
    ): ModuleCommand {
        val command = ModuleCommand(this, name, aliases, description, usage, executor, tab)
        if (permission != null) command.permission = permission
        plugin.commandRegistry.register(command, override)
        commands += command
        return command
    }

    fun permission(
        name: String,
        default: PermissionDefault = PermissionDefault.OP,
        description: String = "",
        children: Map<String, Boolean> = emptyMap(),
    ): Permission {
        val pm = Bukkit.getPluginManager()
        pm.getPermission(name)?.let { pm.removePermission(it) }
        val permission = Permission(name, description, default, children)
        pm.addPermission(permission)
        permissions += permission
        return permission
    }
}
