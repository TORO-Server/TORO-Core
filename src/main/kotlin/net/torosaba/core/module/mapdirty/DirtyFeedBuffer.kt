package net.torosaba.core.module.mapdirty

/**
 * TORO-Renderer へ送る前の変更の貯め。窓が確定するたびに [offer] で足し、
 * 送れるときに [take] で 1 通分を取り出す。
 *
 * 変更はチャンクの集合で数えるので、窓をまたいで重なった分は 1 つに潰れてよい。
 * 経路 (Bukkit / WorldEdit) の区別は受け手では使わないので、ここで落とす。
 * 1 通には [take] の引数の数までしか入れず、超えた分は貯めに残る (捨てない)。
 */
class DirtyFeedBuffer(private val maxChunks: Int = DEFAULT_MAX_CHUNKS) {
    private var startMillis = -1L
    private var endMillis = -1L
    private val pending = LinkedHashMap<String, MutableSet<Long>>()

    @Synchronized
    fun offer(snapshot: WindowSnapshot) {
        if (startMillis < 0) startMillis = snapshot.startMillis
        endMillis = snapshot.endMillis
        var remaining = (maxChunks - size()).coerceAtLeast(0)
        if (remaining == 0) return
        for ((world, bySource) in snapshot.worlds) {
            if (remaining == 0) break
            val set = pending.getOrPut(world) { HashSet() }
            for (chunks in bySource.values) {
                for (c in chunks) {
                    if (set.add(c)) {
                        remaining--
                        if (remaining == 0) break
                    }
                }
                if (remaining == 0) break
            }
            if (set.isEmpty()) pending.remove(world)
        }
    }

    /** 貯まっているチャンクの総数 */
    @Synchronized
    fun size(): Int = pending.values.sumOf { it.size }

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    /** 1 通分を取り出す。貯めが空なら null。取り出した分は貯めから消える */
    @Synchronized
    fun take(maxChunks: Int): WindowSnapshot? = takeWithin(maxChunks = maxChunks, maxBytes = Int.MAX_VALUE)

    /** JSON の UTF-8 バイト数が [maxBytes] 以下になる範囲で 1 通分を取り出す */
    @Synchronized
    fun takeMaxBytes(maxBytes: Int): WindowSnapshot? = takeWithin(maxChunks = Int.MAX_VALUE, maxBytes = maxBytes)

    private fun takeWithin(maxChunks: Int, maxBytes: Int): WindowSnapshot? {
        if (pending.isEmpty() || maxChunks <= 0) return null
        val worlds = LinkedHashMap<String, Map<DirtySource, Set<Long>>>()
        var budgetChunks = maxChunks
        // {"t":"dirty","start":<long>,"end":<long>,"worlds":{},"counts":{"bukkit":99999999,"worldedit":0}}
        val baseOverhead = 96 + startMillis.toString().length + endMillis.toString().length
        var usedBytes = baseOverhead
        val entries = pending.entries.iterator()
        while (entries.hasNext() && budgetChunks > 0) {
            val (world, chunks) = entries.next()
            val worldOverhead = worldJsonBytes(world) + 14 + (if (worlds.isEmpty()) 0 else 1)
            if (worlds.isNotEmpty() && usedBytes + worldOverhead >= maxBytes) break
            val part = LinkedHashSet<Long>()
            var worldBytes = worldOverhead
            val it = chunks.iterator()
            while (it.hasNext() && budgetChunks > 0) {
                val packed = it.next()
                val chunkStr = "[${ChunkMath.unpackX(packed)},${ChunkMath.unpackZ(packed)}]"
                val chunkBytes = chunkStr.length + (if (part.isEmpty()) 0 else 1)
                if ((worlds.isNotEmpty() || part.isNotEmpty()) && usedBytes + worldBytes + chunkBytes > maxBytes) {
                    break
                }
                part += packed
                it.remove()
                worldBytes += chunkBytes
                budgetChunks--
            }
            if (chunks.isEmpty()) entries.remove()
            if (part.isNotEmpty()) {
                worlds[world] = mapOf(DirtySource.BUKKIT to part)
                usedBytes += worldBytes
            } else {
                break
            }
        }
        if (worlds.isEmpty()) return null
        val snapshot = WindowSnapshot(startMillis, endMillis, worlds)
        if (pending.isEmpty()) {
            startMillis = -1
            endMillis = -1
        }
        return snapshot
    }

    private fun worldJsonBytes(world: String): Int {
        var bytes = 2 // quotes
        for (c in world) {
            bytes += when {
                c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' -> 2
                c < ' ' -> 6
                else -> c.toString().toByteArray(Charsets.UTF_8).size
            }
        }
        return bytes
    }

    companion object {
        /** 貯められる最大チャンク数。超えた分は領域ファイルの定期走査に任せる */
        const val DEFAULT_MAX_CHUNKS = 50_000
    }
}
