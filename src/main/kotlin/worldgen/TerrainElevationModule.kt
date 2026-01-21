package dev.nda.worldgen

import dev.nda.fieldgen.*
import dev.nda.math.*
import kotlin.math.*

object TerrainElevationModule {

    data class Params(
        val continentMaskStrength: Float = 0.9f,
        val plateBiasStrength: Float = 0.35f,
        val boundaryStrength: Float = 0.9f,
        val noiseStrength: Float = 0.5f,
        val craterStrength: Float = 0.66f,
        val volcanoStrength: Float = 1f,

        val smoothPasses: Int = 1,

        val peakBasinRadius: Int = 2,
        val roughnessRadius: Int = 1
    )

    data class Fields(
        val elevation01: Field01,
        val slope01: Field01,
        val roughness01: Field01,
        val peak01: Field01,
        val basin01: Field01
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
        val (peak01, basin01) = computePeakBasin01(elevation01, radius = params.peakBasinRadius)
        return Fields(
            elevation01 = elevation01,
            slope01 = computeSlope01(elevation01),
            roughness01 = computeRoughness01(elevation01, radius = params.roughnessRadius),
            peak01 = peak01,
            basin01 = basin01
        )
    }

    private fun computeSlope01(elev01: Field01): Field01 {
        val w = elev01.size
        val h = elev01[0].size
        val out = zerosField(w, h)

        fun sample(x: Int, y: Int): Float {
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            return elev01[xx][yy]
        }

        for (x in 0 until w) for (y in 0 until h) {
            val dx = (sample(x + 1, y) - sample(x - 1, y)) * 0.5f
            val dy = (sample(x, y + 1) - sample(x, y - 1)) * 0.5f
            out[x][y] = sqrt(dx * dx + dy * dy)
        }

        return normalize01InPlace(out)
    }

    private fun computeRoughness01(elev01: Field01, radius: Int = 1): Field01 {
        val w = elev01.size
        val h = elev01[0].size
        val out = zerosField(w, h)

        for (x in 0 until w) for (y in 0 until h) {
            var sum = 0f
            var count = 0
            for (dx in -radius..radius) for (dy in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                sum += elev01[nx][ny]
                count++
            }
            val mean = sum / max(1, count)
            out[x][y] = abs(elev01[x][y] - mean)
        }

        return normalize01InPlace(out)
    }

    private fun computePeakBasin01(elev01: Field01, radius: Int = 2): Pair<Field01, Field01> {
        val w = elev01.size
        val h = elev01[0].size
        val peak = zerosField(w, h)
        val basin = zerosField(w, h)

        for (x in 0 until w) for (y in 0 until h) {
            var sum = 0f
            var count = 0
            for (dx in -radius..radius) for (dy in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                sum += elev01[nx][ny]
                count++
            }
            val mean = sum / max(1, count)
            val d = elev01[x][y] - mean
            if (d >= 0f) peak[x][y] = d else basin[x][y] = -d
        }

        return normalize01InPlace(peak) to normalize01InPlace(basin)
    }
}