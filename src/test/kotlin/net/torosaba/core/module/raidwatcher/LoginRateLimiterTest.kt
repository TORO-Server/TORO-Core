package net.torosaba.core.module.raidwatcher

import net.torosaba.core.module.raidwatcher.LoginRateLimiter.Result
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginRateLimiterTest {
    private fun ip(n: Int) = "203.0.113.${n % 250}"

    @Test
    fun configWithoutNewKeysGetsSafeLimits() {
        // 本番の設定ファイルに古いキーしか無いとき
        val limits = LoginLimits.of(allowed = 3, perTicks = 200)
        assertEquals(10_000L, limits.windowMillis)
        assertEquals(5, limits.perIp)
        assertEquals(5, limits.perAccount)
        assertEquals(60, limits.global)
    }

    @Test
    fun newKeysOverrideAllowed() {
        val limits = LoginLimits.of(allowed = 3, perTicks = 200, perIp = 8, perAccount = 6, global = 100)
        assertEquals(LoginLimits(8, 6, 100, 10_000L), limits)
    }

    @Test
    fun limitsCannotGoBelowTheReturnRate() {
        val limits = LoginLimits.of(allowed = 0, perTicks = 200, perIp = 1, perAccount = 1, global = 1)
        assertEquals(LoginLimits.RETURN_BATCH, limits.perIp)
        assertEquals(5, limits.perAccount)
        assertEquals(25, limits.global)

        // 期間を長くすると、戻す人数に合わせて下限も上がる
        assertEquals(105, LoginLimits.of(allowed = 3, perTicks = 1200, global = 1).global)
        assertEquals(210, LoginLimits.of(allowed = 3, perTicks = 1200).global)

        // 期間が書かれていない・0 のときも動く
        assertTrue(LoginLimits.of(allowed = 3, perTicks = 0).windowMillis > 0)
    }

    @Test
    fun limboReturnNeverTriggersLimits() {
        // 古い設定のまま、3 秒ごとに 5 人ずつ別の人が 30 分間戻り続けても断られない
        val limiter = LoginRateLimiter(LoginLimits.of(allowed = 3, perTicks = 200))
        var now = 1_000_000L
        var player = 0
        repeat(600) {
            repeat(LoginLimits.RETURN_BATCH) {
                assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(player), UUID.randomUUID(), now))
                player++
            }
            now += LoginLimits.RETURN_INTERVAL_MILLIS
        }
    }

    @Test
    fun limboRetriesOfOneAccountAreNotLimited() {
        // 戻そうとして失敗した人は 3 秒ごとに戻され直す
        val limiter = LoginRateLimiter(LoginLimits.of(allowed = 3, perTicks = 200))
        val account = UUID.randomUUID()
        var now = 1_000_000L
        repeat(100) {
            assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(1), account, now))
            now += LoginLimits.RETURN_INTERVAL_MILLIS
        }
    }

    @Test
    fun oneIpIsLimitedAloneAndRecovers() {
        val limiter = LoginRateLimiter(LoginLimits.of(allowed = 3, perTicks = 200))
        val now = 1_000_000L
        repeat(5) { assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(1), UUID.randomUUID(), now)) }
        assertEquals(Result.IP_LIMITED, limiter.tryAcquire(ip(1), UUID.randomUUID(), now))
        // ほかの人は入れる
        assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(2), UUID.randomUUID(), now))
        // 断られた分は数えないので、期間が過ぎれば入れる
        repeat(100) { limiter.tryAcquire(ip(1), UUID.randomUUID(), now + 9_000) }
        assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(1), UUID.randomUUID(), now + 10_000))
    }

    @Test
    fun oneAccountIsLimitedAcrossIps() {
        val limiter = LoginRateLimiter(LoginLimits.of(allowed = 3, perTicks = 200))
        val account = UUID.randomUUID()
        val now = 1_000_000L
        repeat(5) { assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(it), account, now)) }
        assertEquals(Result.ACCOUNT_LIMITED, limiter.tryAcquire(ip(9), account, now))
        assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(9), UUID.randomUUID(), now))
    }

    @Test
    fun globalLimitTriggersOnceAndRestartsCounting() {
        val limiter = LoginRateLimiter(LoginLimits.of(allowed = 3, perTicks = 200))
        val now = 1_000_000L
        repeat(60) { assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(it), UUID.randomUUID(), now)) }
        assertEquals(Result.GLOBAL_LIMITED, limiter.tryAcquire(ip(100), UUID.randomUUID(), now))
        // 規制が解けた直後の 1 人で、また規制が始まらない
        assertEquals(Result.ALLOWED, limiter.tryAcquire(ip(101), UUID.randomUUID(), now + 1))
    }

    @Test
    fun addressesThatCannotIdentifyAClientAreNotCountedPerIp() {
        val limiter = LoginRateLimiter(LoginLimits.of(allowed = 3, perTicks = 200))
        val now = 1_000_000L
        repeat(20) { assertEquals(Result.ALLOWED, limiter.tryAcquire(null, UUID.randomUUID(), now)) }
    }
}
