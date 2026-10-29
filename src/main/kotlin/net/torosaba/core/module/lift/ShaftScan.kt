// Lift (croxis, LGPL-3.0-or-later) の BukkitElevatorManager の昇降路の調べ方を Kotlin で書き直したもの
package net.torosaba.core.module.lift

/** 昇降路の調べ方 (サーバーに触らない部分)。座標は (x, z) の列 [Column] で扱う */
internal object ShaftScan {
    data class Column(val x: Int, val z: Int)

    /** 昇降路の中の 1 マス */
    enum class Cell {
        /** 通れる (空気・ガラスの床・はしごなど) */
        OPEN,

        /** 通れるボタン。そこが 1 つの階になる */
        BUTTON,

        /** ふさがっている。その列はここまで */
        BLOCKED,
    }

    /**
     * [start] から東西南北につながった土台の列を集める。[maxArea] 個で打ち切る。
     * 元と同じく北・東・南・西の順の深さ優先で集める。
     */
    fun baseColumns(start: Column, maxArea: Int, isBase: (Column) -> Boolean): Set<Column> {
        val found = LinkedHashSet<Column>()
        fun visit(c: Column) {
            if (found.size >= maxArea || c in found) return
            found += c
            for (next in listOf(Column(c.x, c.z - 1), Column(c.x + 1, c.z), Column(c.x, c.z + 1), Column(c.x - 1, c.z))) {
                if (isBase(next)) visit(next)
            }
        }
        visit(start)
        return found
    }

    /** [floors] の結果 */
    data class Floors(
        /** ボタンのある高さ (階) を下から順に */
        val buttonYs: List<Int>,
        /** ふさがっていた場所 (x, y, z)。`/lift` の報告用 */
        val blockages: List<Triple<Int, Int, Int>>,
    )

    /**
     * 土台 ([baseY]) の上を [maxHeight] まで調べ、ボタンのある高さを階として返す。
     * どこかの列がふさがっていたら、それより上の階は使わない。
     * [floorOk] は checkFloor のとき、ボタンの 2 つ下の高さ (床の層) が床に使えるか。偽ならその列はそこまで。
     * [worldTop] より上 (ワールドの外) は調べない。
     */
    fun floors(
        columns: Collection<Column>,
        baseY: Int,
        maxHeight: Int,
        worldTop: Int,
        cell: (x: Int, y: Int, z: Int) -> Cell,
        floorOk: ((y: Int) -> Boolean)? = null,
    ): Floors {
        var limit = baseY + maxHeight
        val buttons = sortedSetOf<Int>()
        val blockages = mutableListOf<Triple<Int, Int, Int>>()
        for (c in columns) {
            for (y in baseY + 1..minOf(baseY + maxHeight + 1, worldTop - 1)) {
                when (cell(c.x, y, c.z)) {
                    Cell.BLOCKED -> {
                        blockages += Triple(c.x, y, c.z)
                        limit = minOf(limit, y)
                        break
                    }
                    Cell.BUTTON -> {
                        if (floorOk != null && !floorOk(y - 2)) break
                        buttons += y
                    }
                    Cell.OPEN -> {}
                }
            }
        }
        return Floors(buttons.filter { it < limit }, blockages)
    }
}
