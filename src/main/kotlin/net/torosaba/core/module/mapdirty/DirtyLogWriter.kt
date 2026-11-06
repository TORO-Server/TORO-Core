package net.torosaba.core.module.mapdirty

import java.io.File

/**
 * 記録を JSONL で追記する。今のファイルが [maxBytes] を超えたら世代をずらし、
 * [generations] 世代 (今のファイルを含む) だけ残すので、際限なく増えることはない。
 * ファイル名は map-dirty.jsonl (今のもの) と map-dirty.1.jsonl, map-dirty.2.jsonl, ... (古いもの)。
 */
class DirtyLogWriter(
    private val dir: File,
    private val baseName: String = "map-dirty",
    private val maxBytes: Long,
    private val generations: Int,
) {
    private val active: File get() = File(dir, "$baseName.jsonl")
    private fun archive(index: Int): File = File(dir, "$baseName.$index.jsonl")

    @Synchronized
    fun append(line: String) {
        dir.mkdirs()
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val current = if (active.isFile) active.length() else 0L
        // 1 行だけでも上限を超えるときは、今のファイルが空ならそのまま書く (回転を繰り返さない)
        if (current > 0 && current + bytes.size > maxBytes) rotate()
        active.appendBytes(bytes)
    }

    private fun rotate() {
        if (generations <= 1) {
            active.delete()
            return
        }
        archive(generations - 1).delete()
        for (i in generations - 2 downTo 1) {
            val src = archive(i)
            if (src.exists()) src.renameTo(archive(i + 1))
        }
        if (active.exists()) active.renameTo(archive(1))
    }
}
