package dev.nda.tectonic

import dev.nda.asciiprint.printHeightmap
import dev.nda.math.Vec2
import dev.nda.math.*
import kotlin.math.*
import kotlin.random.Random

class TectonicWorldGen(
    private val width: Int,
    private val height: Int,
    seed: Long,
    private val params: TectonicParams = TectonicParams()
) {
    private val rng = Random(seed)

    private val plates: List<Plate> = generatePlates()

    // For each cell: which plate owns it
    private val plateId = Array(width) { IntArray(height) }

    fun generateHeightmap(): Array<FloatArray> {
        assignPlateOwnership()

        val h = Array(width) { FloatArray(height) { 0f } }

        // 1) Base from plate continental bias + continent mask
        applyContinentMask(h)
        applyPlateBias(h)

        // 2) Boundary effects (ridges/rifts/transform)
        applyBoundaryEffects(h)

        // 3) Add natural detail noise
        addFractalNoise(h, params.noiseStrength, rng)

        // 4) Smooth a bit
        repeat(params.smoothPasses) { blurOnce(h) }

        // 5) Normalize to 0..1
        normalize01(h)

        return h
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

    private fun applyContinentMask(h: Array<FloatArray>) {
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
                val m = (mask * (1f - 0.6f * edge))
                h[x][y] += (m - 0.5f) * params.continentMaskStrength
            }
        }
    }

    private fun edgeFalloff(x: Int, y: Int): Float {
        val fx = min(x, width - 1 - x).toFloat() / (width * 0.5f)
        val fy = min(y, height - 1 - y).toFloat() / (height * 0.5f)
        val d = min(fx, fy) // 0 at edges, ~1 at center
        return 1f - smoothstep(0.1f, 0.9f, d)
    }

    private fun applyPlateBias(h: Array<FloatArray>) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                val pl = plates[plateId[x][y]]
                // bias contributes to elevation baseline
                h[x][y] += (pl.continentalBias - 0.5f) * 0.35f
            }
        }
    }

    private fun applyBoundaryEffects(h: Array<FloatArray>) {
        // Boundary influence map: compute per cell based on plate changes with neighbors
        for (x in 0 until width) {
            for (y in 0 until height) {
                val aId = plateId[x][y]
                val a = plates[aId]

                // look at 4-neighbors and accumulate effects
                var ridge = 0f
                var rift = 0f
                var rough = 0f

                fun consider(nx: Int, ny: Int) {
                    if (nx !in 0 until width || ny !in 0 until height) return
                    val bId = plateId[nx][ny]
                    if (bId == aId) return
                    val b = plates[bId]

                    val rel = b.velocity - a.velocity

                    // Normal from A to B seed (stable, avoids noisy normals)
                    val n = normalize(b.seed - a.seed)

                    val approach = dot(rel, n)      // >0 separating, <0 colliding
                    val shear = abs(cross2(rel, n)) // sideways component

                    val convergent = clamp01((-approach) * 1.2f)
                    val divergent = clamp01((approach) * 1.2f)
                    val transform = clamp01(shear)

                    ridge += convergent * (1f - 0.5f * transform)
                    rift += divergent * (1f - 0.3f * transform)
                    rough += transform
                }

                consider(x + 1, y)
                consider(x - 1, y)
                consider(x, y + 1)
                consider(x, y - 1)

                // Spread boundary effects outward with a cheap falloff:
                // Use local boundary intensity, then blur (later) to spread it.
                val boundaryIntensity = clamp01(max(ridge, max(rift, rough)))

                // Apply immediate local contribution
                h[x][y] += ridge * params.ridgeStrength
                h[x][y] -= rift * params.riftStrength
                h[x][y] += (rough * params.transformRoughness) * (0.5f - rng.nextFloat()) // jagged randomness

                // Optional: mark boundaries stronger so later blur spreads them
                h[x][y] += boundaryIntensity * 0.05f
            }
        }

        // Spread those ridge/rift lines into ranges via repeated blur
        val passes = max(1, (params.boundaryFalloff / 2f).roundToInt())
        repeat(passes) { blurOnce(h) }
    }

}