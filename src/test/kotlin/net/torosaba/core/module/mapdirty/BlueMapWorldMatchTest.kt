package net.torosaba.core.module.mapdirty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BlueMapDirtyHook のワールド照合 (matchesWorld) の単体テスト。
 * BlueMap 本体は読み込まず、照合だけを取り出して確かめる。
 */
class BlueMapWorldMatchTest {
    @Test
    fun `ワールド ID が「ワールド名#ディメンション」の形でも一致する`() {
        // 本番で実測した形式 (BlueMap のワールド ID は main#minecraft:overworld だった)
        assertTrue(matchesWorld("main#minecraft:overworld", null, "main"))
        // 保存フォルダ名が別の名前でも、「#」より前の部分で一致する
        assertTrue(matchesWorld("main#minecraft:overworld", "world", "main"))
    }

    @Test
    fun `無関係なワールドは一致しない`() {
        assertFalse(matchesWorld("other#minecraft:overworld", null, "main"))
        assertFalse(matchesWorld("other#minecraft:overworld", "other", "main"))
        assertFalse(matchesWorld("other", "other", "main"))
        // 前方一致では足りない (main_nether や main2 を main と取り違えない)
        assertFalse(matchesWorld("main_nether#minecraft:the_nether", null, "main"))
        assertFalse(matchesWorld("main2#minecraft:overworld", null, "main"))
        assertFalse(matchesWorld("mai#minecraft:overworld", null, "main"))
    }

    @Test
    fun `従来の照合経路はそのまま一致する`() {
        // ワールド ID の完全一致
        assertTrue(matchesWorld("main", null, "main"))
        assertTrue(matchesWorld("world_nether", null, "world_nether"))
        // 保存フォルダの末尾の名前による一致 (ワールド ID が何であっても)
        assertTrue(matchesWorld("unrelated-id", "main", "main"))
    }
}
