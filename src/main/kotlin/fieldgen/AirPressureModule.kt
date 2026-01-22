package dev.nda.fieldgen

import dev.nda.math.*

object AirPressureModule {

    data class Params(
        val altStrength: Float = 0.75f,     // bigger = lower pressure in mountains
        val tempStrength: Float = 0.35f,    // bigger = warmer => lower pressure
        val noiseStrength: Float = 0.10f,

        val basePeriodTiles: Float = 60f,
        val octaves: Int = 3,

        val midTemp01: Float = 0.55f        // “neutral” temperature baseline
    )

    data class Fields(
        val airPressure01: Field01,
    )

    fun buildAirPressureFields(
        width: Int,
        height: Int,
        worldSeed: Long,
        fluidElevation: FluidElevationModule.Fields,
        temperature: TemperatureModule.Fields,
        params: Params = Params()
    ): Fields {

        val noiseSigned: Field = zerosField(width, height)
        val seed = (worldSeed xor 0xCAFEBABEL).toInt()
        addFractalNoiseSeeded(
            grid = noiseSigned,
            strength = 1f,
            seed = seed,
            basePeriodTiles = params.basePeriodTiles,
            octaves = params.octaves
        )
        normalizeSignedInPlace(noiseSigned)

        val raw: Field = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            val alt = fluidElevation.altAboveSeaLevel01[x][y].coerceIn(0f, 1f)
            val temp01 = temperature.temperature01[x][y].coerceIn(0f, 1f)

            // Hotter than midTemp -> lower pressure (negative)
            val tempAnom = (temp01 - params.midTemp01)

            var p = 0.5f
            p -= alt * params.altStrength
            p -= tempAnom * params.tempStrength
            p += noiseSigned[x][y] * params.noiseStrength

            raw[x][y] = p
        }

        val pressure01 = normalize01InPlace(raw)

        return Fields(
            airPressure01 = pressure01,
        )
    }
}