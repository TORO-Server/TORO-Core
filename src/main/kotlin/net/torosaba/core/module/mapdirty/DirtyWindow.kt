package net.torosaba.core.module.mapdirty

import java.util.EnumMap

/** 変更の検知経路 */
enum class DirtySource(val jsonName: String) {
    BUKKIT("bukkit"),
    WORLDEDIT("worldedit"),
}

/**
 * ブロックの変更をチャンク単位で集める窓。
 * 最初の変更で窓が開き、[windowMillis] が過ぎたら 1 件の記録として取り出せる。
 * イベントハンドラ (ゲームスレッド) や WorldEdit のスレッドから呼ばれるので、
 * 追加はロック付きの集合への put だけにして軽く保つ。個別のブロック座標は持たない。
 */
class DirtyWindow(
    private val windowMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // ワールド名 -> 経路 -> チャンク集合
    private val pending = LinkedHashMap<String, EnumMap<DirtySource, MutableSet<Long>>>()
    private var windowStartMillis = -1L

    @Synchronized
    fun add(world: String, blockX: Int, blockZ: Int, source: DirtySource) {
        addPacked(world, ChunkMath.pack(ChunkMath.chunkOf(blockX), ChunkMath.chunkOf(blockZ)), source)
    }

    @Synchronized
    fun addChunk(world: String, chunkX: Int, chunkZ: Int, source: DirtySource) {
        addPacked(world, ChunkMath.pack(chunkX, chunkZ), source)
    }

    @Synchronized
    fun addChunks(world: String, chunks: Collection<Long>, source: DirtySource) {
        for (packed in chunks) addPacked(world, packed, source)
    }

    private fun addPacked(world: String, packed: Long, source: DirtySource) {
        if (windowStartMillis < 0) windowStartMillis = clock()
        pending.getOrPut(world) { EnumMap(DirtySource::class.java) }
            .getOrPut(source) { HashSet() } += packed
    }

    /** 窓が閉じる時刻を過ぎていれば記録を取り出して空にする。まだなら null */
    @Synchronized
    fun takeIfDue(): WindowSnapshot? {
        if (windowStartMillis < 0) return null
        val now = clock()
        if (now - windowStartMillis < windowMillis) return null
        return drain(now)
    }

    /** 窓の途中でも、残っている変更を記録として取り出す (モジュールの停止時用) */
    @Synchronized
    fun flush(): WindowSnapshot? {
        if (windowStartMillis < 0) return null
        return drain(clock())
    }

    private fun drain(endMillis: Long): WindowSnapshot {
        val worlds = LinkedHashMap<String, Map<DirtySource, Set<Long>>>()
        for ((world, bySource) in pending) {
            val copy = EnumMap<DirtySource, Set<Long>>(DirtySource::class.java)
            for ((source, chunks) in bySource) copy[source] = HashSet(chunks)
            worlds[world] = copy
        }
        val start = windowStartMillis
        pending.clear()
        windowStartMillis = -1
        return WindowSnapshot(start, endMillis, worlds)
    }
}

/** 1 つの窓の記録。JSONL の 1 行になる */
data class WindowSnapshot(
    val startMillis: Long,
    val endMillis: Long,
    val worlds: Map<String, Map<DirtySource, Set<Long>>>,
) {
    fun count(source: DirtySource): Int = worlds.values.sumOf { it[source]?.size ?: 0 }

    fun toJson(): String = buildString {
        append("{\"start\":").append(startMillis)
        append(",\"end\":").append(endMillis)
        append(",\"worlds\":{")
        var firstWorld = true
        for ((world, bySource) in worlds) {
            if (!firstWorld) append(',')
            firstWorld = false
            append(json(world)).append(":{")
            var firstSource = true
            for ((source, chunks) in bySource) {
                if (!firstSource) append(',')
                firstSource = false
                append(json(source.jsonName)).append(":[")
                chunks.map { ChunkMath.unpackX(it) to ChunkMath.unpackZ(it) }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                    .forEachIndexed { i, (x, z) ->
                        if (i > 0) append(',')
                        append('[').append(x).append(',').append(z).append(']')
                    }
                append(']')
            }
            append('}')
        }
        append("},\"counts\":{")
        DirtySource.entries.forEachIndexed { i, source ->
            if (i > 0) append(',')
            append(json(source.jsonName)).append(':').append(count(source))
        }
        append("}}")
    }

    private fun json(text: String): String = buildString {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
