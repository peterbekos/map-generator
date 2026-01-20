package dev.nda.fieldgen

import dev.nda.math.*
import kotlin.math.*

/**
 * Adds natural terrain noise (FBM/value noise) as a signed field [-1,+1].
 *
 * Philosophy:
 * - Keep feature size mostly stable in "tiles", not tied to map size.
 * - But also adapt a little so small maps don't look flat / large maps don't look too uniform.
 */
object NoiseModule {

    data class Params(
        /** Octaves for FBM. */
        val octaves: Int = 4,

        /**
         * Base period in tiles for the lowest-frequency octave.
         * Smaller = noisier / more variation.
         *
         * This is the core knob that fixes "32x64 looks too tectonic-y".
         */
        val basePeriodTiles: Float = 24f,

        /**
         * Optionally, auto-scale the base period with map size.
         * Good default: true.
         *
         * basePeriodUsed = clamp(minDim / featuresAcrossMinDim, minPeriod, maxPeriod)
         */
        val autoScaleBasePeriod: Boolean = true,
        val featuresAcrossMinDim: Float = 6f,
        val minBasePeriodTiles: Float = 6f,
        val maxBasePeriodTiles: Float = 32f,

        /** If you want more "organic breakup", add a second band of noise at a smaller period. */
        val enableDetailBand: Boolean = true,
        val detailBandStrength: Float = 0.45f,     // relative to main band
        val detailBandPeriodMul: Float = 0.45f,    // detailPeriod = basePeriod * mul

        /** Seed offset so different layers don't accidentally correlate. */
        val seedSalt: Int = 0xBEEFCAFE.toInt()
    )

    data class Fields(
        val noiseSigned: FieldSigned,
        /** For debugging/visualization: what basePeriod was actually used. */
        val basePeriodUsed: Float
    )

    /**
     * Builds signed noise in [-1,+1] (normalized by maxAbs).
     */
    fun buildNoiseSigned(
        width: Int,
        height: Int,
        worldSeed: Long,
        params: Params = Params()
    ): Fields {
        val seed = (worldSeed.toInt() xor params.seedSalt)

        val minDim = min(width, height).toFloat()
        val basePeriodUsed = if (params.autoScaleBasePeriod) {
            val computed = (minDim / params.featuresAcrossMinDim)
            computed.coerceIn(params.minBasePeriodTiles, params.maxBasePeriodTiles)
        } else {
            params.basePeriodTiles.coerceIn(1f, 10_000f)
        }

        val noise: Field = zerosField(width, height)

        // Main band
        addFractalNoiseSeeded(
            grid = noise,
            strength = 1f, // build unweighted; normalize later
            seed = seed,
            basePeriodTiles = basePeriodUsed,
            octaves = params.octaves
        )

        // Optional detail band (helps reduce "edgy tectonic look")
        if (params.enableDetailBand) {
            val detail: Field = zerosField(width, height)
            val detailPeriod = (basePeriodUsed * params.detailBandPeriodMul).coerceAtLeast(3f)

            addFractalNoiseSeeded(
                grid = detail,
                strength = 1f,
                seed = seed xor 0x9E37, // different seed
                basePeriodTiles = detailPeriod,
                octaves = (params.octaves - 1).coerceAtLeast(2)
            )

            // Mix detail into main before normalization
            addScaledInPlace(noise, detail, params.detailBandStrength)
        }

        meanCenterInPlace(noise)
        val signed = normalizeSignedInPlace(noise)
        return Fields(noiseSigned = signed, basePeriodUsed = basePeriodUsed)
    }
}