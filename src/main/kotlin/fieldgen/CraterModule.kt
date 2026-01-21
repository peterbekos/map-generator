package dev.nda.fieldgen

import dev.nda.math.*
import kotlin.math.*
import kotlin.random.Random

object CraterModule {

    data class Params(
        // ---- Density ----
        val tilesPerCrater: Int = 3200,
        val minCraters: Int = 1,
        val maxCraters: Int = 40,

        // ---- Radius in tiles (FIXED RANGE) ----
        val radiusMinTiles: Float = 6f,
        val radiusMaxTiles: Float = 14f,

        // ---- Shape knobs (your existing ones) ----
        val craterInnerRadiusMul: Float = 0.55f,
        val craterRimStartMul: Float = 0.85f,
        val craterRimEndMul: Float = 1.10f,
        val craterEjectaEndMul: Float = 1.85f,

        val craterDepth: Float = 0.22f,
        val craterRim: Float = 0.18f,
        val craterEjecta: Float = 0.16f,
        val craterWarp: Float = 0.18f,

        val craterBowlPower: Float = 0.8f,
        val craterEjectaBias: Float = 0.65f,

        // Noise frequencies (so you can tune without hunting inside stamp)
        val warpNoiseFreq: Float = 0.9f,
        val ejectaNoiseFreq: Float = 2.3f,

        // Seed salt so craters don’t correlate with other modules
        val seedSalt: Int = 0xC4A8734
    )

    data class Fields(
        val craterSigned: FieldSigned, // [-1,+1]
        val craterMask01: Field01      // [0,1]
    )

    fun buildCraterFields(
        width: Int,
        height: Int,
        worldSeed: Long,
        params: Params = Params()
    ): Fields {
        val craterSigned: Field = zerosField(width, height)
        val craterMask01: Field = zerosField(width, height)

        val seed = (worldSeed.toInt() xor params.seedSalt)
        val rng = Random(seed)

        val count = computeCraterCount(width, height, params)

        repeat(count) { i ->
            // Pick center anywhere (you can add edge-avoid bias later)
            val cx = rng.nextFloat() * (width - 1)
            val cy = rng.nextFloat() * (height - 1)

            val radius = lerp(params.radiusMinTiles, params.radiusMaxTiles, rng.nextFloat())

            // Per-crater variation
            val depth = params.craterDepth * (0.75f + rng.nextFloat() * 0.5f)
            val rim = params.craterRim * (0.75f + rng.nextFloat() * 0.5f)
            val ejecta = params.craterEjecta * (0.75f + rng.nextFloat() * 0.5f)
            val warp = params.craterWarp * (0.6f + rng.nextFloat() * 0.8f)

            stampCraterMassNearNeutral(
                width = width,
                height = height,
                heightSigned = craterSigned,
                mask01 = craterMask01,
                cx = cx,
                cy = cy,
                radius = radius,
                depth = depth,
                rim = rim,
                ejecta = ejecta,
                warp = warp,
                noiseSeed = seed + i * 1337,
                params = params
            )
        }

        normalizeSignedInPlace(craterSigned)
        normalize01InPlace(craterMask01)

        return Fields(
            craterSigned = craterSigned,
            craterMask01 = craterMask01
        )
    }

    private fun computeCraterCount(width: Int, height: Int, p: Params): Int {
        val tiles = width * height
        val raw = tiles / p.tilesPerCrater
        return raw.coerceIn(p.minCraters, p.maxCraters)
    }

    private fun stampCraterMassNearNeutral(
        width: Int,
        height: Int,
        heightSigned: Field,
        mask01: Field,
        cx: Float,
        cy: Float,
        radius: Float,
        depth: Float,
        rim: Float,
        ejecta: Float,
        warp: Float,
        noiseSeed: Int,
        params: Params
    ) {
        val rInner = radius * params.craterInnerRadiusMul
        val rRimStart = radius * params.craterRimStartMul
        val rRimEnd = radius * params.craterRimEndMul
        val rEjectaEnd = radius * params.craterEjectaEndMul

        val minX = floor(cx - rEjectaEnd).toInt().coerceIn(0, width - 1)
        val maxX = ceil(cx + rEjectaEnd).toInt().coerceIn(0, width - 1)
        val minY = floor(cy - rEjectaEnd).toInt().coerceIn(0, height - 1)
        val maxY = ceil(cy + rEjectaEnd).toInt().coerceIn(0, height - 1)

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val dx = x.toFloat() - cx
                val dy = y.toFloat() - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > rEjectaEnd) continue

                // Warp: stable distortion so it’s not perfectly circular
                val w = (valueNoise2D(
                    x = x.toFloat() * params.warpNoiseFreq,
                    y = y.toFloat() * params.warpNoiseFreq,
                    seed = noiseSeed
                ) - 0.5f) * 2f

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

                    val n = (valueNoise2D(
                        x = x.toFloat() * params.ejectaNoiseFreq,
                        y = y.toFloat() * params.ejectaNoiseFreq,
                        seed = noiseSeed xor 0x5A5A
                    ) - 0.5f) * 2f // -1..1

                    val n01 = (n * 0.5f + 0.5f) // 0..1
                    val biased = lerp(n, n01, params.craterEjectaBias)

                    val ripple = 0.70f + 0.30f * cos(warpedD / (radius * 0.18f))

                    val dep = falloff * ejecta * (0.75f * biased + 0.25f * ripple)
                    delta += dep
                    mask = max(mask, falloff)
                }

                // Attenuate positive additions on already-high terrain (prevents huge stacking peaks)
                val cur = heightSigned[x][y]
                val atten = if (delta > 0f) (1f - clamp01(cur * 0.9f)) else 1f
                heightSigned[x][y] = cur + delta * atten

                // Smooth union for crater mask
                val a = mask01[x][y]
                val b = mask
                mask01[x][y] = 1f - (1f - a) * (1f - b)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}