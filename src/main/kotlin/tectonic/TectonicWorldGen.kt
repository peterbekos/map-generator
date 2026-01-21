package dev.nda.tectonic

import dev.nda.asciiprint.printHeightmap
import dev.nda.fieldgen.ContinentModule
import dev.nda.fieldgen.NoiseModule
import dev.nda.math.Vec2
import dev.nda.math.*
import kotlin.math.*
import kotlin.random.Random

class TectonicWorldGen(
    private val width: Int,
    private val height: Int,
    private val worldSeed: Long,
    private val params: TectonicParams = TectonicParams()
) {
    private val rng = Random(worldSeed)

    private val plates: List<Plate> = generatePlates()

    // For each cell: which plate owns it
    private val plateId = Array(width) { IntArray(height) }

    fun generateHeightmap(): Field01 {
        val noiseSeed = worldSeed.toInt() xor 0xBEEFCAFE.toInt()
        val craterSeed = worldSeed.toInt() xor 0xC0FFEE
        val volcanoSeed = worldSeed.toInt() xor 0x40CA
        assignPlateOwnership()

        // --- Build layers (Signed / 01) ---
        val continentFields = ContinentModule.buildContinentSigned(width, height, worldSeed)
        val continentSigned: FieldSigned = continentFields.continentSigned
        printHeightmap(signedTo01(continentSigned))

        val plateBiasSigned: FieldSigned = buildPlateBiasSigned()
        printHeightmap(signedTo01(plateBiasSigned))

        val boundaryFields = buildBoundaryFields()
        val boundarySigned: FieldSigned = boundaryFields.boundarySigned
        val faultMask01: Field01 = boundaryFields.faultMask01

        printHeightmap(signedTo01(boundarySigned))
//        printHeightmap(faultMask01) // already 0..1

        val noiseFields = NoiseModule.buildNoiseSigned(width, height, worldSeed)
        val noiseSigned = noiseFields.noiseSigned
        printHeightmap(signedTo01(noiseSigned))

        val craterFields = buildCraterFields(craterSeed)
        val craterSigned = craterFields.craterSigned
        val craterMask01 = craterFields.craterMask01

        printHeightmap(signedTo01(craterSigned))
//        printHeightmap(craterMask01)

        val volcanoes = buildVolcanoFields(
            faultMask01,
            boundarySigned,
            volcanoSeed xor 0x2222,
            count = (params.volcanoCount).coerceAtLeast(1)
        )

        printHeightmap(signedTo01(volcanoes.volcanoSigned))
//        printHeightmap(volcanoes.ashMask01)
//        printHeightmap(volcanoes.volcanoMask01)

        // --- Mix layers into raw height (signed-ish accumulator) ---
        val heightRaw: Field = zerosField(width, height)

        addScaledInPlace(heightRaw, continentSigned, params.continentMaskStrength)
        addScaledInPlace(heightRaw, plateBiasSigned, params.plateBiasStrength)
        addScaledInPlace(heightRaw, boundarySigned, params.boundaryStrength)
        addScaledInPlace(heightRaw, noiseSigned, params.noiseStrength)
        addScaledInPlace(heightRaw, craterSigned, params.craterStrength * (3f / 5f))

        // --- Post smoothing ---
        repeat(params.smoothPasses) { blurOnce(heightRaw) }

        // mix craters after blur for more sharpness
        addScaledInPlace(heightRaw, craterSigned, params.craterStrength * (2f / 5f))
        addScaledInPlace(heightRaw, volcanoes.volcanoSigned, params.volcanoStrength)

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

    private data class CraterFields(
        val craterSigned: FieldSigned, // [-1,+1] contribution to height
        val craterMask01: Field01      // [0,1] where craters exist (bowl/rim/ejecta)
    )

    private fun buildCraterFields(craterSeed: Int): CraterFields {
        val craterSigned: Field = zerosField(width, height)
        val craterMask01: Field = zerosField(width, height)

        val rBase = min(width, height).toFloat()

        // deterministic RNG for crater placement
        val rng = Random(craterSeed)

        repeat(params.craterCount) { i ->
            // pick center anywhere (you can bias away from edges later if you want)
            val cx = rng.nextFloat() * (width - 1)
            val cy = rng.nextFloat() * (height - 1)

            val rMinTiles = 6f
            val rMaxTiles = 14f

            val radius = (params.craterRadiusMinMul + rng.nextFloat() * (params.craterRadiusMaxMul - params.craterRadiusMinMul)) * rBase

            val radiusClamped = radius.coerceIn(rMinTiles, rMaxTiles)

            // Per-crater variation (subtle)
            val depth = params.craterDepth * (0.75f + rng.nextFloat() * 0.5f)
            val rim = params.craterRim * (0.75f + rng.nextFloat() * 0.5f)
            val ejecta = params.craterEjecta * (0.75f + rng.nextFloat() * 0.5f)
            val warp = params.craterWarp * (0.6f + rng.nextFloat() * 0.8f)

            stampCraterMassNearNeutral(
                heightSigned = craterSigned,
                mask01 = craterMask01,
                cx = cx,
                cy = cy,
                radius = radiusClamped,
                depth = depth,
                rim = rim,
                ejecta = ejecta,
                warp = warp,
                noiseSeed = craterSeed + i * 1337
            )
        }

        // Normalize outputs
        normalizeSignedInPlace(craterSigned)
        normalize01InPlace(craterMask01)

        return CraterFields(
            craterSigned = craterSigned,
            craterMask01 = craterMask01
        )
    }

    private fun stampCraterMassNearNeutral(
        heightSigned: Field,
        mask01: Field,
        cx: Float,
        cy: Float,
        radius: Float,
        depth: Float,
        rim: Float,
        ejecta: Float,
        warp: Float,
        noiseSeed: Int
    ) {
        val rInner = radius * params.craterInnerRadiusMul
        val rRimStart = radius * params.craterRimStartMul
        val rRimEnd = radius * params.craterRimEndMul
        val rEjectaEnd = radius * params.craterEjectaEndMul

        val minX = floor(cx - rEjectaEnd).toInt().coerceIn(0, width - 1)
        val maxX = ceil(cx + rEjectaEnd).toInt().coerceIn(0, width - 1)
        val minY = floor(cy - rEjectaEnd).toInt().coerceIn(0, height - 1)
        val maxY = ceil(cy + rEjectaEnd).toInt().coerceIn(0, height - 1)

        // Bowl is negative, rim+ejecta is positive.
        // We keep it "near neutral" by making bowl narrower and rim/ejecta wider.
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val dx = x.toFloat() - cx
                val dy = y.toFloat() - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > rEjectaEnd) continue

                // Warp: stable distortion so it’s not perfectly circular
                val w = (valueNoise2D(x.toFloat() * 0.9f, y.toFloat() * 0.9f, noiseSeed) - 0.5f) * 2f // -1..1
                val warpedD = d * (1f + warp * w)

                var delta = 0f
                var mask = 0f

                // 1) Bowl (negative): narrow + soft curve
                if (warpedD < rInner) {
                    val t = 1f - (warpedD / rInner) // 1 at center -> 0 at edge
                    val bowl = smoothstep(0f, 1f, t).pow(params.craterBowlPower)
                    delta -= bowl * depth
                    mask = max(mask, bowl)
                }

                // 2) Rim ring (positive): bell-shaped ring
                if (warpedD in rRimStart..rRimEnd) {
                    val t = (warpedD - rRimStart) / (rRimEnd - rRimStart)
                    val bell = sin(t * Math.PI.toFloat()) // 0..1..0
                    delta += bell * rim
                    mask = max(mask, bell)
                }

                // 3) Ejecta (mostly positive): deposits with texture
                if (warpedD > radius && warpedD < rEjectaEnd) {
                    val falloff = 1f - ((warpedD - radius) / (rEjectaEnd - radius)) // 1 near rim -> 0 outer

                    // textured deposition noise
                    val n = (valueNoise2D(x.toFloat() * 2.3f, y.toFloat() * 2.3f, noiseSeed xor 0x5A5A) - 0.5f) * 2f // -1..1
                    val n01 = (n * 0.5f + 0.5f) // 0..1
                    val biased = lerp(n, n01, params.craterEjectaBias)

                    val ripple = 0.70f + 0.30f * cos(warpedD / (radius * 0.18f))

                    val dep = falloff * ejecta * (0.75f * biased + 0.25f * ripple)
                    delta += dep
                    mask = max(mask, falloff)
                }

                val cur = heightSigned[x][y]
                val atten = if (delta > 0f) (1f - clamp01(cur * 0.9f)) else 1f
                heightSigned[x][y] = cur + delta * atten

                val a = mask01[x][y]
                val b = mask
                mask01[x][y] = 1f - (1f - a) * (1f - b) // probabilistic OR / smooth union
            }
        }
    }

    private data class VolcanoFields(
        val volcanoSigned: FieldSigned,
        val volcanoMask01: Field01,
        val ashMask01: Field01
    )

    private fun buildVolcanoFields(
        faultMask01: Field01,
        boundarySigned: FieldSigned,
        volcanoSeed: Int,
        count: Int = params.volcanoCount
    ): VolcanoFields {
        val volcanoSigned: Field = zerosField(width, height)
        val volcanoMask01: Field = zerosField(width, height)
        val ashMask01: Field = zerosField(width, height)

        val rng = Random(volcanoSeed)
        val base = min(width, height).toFloat()

        // Choose centers weighted by faultMask01
        val centers = pickVolcanoCenters(
            faultMask01 = faultMask01,
            seed = volcanoSeed,
            count = count,
            minSpacingTiles = params.volcanoMinSpacingTiles
        )

        centers.forEachIndexed { i, (cx, cy) ->
            val radius = (
                    params.volcanoRadiusMinMul +
                            rng.nextFloat() * (params.volcanoRadiusMaxMul - params.volcanoRadiusMinMul)
                    ) * base

            val coneH = params.volcanoConeHeight * (0.75f + rng.nextFloat() * 0.5f)
            val warp = params.volcanoWarp * (0.6f + rng.nextFloat() * 0.8f)

            val caldera = rng.nextFloat() < params.volcanoCalderaChance
            val calderaDepth = if (caldera) params.volcanoCalderaDepth * (0.7f + rng.nextFloat() * 0.6f) else 0f

            stampVolcano(
                heightSigned = volcanoSigned,
                volcanoMask01 = volcanoMask01,
                ashMask01 = ashMask01,
                cx = cx,
                cy = cy,
                radius = radius,
                coneHeight = coneH,
                calderaDepth = calderaDepth,
                warp = warp,
                noiseSeed = volcanoSeed + i * 4099,
                faultMask01 = faultMask01,
                boundarySigned = boundarySigned
            )
        }

        normalizeSignedInPlace(volcanoSigned)
        normalize01InPlace(volcanoMask01)
        normalize01InPlace(ashMask01)

        return VolcanoFields(volcanoSigned, volcanoMask01, ashMask01)
    }

    private fun pickVolcanoCenters(
        faultMask01: Field01,
        seed: Int,
        count: Int,
        minSpacingTiles: Int
    ): List<Pair<Float, Float>> {
        val rng = Random(seed)
        val chosen = ArrayList<Pair<Float, Float>>(count)

        val attempts = count * 60
        repeat(attempts) {
            if (chosen.size >= count) return@repeat

            val x = rng.nextInt(width)
            val y = rng.nextInt(height)

            val w = faultMask01[x][y].pow(params.volcanoFaultPower)
            if (rng.nextFloat() > w) return@repeat

            // spacing check
            val ok = chosen.all { (cx, cy) ->
                val dx = x - cx
                val dy = y - cy
                (dx * dx + dy * dy) >= (minSpacingTiles * minSpacingTiles)
            }
            if (ok) chosen.add(x.toFloat() to y.toFloat())
        }

        // if we didn’t get enough, fill randomly (rare)
        while (chosen.size < count) {
            chosen.add(rng.nextInt(width).toFloat() to rng.nextInt(height).toFloat())
        }
        return chosen
    }

    private enum class VolcanoType { SHIELD, STRATO }

    private data class VolcanoShape(
        val rMul: Float,
        val hMul: Float,
        val falloffPower: Float,
        val ashMul: Float,
        val roughMul: Float,
        val calderaMul: Float
    )

    private fun stampVolcano(
        heightSigned: Field,
        volcanoMask01: Field01,
        ashMask01: Field01,
        cx: Float,
        cy: Float,
        radius: Float,
        coneHeight: Float,
        calderaDepth: Float,
        warp: Float,
        noiseSeed: Int,
        faultMask01: Field01,
        boundarySigned: FieldSigned
    ) {
        val ix = cx.toInt().coerceIn(0, width - 1)
        val iy = cy.toInt().coerceIn(0, height - 1)

        // ---- Choose volcano type based on tectonics ----
        val fault = faultMask01[ix][iy]                      // 0..1
        val conv = clamp01(boundarySigned[ix][iy] * 0.5f + 0.5f) // map [-1,+1] -> [0,1]
        // But we only care about "convergent-ness", so bias toward positive boundarySigned:
        val convergent01 = clamp01(boundarySigned[ix][iy])    // 0..1, only positive part

        // Strato favored on strong faults + convergent boundaries
        val stratoChance = clamp01(
            0.15f + 0.75f * fault.pow(1.2f) + 0.85f * convergent01
        )

        val type = if (Random(noiseSeed).nextFloat() < stratoChance)
            VolcanoType.STRATO else VolcanoType.SHIELD

        // ---- Shape modifiers by type ----
        val shape = when (type) {
            VolcanoType.SHIELD -> VolcanoShape(
                rMul = 1.55f,
                hMul = 0.72f,
                falloffPower = 1.15f,
                ashMul = 0.70f,
                roughMul = 0.70f,
                calderaMul = 0.70f
            )
            VolcanoType.STRATO -> VolcanoShape(
                rMul = 0.78f,
                hMul = 1.25f,
                falloffPower = 3.00f,
                ashMul = 1.35f,
                roughMul = 1.15f,
                calderaMul = 1.10f
            )
        }
        val (rMul, hMul, falloffPower, ashMul, roughMul, calderaMul) = shape

        val r = (radius * rMul).coerceAtLeast(3f) // keep visible on small maps
        val h = coneHeight * hMul
        val calderaD = calderaDepth * calderaMul
        val rAsh = r * params.volcanoAshRadiusMul

        val minX = floor(cx - rAsh).toInt().coerceIn(0, width - 1)
        val maxX = ceil(cx + rAsh).toInt().coerceIn(0, width - 1)
        val minY = floor(cy - rAsh).toInt().coerceIn(0, height - 1)
        val maxY = ceil(cy + rAsh).toInt().coerceIn(0, height - 1)

        // Noise frequencies scale with volcano size so small maps still look good
        val warpFreq = (1f / (r * 0.55f)).coerceIn(0.08f, 0.45f)
        val roughFreq = (1f / (r * 0.30f)).coerceIn(0.10f, 0.70f)
        val ashFreq = (1f / (r * 1.10f)).coerceIn(0.03f, 0.20f)

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val dx = x.toFloat() - cx
                val dy = y.toFloat() - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > rAsh) continue

                // Stable warp (irregular cone edge)
                val w = (valueNoise2D(x.toFloat() * warpFreq, y.toFloat() * warpFreq, noiseSeed) - 0.5f) * 2f
                val wd = d * (1f + warp * w)

                var delta = 0f

                // ---- Cone ----
                if (wd < r) {
                    val u = (wd / r).coerceIn(0f, 1f)  // 0 center -> 1 edge
                    val core = (1f - u).coerceIn(0f, 1f)

                    // Shield: gentle slope (power ~1)
                    // Strato: steep cone (power ~3)
                    val cone = core.pow(falloffPower)

                    delta += cone * h

                    // Rough lava/rock texture, scaled by cone strength
                    val rough = (valueNoise2D(x.toFloat() * roughFreq, y.toFloat() * roughFreq, noiseSeed xor 0x1234) - 0.5f) * 2f
                    delta += rough * params.volcanoRoughness * roughMul * cone

                    // Volcano influence mask (smooth union)
                    val m = cone // already 0..1-ish
                    volcanoMask01[x][y] = 1f - (1f - volcanoMask01[x][y]) * (1f - m)
                }

                // ---- Caldera (optional) ----
                if (calderaD > 0f) {
                    val rCaldera = r * (if (type == VolcanoType.STRATO) 0.26f else 0.18f)
                    if (wd < rCaldera) {
                        val u = (wd / rCaldera).coerceIn(0f, 1f)
                        val bowl = 1f - (u * u)
                        delta -= bowl * calderaD
                    }
                }

                // ---- Ash blanket (wide, mostly 0..1 mask) ----
                val ashU = (wd / rAsh).coerceIn(0f, 1f)
                val ashCore = (1f - ashU).coerceIn(0f, 1f)

                // Strato: more ash, slightly broader feel; Shield: less
                val ashBase = ashCore * ashCore * params.volcanoAshStrength * ashMul

                val ashTex = 0.78f + 0.22f * ((valueNoise2D(x.toFloat() * ashFreq, y.toFloat() * ashFreq, noiseSeed xor 0xBEEF) - 0.5f) * 2f)
                val ashVal = (ashBase * ashTex).coerceIn(0f, 1f)

                // Smooth union so multiple volcano ash blankets stack nicely
                ashMask01[x][y] = 1f - (1f - ashMask01[x][y]) * (1f - ashVal)

                heightSigned[x][y] += delta
            }
        }
    }

}