package dev.nda.fieldgen

import dev.nda.math.*
import kotlin.math.*
import kotlin.random.Random

object VolcanoModule {

    data class Params(
        // ---- Placement ----
        // Density control
        val tilesPerVolcano: Int = 2400, // ~1 per 2200 tiles
        val minVolcanoes: Int = 1,
        val maxVolcanoes: Int = 24,
        val volcanoMinSpacingTiles: Int = 10,
        val volcanoFaultPower: Float = 2.2f,   // higher => stronger bias toward faults

        // ---- Size (FIXED TILE RANGE) ----
        // These are the BASE radius values before type multipliers (rMul).
        val radiusMinTiles: Float = 6f,
        val radiusMaxTiles: Float = 22f,

        // Cone height (signed height delta), independent from map size.
        val coneHeightMin: Float = 0.18f,
        val coneHeightMax: Float = 0.42f,

        // ---- Shape / surface ----
        val volcanoWarp: Float = 0.20f,
        val volcanoRoughness: Float = 0.06f,

        // Caldera
        val volcanoCalderaChance: Float = 0.35f,
        val volcanoCalderaDepthMin: Float = 0.05f,
        val volcanoCalderaDepthMax: Float = 0.18f,

        // Ash
        val volcanoAshStrength: Float = 0.55f,   // mask intensity only (doesn't change elevation directly)
        val volcanoAshRadiusMul: Float = 2.2f,   // ash radius = r * mul

        // ---- Type selection ----
        // How strongly convergence influences stratovolcano chance
        val stratoBase: Float = 0.15f,
        val stratoFaultWeight: Float = 0.75f,
        val stratoConvWeight: Float = 0.85f,
        val stratoFaultPow: Float = 1.2f,

        // ---- Determinism ----
        val seedSalt: Int = 0x407C490
    )

    data class Fields(
        val volcanoSigned: FieldSigned,
        val volcanoMask01: Field01,
        val ashMask01: Field01
    )

    fun buildVolcanoFields(
        width: Int,
        height: Int,
        plateBoundaryFields: PlateBoundaryModule.Fields,
        worldSeed: Long,
        params: Params = Params()
    ): Fields {
        val faultMask01: Field01 = plateBoundaryFields.faultMask01
        val boundarySigned: FieldSigned = plateBoundaryFields.boundarySigned
        val volcanoSigned: Field = zerosField(width, height)
        val volcanoMask01: Field = zerosField(width, height)
        val ashMask01: Field = zerosField(width, height)

        val seed = (worldSeed.toInt() xor params.seedSalt)
        val rng = Random(seed)

        val count = computeVolcanoCount(width, height, params)

        // Choose centers weighted by faultMask01
        val centers = pickVolcanoCenters(
            width = width,
            height = height,
            faultMask01 = faultMask01,
            seed = seed,
            params = params,
            count = count
        )

        centers.forEachIndexed { i, (cx, cy) ->
            val baseRadius = lerp(params.radiusMinTiles, params.radiusMaxTiles, rng.nextFloat())
            val coneH = lerp(params.coneHeightMin, params.coneHeightMax, rng.nextFloat())
            val warp = params.volcanoWarp * (0.6f + rng.nextFloat() * 0.8f)

            val caldera = rng.nextFloat() < params.volcanoCalderaChance
            val calderaDepth = if (caldera) {
                lerp(params.volcanoCalderaDepthMin, params.volcanoCalderaDepthMax, rng.nextFloat())
            } else 0f

            stampVolcano(
                width = width,
                height = height,
                heightSigned = volcanoSigned,
                volcanoMask01 = volcanoMask01,
                ashMask01 = ashMask01,
                cx = cx,
                cy = cy,
                baseRadius = baseRadius,
                coneHeight = coneH,
                calderaDepth = calderaDepth,
                warp = warp,
                noiseSeed = seed + i * 4099,
                faultMask01 = faultMask01,
                boundarySigned = boundarySigned,
                params = params
            )
        }

        // Normalize outputs
        normalizeSignedInPlace(volcanoSigned)
        normalize01InPlace(volcanoMask01)
        normalize01InPlace(ashMask01)

        return Fields(
            volcanoSigned = volcanoSigned,
            volcanoMask01 = volcanoMask01,
            ashMask01 = ashMask01
        )
    }

    private fun computeVolcanoCount(
        width: Int,
        height: Int,
        p: Params
    ): Int {
        val tiles = width * height
        val raw = tiles / p.tilesPerVolcano
        return raw.coerceIn(p.minVolcanoes, p.maxVolcanoes)
    }

    private fun pickVolcanoCenters(
        width: Int,
        height: Int,
        faultMask01: Field01,
        seed: Int,
        params: Params,
        count: Int
    ): List<Pair<Float, Float>> {
        val rng = Random(seed xor 0x51ED)
        val chosen = ArrayList<Pair<Float, Float>>(count)

        val minSpacing = params.volcanoMinSpacingTiles.coerceAtLeast(0)
        val minSpacing2 = (minSpacing * minSpacing).toFloat()

        val attempts = max(1, count * 80)
        repeat(attempts) {
            if (chosen.size >= count) return@repeat

            val x = rng.nextInt(width)
            val y = rng.nextInt(height)

            val w = faultMask01[x][y].pow(params.volcanoFaultPower)
            if (rng.nextFloat() > w) return@repeat

            val ok = chosen.all { (cx, cy) ->
                val dx = x.toFloat() - cx
                val dy = y.toFloat() - cy
                (dx * dx + dy * dy) >= minSpacing2
            }
            if (ok) chosen.add(x.toFloat() to y.toFloat())
        }

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
        width: Int,
        height: Int,
        heightSigned: Field,
        volcanoMask01: Field01,
        ashMask01: Field01,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        coneHeight: Float,
        calderaDepth: Float,
        warp: Float,
        noiseSeed: Int,
        faultMask01: Field01,
        boundarySigned: FieldSigned,
        params: Params
    ) {
        val ix = cx.toInt().coerceIn(0, width - 1)
        val iy = cy.toInt().coerceIn(0, height - 1)

        // ---- Choose volcano type based on tectonics ----
        val fault = faultMask01[ix][iy] // 0..1

        // convergent01 = positive part of boundarySigned ([-1,+1] -> [0,1], negatives become 0)
        val convergent01 = clamp01(boundarySigned[ix][iy])

        val stratoChance = clamp01(
            params.stratoBase +
                    params.stratoFaultWeight * fault.pow(params.stratoFaultPow) +
                    params.stratoConvWeight * convergent01
        )

        val type = if (Random(noiseSeed xor 0xA11CE).nextFloat() < stratoChance)
            VolcanoType.STRATO else VolcanoType.SHIELD

        // ---- Shape modifiers by type ----
        val shape = when (type) {
            VolcanoType.SHIELD -> VolcanoShape(
                rMul = 1.55f,  // wide
                hMul = 0.72f,  // lower
                falloffPower = 1.15f, // gentle slope
                ashMul = 0.70f,
                roughMul = 0.70f,
                calderaMul = 0.70f
            )
            VolcanoType.STRATO -> VolcanoShape(
                rMul = 0.78f,  // narrow
                hMul = 1.25f,  // taller
                falloffPower = 3.00f, // steep cone
                ashMul = 1.35f,
                roughMul = 1.15f,
                calderaMul = 1.10f
            )
        }

        val r = (baseRadius * shape.rMul).coerceAtLeast(3f) // keep visible on small maps
        val h = coneHeight * shape.hMul
        val calderaD = calderaDepth * shape.calderaMul
        val rAsh = r * params.volcanoAshRadiusMul

        val minX = floor(cx - rAsh).toInt().coerceIn(0, width - 1)
        val maxX = ceil(cx + rAsh).toInt().coerceIn(0, width - 1)
        val minY = floor(cy - rAsh).toInt().coerceIn(0, height - 1)
        val maxY = ceil(cy + rAsh).toInt().coerceIn(0, height - 1)

        // Noise frequencies scale with volcano size so small radii still have detail
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
                    val cone = core.pow(shape.falloffPower)

                    delta += cone * h

                    // Rough lava/rock texture scaled by cone strength
                    val rough = (valueNoise2D(x.toFloat() * roughFreq, y.toFloat() * roughFreq, noiseSeed xor 0x1234) - 0.5f) * 2f
                    delta += rough * params.volcanoRoughness * shape.roughMul * cone

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

                // ---- Ash blanket (mask) ----
                val ashU = (wd / rAsh).coerceIn(0f, 1f)
                val ashCore = (1f - ashU).coerceIn(0f, 1f)

                val ashBase = ashCore * ashCore * params.volcanoAshStrength * shape.ashMul
                val ashTex = 0.78f + 0.22f * ((valueNoise2D(x.toFloat() * ashFreq, y.toFloat() * ashFreq, noiseSeed xor 0xBEEF) - 0.5f) * 2f)

                val ashVal = (ashBase * ashTex).coerceIn(0f, 1f)
                ashMask01[x][y] = 1f - (1f - ashMask01[x][y]) * (1f - ashVal)

                heightSigned[x][y] += delta
            }
        }
    }

}