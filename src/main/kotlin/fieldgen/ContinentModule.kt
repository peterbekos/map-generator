package dev.nda.fieldgen
import dev.nda.math.*
import kotlin.math.*

/**
 * Produces a signed continent “macro landmass pressure” field in [-1,+1].
 * Positive => pushes toward land / higher elevation
 * Negative => pushes toward ocean / lower elevation
 *
 * NOTE: We intentionally do NOT mean-center this field. Its bias controls
 * how ocean-heavy vs land-heavy the world tends to be.
 */
object ContinentModule {

    data class Params(
        /** How many blob centers to seed (random in [minCenters]..[maxCenters]). */
        val minCenters: Int = 1,
        val maxCenters: Int = 3,

        /** Blob radius is minDim * random(radiusMinMul..radiusMaxMul). */
        val radiusMinMul: Float = 0.35f,
        val radiusMaxMul: Float = 0.50f,

        /** How much edges are depressed inside the mask (0..1). */
        val edgeDepressMul: Float = 0.60f,

        /** Edge falloff smoothing window. */
        val edgeFalloffInner: Float = 0.10f,
        val edgeFalloffOuter: Float = 0.90f,

        /** Optional: add some warping noise to blob distances so coasts are less round. */
        val enableWarp: Boolean = true,
        val warpStrength: Float = 0.18f,     // 0..~0.35 looks good
        val warpPeriodTiles: Float = 28f,    // larger = smoother warp
        val warpSeedSalt: Int = 0xC07771    // any int; just a salt
    )

    data class Fields(
        val continentSigned: FieldSigned
    )

    fun buildContinentSigned(
        width: Int,
        height: Int,
        seed: Long,
        params: Params = Params()
    ): Fields {
        val rng = kotlin.random.Random(seed xor 0x51A7E5L)

        val f: Field = zerosField(width, height)

        val centers = (params.minCenters + rng.nextInt((params.maxCenters - params.minCenters + 1).coerceAtLeast(1)))
            .coerceIn(1, 16)

        val blobs = List(centers) {
            Vec2(rng.nextFloat() * (width - 1), rng.nextFloat() * (height - 1))
        }

        val minDim = min(width, height).toFloat()
        val blobRadius = minDim * (params.radiusMinMul + rng.nextFloat() * (params.radiusMaxMul - params.radiusMinMul))

        // Warp setup (optional)
        val warpFreq = if (params.enableWarp) 1f / params.warpPeriodTiles else 0f
        val warpSeed = (seed.toInt() xor params.warpSeedSalt)

        for (x in 0 until width) {
            for (y in 0 until height) {

                var mask = 0f

                // Optional stable warp per tile (keeps coastlines from being perfect circles)
                val warp = if (params.enableWarp) {
                    val wn = valueNoise2D(x.toFloat() * warpFreq, y.toFloat() * warpFreq, warpSeed)
                    ((wn - 0.5f) * 2f) * params.warpStrength // [-warpStrength, +warpStrength]
                } else 0f

                for (c in blobs) {
                    val dx = x.toFloat() - c.x
                    val dy = y.toFloat() - c.y
                    var d = sqrt(dx * dx + dy * dy)

                    // warp distance slightly (multiplicative so it scales with d)
                    d *= (1f + warp)

                    val t = 1f - (d / blobRadius)   // 1 at center -> 0 at radius
                    mask = max(mask, smoothstep(0f, 1f, t))
                }

                // Edge falloff: 1 at edges, 0 toward center (your original behavior)
                val edge = edgeFalloff(x, y, width, height, params)

                // “0..1-ish” mask after edge depression
                val m = (mask * (1f - params.edgeDepressMul * edge))

                // Signed around 0: neutral at ~0.5
                f[x][y] = (m - 0.5f)
            }
        }

        // Normalize to [-1, +1] so layers are comparable in mixer space
        val signed = normalizeSignedInPlace(f)
        return Fields(continentSigned = signed)
    }

    private fun edgeFalloff(x: Int, y: Int, width: Int, height: Int, p: Params): Float {
        val fx = min(x, width - 1 - x).toFloat() / (width * 0.5f)
        val fy = min(y, height - 1 - y).toFloat() / (height * 0.5f)
        val d = min(fx, fy) // 0 at edges, ~1 at center
        return 1f - smoothstep(p.edgeFalloffInner, p.edgeFalloffOuter, d)
    }
}