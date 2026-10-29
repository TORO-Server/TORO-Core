// WorldBorder (Brettflan, BSD-2-Clause License, Copyright (c) 2020 Brett Flannigan) の BorderData を移植したもの
package net.torosaba.core.module.worldborder

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 1 つのワールドの境界。中心 ([x], [z]) と X/Z 方向の半径を持つ。
 * [shapeRound] が null のときは、全体の既定の形 (round-border) を使う。
 */
data class BorderData(
    val x: Double,
    val z: Double,
    val radiusX: Int,
    val radiusZ: Int,
    val shapeRound: Boolean? = null,
    val wrapping: Boolean = false,
) {
    // 判定を速くするための値
    private val maxX = x + radiusX
    private val minX = x - radiusX
    private val maxZ = z + radiusZ
    private val minZ = z - radiusZ
    private val radiusXSquared = radiusX.toDouble() * radiusX
    private val radiusZSquared = radiusZ.toDouble() * radiusZ
    private val definiteRectangleX = sqrt(.5 * radiusXSquared)
    private val definiteRectangleZ = sqrt(.5 * radiusZSquared)
    private val radiusSquaredQuotient = radiusXSquared / radiusZSquared

    fun isRound(defaultRound: Boolean): Boolean = shapeRound ?: defaultRound

    /** 境界の内側か。頻繁に呼ぶので速く */
    fun insideBorder(xLoc: Double, zLoc: Double, defaultRound: Boolean): Boolean {
        if (!isRound(defaultRound)) return !(xLoc < minX || xLoc > maxX || zLoc < minZ || zLoc > maxZ)

        // 円の判定は rBorder (Reil) のもの
        val dx = abs(x - xLoc)
        val dz = abs(z - zLoc)
        return when {
            dx < definiteRectangleX && dz < definiteRectangleZ -> true // 確実に内側
            dx >= radiusX || dz >= radiusZ -> false // 確実に外側
            else -> dx * dx + dz * dz * radiusSquaredQuotient < radiusXSquared
        }
    }

    /**
     * 外に出た位置 ([xLoc], [zLoc]) を、境界から [knockBack] ブロック内側へ戻した位置。
     * [wrapping] のときは反対側の端へ送る。
     */
    fun correctedXZ(xLoc: Double, zLoc: Double, defaultRound: Boolean, knockBack: Double): Pair<Double, Double> {
        var nx = xLoc
        var nz = zLoc
        if (!isRound(defaultRound)) {
            if (wrapping) {
                if (nx <= minX) nx = maxX - knockBack else if (nx >= maxX) nx = minX + knockBack
                if (nz <= minZ) nz = maxZ - knockBack else if (nz >= maxZ) nz = minZ + knockBack
            } else {
                if (nx <= minX) nx = minX + knockBack else if (nx >= maxX) nx = maxX - knockBack
                if (nz <= minZ) nz = minZ + knockBack else if (nz >= maxZ) nz = maxZ - knockBack
            }
        } else {
            // 楕円を半径 1 の円に直して、いちばん近い境界上の点から knockBack だけ内側へ (Lang Lukas の方法)
            val dX = xLoc - x
            val dZ = zLoc - z
            val dU = sqrt(dX * dX + dZ * dZ)
            val dT = sqrt(dX * dX / radiusXSquared + dZ * dZ / radiusZSquared)
            val f = 1 / dT - knockBack / dU
            if (wrapping) {
                nx = x - dX * f
                nz = z - dZ * f
            } else {
                nx = x + dX * f
                nz = z + dZ * f
            }
        }
        return nx to nz
    }

    /** `/wb list` などに出す説明 */
    fun describe(): String {
        val radius = if (radiusX == radiusZ) "$radiusX" else "${radiusX}x$radiusZ"
        val shape = shapeRound?.let { " (shape override: ${shapeName(it)})" } ?: ""
        val wrap = if (wrapping) " (wrapping)" else ""
        return "radius $radius at X: ${COORD.format(x)} Z: ${COORD.format(z)}$shape$wrap"
    }

    companion object {
        internal val COORD = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ROOT))

        fun shapeName(round: Boolean?): String = when (round) {
            null -> "default"
            true -> "elliptic/round"
            false -> "rectangular/square"
        }

        /** 2 つの角から境界を作る (setcorners) */
        fun fromCorners(x1: Double, z1: Double, x2: Double, z2: Double, shapeRound: Boolean?, wrap: Boolean): BorderData {
            val radiusX = abs(x1 - x2) / 2
            val radiusZ = abs(z1 - z2) / 2
            return BorderData(
                minOf(x1, x2) + radiusX,
                minOf(z1, z2) + radiusZ,
                Math.round(radiusX).toInt(),
                Math.round(radiusZ).toInt(),
                shapeRound,
                wrap,
            )
        }
    }
}
