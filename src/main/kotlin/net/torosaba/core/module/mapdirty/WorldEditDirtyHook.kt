package net.torosaba.core.module.mapdirty

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.function.operation.Operation
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.eventbus.Subscribe
import com.sk89q.worldedit.world.block.BlockStateHolder
import java.util.concurrent.ConcurrentHashMap

/**
 * WorldEdit の編集をチャンク単位で拾う。WorldEdit はブロック変更の Bukkit イベントを出さないので、
 * EditSession の extent を包んで、書き換えた位置のチャンクをセッションごとに集める。
 * WorldEdit が入っていないときはこのクラス自体を読み込まない (呼び出し側で分けてある)。
 *
 * 集めたチャンクは、セッションがコミットされたとき (extent の commit) にまとめて [DirtyWindow] へ渡す。
 * ただしコミットは 1 回限りではない。LocalSession.createEditSession は生成の仕上げに
 * EditSession.setReorderMode を呼び、その中でセッションがコミットされるため、
 * 編集が 1 件も無いうちに最初のコミットが届く。確定を 1 回限りにすると、その後の編集が
 * すべて捨てられてしまう。そのため確定は、呼ばれるたびに未送信のチャンクだけを渡す形にする。
 * コミットを通らない進み方に備えて、最後の書き換えから [IDLE_MILLIS] 静かだったセッションも
 * [sweep] が確定させる。WorldEdit 7.4.6 にはセッションの終了を知らせる公開イベントが無いための措置。
 */
class WorldEditDirtyHook(private val window: DirtyWindow) {
    private val sessions = ConcurrentHashMap.newKeySet<SessionChunks>()
    private val handler = Handler()

    fun start() {
        WorldEdit.getInstance().eventBus.register(handler)
    }

    fun stop() {
        WorldEdit.getInstance().eventBus.unregister(handler)
        // 残っているセッションは全部確定させる
        for (session in sessions.toList()) session.publish()
        sessions.clear()
    }

    /** 静かになったセッションを確定させて手放す。モジュールのタイマーから定期的に呼ぶ */
    fun sweep(nowMillis: Long) {
        for (session in sessions) {
            if (nowMillis - session.lastActivityMillis >= IDLE_MILLIS) {
                session.publish()
                sessions.remove(session)
            }
        }
    }

    inner class Handler {
        @Subscribe
        fun onEditSession(event: EditSessionEvent) {
            if (event.stage != EditSession.Stage.BEFORE_CHANGE) return
            val worldName = event.world?.name ?: return
            val session = SessionChunks(worldName)
            sessions += session
            event.extent = RecordingExtent(event.extent, session)
        }
    }

    inner class SessionChunks(val worldName: String) {
        val chunks: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        @Volatile
        var lastActivityMillis: Long = System.currentTimeMillis()
            private set

        fun record(blockX: Int, blockZ: Int) {
            chunks += ChunkMath.pack(ChunkMath.chunkOf(blockX), ChunkMath.chunkOf(blockZ))
            lastActivityMillis = System.currentTimeMillis()
        }

        /**
         * 集めたチャンクを窓へ渡し、手元からは消す。
         * 編集前にコミットが届くことがある (クラスの説明参照) ので 1 回限りにはせず、
         * 空のうちに呼ばれたら何もしない。後の編集は次の確定で渡る
         */
        fun publish() {
            if (chunks.isEmpty()) return
            val drained = chunks.toList()
            for (packed in drained) chunks.remove(packed)
            window.addChunks(worldName, drained, DirtySource.WORLDEDIT)
        }
    }

    inner class RecordingExtent(extent: Extent, private val session: SessionChunks) :
        AbstractDelegateExtent(extent) {

        // 7.4.6 では BlockVector3 の int アクセサが全て非推奨になっている (ベクトルの移行中)。
        // ブロック位置は常に整数なので、レコードの要素をそのまま読むのが正確
        @Suppress("DEPRECATION")
        override fun <T : BlockStateHolder<T>> setBlock(position: BlockVector3, block: T): Boolean {
            session.record(position.x, position.z)
            return super.setBlock(position, block)
        }

        override fun commitBefore(): Operation? {
            // EditSession のコミットで、集めたチャンクをまとめて dirty にする
            session.publish()
            return super.commitBefore()
        }
    }

    private companion object {
        const val IDLE_MILLIS = 3000L
    }
}
