package net.torosaba.core.module.lunachat

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageTest {
    private fun tempDir(): File = Files.createTempDirectory("lunachat-test").toFile().apply { deleteOnExit() }

    @Test
    fun readsLunaChatChannelFile() {
        // LunaChat 3 (SnakeYAML の dumpAsMap) が書く形
        val file = File(tempDir(), "main.yml")
        file.writeText(
            """
            alias: m
            allowcc: true
            ban_expires:
              ${'$'}0f3c5b8e-0000-4000-8000-000000000001: 1767225600000
            banned: []
            broadcast: false
            color: '&b'
            desc: メインチャンネル
            format: '&f[%color%ch&f]%displayname&a:&f %msg'
            hided: []
            japanize: null
            members:
            - ${'$'}0f3c5b8e-0000-4000-8000-000000000001
            moderator: []
            mute_expires: {}
            muted: []
            name: main
            password: ''
            range: 0
            visible: true
            world: false
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val data = FlatYaml.load(file)
        assertEquals("main", data["name"])
        assertEquals("メインチャンネル", data["desc"])
        assertEquals(listOf("\$0f3c5b8e-0000-4000-8000-000000000001"), data["members"])
        assertEquals(null, data["japanize"])
        val expires = data["ban_expires"] as Map<*, *>
        assertEquals(1767225600000L, (expires["\$0f3c5b8e-0000-4000-8000-000000000001"] as Number).toLong())
    }

    @Test
    fun flatKeysWithDotsRoundTrip() {
        val file = File(tempDir(), "dictionary.yml")
        val data = linkedMapOf<String, Any?>("w." to "笑", "nakazasi" to "中指", "0" to "テンプレ", "on" to true)
        FlatYaml.save(file, data)
        assertEquals(data, FlatYaml.load(file))
    }

    @Test
    fun chatLogWriteAndRead() {
        val dir = tempDir()
        val direct = Executor { it.run() }
        val logger = ChatLogger(dir, "main", direct)
        logger.log("§ahello, world", "Steve")
        logger.log("bye", "Alex")

        val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val file = File(dir, "logs/$today/main.log")
        assertTrue(file.isFile)
        val lines = logger.read(null, null, null, false)
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(",hello， world,Steve"))
        assertEquals(1, logger.read("Alex", null, null, false).size)
        assertEquals(1, logger.read(null, "bye", null, false).size)
        assertTrue(logger.read(null, null, null, true)[0].endsWith(",bye,Alex"))
        assertTrue(logger.read(null, null, "0101", false).isEmpty() || today.endsWith("01-01"))
    }

    @Test
    fun chatLogReadIsLimited() {
        val direct = Executor { it.run() }
        val logger = ChatLogger(tempDir(), "main", direct)
        repeat(150) { logger.log("line$it", if (it % 2 == 0) "Steve" else "Alex") }

        // 既定では新しい方から 100 行を、古い順に返す
        val lines = logger.read(null, null, null, false)
        assertEquals(ChatLogger.MAX_READ_LINES, lines.size)
        assertTrue(lines.first().contains(",line50,"))
        assertTrue(lines.last().contains(",line149,"))

        // 逆順は、これまでと同じく古い方から 100 行を新しい順に返す
        val reversed = logger.read(null, null, null, true)
        assertEquals(ChatLogger.MAX_READ_LINES, reversed.size)
        assertTrue(reversed.first().contains(",line99,"))
        assertTrue(reversed.last().contains(",line0,"))

        // 絞り込みの後の行数で数える
        val steve = logger.read("Steve", null, null, false, limit = 3)
        assertEquals(listOf(",line144,", ",line146,", ",line148,"), steve.map { line -> ",${line.split(",")[1]}," })
    }

    @Test
    fun batcherWritesEachFileOnceWithTheLatestContent() {
        val dir = tempDir()
        val file = File(dir, "channels/main.yml")
        val tasks = ArrayList<Runnable>()
        val batcher = WriteBatcher({ tasks += it })
        var built = 0

        var name = "first"
        repeat(3) {
            batcher.mark(file) {
                built++
                mapOf("name" to name)
            }
        }
        assertEquals(0, built)
        assertTrue(!file.exists())

        name = "last"
        batcher.flush()
        assertEquals(1, built)
        assertEquals(1, tasks.size)
        tasks.forEach { it.run() }
        assertEquals("last", FlatYaml.load(file)["name"])

        // 何も変わっていなければ書かない
        tasks.clear()
        batcher.flush()
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun batcherDeleteDropsPendingWriteAndKeepsOrder() {
        val dir = tempDir()
        val file = File(dir, "channels/main.yml")
        val direct = Executor { it.run() }
        val batcher = WriteBatcher(direct)

        batcher.mark(file) { mapOf("name" to "main") }
        batcher.flush()
        assertTrue(file.isFile)

        batcher.mark(file) { mapOf("name" to "changed") }
        batcher.delete(file)
        batcher.flush()
        assertTrue(!file.exists())

        // 消した後に同じ名前で作り直しても書ける
        batcher.mark(file) { mapOf("name" to "again") }
        batcher.flush()
        assertEquals("again", FlatYaml.load(file)["name"])
    }

    @Test
    fun flushBeforeShutdownKeepsAllPendingWrites() {
        // 停止のときと同じ順番 (flush → shutdown → awaitTermination) で、書き込みが失われない
        val dir = tempDir()
        val writer = Executors.newSingleThreadExecutor()
        val batcher = WriteBatcher(writer)
        val files = (1..20).map { File(dir, "channels/c$it.yml") }
        files.forEachIndexed { i, file -> batcher.mark(file) { mapOf("name" to "c${i + 1}") } }

        batcher.flush()
        writer.shutdown()
        assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS))

        files.forEachIndexed { i, file -> assertEquals("c${i + 1}", FlatYaml.load(file)["name"]) }
        assertTrue(File(dir, "channels").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun saveReplacesExistingFile() {
        val file = File(tempDir(), "defaults.yml")
        FlatYaml.save(file, mapOf("a" to "1"))
        FlatYaml.save(file, mapOf("b" to "2"))
        assertEquals(mapOf<String, Any?>("b" to "2"), FlatYaml.load(file))
    }
}
