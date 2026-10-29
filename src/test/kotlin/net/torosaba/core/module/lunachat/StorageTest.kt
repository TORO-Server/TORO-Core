package net.torosaba.core.module.lunachat

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executor
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
}
