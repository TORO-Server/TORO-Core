package net.torosaba.core.module.lunachat.japanize

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import net.torosaba.core.module.lunachat.Colors
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Pattern

/** Japanize 変換の種類。設定には id (大文字小文字は問わない) で書く */
enum class JapanizeType(val id: String) {
    NONE("none"),
    KANA("kana"),
    GOOGLE_IME("googleime");

    override fun toString(): String = id

    companion object {
        fun fromId(id: String?, def: JapanizeType?): JapanizeType? =
            if (id == null) def else entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: def
    }
}

/** Google の変換 API で、ひらがなの文を漢字かな混じりにする (第 1 候補をつなげる) */
object GoogleIme {
    // LunaChat と同じ URL。`|` は java.net.URI が受け付けないので %7C にしてある
    private const val URL = "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text="

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /** 変換する。通信や解析に失敗したら null。呼んだスレッドで通信を待つ */
    fun convert(hiragana: String): String? {
        if (hiragana.isEmpty()) return ""
        return try {
            val request = HttpRequest.newBuilder(URI.create(URL + URLEncoder.encode(hiragana, Charsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (response.statusCode() != 200) null else parseJson(response.body())
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            null
        }
    }

    /** `[["にほんご",["日本語",...]], ...]` の各第 1 候補をつなげる */
    fun parseJson(json: String): String {
        val result = StringBuilder()
        for (segment in JsonParser.parseString(json).asJsonArray) {
            result.append((segment.asJsonArray[1] as JsonArray)[0].asString)
        }
        return result.toString()
    }
}

/**
 * LunaChat の Japanize 変換 (JapanizeConvertTask) と、変換するかどうかの判定。
 * 通信を伴うので、メインスレッドでは呼ばない。
 */
object Japanizer {
    private val urlRegex = Regex("https?://[\\w/:%#\\$&\\?\\(\\)~\\.=\\+\\-]+")
    private val halfKatakanaOnly = Regex("[ \\uFF61-\\uFF9F]+")

    /**
     * 2 バイト文字 (ASCII 以外) を含むか、半角カタカナと空白だけなら、変換しない。
     * [stripped] は色コードを取り除いたもの
     */
    fun isSkipped(stripped: String): Boolean =
        stripped.toByteArray(Charsets.UTF_8).size > stripped.length || halfKatakanaOnly.matches(stripped)

    /**
     * 発言の頭に [marker] が付いていれば、外した発言を返す。付いていなければ null。
     * 付いていたら一時的に変換しない
     */
    fun stripMarker(message: String, marker: String): String? =
        if (marker.isNotEmpty() && message.startsWith(marker)) message.substring(marker.length) else null

    /**
     * 変換する。
     *
     * @param org 元の発言 (色コードを含んでよい)
     * @param keywords 変換しない語 (オンラインのプレイヤー名など)
     * @param dictionary 辞書 (語 → 変換後)
     * @param ime ひらがなを漢字にする関数。失敗したら null (そのときはかなのまま)
     */
    fun japanize(
        org: String,
        type: JapanizeType,
        keywords: Collection<String>,
        dictionary: Map<String, String>,
        ngwords: List<Pattern>,
        ime: (String) -> String? = GoogleIme::convert,
    ): String {
        if (type == JapanizeType.NONE) return org

        // 色コードと URL を取り除く
        val deletedUrl = Colors.strip(org.replace(urlRegex, " "))

        // 変換しない語を ＜１＞ などに置き換えておく
        val locked = HashMap<String, String>()
        var index = 0
        var text = deletedUrl
        for (keyword in keywords) {
            if (keyword.isNotEmpty() && text.contains(keyword)) {
                val key = "＜${fullWidthDigits(++index)}＞"
                text = text.replace(keyword, key)
                locked[key] = keyword
            }
        }
        for ((word, value) in dictionary) {
            if (word.isNotEmpty() && text.contains(word)) {
                val key = "＜${fullWidthDigits(++index)}＞"
                text = text.replace(word, key)
                locked[key] = value
            }
        }

        var japanized = KanaConverter.conv(text)
        if (type == JapanizeType.GOOGLE_IME) {
            japanized = ime(japanized) ?: japanized
        }

        for ((key, value) in locked) {
            japanized = japanized.replace(key, value)
        }

        return maskNgWords(japanized, ngwords)
    }

    /** 1 行表示・2 行表示の書式 (`%msg` と `%japanize`) に当てはめる */
    fun format(format: String, org: String, japanized: String): String =
        format.replace("%msg", org).replace("%japanize", japanized)

    /**
     * NG ワードを * にする。LunaChat と同じく、パターンごとに最初の一致の長さの * で全部の一致を置き換える
     */
    fun maskNgWords(message: String, ngwords: List<Pattern>): String {
        var result = message
        for (pattern in ngwords) {
            val matcher = pattern.matcher(result)
            if (matcher.find()) {
                result = matcher.replaceAll("*".repeat(matcher.group(0).length))
            }
        }
        return result
    }

    /** NG ワードを含むか */
    fun containsNgWord(message: String, ngwords: List<Pattern>): Boolean =
        ngwords.any { it.matcher(message).find() }

    private fun fullWidthDigits(n: Int): String =
        n.toString().map { '０' + (it - '0') }.joinToString("")
}
