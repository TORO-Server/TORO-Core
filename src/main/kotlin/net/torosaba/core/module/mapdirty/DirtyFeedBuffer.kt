package net.torosaba.core.module.mapdirty

/**
 * TORO-Renderer へ送る前の変更の貯め。窓が確定するたびに [offer] で足し、
 * 送れるときに [take] で 1 通分を取り出す。
 *
 * 変更はチャンクの集合で数えるので、窓をまたいで重なった分は 1 つに潰れてよい。
 * 経路 (Bukkit / WorldEdit) の区別は受け手では使わないので、ここで落とす。
 * 1 通には [take] の引数の数までしか入れず、超えた分は貯めに残る (捨てない)。
 */
class DirtyFeedBuffer {
    private var startMillis = -1L
    private var endMillis = -1L
    private val pending = LinkedHashMap<String, MutableSet<Long>>()

    @Synchronized
    fun offer(snapshot: WindowSnapshot) {
        if (startMillis < 0) startMillis = snapshot.startMillis
        endMillis = snapshot.endMillis
        for ((world, bySource) in snapshot.worlds) {
            val set = pending.getOrPut(world) { HashSet() }
            for (chunks in bySource.values) set += chunks
        }
    }

    /** 貯まっているチャンクの総数 */
    @Synchronized
    fun size(): Int = pending.values.sumOf { it.size }

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    /** 1 通分を取り出す。貯めが空なら null。取り出した分は貯めから消える */
    @Synchronized
    fun take(maxChunks: Int): WindowSnapshot? {
        if (pending.isEmpty()) return null
        val worlds = LinkedHashMap<String, Map<DirtySource, Set<Long>>>()
        var budget = maxChunks
        val entries = pending.entries.iterator()
        while (entries.hasNext() && budget > 0) {
            val (world, chunks) = entries.next()
            val part = if (chunks.size <= budget) chunks.toSet() else chunks.take(budget).toSet()
            chunks.removeAll(part)
            if (chunks.isEmpty()) entries.remove()
            budget -= part.size
            if (part.isNotEmpty()) worlds[world] = mapOf(DirtySource.BUKKIT to part)
        }
        if (worlds.isEmpty()) return null
        val snapshot = WindowSnapshot(startMillis, endMillis, worlds)
        if (pending.isEmpty()) {
            startMillis = -1
            endMillis = -1
        }
        return snapshot
    }
}
