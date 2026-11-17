package net.torosaba.core.module.signkeep

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 看板の文字の「見た目の部分」と「実行される部分」を分ける。
 *
 * 見た目の部分 (そのまま残す): 文字そのもの、色、太字・斜体・下線・取り消し線・難読化、フォント。
 * 実行される部分 (OP 以外のデータからは決して書かない): クリックイベント (コマンド実行)、
 * ホバーイベント、Shift クリックの挿入テキスト、翻訳・スコア・セレクター・NBT・キーバインド・オブジェクト
 * といった「サーバーが解決する」要素。これらは取り除き、解決しない。
 *
 * 26.3 では OP 専用データ (`block_entity_data`) の看板は click イベントを実行しない (`allow_op_features`
 * が false のとき) が、ここで書く看板はそもそも実行される要素を持たないようにする。
 */
internal object SignTextFilter {
    /** 1 行を見た目の部分だけにする。取り除いた要素の種類は [removed] に足す */
    fun sanitize(line: Component, removed: MutableSet<String>? = null): Component {
        val style = sanitizeStyle(line.style(), removed)
        val children = line.children().map { sanitize(it, removed) }
        val base: Component = when (line) {
            is TextComponent -> Component.text(line.content())
            else -> {
                // 翻訳・スコア・セレクター・NBT・キーバインドは解決せず、文字としても書かない
                removed?.add(line.javaClass.interfaces.firstOrNull()?.simpleName ?: line.javaClass.simpleName)
                Component.empty()
            }
        }
        return base.style(style).children(children)
    }

    private fun sanitizeStyle(style: Style, removed: MutableSet<String>?): Style {
        if (style.clickEvent() != null) removed?.add("clickEvent")
        if (style.hoverEvent() != null) removed?.add("hoverEvent")
        if (style.insertion() != null) removed?.add("insertion")
        return style.clickEvent(null).hoverEvent(null).insertion(null)
    }

    fun plain(line: Component): String = PlainTextComponentSerializer.plainText().serialize(line)

    fun hasText(lines: List<Component>): Boolean = lines.any { plain(it).isNotEmpty() }
}
