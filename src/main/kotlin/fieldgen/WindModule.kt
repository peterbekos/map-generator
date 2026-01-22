package dev.nda.fieldgen

import dev.nda.math.*
import dev.nda.fieldgen.TerrainElevationModule
import kotlin.math.*

object WindModule {

    data class Params(
        // --- Base ---
        val baseWind: Float = 0.20f,

        // --- Exposure / acceleration boosts ---
        val coastBoost: Float = 0.20f,      // coastMask01
        val highAltBoost: Float = 0.25f,    // altAboveSeaLevel01
        val peakBoost: Float = 0.25f,       // peak01
        val slopeBoost: Float = 0.18f,      // slope01
        val waterSpeedBoost: Float = 0.15f,  // waterMask

        // --- Dampening ---
        val basinShelter: Float = 0.35f,    // basin01 reduces wind
        val roughnessDrag: Float = 0.12f,   // roughness01 reduces wind

        // --- Curve shaping ---
        val altPower: Float = 1.20f,
        val peakPower: Float = 1.10f,
        val slopePower: Float = 1.30f,
        val basinPower: Float = 1.25f,
        val roughnessPower: Float = 1.00f,

        // --- Noise (synoptic variability) ---
        val noiseStrength: Float = 0.12f,
        val basePeriodTiles: Float = 42f,
        val octaves: Int = 3,

        // --- Wind band speed multiplier mapping ---
        // WindBandsModule currently stores bandSpeedMul01 normalized from [0.4..2.0] into [0..1].
        // Keep this here so WindBase can apply a meaningful multiplier without depending on WindBands params.
        val bandSpeedMulMin: Float = 0.4f,
        val bandSpeedMulMax: Float = 2.0f,

        // How strongly the band multiplier affects final speed.
        // 0 = ignore bands, 1 = full multiply.
        val bandSpeedInfluence: Float = 1.0f
    )

    data class Fields(
        val windSpeed01: Field01,

        // Per-tile direction (from bands)
        val windDirXSigned: FieldSigned, // [-1,+1]
        val windDirYSigned: FieldSigned, // [-1,+1]

        // Debug/visualization layers (optional but useful)
        val exposure01: Field01,
        val shelter01: Field01,
        val topoAccel01: Field01,

        // Optional debug: the absolute multiplier applied from bands
        val bandSpeedMulAbs01: Field01
    )

    data class Inputs(
        val terrainElevation: TerrainElevationModule.Fields,
        val fluidElevation: FluidElevationModule.Fields,
        val terrainMorphology: TerrainMorphologyModule.Fields,
        val windCirculation: WindCirculationModule.Fields
    )

    fun buildWindBaseFields(
        width: Int,
        height: Int,
        worldSeed: Long,
        inputs: Inputs,
        params: Params = Params()
    ): Fields {

        // --- 1) Noise (signed) ---
        val noiseSigned: Field = zerosField(width, height)
        val noiseSeed = (worldSeed xor 0x9E3779B7F4A71L).toInt()
        addFractalNoiseSeeded(
            grid = noiseSigned,
            strength = 1f,
            seed = noiseSeed,
            basePeriodTiles = params.basePeriodTiles,
            octaves = params.octaves
        )
        normalizeSignedInPlace(noiseSigned) // [-1,+1]

        // --- 2) Exposure (0..1) ---
        val exposure01: Field = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val coast = inputs.fluidElevation.coastMask01[x][y].coerceIn(0f, 1f)
            val alt = inputs.fluidElevation.altAboveSeaLevel01[x][y].coerceIn(0f, 1f)

            val altE = alt.pow(params.altPower)
            val cE = coast

            val exp = 1f - (1f - cE) * (1f - altE)
            exposure01[x][y] = exp.coerceIn(0f, 1f)
        }

        // --- 3) Topographic acceleration (0..1) ---
        val topoAccel01: Field = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val water = inputs.fluidElevation.isWater01[x][y].coerceIn(0f, 1f)
            val land = 1f - water

            val peak = inputs.terrainMorphology.peak01[x][y].coerceIn(0f, 1f).pow(params.peakPower) * land
            val slope = inputs.terrainMorphology.steepness01[x][y].coerceIn(0f, 1f).pow(params.slopePower) * land

            val accel = 1f - (1f - peak) * (1f - slope)
            topoAccel01[x][y] = accel.coerceIn(0f, 1f)
        }

        // --- 4) Shelter / drag (0..1) ---
        val shelter01: Field = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val water = inputs.fluidElevation.isWater01[x][y].coerceIn(0f, 1f)
            val land = 1f - water

            val basin = inputs.terrainMorphology.basin01[x][y].coerceIn(0f, 1f).pow(params.basinPower) * land
            val rough = inputs.terrainMorphology.roughness01[x][y].coerceIn(0f, 1f).pow(params.roughnessPower) * land

            val shelter = (basin * 0.75f + rough * 0.25f).coerceIn(0f, 1f)
            shelter01[x][y] = shelter
        }

        // --- 5) Band speed multiplier (absolute) ---
        val bandSpeedMulAbs01: Field = zerosField(width, height)
        val bandMulAbs: Field = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            val t01 = inputs.windCirculation.bandSpeedMul01[x][y].coerceIn(0f, 1f)
            val absMul = lerp(params.bandSpeedMulMin, params.bandSpeedMulMax, t01)
            bandMulAbs[x][y] = absMul

            bandSpeedMulAbs01[x][y] = ((absMul - params.bandSpeedMulMin) /
                    (params.bandSpeedMulMax - params.bandSpeedMulMin)).coerceIn(0f, 1f)
        }

        // --- 6) Final wind speed (raw), then normalize to 0..1 ---
        val windSpeedRaw: Field = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            val water = inputs.fluidElevation.isWater01[x][y].coerceIn(0f, 1f)
            val land = 1f - water

            val coast = inputs.fluidElevation.coastMask01[x][y].coerceIn(0f, 1f)
            val alt = inputs.fluidElevation.altAboveSeaLevel01[x][y].coerceIn(0f, 1f).pow(params.altPower)

            // Land-only topo terms (important fix)
            val peak = inputs.terrainMorphology.peak01[x][y].coerceIn(0f, 1f).pow(params.peakPower) * land
            val slope = inputs.terrainMorphology.steepness01[x][y].coerceIn(0f, 1f).pow(params.slopePower) * land
            val basin = inputs.terrainMorphology.basin01[x][y].coerceIn(0f, 1f).pow(params.basinPower) * land
            val rough = inputs.terrainMorphology.roughness01[x][y].coerceIn(0f, 1f).pow(params.roughnessPower) * land

            var w = params.baseWind

            // Coast + altitude are OK everywhere (alt will be 0 on water)
            w += coast * params.coastBoost
            w += alt * params.highAltBoost

            // Land-only accel
            w += peak * params.peakBoost
            w += slope * params.slopeBoost

            // Land-only shelter/drag
            w -= basin * params.basinShelter
            w -= rough * params.roughnessDrag

            // Water “slickness / fetch” boost (optional but reads great)
            w += water * params.waterSpeedBoost

            // Variability
            w += noiseSigned[x][y] * params.noiseStrength

            // Apply bands multiplier
            val mul = bandMulAbs[x][y]
            val appliedMul = lerp(1f, mul, params.bandSpeedInfluence.coerceIn(0f, 1f))
            w *= appliedMul

            windSpeedRaw[x][y] = w
        }

        val windSpeed01 = normalize01InPlace(windSpeedRaw)

        return Fields(
            windSpeed01 = windSpeed01,
            windDirXSigned = inputs.windCirculation.windDirXSigned,
            windDirYSigned = inputs.windCirculation.windDirYSigned,
            exposure01 = exposure01,
            shelter01 = shelter01,
            topoAccel01 = topoAccel01,
            bandSpeedMulAbs01 = bandSpeedMulAbs01
        )
    }
}