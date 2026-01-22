package dev.nda.fieldgen

import dev.nda.math.Field
import dev.nda.math.Field01
import dev.nda.math.addScaledInPlace
import dev.nda.math.blurOnce
import dev.nda.math.normalize01InPlace
import dev.nda.math.zerosField

object TerrainElevationModule {

    data class Params(
        val continentMaskStrength: Float = 0.9f,
        val plateBiasStrength: Float = 0.35f,
        val boundaryStrength: Float = 0.9f,
        val noiseStrength: Float = 0.5f,
        val craterStrength: Float = 0.66f,
        val volcanoStrength: Float = 1f,

        val smoothPasses: Int = 1,
    )

    data class Fields(
        val elevation01: Field01,
    )

    data class Inputs(
        val platesFields: TectonicPlatesModule.Fields,
        val continentFields: ContinentModule.Fields,
        val plateBiasFields: PlateBiasModule.Fields,
        val plateBoundaryFields: PlateBoundaryModule.Fields,
        val noiseFields: NoiseModule.Fields,
        val craterFields: CraterModule.Fields,
        val volcanoFields: VolcanoModule.Fields
    )

    fun buildTerrainElevationFields(
        width: Int,
        height: Int,
        inputs: Inputs,
        params: Params = Params()
    ): Fields {
        // --- Mix layers into raw height (signed-ish accumulator) ---
        val elevationRaw: Field = zerosField(width, height)

        addScaledInPlace(elevationRaw, inputs.continentFields.continentSigned, params.continentMaskStrength)
        addScaledInPlace(elevationRaw, inputs.plateBiasFields.plateBiasSigned, params.plateBiasStrength)
        addScaledInPlace(elevationRaw, inputs.plateBoundaryFields.boundarySigned, params.boundaryStrength)
        addScaledInPlace(elevationRaw, inputs.noiseFields.noiseSigned, params.noiseStrength)
        addScaledInPlace(elevationRaw, inputs.craterFields.craterSigned, params.craterStrength * (3f / 6f))

        // --- Post smoothing ---
        repeat(params.smoothPasses) { blurOnce(elevationRaw) }

        // mix craters after blur for more sharpness
        addScaledInPlace(elevationRaw, inputs.craterFields.craterSigned, params.craterStrength * (3f / 6f))
        addScaledInPlace(elevationRaw, inputs.volcanoFields.volcanoSigned, params.volcanoStrength)

        // --- Normalize final to [0,1] ---
        val elevation01 = normalize01InPlace(elevationRaw)
        return Fields(
            elevation01 = elevation01
        )
    }

}