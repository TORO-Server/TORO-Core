package net.torosaba.core.module.raidwatcher

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RaidWatcherIpKeyTest {
    @Test
    fun ipv6IsNormalizedTo64Prefix() {
        val a = InetAddress.getByName("2001:db8:1:2:aaaa::1")
        val b = InetAddress.getByName("2001:db8:1:2:bbbb:cccc:dddd:2")
        val other = InetAddress.getByName("2001:db8:1:3::1")

        assertEquals("2001:0db8:0001:0002::/64", RaidWatcherModule.ipKey(a))
        assertEquals(RaidWatcherModule.ipKey(a), RaidWatcherModule.ipKey(b))
        assertNotEquals(RaidWatcherModule.ipKey(a), RaidWatcherModule.ipKey(other))
    }

    @Test
    fun ipv4AndMappedIpv4StayExact() {
        val v4a = InetAddress.getByName("203.0.113.5")
        val v4b = InetAddress.getByName("203.0.113.6")
        val mapped = InetAddress.getByName("::ffff:203.0.113.5")

        assertEquals("203.0.113.5", RaidWatcherModule.ipKey(v4a))
        assertEquals("203.0.113.5", RaidWatcherModule.ipKey(mapped))
        assertNotEquals(RaidWatcherModule.ipKey(v4a), RaidWatcherModule.ipKey(v4b))
    }
}
