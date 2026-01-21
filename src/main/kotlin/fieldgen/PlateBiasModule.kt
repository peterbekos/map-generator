package dev.nda.fieldgen

import dev.nda.math.*

/**
 * Builds a signed plate-bias field in [-1,+1] using output from TectonicPlatesModule.
 * Positive => more continental / higher baseline
 * Negative => more oceanic / lower baseline
 *
 * Mix strength stays global.
 */
object PlateBiasModule {

    data class Params(
        /**
         * Centers Plate.continentalBias (0..1) into signed around 0.
         * With biasCenter=0.5, bias 0.5 => 0, 1.0 => +0.5, 0.0 => -0.5.
         */
        val biasCenter: Float = 0.5f,

        /**
         * Optional smoothing so plate interiors blend instead of hard edges.
         * Recommended: 1–3 (more makes it less “cellular”).
         */
        val blurPasses: Int = 0,

        /**
         * Normalize to [-1,+1] after blur so the layer is comparable with other signed layers.
         */
        val normalizeSigned: Boolean = true
    )

    data class Fields(
        val plateBiasSigned: FieldSigned
    )

    fun buildPlateBiasSigned(
        width: Int,
        height: Int,
        platesFields: TectonicPlatesModule.Fields,
        params: Params = Params()
    ): Fields {
        val plateId = platesFields.plateId
        val plates = platesFields.plates

        val f: Field = zerosField(width, height)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val id = plateId[x][y]
                val pl = plates[id]
                f[x][y] = (pl.continentalBias - params.biasCenter)
            }
        }

        repeat(params.blurPasses.coerceAtLeast(0)) { blurOnce(f) }

        val signed = if (params.normalizeSigned) normalizeSignedInPlace(f) else f
        return Fields(plateBiasSigned = signed)
    }
}