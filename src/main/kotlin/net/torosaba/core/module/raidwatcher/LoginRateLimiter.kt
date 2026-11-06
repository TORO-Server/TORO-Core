package net.torosaba.core.module.raidwatcher

import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.ceil

/** [windowMillis] の間に入れるログインの回数の上限 */
internal data class LoginLimits(val perIp: Int, val perAccount: Int, val global: Int, val windowMillis: Long) {
    companion object {
        // TORO-Proxy が再起動の後に limbo から戻す速さの既定 (ProxyConfig の return-batch-size と check-interval-seconds)
        const val RETURN_BATCH = 5
        const val RETURN_INTERVAL_MILLIS = 3_000L

        const val DEFAULT_GLOBAL = 60

        /** 期間の中に入る、戻す回数 (期間の両端にかかる分を 1 回足す) */
        private fun returnRounds(windowMillis: Long): Int =
            ceil(windowMillis.toDouble() / RETURN_INTERVAL_MILLIS).toInt() + 1

        /**
         * 設定の値から作る。
         *
         * - [allowed] と [perTicks] は元からある設定。IP とアカウントそれぞれの上限の既定に使う
         * - [perIp] [perAccount] [global] は書かれていなければ null
         *
         * どの値でも、limbo から戻るプレイヤーが規制に掛からないよう下限を設ける。
         * 全体の上限は戻す速さより常に高く、アカウントの上限は 1 人が戻されようとする回数より高い
         */
        fun of(allowed: Int, perTicks: Int, perIp: Int? = null, perAccount: Int? = null, global: Int? = null): LoginLimits {
            val windowMillis = perTicks.coerceAtLeast(1) * 50L
            val rounds = returnRounds(windowMillis)
            val globalFloor = rounds * RETURN_BATCH
            return LoginLimits(
                perIp = (perIp ?: allowed).coerceAtLeast(RETURN_BATCH),
                perAccount = (perAccount ?: allowed).coerceAtLeast(rounds),
                global = (global ?: maxOf(DEFAULT_GLOBAL, globalFloor * 2, allowed)).coerceAtLeast(globalFloor),
                windowMillis = windowMillis,
            )
        }
    }
}

/**
 * ログインの回数を IP・アカウント・全体の 3 つで数える。
 * 入れたログインだけを数えるので、断られ続けても期間は延びず、覚える量は全体の上限を超えない。
 * ログインのイベントは複数の非同期スレッドから来るので、[tryAcquire] はロックする
 */
internal class LoginRateLimiter(private val limits: LoginLimits) {
    enum class Result { ALLOWED, IP_LIMITED, ACCOUNT_LIMITED, GLOBAL_LIMITED }

    private val all = ArrayDeque<Long>()
    private val byIp = HashMap<String, ArrayDeque<Long>>()
    private val byAccount = HashMap<UUID, ArrayDeque<Long>>()

    /**
     * ログインを 1 回通してよいかを調べ、通すなら数える。
     * @param ip クライアントの IP。プロキシの内側のアドレスなどで個人を区別できないときは null (IP では数えない)
     */
    @Synchronized
    fun tryAcquire(ip: String?, account: UUID, now: Long): Result {
        val from = now - limits.windowMillis
        all.dropOlderThan(from)
        byIp.values.removeIf { it.dropOlderThan(from); it.isEmpty() }
        byAccount.values.removeIf { it.dropOlderThan(from); it.isEmpty() }

        if ((byAccount[account]?.size ?: 0) >= limits.perAccount) return Result.ACCOUNT_LIMITED
        if (ip != null && (byIp[ip]?.size ?: 0) >= limits.perIp) return Result.IP_LIMITED
        if (all.size >= limits.global) {
            // 全体の規制を始める。解けたら数え直す (残った分ですぐまた規制されないように)
            reset()
            return Result.GLOBAL_LIMITED
        }

        all.addLast(now)
        byAccount.getOrPut(account) { ArrayDeque() }.addLast(now)
        if (ip != null) byIp.getOrPut(ip) { ArrayDeque() }.addLast(now)
        return Result.ALLOWED
    }

    @Synchronized
    fun reset() {
        all.clear()
        byIp.clear()
        byAccount.clear()
    }

    private fun ArrayDeque<Long>.dropOlderThan(from: Long) {
        while (isNotEmpty() && peekFirst() <= from) removeFirst()
    }
}
