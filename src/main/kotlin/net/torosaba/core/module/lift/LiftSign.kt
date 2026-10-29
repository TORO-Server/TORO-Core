// Lift (croxis, LGPL-3.0-or-later) の LiftSign の書式を Kotlin で書き直したもの
package net.torosaba.core.module.lift

/**
 * ボタンの上の看板の内容。書式は元の Lift と同じ。
 *
 * - 版 2 (今の書式): 1 行目「現在の階: 番号」、2 行目「階の名前」、3 行目「行先: 番号」、4 行目「行先の名前」
 * - 版 1 (古い書式): 1 行目「現在の階」、2 行目「番号」、3 行目「行先: 番号」、4 行目「行先の名前」
 * - 1 行目だけ書いた (`:` を含まない) 看板は、その階の名前として読む
 *
 * [currentLabel] と [destinationLabel] は設定の messages.currentFloor と destination。
 */
internal class LiftSign(
    private val currentLabel: String,
    private val destinationLabel: String,
    lines: List<String>,
) {
    private val sign = Array(4) { lines.getOrNull(it) ?: "" }

    var currentName = ""
        private set

    init {
        val line0 = sign[0]
        if (line0.isNotEmpty() && ':' !in line0 && sign[1].isEmpty() && sign[2].isEmpty() && sign[3].isEmpty()) {
            setCurrentName(line0)
        } else if (line0.isNotEmpty()) {
            // Java の split と同じく、末尾の空の要素は数えない
            when (line0.split(":").dropLastWhile { it.isEmpty() }.size) {
                1 -> {
                    // 版 1 は版 2 に直す
                    sign[1].trim().toIntOrNull()?.let { setCurrentFloor(it) }
                }
                2 -> currentName = sign[1]
            }
        }
    }

    val currentFloor: Int get() = number(sign[0])
    val destinationFloor: Int get() = number(sign[2])
    val isEmpty: Boolean get() = sign.all { it.isEmpty() }
    val lines: List<String> get() = sign.toList()

    fun setCurrentFloor(floor: Int) {
        sign[0] = "$currentLabel: $floor§r"
    }

    fun setDestinationFloor(floor: Int) {
        sign[2] = "$destinationLabel: $floor§r"
    }

    fun setDestinationName(name: String) {
        sign[3] = "$name§r"
    }

    fun setCurrentName(name: String) {
        currentName = name
        sign[1] = "$name§r"
    }

    private fun number(line: String): Int {
        val parts = line.split(": ")
        if (parts.size < 2) return 0
        return parts[1].replace(Regex("\\s"), "").replace("§r", "").toIntOrNull() ?: 0
    }
}

/** 階の選び方 (サーバーに触らない部分) */
internal object FloorCycle {
    /**
     * 行先を [step] (1 か -1) だけ進める。今いる階は飛ばし、端まで行ったら反対の端へ戻る。
     * [destination] は今の行先 (未設定なら 0)、[current] は今いる階、[total] は階の数。
     */
    fun next(destination: Int, current: Int, total: Int, step: Int): Int {
        var dest = destination + step
        if (dest == current) dest += step
        if (dest > total) {
            dest = if (current == 1) 2 else 1
        }
        if (dest < 1) {
            dest = if (current == total) total - 1 else total
        }
        return dest
    }
}
