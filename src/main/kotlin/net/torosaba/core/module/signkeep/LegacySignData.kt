package net.torosaba.core.module.signkeep

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.DyeColor

/** 看板の片面 (表か裏) のデータ */
internal class SideData(val lines: List<Component>, val color: DyeColor, val glowing: Boolean) {
    fun hasText(): Boolean = SignTextFilter.hasText(lines)
}

internal class LegacySign(val front: SideData?, val back: SideData?)

/**
 * アイテムの `minecraft:block_entity_data` (看板の旧い形式のデータ) を読む。
 *
 * 26.3 では、アイテムに `block_entity_data` だけで看板の文字を持たせても、
 * - OP 以外: サーバーはそもそも適用しない (OP 専用データ型)
 * - OP / `minecraft.nbt.place` を持つクリエイティブ: 適用したあと、アイテムの `sign_text_front` などの
 *   component (無ければ空) で上書きされ、結果は白紙になる (Paper 26.3-245 のバイトコードで確認)
 * うえに、Paper の `BlockStateMeta` はこの形式の文字を読めない (空になる。実測)。
 * そのためアイテムを JSON に直した結果 (`serializeItemAsJson`) から、`block_entity_data` を直接読む。
 * `sign_text_front` / `sign_text_back` の component を持つアイテムは、バニラが誰にでも適用するので扱わない。
 */
internal object LegacySignData {
    private val gson = GsonComponentSerializer.gson()
    private val booleanKeys = setOf("bold", "italic", "underlined", "strikethrough", "obfuscated")

    /** `UnsafeValues#serializeItemAsJson` の結果。看板の `block_entity_data` が無いときは null */
    fun fromItemJson(item: JsonObject): LegacySign? {
        val raw = item.getAsJsonObject("components")?.get("minecraft:block_entity_data") ?: return null
        val data: Any? = when {
            raw.isJsonPrimitive -> Snbt.parse(raw.asString)
            raw.isJsonObject -> fromJson(raw)
            else -> null
        }
        return fromData(data as? Map<*, *> ?: return null)
    }

    fun fromData(data: Map<*, *>): LegacySign? {
        val id = data["id"] as? String
        if (id != "minecraft:sign" && id != "minecraft:hanging_sign") return null
        val front = side(data["front_text"] as? Map<*, *>)
        val back = side(data["back_text"] as? Map<*, *>)
        if (front == null && back == null) return null
        return LegacySign(front, back)
    }

    private fun side(text: Map<*, *>?): SideData? {
        if (text == null || text.isEmpty()) return null
        val messages = text["messages"] as? List<*> ?: return null
        val lines = (0 until 4).map { i -> if (i < messages.size) toComponent(messages[i]) else Component.empty() }
        val color = runCatching { DyeColor.valueOf((text["color"] as? String ?: "black").uppercase()) }.getOrDefault(DyeColor.BLACK)
        val glow = when (val g = text["has_glowing_text"]) {
            is Snbt.SnbtByte -> g.value != 0
            is Boolean -> g
            else -> false
        }
        return SideData(lines, color, glow)
    }

    /** 1 行。文字列はそのまま文字、複合タグは JSON の文章コンポーネントとして読む。読めなければ空 */
    internal fun toComponent(value: Any?): Component = try {
        when (value) {
            is String -> Component.text(value)
            is Map<*, *> -> gson.deserializeFromTree(toJson(value, null))
            else -> Component.empty()
        }
    } catch (_: Exception) {
        Component.empty()
    }

    private fun toJson(v: Any?, key: String?): JsonElement = when (v) {
        is Map<*, *> -> JsonObject().also { o -> v.forEach { (k, x) -> o.add(k.toString(), toJson(x, k.toString())) } }
        is List<*> -> JsonArray().also { a -> v.forEach { a.add(toJson(it, null)) } }
        is String -> JsonPrimitive(v)
        is Snbt.SnbtByte -> if (key in booleanKeys) JsonPrimitive(v.value != 0) else JsonPrimitive(v.value)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonNull.INSTANCE
    }

    /** Gson の木を、[Snbt] と同じ形 (Map / List / String / Long / Double / Boolean) にする */
    private fun fromJson(e: JsonElement): Any? = when {
        e.isJsonObject -> e.asJsonObject.entrySet().associate { it.key to fromJson(it.value) }
        e.isJsonArray -> e.asJsonArray.map { fromJson(it) }
        e.isJsonPrimitive -> {
            val p = e.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber.let { n -> if (n.toDouble() % 1.0 == 0.0) n.toLong() else n.toDouble() }
                else -> p.asString
            }
        }
        else -> null
    }
}
