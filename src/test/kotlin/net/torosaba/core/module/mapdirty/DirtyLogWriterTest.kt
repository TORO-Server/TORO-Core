package net.torosaba.core.module.mapdirty

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirtyLogWriterTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `上限を超えたら世代をずらし、世代数だけ残す`() {
        val writer = DirtyLogWriter(dir, "map-dirty", maxBytes = 30, generations = 3)
        // 1 行 10 バイト ("123456789" + 改行)。3 行で 30 バイトちょうど、次の行で回転する
        repeat(3) { writer.append("123456789") }
        writer.append("AAAAAAAAA") // ここで回転する
        writer.append("BBBBBBBBB")
        writer.append("CCCCCCCCC")
        writer.append("DDDDDDDDD") // もう一度回転する
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("map-dirty.1.jsonl", "map-dirty.2.jsonl", "map-dirty.jsonl"), names)
        assertTrue(File(dir, "map-dirty.jsonl").readText().contains("DDDDDDDDD"))
        assertTrue(File(dir, "map-dirty.1.jsonl").readText().contains("AAAAAAAAA"))
        assertTrue(File(dir, "map-dirty.2.jsonl").readText().contains("123456789"))
        // さらに書いても世代は増えない
        repeat(3) { writer.append("EEEEEEEEE") }
        assertEquals(3, dir.listFiles()!!.size)
        assertFalse(File(dir, "map-dirty.3.jsonl").exists())
    }

    @Test
    fun `1 行が上限より大きくても空のファイルにはそのまま書く`() {
        val writer = DirtyLogWriter(dir, "map-dirty", maxBytes = 5, generations = 2)
        writer.append("123456789012345")
        assertEquals("123456789012345\n", File(dir, "map-dirty.jsonl").readText())
        writer.append("x")
        // 2 行目は回転してから書かれる
        assertEquals("x\n", File(dir, "map-dirty.jsonl").readText())
        assertTrue(File(dir, "map-dirty.1.jsonl").readText().contains("123456789012345"))
    }

    @Test
    fun `世代が 1 のときはファイルを消して書き直す`() {
        val writer = DirtyLogWriter(dir, "map-dirty", maxBytes = 10, generations = 1)
        writer.append("123456789")
        writer.append("ABCDEFGHI")
        assertEquals("ABCDEFGHI\n", File(dir, "map-dirty.jsonl").readText())
        assertEquals(1, dir.listFiles()!!.size)
    }
}
