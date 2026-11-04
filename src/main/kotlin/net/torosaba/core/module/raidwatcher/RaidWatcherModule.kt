package net.torosaba.core.module.raidwatcher

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionDefault
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.regex.Pattern

/**
 * 旧 raidwatcher。荒らしの大量参加とスパムへの対策。
 * - 同じ IP の別アカウントの同時参加を断る
 * - 同じ IP・同じアカウントのログインが短い時間に多すぎたら、その人だけ断る
 * - 全体のログインが短い時間に多すぎたら、しばらく入室を制限する
 * - チャットの連投と禁止ワードを止め、Essentials でミュート・キックする
 */
class RaidWatcherModule : Module("raid-watcher", "raidwatcher") {
    private lateinit var settings: Settings
    private lateinit var loginLimiter: LoginRateLimiter
    private var essentials: EssentialsHook? = null
    private var luckPerms: LuckPermsHook? = null

    // ログインもチャットも非同期のスレッドから来る
    @Volatile private var lockdownEnd = 0L
    private val chatRate = ConcurrentHashMap<UUID, AtomicInteger>()
    private val onlinePlayerIps = ConcurrentHashMap<UUID, String>()

    private val red = TextColor.color(0xFF0000)

    override fun onEnable() {
        settings = Settings.load(config)
        val limits = settings.login.limits
        loginLimiter = LoginRateLimiter(limits)
        lockdownEnd = 0L
        logger.info(
            "ログインの上限 (${limits.windowMillis / 1000.0} 秒ごと): " +
                "IP ごと ${limits.perIp} 回、アカウントごと ${limits.perAccount} 回、全体 ${limits.global} 回",
        )

        permission(BYPASS, PermissionDefault.OP, "プレイヤーを監視対象から除外・ログイン規制中でも参加可能")

        val pm = Bukkit.getPluginManager()
        essentials = if (pm.isPluginEnabled("Essentials")) EssentialsHook() else null
        if (essentials == null) logger.warning("Essentials が無いので、スパムと禁止ワードのミュートは行いません")
        luckPerms = if (pm.isPluginEnabled("LuckPerms")) LuckPermsHook() else null

        listen(WatchListener())
        runTimer(0, settings.spam.per.toLong().coerceAtLeast(1)) { chatRate.values.forEach { it.set(0) } }
    }

    override fun onDisable() {
        chatRate.clear()
        onlinePlayerIps.clear()
    }

    private inner class WatchListener : Listener {
        @EventHandler
        fun onPlayerJoin(event: PlayerJoinEvent) {
            val ip = event.player.address?.address?.hostAddress ?: event.player.address?.hostString
            if (ip != null) onlinePlayerIps[event.player.uniqueId] = ip
        }

        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            onlinePlayerIps.remove(event.player.uniqueId)
            chatRate.remove(event.player.uniqueId)
        }

        // PlayerLoginEvent は廃止予定なので、ログイン前のイベントで判定する。
        // 先に断られたログイン (共有 BAN など) は、元と同じく数えない
        @EventHandler(priority = EventPriority.HIGH)
        fun onLogin(event: AsyncPlayerPreLoginEvent) {
            if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
            if (hasBypass(event.uniqueId)) return

            // 同じ IP で別アカウントの同時参加
            val ip = event.address.hostAddress
            if (settings.oneIpMultiAccount && onlinePlayerIps.values.any { it == ip }) {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("同一IPでの同時ログインは禁止されています。ご確認ください。", red),
                )
                return
            }

            // 高速複数アカウントログインへの対抗
            val login = settings.login
            if (login.enable) {
                val now = System.currentTimeMillis()
                if (lockdownEnd > now) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, BUSY_MESSAGE)
                    return
                }
                when (loginLimiter.tryAcquire(clientIp(event.address), event.uniqueId, now)) {
                    LoginRateLimiter.Result.ALLOWED -> {}
                    LoginRateLimiter.Result.IP_LIMITED, LoginRateLimiter.Result.ACCOUNT_LIMITED ->
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, TOO_MANY_LOGINS_MESSAGE)
                    LoginRateLimiter.Result.GLOBAL_LIMITED -> {
                        parseDateDiff(login.cooldown)?.let { lockdownEnd = it }
                        logger.warning("短い時間のログインが全体の上限を超えたので、${login.cooldown} の間 入室を制限します")
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, BUSY_MESSAGE)
                    }
                }
            }
        }

        @EventHandler
        fun onAsyncChat(event: AsyncChatEvent) {
            // lunachat モジュールが取り消した発言は、Channel.chat の前に判定済みなので数えない
            if (event.isCancelled && plugin.modules.any { it.id == "lunachat" && it.enabled }) return
            val text = PlainTextComponentSerializer.plainText().serialize(event.message())
            if (!checkChat(event.player, text)) event.isCancelled = true
        }
    }

    /**
     * 発言を通してよいかを調べる。連投と禁止ワードなら罰を与えて false を返す。
     * チャットのイベントと、イベントを通らない発言 (lunachat のチャンネル発言と /tell) の両方で使う。どのスレッドからでも呼べる
     */
    internal fun checkChat(player: Player, text: String): Boolean {
        if (player.hasPermission(BYPASS)) return true
        val uuid = player.uniqueId
        var allowed = true

        val spam = settings.spam
        if (spam.enable) {
            val count = chatRate.computeIfAbsent(uuid) { AtomicInteger() }.incrementAndGet()
            if (count > spam.allowed) {
                allowed = false
                runSync {
                    mute(uuid, "spam", spam.penaltyMute)
                    if (spam.penaltyKick) Bukkit.getPlayer(uuid)?.kick(Component.text("スパム行為が検知されました。", red))
                }
            }
        }

        val filter = settings.filter
        if (filter.enable && filter.pattern.any { it.matcher(text).find() }) {
            allowed = false
            runSync {
                mute(uuid, "blocked word", filter.penaltyMute)
                if (filter.penaltyKick) {
                    Bukkit.getPlayer(uuid)?.kick(Component.text("あなたのチャットには禁止ワードが含まれていました。", red))
                }
            }
        }
        return allowed
    }

    /** Essentials でミュートされているか。イベントを通らない発言 (/tell など) で確かめるのに使う */
    internal fun isMuted(player: Player): Boolean =
        !player.hasPermission(BYPASS) && runCatching { essentials?.isMuted(player.uniqueId) }.getOrNull() == true

    /** IP で人を区別できるアドレスなら文字列にする。プロキシの内側のアドレスは全員同じに見えることがあるので使わない */
    private fun clientIp(address: InetAddress): String? =
        address.takeUnless { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            ?.hostAddress

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
            // allowed-per-ip などは後から足した設定で、本番の設定ファイルには無いことがある
            private fun YamlConfiguration.intOrNull(path: String): Int? = if (isInt(path)) getInt(path) else null

            fun load(file: YamlConfiguration) = Settings(
                HighRateLoginBlocker(
                    file.getBoolean("HighRateLoginBlocker.enable"),
                    LoginLimits.of(
                        allowed = file.getInt("HighRateLoginBlocker.allowed"),
                        perTicks = file.getInt("HighRateLoginBlocker.per"),
                        perIp = file.intOrNull("HighRateLoginBlocker.allowed-per-ip"),
                        perAccount = file.intOrNull("HighRateLoginBlocker.allowed-per-account"),
                        global = file.intOrNull("HighRateLoginBlocker.allowed-global"),
                    ),
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

    private data class HighRateLoginBlocker(val enable: Boolean, val limits: LoginLimits, val cooldown: String?)
    private data class SpamDetector(val enable: Boolean, val allowed: Int, val per: Int, val penaltyMute: String?, val penaltyKick: Boolean)
    private data class ChatFilter(val enable: Boolean, val pattern: List<Pattern>, val penaltyMute: String?, val penaltyKick: Boolean)

    private companion object {
        const val BYPASS = "raidwatcher.bypass"
        val BUSY_MESSAGE = Component.text("現在、サーバーが大変込み合っておりますので入室制限をかけております。時間を置いて再度お試しください。")
        val TOO_MANY_LOGINS_MESSAGE = Component.text("短い時間に何度も接続しています。時間を置いて再度お試しください。")
    }
}
