package dev.nda.tectonic

import dev.nda.asciiprint.printHeightmap
import dev.nda.math.Vec2
import dev.nda.math.*
import kotlin.math.*
import kotlin.random.Random

class TectonicWorldGen(
    private val width: Int,
    private val height: Int,
    private val seed: Long,
    private val params: TectonicParams = TectonicParams()
) {
    private val rng = Random(seed)

    private val plates: List<Plate> = generatePlates()

    // For each cell: which plate owns it
    private val plateId = Array(width) { IntArray(height) }

    fun generateHeightmap(): Field01 {
        val noiseSeed = seed.toInt() xor 0xBEEFCAFE.toInt()
        assignPlateOwnership()

        // --- Build layers (Signed / 01) ---
        val continentSigned: FieldSigned = buildContinentSigned()
        printHeightmap(signedTo01(continentSigned))

        val plateBiasSigned: FieldSigned = buildPlateBiasSigned().also { blurOnce(it) }
        printHeightmap(signedTo01(plateBiasSigned))

        val boundaryFields = buildBoundaryFields()
        val boundarySigned: FieldSigned = boundaryFields.boundarySigned
        val faultMask01: Field01 = boundaryFields.faultMask01

        printHeightmap(signedTo01(boundarySigned))
        printHeightmap(faultMask01) // already 0..1

        val noiseSigned: FieldSigned = buildNoiseSigned(noiseSeed)
        printHeightmap(signedTo01(noiseSigned))

        // --- Mix layers into raw height (signed-ish accumulator) ---
        val heightRaw: Field = zerosField(width, height)

        addScaledInPlace(heightRaw, continentSigned, params.continentMaskStrength)
        addScaledInPlace(heightRaw, plateBiasSigned, params.plateBiasStrength)
        addScaledInPlace(heightRaw, boundarySigned, params.boundaryStrength)
        addScaledInPlace(heightRaw, noiseSigned, params.noiseStrength)

        // --- Post smoothing ---
        repeat(params.smoothPasses) { blurOnce(heightRaw) }

        // --- Normalize final to [0,1] ---
        return normalize01InPlace(heightRaw)
    }

    private fun assignPlateOwnership() {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var bestId = 0
                var bestD2 = Float.POSITIVE_INFINITY

                for (p in plates) {
                    val dx = x.toFloat() - p.seed.x
                    val dy = y.toFloat() - p.seed.y
                    val d2 = dx * dx + dy * dy
                    if (d2 < bestD2) {
                        bestD2 = d2
                        bestId = p.id
                    }
                }
                plateId[x][y] = bestId
            }
        }
    }

    private fun generatePlates(): List<Plate> {
        val list = ArrayList<Plate>(params.plateCount)
        for (i in 0 until params.plateCount) {
            val sx = rng.nextFloat() * (width - 1)
            val sy = rng.nextFloat() * (height - 1)

            // Velocity: random direction + magnitude
            val angle = rng.nextFloat() * (2f * Math.PI.toFloat())
            val mag = 0.2f + rng.nextFloat() * 0.8f
            val v = Vec2(cos(angle) * mag, sin(angle) * mag)

            // Continental bias: choose some plates more continental, others oceanic
            val bias = if (rng.nextFloat() < 0.55f) {
                0.6f + rng.nextFloat() * 0.4f // continental-ish
            } else {
                rng.nextFloat() * 0.4f // oceanic-ish
            }

            list.add(Plate(i, Vec2(sx, sy), v, bias))
        }
        return list
    }

    private fun buildContinentSigned(): FieldSigned {
        val f = zerosField(width, height)

        // Simple “one or two blobs” mask: pick 1-3 centers, add radial falloff
        val centers = (1 + rng.nextInt(3)).coerceAtMost(3)
        val blobs = List(centers) {
            Vec2(rng.nextFloat() * (width - 1), rng.nextFloat() * (height - 1))
        }
        val blobRadius = min(width, height) * (0.35f + rng.nextFloat() * 0.15f)

        for (x in 0 until width) {
            for (y in 0 until height) {
                var mask = 0f
                for (c in blobs) {
                    val dx = x - c.x
                    val dy = y - c.y
                    val d = sqrt(dx * dx + dy * dy)
                    val t = 1f - (d / blobRadius)
                    mask = max(mask, smoothstep(0f, 1f, t))
                }

                // push edges down slightly so oceans form more naturally
                val edge = edgeFalloff(x, y)

                // This is still 0..1-ish
                val m = (mask * (1f - 0.6f * edge))

                // Convert to SIGNED around 0 (neutral at ~0.5)
                // IMPORTANT: no params.continentMaskStrength here; that becomes a mixer knob later.
                f[x][y] = (m - 0.5f)
            }
        }

        // Normalize field into [-1, +1] so all layers are comparable
        return normalizeSignedInPlace(f)
    }


    private fun edgeFalloff(x: Int, y: Int): Float {
        val fx = min(x, width - 1 - x).toFloat() / (width * 0.5f)
        val fy = min(y, height - 1 - y).toFloat() / (height * 0.5f)
        val d = min(fx, fy) // 0 at edges, ~1 at center
        return 1f - smoothstep(0.1f, 0.9f, d)
    }

    private fun buildPlateBiasSigned(): FieldSigned {
        val f = zerosField(width, height)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pl = plates[plateId[x][y]]

                // pl.continentalBias is ~0..1, where higher = more land
                // Convert to signed around 0
                f[x][y] = (pl.continentalBias - 0.5f)
            }
        }

        return normalizeSignedInPlace(f)
    }

    private data class BoundaryFields(
        val boundarySigned: FieldSigned, // [-1,+1]
        val faultMask01: Field01         // [0,1]
    )

    private fun buildBoundaryFields(): BoundaryFields {
        val boundarySigned = zerosField(width, height)      // signed contribution
        val faultMask01 = zerosField(width, height)     // we'll store 0..1 here

        // 1) Compute local boundary contributions (no spreading yet)
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
                    val n = normalize(b.seed - a.seed)

                    val approach = dot(rel, n)       // >0 separating, <0 colliding
                    val shear = abs(cross2(rel, n))

                    val convergent = clamp01((-approach) * 1.2f)
                    val divergent  = clamp01((approach) * 1.2f)
                    val transform  = clamp01(shear)

                    ridge += convergent * (1f - 0.5f * transform)
                    rift  += divergent  * (1f - 0.3f * transform)
                    rough += transform
                }

                consider(x + 1, y)
                consider(x - 1, y)
                consider(x, y + 1)
                consider(x, y - 1)

                val boundaryIntensity = clamp01(kotlin.math.max(ridge, kotlin.math.max(rift, rough)))
                faultMask01[x][y] = boundaryIntensity

                // Signed height effect before normalization:
                // + ridges, - rifts
                var v = 0f
                v += ridge * params.ridgeStrength
                v -= rift * params.riftStrength

                // Deterministic roughness noise in [-1, +1]
                val noise = valueNoise2D(
                    x = x.toFloat() * 1.8f,   // frequency (tweakable)
                    y = y.toFloat() * 1.8f,
                    seed = aId * 92821 + 1337 // plate-stable seed
                )

                val j = (noise - 0.5f) * 2f  // remap 0..1 -> -1..1

                v += (rough * params.transformRoughness) * j

                // Slight mark so later blur spreads ranges outward
                v += boundaryIntensity * 0.05f

                boundarySigned[x][y] = v
            }
        }

        // 2) Spread boundary effects outward into ranges via repeated blur
        val passes = max(1, (params.boundaryFalloff / 2f).roundToInt())
        repeat(passes) { blurOnce(boundarySigned) }

        // (Optional) also blur fault mask a little so it reads as a proximity field
        // Helps volcano placement and “mystic along faults” feel.
        repeat(max(1, passes / 2)) { blurOnce(faultMask01) }

        // 3) Normalize outputs
        normalizeSignedInPlace(boundarySigned)     // -> [-1,+1]
        normalize01InPlace(faultMask01)        // -> [0,1]

        return BoundaryFields(boundarySigned = boundarySigned, faultMask01 = faultMask01)
    }

    private fun buildNoiseSigned(noiseSeed: Int): FieldSigned {
        val f = zerosField(width, height)

        // Reuse your current fractal noise function which adds into the array.
        // We pass strength=1f (unit layer), normalize, then apply real strength in the mixer later.
        addFractalNoiseSeeded(f, 1f, noiseSeed)

        // Convert to signed-ish: if addFractalNoise produces mostly positive values, center it.
        // We'll do mean-centering before normalizeSigned.
        meanCenterInPlace(f)

        return normalizeSignedInPlace(f)
    }

    private fun meanCenterInPlace(a: Array<FloatArray>) {
        var sum = 0f
        var n = 0
        for (x in 0 until width) for (y in 0 until height) {
            sum += a[x][y]
            n++
        }
        val mean = sum / n.toFloat()
        for (x in 0 until width) for (y in 0 until height) {
            a[x][y] -= mean
        }
    }

}