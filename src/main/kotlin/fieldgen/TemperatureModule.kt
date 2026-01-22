package dev.nda.fieldgen

import dev.nda.math.*
import kotlin.math.*

object TemperatureModule {

    data class Params(
        val latitudeStrength: Float = 1.0f,
        val altitudeStrength: Float = 0.65f,
        val noiseStrength: Float = 0.06f,

        val latitudeCurvePower: Float = 1.15f,
        val altitudeCurvePower: Float = 1.10f,

        val coastModerationStrength: Float = 0.20f,
        val mildTemp01: Float = 0.55f,

        // Noise params
        val basePeriodTiles: Float = 28f,
        val octaves: Int = 4,

        // --- Geothermal mixing ---
        val geothermalStrength: Float = 0.12f,
        val geothermalPower: Float = 1.15f,
        val geothermalClampInsteadOfRenorm: Boolean = true
    )

    data class Fields(
        val temperature01: Field01,
        val temperatureNonGeothermal01: Field01,
        val tempLat01: Field01,
        val tempAltCool01: Field01,
    )

    fun buildTemperatureFields(
        width: Int,
        height: Int,
        worldSeed: Long,
        latitudeFields: LatitudeModule.Fields,
        fluidElevation: FluidElevationModule.Fields,
        geothermal: GeothermalModule.Fields,
        params: Params = Params()
    ): Fields {

        val tempLat01 = buildTempLat01(width, height, latitudeFields, params)
        val tempAltCool01 = buildTempAltCool01(width, height, fluidElevation, params)
        val tempNoiseSigned = buildTempNoiseSigned(width, height, worldSeed, params)

        val temperatureBase01 = buildTemperatureBase01(
            width = width,
            height = height,
            fluidElevation = fluidElevation,
            tempLat01 = tempLat01,
            tempAltCool01 = tempAltCool01,
            tempNoiseSigned = tempNoiseSigned,
            params = params
        )

        val temperature01 = mixGeothermalIntoTemp01(
            width = width,
            height = height,
            temperatureBase01 = temperatureBase01,
            geothermalHeat01 = geothermal.geothermalHeat01,
            params = params
        )

        return Fields(
            temperature01 = temperature01,
            temperatureNonGeothermal01 = temperatureBase01,
            tempLat01 = tempLat01,
            tempAltCool01 = tempAltCool01,
        )
    }

    // -------------------------
    // Private builders
    // -------------------------

    private fun buildTempLat01(
        width: Int,
        height: Int,
        latitudeFields: LatitudeModule.Fields,
        params: Params
    ): Field01 {
        val out = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val latSigned = latitudeFields.latitudeSigned[x][y].coerceIn(-1f, 1f)
            val lat01 = latSigned * 0.5f + 0.5f

            val distFromEquator01 = kotlin.math.abs(lat01 - 0.5f) * 2f
            val heat01 = (1f - distFromEquator01)
                .coerceIn(0f, 1f)
                .pow(params.latitudeCurvePower)

            out[x][y] = heat01
        }
        return out
    }

    private fun buildTempAltCool01(
        width: Int,
        height: Int,
        fluidElevation: FluidElevationModule.Fields,
        params: Params
    ): Field01 {
        val out = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val a = fluidElevation.altAboveSeaLevel01[x][y].coerceIn(0f, 1f)
            out[x][y] = a.pow(params.altitudeCurvePower)
        }
        return out
    }

    private fun buildTempNoiseSigned(
        width: Int,
        height: Int,
        worldSeed: Long,
        params: Params
    ): FieldSigned {
        val out = zerosField(width, height)
        val seed = (worldSeed xor 0x13579BDFL).toInt()
        addFractalNoiseSeeded(
            grid = out,
            strength = 1f,
            seed = seed,
            basePeriodTiles = params.basePeriodTiles,
            octaves = params.octaves
        )
        return normalizeSignedInPlace(out)
    }

    private fun buildTemperatureBase01(
        width: Int,
        height: Int,
        fluidElevation: FluidElevationModule.Fields,
        tempLat01: Field01,
        tempAltCool01: Field01,
        tempNoiseSigned: FieldSigned,
        params: Params
    ): Field01 {
        val raw = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            var t = tempLat01[x][y] * params.latitudeStrength
            t -= tempAltCool01[x][y] * params.altitudeStrength
            t += tempNoiseSigned[x][y] * params.noiseStrength

            val coast = fluidElevation.coastMask01[x][y].coerceIn(0f, 1f)
            val k = (coast * params.coastModerationStrength).coerceIn(0f, 1f)
            t = lerp(t, params.mildTemp01, k)

            raw[x][y] = t
        }

        return normalize01InPlace(raw)
    }

    private fun mixGeothermalIntoTemp01(
        width: Int,
        height: Int,
        temperatureBase01: Field01,
        geothermalHeat01: Field01,
        params: Params
    ): Field01 {
        val out = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            val b = temperatureBase01[x][y]
            val g = geothermalHeat01[x][y].coerceIn(0f, 1f).pow(params.geothermalPower)
            val mixed = b + g * params.geothermalStrength

            out[x][y] = if (params.geothermalClampInsteadOfRenorm) {
                mixed.coerceIn(0f, 1f)
            } else {
                mixed
            }
        }

        return if (params.geothermalClampInsteadOfRenorm) out else normalize01InPlace(out)
    }

}