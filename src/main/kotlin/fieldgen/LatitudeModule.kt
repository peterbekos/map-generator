package dev.nda.fieldgen

import dev.nda.math.Field
import dev.nda.math.FieldSigned
import dev.nda.math.zerosField

object LatitudeModule {

    data class Params(
        // xLatPercentage system:
        // 0 = top edge of grid, 1 = bottom edge of grid
        // values can be <0 or >1 to put poles/equator off-map
        val poleNorthLatPercent: Float = 0.0f,
        val equatorYLatPercent: Float = 1.0f,
        val poleSouthLatPercent: Float = 2.0f,
        val monoLatPercent: Float? = null // use for the whole map to be at the same lat
    )

    data class Fields(
        val latitudeSigned: FieldSigned,
    )

    fun buildLatitudeFields(width: Int, height: Int, params: Params = Params()): Fields {
        // Latitude mapping (Signed: -1 north pole, 0 equator, +1 south pole)
        val poleN = params.poleNorthLatPercent
        val equator = params.equatorYLatPercent
        val poleS = params.poleSouthLatPercent

        val latitudeSigned: Field = zerosField(width, height)

        // Guard against degenerate configs (equator == pole)
        val denomNorth = (equator - poleN).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }
        val denomSouth = (poleS - equator).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }

        fun latSignedFromYPercent(yPercent: Float): Float {
            return if (yPercent <= equator) {
                // North side: equator -> 0, poleN -> -1
                val t = (yPercent - equator) / denomNorth
                t.coerceIn(-1f, 0f)
            } else {
                // South side: equator -> 0, poleS -> +1
                val t = (yPercent - equator) / denomSouth
                t.coerceIn(0f, 1f)
            }
        }

        if (params.monoLatPercent == null) {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    val yPercent = if (height <= 1) 0f else y.toFloat() / (height - 1).toFloat()
                    latitudeSigned[x][y] = latSignedFromYPercent(yPercent)
                }
            }
        } else {
            val monoYPercent = params.monoLatPercent
            val monoSigned = latSignedFromYPercent(monoYPercent)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    latitudeSigned[x][y] = monoSigned
                }
            }
        }

        return Fields(latitudeSigned)
    }
}