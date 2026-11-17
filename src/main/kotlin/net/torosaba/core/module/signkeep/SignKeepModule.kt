package net.torosaba.core.module.signkeep

import io.papermc.paper.event.player.PlayerOpenSignEvent
import net.kyori.adventure.text.Component
import net.torosaba.core.Module
import net.torosaba.core.module.fakeop.FakeOp
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.permissions.PermissionDefault

/**
 * 文字入りの看板を置いた直後の編集画面を開かせず、旧い形式のアイテムの看板データを OP 以外の建築担当にも
 * 書く (Minecraft 26.3 の仕様変更への対策)。
 *
 * 26.3 + Paper 245 で実測した事実 (ローカルの実クライアント):
 * - `sign_text_front` component を持つアイテム (26.3 の Ctrl+ピックブロックが作る形式) は、バニラが誰にでも
 *   適用する。文字は残るが、設置の直後に編集画面が開く。
 * - `block_entity_data` だけで文字を持つ旧い形式のアイテムは、OP 以外には適用されず、OP でも適用後に
 *   component (無ければ空) で上書きされて白紙になる。Paper の `BlockStateMeta` でも読めない。
 *
 * このモジュールのすること:
 * - 設置のイベントで、置いた看板が白紙なのにアイテムが旧い形式の文字を持っていれば、サーバー自身がその
 *   中身を書く (TextBeacon と同じ方式)。書くのは [permission] を持つプレイヤー (と、設定すれば OP) だけ。
 *   書くのは「見た目の部分」(文字・色・光る文字) だけで、クリックイベントなどの「実行される部分」は
 *   決して書かない ([SignTextFilter])。
 * - 置いた看板に文字があるとき (サーバーが書いた場合も、バニラが書いた場合も) 設置直後の編集画面を止める。
 *   空の看板は何もしない。MarumaSign などが編集画面へ文字を流し込む通常の流れを妨げないため。
 *   看板を右クリックして開く場合 (INTERACT) も対象にしない。
 * - 設定 `fake-op.enabled` を true にすると、共有部品 [FakeOp] を通して対象のプレイヤーの
 *   クライアントにだけ OP の権限レベルを伝える (サーバー側の権限は変えない)。看板の文字を残すだけなら
 *   不要 (ピックブロックの判定はサーバー側で、Paper の `minecraft.nbt.copy` 権限による)。
 */class SignKeepModule : Module("sign-keep"), Listener {
    private var log = true
    private var permissionNode = DEFAULT_PERMISSION
    private var includeOps = true
    private var writeFromItem = true
    private var copyBack = true

    override fun onEnable() {
        log = config.getBoolean("log", true)
        permissionNode = config.getString("permission", DEFAULT_PERMISSION)!!.ifBlank { DEFAULT_PERMISSION }
        includeOps = config.getBoolean("include-ops", true)
        writeFromItem = config.getBoolean("write-from-item", true)
        copyBack = config.getBoolean("copy-back-side", true)
        permission(DEFAULT_PERMISSION, PermissionDefault.FALSE, "文字入りの看板を置くときの補助 (sign-keep) の対象にする")
        listen(this)

        if (config.getBoolean("fake-op.enabled", false)) {
            val level = config.getInt("fake-op.level", FakeOp.GAMEMASTER_LEVEL)
            FakeOp.register(FAKE_OP_OWNER, level, ::isTarget)
            logger.info("対象のプレイヤーのクライアントに権限レベル ${level.coerceIn(1, 4)} を伝えます (fake-op モジュールが有効のとき)")
            // すでに入っているプレイヤーにもすぐ反映する (/torocore reload など)
            Bukkit.getOnlinePlayers().forEach { FakeOp.refresh(it) }
        }
    }

    override fun onDisable() {
        FakeOp.unregister(FAKE_OP_OWNER, Bukkit.getOnlinePlayers())
    }

    /** このモジュールの対象か: 設定の権限を持つ、または (設定すれば) OP */
    internal fun isTarget(player: Player): Boolean =
        player.hasPermission(permissionNode) || (includeOps && player.isOp)

    // アイテムを JSON にするのは UnsafeValues だけ。Paper 26.3-245 で動作を確認済み (バージョンを上げたら再確認する)
    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!writeFromItem) return
        val placed = event.blockPlaced.state as? Sign ?: return
        if (!isTarget(event.player)) return
        // サーバーがすでに中身を書いていれば (OP や、バニラが誰にでも適用する sign_text_front component 経由) 何もしない
        if (Side.values().any { hasText(placed, it) }) return
        val source = try {
            LegacySignData.fromItemJson(Bukkit.getUnsafe().serializeItemAsJson(event.itemInHand))
        } catch (e: Exception) {
            logger.warning("アイテムの看板データを読めませんでした (player=${event.player.name}): ${e.message}")
            null
        } ?: return
        if (listOfNotNull(source.front, source.back).none { it.hasText() }) return

        val removed = linkedSetOf<String>()
        fun write(side: Side, data: SideData?) {
            if (data == null) return
            val to = placed.getSide(side)
            data.lines.forEachIndexed { i, line -> to.line(i, SignTextFilter.sanitize(line, removed)) }
            to.color = data.color
            to.isGlowingText = data.glowing
        }
        write(Side.FRONT, source.front)
        if (copyBack) write(Side.BACK, source.back)
        placed.update(true, false)
        if (log) {
            val note = if (removed.isEmpty()) "" else " 取り除いた要素=$removed"
            logger.info("アイテムの block_entity_data から看板の文字を書きました (player=${event.player.name})$note")
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onOpenSign(event: PlayerOpenSignEvent) {
        if (event.cause != PlayerOpenSignEvent.Cause.PLACE) return
        if (!isTarget(event.player)) return
        if (!shouldKeep(event.cause, hasText(event.sign, event.side))) return
        event.isCancelled = true
        if (log) logger.info("文字入りの看板の編集画面を開かないようにしました (player=${event.player.name})")
    }

    private fun hasText(sign: Sign, side: Side): Boolean = SignTextFilter.hasText(sign.getSide(side).lines())

    internal companion object {
        const val DEFAULT_PERMISSION = "torocore.signkeep.use"
        const val FAKE_OP_OWNER = "sign-keep"

        /** 設置直後 (PLACE) で、表側に文字があるときだけ編集画面を止める */
        fun shouldKeep(cause: PlayerOpenSignEvent.Cause, hasText: Boolean): Boolean =
            cause == PlayerOpenSignEvent.Cause.PLACE && hasText

        fun hasText(lines: List<Component>): Boolean = SignTextFilter.hasText(lines)
    }
}
