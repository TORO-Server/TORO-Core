/*
 * このモジュールは LunaChat (https://github.com/ucchyocean/LunaChat, 作者 ucchy, LGPL-3.0) を
 * Kotlin に移したもの。TORO で使う機能 (Japanize、通常チャットの書式、チャンネルチャット、/tell /reply、dynmap 連携) だけを持つ。
 * かな変換の表 (japanize/KanaConverter.kt) は YukiLeafX による YukiKanaConverter の移植。
 * このディレクトリのファイルは LGPL-3.0 で配布する (NOTICE.md を参照)。
 */
package net.torosaba.core.module.lunachat

import net.kyori.adventure.text.Component
import net.torosaba.core.Module
import net.torosaba.core.module.lunachat.bridge.Bridges
import net.torosaba.core.module.lunachat.bridge.DynmapLink
import net.torosaba.core.module.lunachat.bridge.PrefixSource
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionDefault
import java.io.File
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 旧 LunaChat。ローマ字の発言のかな・漢字変換 (Japanize)、通常チャットの書式、チャンネルチャット、
 * /tell /reply (Essentials のものを置き換える)、dynmap の Web チャットとの連携。
 *
 * 設定とデータのファイル名と書式は LunaChat と同じなので、`plugins/LunaChat/` から写したものをそのまま読む
 */
class LunaChatModule : Module("lunachat", "LunaChat") {
    internal lateinit var settings: Settings
        private set
    internal lateinit var messages: Messages
        private set
    internal lateinit var manager: ChannelManager
        private set
    internal lateinit var normalChatLogger: ChatLogger
        private set

    /** ログと uuidcache.yml の書き込み用 (順番どおりに書く) */
    internal lateinit var logWriter: ExecutorService
        private set

    internal var dynmap: DynmapLink? = null
        private set
    private var prefixSource: PrefixSource? = null

    /** プレイヤーの UUID → 名前 (uuidcache.yml) */
    private val uuidCache = ConcurrentHashMap<String, String>()

    /** 招待された人 → 招待されたチャンネル名 */
    internal val inviteMap = ConcurrentHashMap<String, String>()

    /** 招待された人 → 招待した人 */
    internal val inviterMap = ConcurrentHashMap<String, String>()

    /** /tell /reply の会話相手 */
    internal val privateMessageMap = ConcurrentHashMap<String, String>()

    private val uuidCacheFile get() = File(dataFolder, "uuidcache.yml")

    override fun onEnable() {
        logWriter = Executors.newSingleThreadExecutor { Thread(it, "TORO-Core LunaChat writer").apply { isDaemon = true } }
        loadSettings()
        loadUuidCache()
        manager = ChannelManager(this).also { it.reloadAll() }
        normalChatLogger = ChatLogger(dataFolder, "==normalchat", logWriter)

        // チャンネルチャットが無効なら、発言先を全部消す (LunaChat issue #59)
        if (!settings.enableChannelChat) manager.removeAllDefaultChannels()

        prefixSource = Bridges.vault()
        Bridges.dynmap(::onDynmapWebChat)?.let { (link, listener) ->
            dynmap = link
            listen(listener)
        }

        listen(ChatListener(this))
        registerPermissions()
        registerCommands()

        // 期限付きの BAN と Mute を 30 秒ごとに確かめる
        runTimer(100, 600) { manager.channels.toList().forEach { it.checkExpires() } }
    }

    override fun onDisable() {
        if (!::logWriter.isInitialized) return
        saveUuidCache()
        logWriter.shutdown()
        logWriter.awaitTermination(5, TimeUnit.SECONDS)
        dynmap = null
        prefixSource = null
    }

    private fun loadSettings() {
        settings = Settings(config) { logger.warning(it) }
        // メッセージは messages/messages_<lang>.yml。無い言語なら同梱の日本語を使う
        saveResource("messages/messages_ja.yml")
        val defaults = resource("messages/messages_ja.yml") ?: YamlConfiguration()
        val file = File(dataFolder, "messages/messages_${settings.lang}.yml").takeIf { it.isFile }
            ?: File(dataFolder, "messages/messages_ja.yml")
        val resources = if (file.isFile) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        resources.setDefaults(defaults)
        messages = Messages(resources)
    }

    /** /ch reload */
    internal fun reloadAll() {
        reloadConfig()
        loadSettings()
        manager.reloadAll()
        if (!settings.enableChannelChat) manager.removeAllDefaultChannels()
    }

    // ---- コマンドと権限 ----

    private fun registerCommands() {
        val lunachat = LunaChatCommand(this)
        command(
            "lunachat", listOf("ch", "lc"), permission = "lunachat.command",
            description = "LunaChat のチャンネルチャット", usage = "",
            tab = { sender, _, args -> lunachat.tabComplete(member(sender), args) },
        ) { sender, label, args ->
            lunachat.execute(member(sender), label, args)
            true
        }

        val pm = PrivateMessageCommands(this)
        command(
            "tell", listOf("msg", "message", "m", "t"), permission = "lunachat.message",
            description = "1:1 チャット", usage = "", override = true,
            tab = { _, _, args -> pm.tellTab(args) },
        ) { sender, label, args ->
            pm.tell(member(sender), label, args)
            true
        }
        command(
            "reply", listOf("r"), permission = "lunachat.reply",
            description = "1:1 チャットに返信する", usage = "", override = true,
            tab = { _, _, _ -> emptyList() },
        ) { sender, label, args ->
            pm.reply(member(sender), label, args)
            true
        }
        command(
            "japanize", listOf("jp"), permission = "lunachat.japanize",
            description = "Japanize 変換の切り替え", usage = "",
            tab = { sender, _, args -> pm.japanizeTab(member(sender), args) },
        ) { sender, label, args ->
            pm.japanize(member(sender), label, args)
            true
        }
    }

    private fun registerPermissions() {
        val user = listOf(
            "command", "join", "leave", "list", "invite", "accept", "deny", "kick", "ban", "pardon", "mute",
            "unmute", "hide", "unhide", "info", "log", "create", "remove", "format", "moderator", "option",
            "message", "reply", "help", "japanize", "allowcc",
        ).map { "lunachat.$it" }
        user.forEach { permission(it, PermissionDefault.TRUE) }
        permission("lunachat.*", PermissionDefault.OP, "一般プレイヤー向けの権限", user.associateWith { true })

        val options = mapOf(
            "description" to PermissionDefault.TRUE, "alias" to PermissionDefault.TRUE, "color" to PermissionDefault.TRUE,
            "broadcast" to PermissionDefault.OP, "range" to PermissionDefault.OP, "password" to PermissionDefault.TRUE,
            "visible" to PermissionDefault.TRUE, "allowcc" to PermissionDefault.TRUE, "japanize" to PermissionDefault.TRUE,
        )
        options.forEach { (key, default) -> permission("lunachat.option.$key", default) }
        permission("lunachat.option.*", PermissionDefault.OP, "option コマンドの権限", options.keys.associate { "lunachat.option.$it" to true })

        val admin = listOf(
            "mod-all-channels", "listen-all-channels", "template", "dictionary", "set", "reload", "japanize-other", "force-invite",
        ).map { "lunachat-admin.$it" }
        admin.forEach { permission(it, PermissionDefault.OP) }
        permission("lunachat-admin.*", PermissionDefault.OP, "管理者向けの権限", admin.associateWith { true })
    }

    // ---- メンバー ----

    internal fun member(sender: CommandSender): ChannelMember =
        if (sender is Player) PlayerMember(sender.uniqueId, memberContext) else SenderMember(sender)

    /** チャンネルのファイルの ID (`$` + UUID か名前) から */
    internal fun memberById(id: String): ChannelMember? {
        if (id.startsWith("$")) {
            return try {
                PlayerMember(UUID.fromString(id.substring(1)), memberContext)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return memberByName(id) ?: OtherMember(id)
    }

    /** プレイヤー名から。オンラインの人、uuidcache.yml にある人、サーバーが覚えている人の順に探す */
    internal fun memberByName(name: String): ChannelMember? {
        Bukkit.getPlayerExact(name)?.let { return member(it) }
        uuidCache.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.let { (uuid, _) ->
            try {
                return PlayerMember(UUID.fromString(uuid), memberContext)
            } catch (_: IllegalArgumentException) {
            }
        }
        Bukkit.getOfflinePlayerIfCached(name)?.let { return PlayerMember(it.uniqueId, memberContext) }
        val console = Bukkit.getConsoleSender()
        if (name == console.name) return SenderMember(console)
        return null
    }

    private val memberContext = object : MemberContext {
        override fun cachedName(uuid: UUID): String? = uuidCache[uuid.toString()]
        override fun prefix(player: Player): String = prefixSource?.prefix(player) ?: ""
        override fun suffix(player: Player): String = prefixSource?.suffix(player) ?: ""
        override fun worldName(world: World): String = Bridges.multiverseAlias(world) ?: world.name
    }

    /** Japanize で変換しないプレイヤー名 (名前順) */
    internal fun onlinePlayerNames(): Set<String> = Bukkit.getOnlinePlayers().mapTo(TreeSet()) { it.name }

    // ---- uuidcache.yml ----

    private fun loadUuidCache() {
        uuidCache.clear()
        if (!uuidCacheFile.exists()) FlatYaml.save(uuidCacheFile, emptyMap())
        for ((k, v) in FlatYaml.load(uuidCacheFile)) if (v != null) uuidCache[k] = v.toString()
    }

    internal fun cacheName(player: Player) {
        if (uuidCache.put(player.uniqueId.toString(), player.name) != player.name) saveUuidCache()
    }

    private fun saveUuidCache() {
        val data = uuidCache.toSortedMap()
        logWriter.execute {
            try {
                FlatYaml.save(uuidCacheFile, data)
            } catch (e: Exception) {
                logger.warning("uuidcache.yml を保存できませんでした: $e")
            }
        }
    }

    // ---- スレッド ----

    /** メインスレッドで実行する。すでにメインスレッドならすぐ実行する */
    internal fun sync(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            block()
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { if (enabled) block() })
        }
    }

    /** [ticks] 後にメインスレッドで実行する (どのスレッドからでも呼べる) */
    internal fun later(ticks: Long, block: () -> Unit) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (enabled) block() }, ticks)
    }

    /** 別スレッドで実行する (どのスレッドからでも呼べる) */
    internal fun async(block: () -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable(block))
    }

    /** チャットの内容をコンソールに出す */
    internal fun consoleLog(legacy: String) {
        Bukkit.getConsoleSender().sendMessage(Colors.component(legacy))
    }

    // ---- dynmap ----

    /** dynmap の Web チャットの発言を、dynmapChannel かグローバルチャンネルに流す。どちらも無ければ dynmap に任せる */
    private fun onDynmapWebChat(name: String, source: String?, message: String): Boolean {
        val target = settings.dynmapChannel.ifEmpty { settings.globalChannel }
        if (target.isEmpty()) return false
        val channel = manager.getChannel(target) ?: return false
        sync { channel.chatFromOtherSource(name, source, message) }
        return true
    }

    // ---- チャンネルの一覧 ----

    /** 参加人数の多い順 (同じなら名前順) */
    private fun sortedChannels(): List<Channel> =
        manager.channels.sortedWith(compareByDescending<Channel> { it.onlineNum }.thenBy { it.name })

    private fun listLine(channel: Channel, display: String): Component? =
        messages.channel("listFormat", "", display, "online" to channel.onlineNum, "total" to channel.totalNum, "topic" to channel.description)

    /** /ch list。page が 0 なら全部 */
    internal fun channelList(member: ChannelMember, page: Int): List<Component> {
        val def = manager.getDefaultChannel(member.name)?.name ?: ""
        val items = sortedChannels().mapNotNull { channel ->
            if (member in channel.banned || channel.isPersonalChat) return@mapNotNull null
            var disp = when {
                channel.name.equals(def, true) -> "§c"
                member in channel.hided -> "§3"
                else -> "§f"
            } + channel.name
            if (member !in channel.members && !channel.isGlobalChannel) {
                if (!channel.visible) return@mapNotNull null
                disp = "§7" + channel.name
            }
            listLine(channel, disp)
        }
        val maxPage = items.size / LIST_PAGE_SIZE + 1
        val p = page.coerceIn(0, maxPage)
        val lines = mutableListOf<Component>()
        if (p == 0) {
            lines += Colors.component(messages.plain("listFirstLine"))
            lines += items
        } else {
            lines += Colors.component(messages.plain("listFirstLinePaging", "page" to p, "max" to maxPage))
            lines += items.subList((p - 1) * LIST_PAGE_SIZE, minOf(p * LIST_PAGE_SIZE, items.size))
        }
        lines += Colors.component(messages.plain("listEndLine"))
        return lines
    }

    /** ログイン時に見せる、参加中のチャンネル (showListOnJoin) */
    internal fun channelListForJoin(member: ChannelMember): List<Component> {
        val def = manager.getDefaultChannel(member.name)?.name ?: ""
        val lines = mutableListOf(Colors.component(messages.plain("motdFirstLine")))
        var count = 0
        for (channel in sortedChannels()) {
            if (member in channel.banned || channel.isPersonalChat) continue
            if (member !in channel.members && !channel.isGlobalChannel) continue
            val disp = (if (channel.name.equals(def, true)) "§c" else "§f") + channel.name
            listLine(channel, disp)?.let { lines += it }
            if (++count > MOTD_MAX_ITEMS) break
        }
        lines += Colors.component(messages.plain("listEndLine"))
        return lines
    }

    private companion object {
        const val LIST_PAGE_SIZE = 8
        const val MOTD_MAX_ITEMS = 8
    }
}
