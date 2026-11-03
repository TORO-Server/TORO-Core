package net.torosaba.core.module.lunachat

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executor

/**
 * LunaChat のデータファイル (defaults.yml など) の読み書き。
 * LunaChat はキーの `.` を区切りとして扱わない平らな YAML で書くので、Bukkit の YamlConfiguration ではなく SnakeYAML を直接使う
 */
internal object FlatYaml {
    fun load(file: File, onError: (Exception) -> Unit = {}): Map<String, Any?> {
        if (!file.isFile || file.length() == 0L) return emptyMap()
        return try {
            val data = file.reader(Charsets.UTF_8).use { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(it) }
            (data as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
        } catch (e: Exception) {
            onError(e)
            emptyMap()
        }
    }

    fun save(file: File, data: Map<String, Any?>) {
        file.parentFile?.mkdirs()
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        file.writeText(Yaml(options).dump(data), Charsets.UTF_8)
    }
}

/**
 * LunaChat のチャットログ。`logs/yyyy-MM-dd/<name>.log` に `日時,発言,発言者` を 1 行ずつ足す
 */
internal class ChatLogger(private val dataFolder: File, private val name: String, private val writer: Executor) {
    private val lineFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val dirFormat get() = SimpleDateFormat("yyyy-MM-dd")

    private fun folder(date: Date) = File(dataFolder, "logs" + File.separator + dirFormat.format(date))

    fun log(message: String, player: String) {
        val now = Date()
        val file = File(folder(now), "$name.log")
        file.parentFile?.mkdirs()
        val msg = Colors.strip(message).replace(",", "，")
        val line = lineFormat.format(now) + "," + msg + "," + player + "\r\n"
        // 発言の処理を重くしないよう、書き込みは別スレッドで行う (LunaChat issue #40)
        writer.execute {
            synchronized(ChatLogger::class.java) {
                file.parentFile.mkdirs()
                file.appendText(line, Charsets.UTF_8)
            }
        }
    }

    /**
     * ログを読む。
     * @param player 発言者名で絞る (null なら絞らない)
     * @param filter 発言内容で絞る (null なら絞らない)
     * @param date `MMdd` か `yyyyMMdd`。null なら今日
     */
    fun read(player: String?, filter: String?, date: String?, reverse: Boolean): List<String> {
        val file = logFile(date) ?: return emptyList()
        if (!file.isFile) return emptyList()
        var lines = file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        if (player != null) lines = lines.filter { it.split(",").let { p -> p.size >= 3 && p[2].contains(player) } }
        if (filter != null) lines = lines.filter { it.split(",").let { p -> p.size >= 2 && p[1].contains(filter) } }
        return if (reverse) lines.reversed() else lines
    }

    private fun logFile(date: String?): File? {
        if (date == null) return File(folder(Date()), "$name.log")
        var d = date
        if (d.matches(Regex("[0-9]{4}"))) d = Calendar.getInstance().get(Calendar.YEAR).toString() + d
        if (!d.matches(Regex("[0-9]{8}"))) return null
        val parsed = try {
            SimpleDateFormat("yyyyMMdd").apply { isLenient = false }.parse(d)
        } catch (e: Exception) {
            return null
        }
        return File(folder(parsed), "$name.log").takeIf { it.isFile }
    }
}
