package net.torosaba.core.module.fakeop

import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeOpTest {
    private fun player(name: String): Player = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> name
            "hashCode" -> name.hashCode()
            "equals" -> false
            "toString" -> name
            else -> null
        }
    } as Player

    @Test
    fun noRequestMeansNoFakeLevel() {
        assertEquals(0, FakeOp.effectiveLevel(emptyList(), player("a")))
    }

    @Test
    fun onlyMatchingRequestsCount_andTheMaximumWins() {
        val reqs = listOf(
            FakeOp.Request("one", 2) { it.name == "builder" },
            FakeOp.Request("two", 3) { it.name == "builder" },
            FakeOp.Request("three", 4) { it.name == "other" },
        )
        assertEquals(3, FakeOp.effectiveLevel(reqs, player("builder")))
        assertEquals(4, FakeOp.effectiveLevel(reqs, player("other")))
        assertEquals(0, FakeOp.effectiveLevel(reqs, player("nobody")))
    }

    @Test
    fun registerClampsAndIgnoresNonPositiveLevels() {
        FakeOp.clear()
        FakeOp.register("x", 9) { true }
        assertEquals(4, FakeOp.levelFor(player("p")))
        FakeOp.register("x", 0) { true }
        assertEquals(0, FakeOp.levelFor(player("p")))
        FakeOp.clear()
    }
}
