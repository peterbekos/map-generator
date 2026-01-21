package dev.nda.fieldgen

import dev.nda.math.*
import kotlin.math.*

/**
 * Computes plate boundary effects:
 * - boundarySigned: signed height pressure from convergent/divergent/transform boundaries ([-1,+1] after normalize)
 * - faultMask01: boundary proximity/intensity field ([0,1]) useful for volcano placement, mystic zones, etc.
 *
 * Depends on TectonicPlatesModule.Fields (plates + plateId).
 * Mix strength stays global.
 */
object PlateBoundaryModule {

    data class Params(
        // How strongly ridge/rift contribute BEFORE normalization
        val ridgeStrength: Float = 0.9f,
        val riftStrength: Float = 0.6f,

        // Transform boundary “jaggedness” noise scale
        val transformRoughness: Float = 0.25f,

        // How far boundary effects spread outward (in blur passes terms)
        val boundaryFalloff: Float = 8.0f,

        // Extra small positive mark so blur spreads ranges outward
        val boundaryMarkStrength: Float = 0.05f,

        // Roughness noise frequency in tile units (higher = busier)
        val roughNoiseFreq: Float = 1.8f,

        // Approach scaling (keeps your original 1.2f)
        val approachScale: Float = 1.2f,

        // Optional: blur fault mask so it reads as a proximity field rather than razor lines
        val blurFaultMask: Boolean = true,

        // How much to blur the fault mask relative to boundary passes (0.5 = half)
        val faultBlurMul: Float = 0.5f,

        // Salt for deterministic transform noise (plate-stable)
        val roughSeedSalt: Int = 1337
    )

    data class Fields(
        val boundarySigned: FieldSigned, // [-1,+1]
        val faultMask01: Field01         // [0,1]
    )

    fun buildBoundaryFields(
        width: Int,
        height: Int,
        platesFields: TectonicPlatesModule.Fields,
        params: Params = Params()
    ): Fields {
        val plates = platesFields.plates
        val plateId = platesFields.plateId

        val boundarySigned: Field = zerosField(width, height)
        val faultMask01: Field = zerosField(width, height)

        // 1) Local boundary contributions (no spreading yet)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val aId = plateId[x][y]
                val a = plates[aId]

                var ridge = 0f
                var rift = 0f
                var rough = 0f

                fun consider(nx: Int, ny: Int) {
                    if (nx !in 0 until width || ny !in 0 until height) return
                    val bId = plateId[nx][ny]
                    if (bId == aId) return
                    val b = plates[bId]

                    val rel = b.velocity - a.velocity
                    val n = normalize(b.seed - a.seed) // stable boundary normal

                    val approach = dot(rel, n)               // >0 separating, <0 colliding
                    val shear = abs(cross2(rel, n))          // sideways component

                    val convergent = clamp01((-approach) * params.approachScale)
                    val divergent  = clamp01(( approach) * params.approachScale)
                    val transform  = clamp01(shear)

                    ridge += convergent * (1f - 0.5f * transform)
                    rift  += divergent  * (1f - 0.3f * transform)
                    rough += transform
                }

                // 4-neighborhood boundaries
                consider(x + 1, y)
                consider(x - 1, y)
                consider(x, y + 1)
                consider(x, y - 1)

                val boundaryIntensity = clamp01(max(ridge, max(rift, rough)))
                faultMask01[x][y] = boundaryIntensity

                // Signed height pressure
                var v = 0f
                v += ridge * params.ridgeStrength
                v -= rift  * params.riftStrength

                // Deterministic roughness noise in [-1, +1], plate-stable
                val noise01 = valueNoise2D(
                    x = x.toFloat() * params.roughNoiseFreq,
                    y = y.toFloat() * params.roughNoiseFreq,
                    seed = aId * 92821 + params.roughSeedSalt
                )
                val j = (noise01 - 0.5f) * 2f
                v += (rough * params.transformRoughness) * j

                // Small mark so blur turns lines into ranges
                v += boundaryIntensity * params.boundaryMarkStrength

                boundarySigned[x][y] = v
            }
        }

        // 2) Spread boundary effects outward into ranges via blur
        val passes = max(1, (params.boundaryFalloff / 2f).roundToInt())
        repeat(passes) { blurOnce(boundarySigned) }

        // Optional: blur fault mask into a proximity field
        if (params.blurFaultMask) {
            val fPasses = max(1, (passes * params.faultBlurMul).roundToInt())
            repeat(fPasses) { blurOnce(faultMask01) }
        }

        // 3) Normalize outputs
        normalizeSignedInPlace(boundarySigned) // [-1,+1]
        normalize01InPlace(faultMask01)        // [0,1]

        return Fields(
            boundarySigned = boundarySigned,
            faultMask01 = faultMask01
        )
    }
}