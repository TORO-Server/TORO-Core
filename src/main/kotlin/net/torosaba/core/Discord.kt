package net.torosaba.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Discord の Webhook に送る文字列と JSON の処理 */
internal object Discord {
    // Discord の上限 (https://discord.com/developers/docs/resources/message)
    const val MAX_CONTENT = 2000
    const val MAX_FIELD_NAME = 256
    const val MAX_FIELD_VALUE = 1024

    /** Markdown・メンション・リンク・絵文字として働く文字 */
    private const val MARKDOWN_CHARS = "\\*_~`|>#-+.[]()<@:"

    /** プレイヤーの文字列が書式にならないようにする。改行で見出しや引用を作れないよう、制御文字は空白にする */
    fun escape(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            when {
                c.isISOControl() -> append(' ')
                c in MARKDOWN_CHARS -> append('\\').append(c)
                else -> append(c)
            }
        }
    }

    /** [max] 文字に収める。切ったときは末尾を `…` にする。サロゲートペアとエスケープの途中では切らない */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        var body = text.substring(0, max - 1)
        if (body.lastOrNull()?.isHighSurrogate() == true) body = body.dropLast(1)
        if (body.takeLastWhile { it == '\\' }.length % 2 == 1) body = body.dropLast(1)
        return "$body…"
    }

    /** メンションを一切通知しない `allowed_mentions` */
    fun noMentions(): JsonObject = JsonObject().apply { add("parse", JsonArray()) }
}
