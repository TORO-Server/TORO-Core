package net.torosaba.core.module.lunachat

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /** 一時ファイルに書いてから置き換える (書いている途中で止まっても、前の内容が残る) */
    fun save(file: File, data: Map<String, Any?>) {
        file.parentFile?.mkdirs()
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(Yaml(options).dump(data), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * データファイルの書き込みをまとめる。
 *
 * [mark] は書く印を付けるだけで、[flush] のときに中身を作り、[writer] のスレッドで書く。
 * 同じファイルに何度印を付けても、次の [flush] で 1 回だけ、そのときの内容を書く。
 * [writer] が単一スレッドなら、書き込みと削除は呼んだ順に行われる
 */
internal class WriteBatcher(private val writer: Executor, private val onError: (File, Exception) -> Unit = { _, _ -> }) {
    private val pending = LinkedHashMap<File, () -> Map<String, Any?>>()

    /** 次の [flush] で [file] を書く。中身は [flush] のときに [data] から作る */
    fun mark(file: File, data: () -> Map<String, Any?>) {
        synchronized(pending) { pending[file] = data }
    }

    /** [file] を消す。まだ書いていない内容は捨てる */
    fun delete(file: File) {
        synchronized(pending) { pending.remove(file) }
        writer.execute {
            if (file.exists() && !file.delete()) onError(file, IOException("ファイルを消せませんでした"))
        }
    }

    /** 印の付いたファイルを書きに行く。中身の写しは呼んだスレッドで作るので、メインスレッドで呼ぶ */
    fun flush() {
        val batch = synchronized(pending) { pending.toMap().also { pending.clear() } }
        for ((file, data) in batch) {
            // 1 つのファイルの失敗で、残りのファイルを書き損ねないようにする
            val snapshot = try {
                data()
            } catch (e: Exception) {
                onError(file, e)
                continue
            }
            writer.execute {
                try {
                    FlatYaml.save(file, snapshot)
                } catch (e: Exception) {
                    onError(file, e)
                }
            }
        }
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
     * ログを読む。大きなファイルでも、メモリには [limit] 行までしか持たない。
     * ファイルを読むので、メインスレッドでは呼ばない。
     *
     * @param player 発言者名で絞る (null なら絞らない)
     * @param filter 発言内容で絞る (null なら絞らない)
     * @param date `MMdd` か `yyyyMMdd`。null なら今日
     * @param reverse 逆順にする
     * @param limit 返す行数の上限。普通は一番新しい行から、[reverse] なら一番古い行から数える
     */
    fun read(player: String?, filter: String?, date: String?, reverse: Boolean, limit: Int = MAX_READ_LINES): List<String> {
        val file = logFile(date) ?: return emptyList()
        if (!file.isFile) return emptyList()
        val lines = ArrayDeque<String>()
        file.useLines(Charsets.UTF_8) { sequence ->
            for (raw in sequence) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (player != null && line.split(",").let { p -> p.size < 3 || !p[2].contains(player) }) continue
                if (filter != null && line.split(",").let { p -> p.size < 2 || !p[1].contains(filter) }) continue
                if (reverse && lines.size >= limit) break
                lines.addLast(line)
                if (lines.size > limit) lines.removeFirst()
            }
        }
        return if (reverse) lines.reversed() else lines.toList()
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

    companion object {
        /** /ch log で見せる行数の上限 */
        const val MAX_READ_LINES = 100
    }
}
