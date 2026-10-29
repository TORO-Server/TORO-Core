package net.torosaba.core.module.raidwatcher

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.permissions.PermissionDefault
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.regex.Pattern

/**
 * 旧 raidwatcher。荒らしの大量参加とスパムへの対策。
 * - 同じ IP の別アカウントの同時参加を断る
 * - 短い時間に参加が多すぎたら、しばらく入室を制限する
 * - チャットの連投と禁止ワードを止め、Essentials でミュート・キックする
 */
class RaidWatcherModule : Module("raid-watcher", "raidwatcher") {
    private lateinit var settings: Settings
    private var essentials: EssentialsHook? = null
    private var luckPerms: LuckPermsHook? = null

    // ログインもチャットも非同期のスレッドから来る
    private val loginRate = AtomicInteger(0)
    @Volatile private var lockdownEnd = 0L
    private val chatRate = ConcurrentHashMap<UUID, AtomicInteger>()

    private val red = TextColor.color(0xFF0000)

    override fun onEnable() {
        settings = Settings.load(config)

        permission(BYPASS, PermissionDefault.OP, "プレイヤーを監視対象から除外・ログイン規制中でも参加可能")

        val pm = Bukkit.getPluginManager()
        essentials = if (pm.isPluginEnabled("Essentials")) EssentialsHook() else null
        if (essentials == null) logger.warning("Essentials が無いので、スパムと禁止ワードのミュートは行いません")
        luckPerms = if (pm.isPluginEnabled("LuckPerms")) LuckPermsHook() else null

        listen(WatchListener())
        runTimer(0, settings.login.per.toLong()) { loginRate.set(0) }
        runTimer(0, settings.spam.per.toLong()) { chatRate.values.forEach { it.set(0) } }
    }

    override fun onDisable() {
        chatRate.clear()
    }

    private inner class WatchListener : Listener {
        // PlayerLoginEvent は廃止予定なので、ログイン前のイベントで判定する。
        // 先に断られたログイン (共有 BAN など) は、元と同じく数えない
        @EventHandler(priority = EventPriority.HIGH)
        fun onLogin(event: AsyncPlayerPreLoginEvent) {
            if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
            if (hasBypass(event.uniqueId)) return

            // 同じ IP で別アカウントの同時参加
            val ip = event.address.hostAddress
            if (settings.oneIpMultiAccount && Bukkit.getOnlinePlayers().any { it.address?.hostString == ip }) {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("同一IPでの同時ログインは禁止されています。ご確認ください。", red),
                )
            }

            // 高速複数アカウントログインへの対抗
            val login = settings.login
            if (login.enable) {
                if (loginRate.get() > login.allowed) parseDateDiff(login.cooldown)?.let { lockdownEnd = it }
                if (lockdownEnd > System.currentTimeMillis()) {
                    event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("現在、サーバーが大変込み合っておりますので入室制限をかけております。時間を置いて再度お試しください。"),
                    )
                }
                loginRate.incrementAndGet()
            }
        }

        @EventHandler
        fun onAsyncChat(event: AsyncChatEvent) {
            val player = event.player
            if (player.hasPermission(BYPASS)) return
            val uuid = player.uniqueId

            val spam = settings.spam
            if (spam.enable) {
                val count = chatRate.computeIfAbsent(uuid) { AtomicInteger() }.incrementAndGet()
                if (count > spam.allowed) {
                    event.isCancelled = true
                    runSync {
                        mute(uuid, "spam", spam.penaltyMute)
                        if (spam.penaltyKick) Bukkit.getPlayer(uuid)?.kick(Component.text("スパム行為が検知されました。", red))
                    }
                }
            }

            val filter = settings.filter
            if (filter.enable) {
                val text = PlainTextComponentSerializer.plainText().serialize(event.message())
                if (filter.pattern.any { it.matcher(text).find() }) {
                    event.isCancelled = true
                    runSync {
                        mute(uuid, "blocked word", filter.penaltyMute)
                        if (filter.penaltyKick) {
                            Bukkit.getPlayer(uuid)?.kick(Component.text("あなたのチャットには禁止ワードが含まれていました。", red))
                        }
                    }
                }
            }
        }
    }

    /** まだ Player が無いので、LuckPerms か OP で raidwatcher.bypass を調べる */
    private fun hasBypass(uuid: UUID): Boolean {
        val byLuckPerms = try {
            luckPerms?.check(uuid, BYPASS)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "LuckPerms で権限を調べられませんでした", e)
            null
        }
        return byLuckPerms ?: Bukkit.getOfflinePlayer(uuid).isOp
    }

    private fun mute(uuid: UUID, reason: String, time: String?) {
        val ess = essentials ?: return
        // 元と同じく、時間の書式が違うなどで失敗しても無視する
        runCatching { ess.mute(uuid, reason, time ?: "") }
    }

    /** Essentials の書式 ("10s" "1m" など) の時間を、今からその時間がたった時刻にする */
    private fun parseDateDiff(time: String?): Long? {
        if (time == null) return null
        essentials?.let { return runCatching { it.parseDateDiff(time) }.getOrNull() }
        // Essentials が無いときの簡易版
        val units = mapOf("y" to 31_536_000_000L, "mo" to 2_592_000_000L, "w" to 604_800_000L,
            "d" to 86_400_000L, "h" to 3_600_000L, "m" to 60_000L, "s" to 1_000L)
        val parts = Regex("(\\d+)\\s*(mo|y|w|d|h|m|s)", RegexOption.IGNORE_CASE).findAll(time).toList()
        if (parts.isEmpty()) return null
        return System.currentTimeMillis() +
            parts.sumOf { it.groupValues[1].toLong() * units.getValue(it.groupValues[2].lowercase()) }
    }

    private data class Settings(
        val login: HighRateLoginBlocker,
        val spam: SpamDetector,
        val filter: ChatFilter,
        val oneIpMultiAccount: Boolean,
    ) {
        companion object {
            fun load(file: YamlConfiguration) = Settings(
                HighRateLoginBlocker(
                    file.getBoolean("HighRateLoginBlocker.enable"),
                    file.getInt("HighRateLoginBlocker.allowed"),
                    file.getInt("HighRateLoginBlocker.per"),
                    file.getString("HighRateLoginBlocker.cooldown"),
                ),
                SpamDetector(
                    file.getBoolean("SpamDetector.enable"),
                    file.getInt("SpamDetector.allowed"),
                    file.getInt("SpamDetector.per"),
                    file.getString("SpamDetector.penalty.mute"),
                    file.getBoolean("SpamDetector.penalty.kick"),
                ),
                ChatFilter(
                    file.getBoolean("ChatFilter.enable"),
                    file.getStringList("ChatFilter.pattern").map { Pattern.compile(it) },
                    file.getString("ChatFilter.penalty.mute"),
                    file.getBoolean("ChatFilter.penalty.kick"),
                ),
                file.getBoolean("OneIPMultiAccountBlocker.enable"),
            )
        }
    }

    private data class HighRateLoginBlocker(val enable: Boolean, val allowed: Int, val per: Int, val cooldown: String?)
    private data class SpamDetector(val enable: Boolean, val allowed: Int, val per: Int, val penaltyMute: String?, val penaltyKick: Boolean)
    private data class ChatFilter(val enable: Boolean, val pattern: List<Pattern>, val penaltyMute: String?, val penaltyKick: Boolean)

    private companion object {
        const val BYPASS = "raidwatcher.bypass"
    }
}
