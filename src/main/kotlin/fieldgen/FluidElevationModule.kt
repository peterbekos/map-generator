package dev.nda.fieldgen

import dev.nda.math.Field
import dev.nda.math.Field01
import dev.nda.math.FieldSigned
import dev.nda.math.blurOnce
import dev.nda.math.normalize01InPlace
import dev.nda.math.smoothstep
import dev.nda.math.zerosField
import dev.nda.fieldgen.TerrainElevationModule

object FluidElevationModule {

    data class Params(
        val seaLevel01: Float = 0.42f,
        val thinAirLevel: Float = 0.80f,
        val thinAirRamp: Float = 0.10f,
    )

    data class Fields(
        val isWater01: Field01,
        val altFromSeaLevelSigned: FieldSigned,
        val altAboveSeaLevel01: Field01,
        val thinAirMask01: Field01,
        val coastMask01: Field01
    )

    fun buildFluidElevationFields(
        width: Int,
        height: Int,
        terrainElevationFields: TerrainElevationModule.Fields,
        params: Params = Params()
    ): Fields {
        val elevation01 = terrainElevationFields.elevation01 // [0,1]

        // --------- 1) Water & altitude relative to sea level ---------
        val isWater01: Field = zerosField(width, height)
        val altFromSeaSigned: Field = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            val alt = elevation01[x][y] - params.seaLevel01 // signed
            altFromSeaSigned[x][y] = alt
            isWater01[x][y] = if (alt <= 0f) 1f else 0f
        }

        // --------- 2) Altitude above sea only, normalized ---------
        // We want: 0 at sea level, 1 at highest peak above sea (on this map).
        val altAboveSea01: Field = zerosField(width, height)
        var maxAbove = 0f
        for (x in 0 until width) for (y in 0 until height) {
            val a = altFromSeaSigned[x][y]
            if (a > 0f && a > maxAbove) maxAbove = a
        }
        val invMaxAbove = if (maxAbove > 1e-6f) 1f / maxAbove else 0f

        for (x in 0 until width) for (y in 0 until height) {
            val a = altFromSeaSigned[x][y]
            altAboveSea01[x][y] = if (a > 0f) (a * invMaxAbove).coerceIn(0f, 1f) else 0f
        }

        // --------- 3) Thin air mask (0..1) from altAboveSea01 ---------
        val thinAirMask01: Field = zerosField(width, height)
        val start = params.thinAirLevel.coerceIn(0f, 1f)
        val ramp = params.thinAirRamp.coerceAtLeast(1e-6f)

        for (x in 0 until width) for (y in 0 until height) {
            val a = altAboveSea01[x][y]
            // Smooth ramp: 0 below start, 1 above start+ramp
            thinAirMask01[x][y] = smoothstep(start, start + ramp, a)
        }

        // --------- 4) Coast mask (0..1) ---------
        val coastMask01: Field = zerosField(width, height)

        fun waterAt(nx: Int, ny: Int, selfX: Int, selfY: Int): Float {
            val cx = nx.coerceIn(0, width - 1)
            val cy = ny.coerceIn(0, height - 1)
            return isWater01[cx][cy]
        }

        for (x in 0 until width) for (y in 0 until height) {
            val w = isWater01[x][y]

            val diff =
                kotlin.math.abs(w - waterAt(x + 1, y, x, y)) +
                        kotlin.math.abs(w - waterAt(x - 1, y, x, y)) +
                        kotlin.math.abs(w - waterAt(x, y + 1, x, y)) +
                        kotlin.math.abs(w - waterAt(x, y - 1, x, y))

            coastMask01[x][y] = if (diff > 0f) 1f else 0f
        }

        // Blur to make it a "near coast" field instead of a 1-tile outline.
        repeat(3) { blurOnce(coastMask01) }
        normalize01InPlace(coastMask01)

        return Fields(
            isWater01 = isWater01,
            altFromSeaLevelSigned = altFromSeaSigned,
            altAboveSeaLevel01 = altAboveSea01,
            thinAirMask01 = thinAirMask01,
            coastMask01 = coastMask01
        )
    }

}