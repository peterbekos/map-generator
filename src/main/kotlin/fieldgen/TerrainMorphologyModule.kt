package dev.nda.fieldgen

import dev.nda.math.*
import dev.nda.fieldgen.TerrainElevationModule
import kotlin.math.*

object TerrainMorphologyModule {

    data class Params(
        // Neighborhood radius for peak/basin (mean filter window)
        val peakBasinRadiusTiles: Int = 4,

        // Roughness neighborhood radius (smaller makes it “micro” roughness)
        val roughnessRadiusTiles: Int = 2,

        // Gradient / steepness scaling:
        // elev01 is 0..1, so raw gradient magnitudes are “in normalized units per tile”.
        // This scale lets you decide how fast steepness reaches 90°.
        val steepnessScale: Float = 6.0f,

        // Must match FluidElevation sea level so “floor/ceiling” behavior is consistent.
        val seaLevel01: Float = 0.42f
    )

    data class Fields(
        val peak01: Field01,
        val basin01: Field01,
        val peakBelowSea01: Field01,
        val basinBelowSea01: Field01,
        val roughness01: Field01,

        val steepness01: Field01,      // 0..1 mapped to 0..90deg
        val gradXSigned: FieldSigned,  // [-1,+1] (consistent scaling with gradY)
        val gradYSigned: FieldSigned,  // [-1,+1]

        val normalXSigned: FieldSigned, // uphill direction (unit-ish)
        val normalYSigned: FieldSigned
    )

    fun buildTerrainMorphologyFields(
        width: Int,
        height: Int,
        terrain: TerrainElevationModule.Fields,
        fluid: FluidElevationModule.Fields,
        params: Params = Params()
    ): Fields {

        val elev01 = terrain.elevation01
        val isWater01 = fluid.isWater01
        val sea = params.seaLevel01

        // ---- Effective elevation variants ----
        // Above-sea computation: treat water as sea-level floor.
        val elevAbove = zerosField(width, height)
        // Below-sea computation: treat land as sea-level ceiling.
        val elevBelow = zerosField(width, height)

        for (x in 0 until width) for (y in 0 until height) {
            val e = elev01[x][y]
            elevAbove[x][y] = maxOf(e, sea)
            elevBelow[x][y] = minOf(e, sea)
        }

        // ---- Peaks / basins (above sea) ----
        val (peakAboveAll01, basinAboveAll01) =
            computePeakBasin01(elevAbove, params.peakBasinRadiusTiles)

        // Mask to land only
        val peak01 = zerosField(width, height)
        val basin01 = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val landMask = (1f - isWater01[x][y]).coerceIn(0f, 1f)
            peak01[x][y] = peakAboveAll01[x][y] * landMask
            basin01[x][y] = basinAboveAll01[x][y] * landMask
        }
        normalize01InPlace(peak01)
        normalize01InPlace(basin01)

        // ---- Peaks / basins (below sea) ----
        val (peakBelowAll01, basinBelowAll01) =
            computePeakBasin01(elevBelow, params.peakBasinRadiusTiles)

        // Mask to water only
        val peakBelowSea01 = zerosField(width, height)
        val basinBelowSea01 = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {
            val waterMask = isWater01[x][y].coerceIn(0f, 1f)
            peakBelowSea01[x][y] = peakBelowAll01[x][y] * waterMask
            basinBelowSea01[x][y] = basinBelowAll01[x][y] * waterMask
        }
        normalize01InPlace(peakBelowSea01)
        normalize01InPlace(basinBelowSea01)

        // ---- Roughness (single field) ----
        // Here: mean absolute deviation from local mean (good “bumpy vs smooth” signal)
        val roughness01 = computeRoughness01(elev01, params.roughnessRadiusTiles)

        // ---- Gradient (signed) + steepness + normals ----
        val gradX = zerosField(width, height)
        val gradY = zerosField(width, height)

        fun sampleClamped(xx: Int, yy: Int): Float {
            val x = xx.coerceIn(0, width - 1)
            val y = yy.coerceIn(0, height - 1)
            return elev01[x][y]
        }

        // Raw central differences (signed)
        for (x in 0 until width) for (y in 0 until height) {
            val dx = (sampleClamped(x + 1, y) - sampleClamped(x - 1, y)) * 0.5f
            val dy = (sampleClamped(x, y + 1) - sampleClamped(x, y - 1)) * 0.5f
            gradX[x][y] = dx
            gradY[x][y] = dy
        }

        // Normalize gradX/gradY together so direction is preserved (important!)
        normalizeVectorPairSignedInPlace(gradX, gradY)

        val steepness01 = zerosField(width, height)
        val normalX = zerosField(width, height)
        val normalY = zerosField(width, height)

        val sScale = maxOf(1e-6f, params.steepnessScale)
        val invHalfPi = (1f / (Math.PI.toFloat() * 0.5f))

        for (x in 0 until width) for (y in 0 until height) {
            // Use *raw* (pre-normalization) slope for steepness?
            // We already normalized grad fields for direction. For steepness, re-derive from elev01:
            // (Keeps steepness meaningful even if gradients are normalized for vector stability.)
            val dx = (sampleClamped(x + 1, y) - sampleClamped(x - 1, y)) * 0.5f
            val dy = (sampleClamped(x, y + 1) - sampleClamped(x, y - 1)) * 0.5f
            val g = kotlin.math.sqrt(dx * dx + dy * dy)

            val angle = kotlin.math.atan(g * sScale) // 0..pi/2
            steepness01[x][y] = (angle * invHalfPi).coerceIn(0f, 1f)

            // Normal (uphill) from normalized grad fields
            val gx = gradX[x][y]
            val gy = gradY[x][y]
            val m = kotlin.math.sqrt(gx * gx + gy * gy)
            if (m < 1e-6f) {
                normalX[x][y] = 0f
                normalY[x][y] = 0f
            } else {
                val inv = 1f / m
                normalX[x][y] = gx * inv
                normalY[x][y] = gy * inv
            }
        }

        // steepness01 already 0..1-ish, but normalize for consistent ranges across maps
        normalize01InPlace(steepness01)

        return Fields(
            peak01 = peak01,
            basin01 = basin01,
            peakBelowSea01 = peakBelowSea01,
            basinBelowSea01 = basinBelowSea01,
            roughness01 = roughness01,

            steepness01 = steepness01,
            gradXSigned = gradX,
            gradYSigned = gradY,

            normalXSigned = normalX,
            normalYSigned = normalY
        )
    }

    /** Peaks & basins relative to local mean. Returns (peak01, basin01). */
    private fun computePeakBasin01(elev: Field01, radius: Int): Pair<Field01, Field01> {
        val w = elev.size
        val h = elev[0].size
        val peak = zerosField(w, h)
        val basin = zerosField(w, h)

        for (x in 0 until w) for (y in 0 until h) {
            var sum = 0f
            var count = 0
            for (dx in -radius..radius) for (dy in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                sum += elev[nx][ny]
                count++
            }
            val mean = sum / maxOf(1, count)
            val d = elev[x][y] - mean
            if (d >= 0f) peak[x][y] = d else basin[x][y] = -d
        }

        return normalize01InPlace(peak) to normalize01InPlace(basin)
    }

    /** Mean absolute deviation from local mean (simple roughness). */
    private fun computeRoughness01(elev: Field01, radius: Int): Field01 {
        val w = elev.size
        val h = elev[0].size
        val out = zerosField(w, h)

        for (x in 0 until w) for (y in 0 until h) {
            var sum = 0f
            var count = 0
            for (dx in -radius..radius) for (dy in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                sum += elev[nx][ny]
                count++
            }
            val mean = sum / maxOf(1, count)

            var mad = 0f
            for (dx in -radius..radius) for (dy in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                mad += kotlin.math.abs(elev[nx][ny] - mean)
            }
            out[x][y] = mad / maxOf(1, count)
        }

        return normalize01InPlace(out)
    }

    /**
     * Normalize two signed fields together so vector direction is preserved.
     * Scales both by the max absolute component magnitude seen across both.
     */
    private fun normalizeVectorPairSignedInPlace(x: Field, y: Field): Pair<FieldSigned, FieldSigned> {
        val w = x.size
        val h = x[0].size
        var maxAbs = 0f

        for (i in 0 until w) for (j in 0 until h) {
            val ax = kotlin.math.abs(x[i][j])
            val ay = kotlin.math.abs(y[i][j])
            if (ax > maxAbs) maxAbs = ax
            if (ay > maxAbs) maxAbs = ay
        }

        if (maxAbs < 1e-6f) return x to y

        val inv = 1f / maxAbs
        for (i in 0 until w) for (j in 0 until h) {
            x[i][j] *= inv
            y[i][j] *= inv
        }
        return x to y
    }
}