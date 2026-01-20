package dev.nda.math

import kotlin.math.*

fun clamp01(v: Float): Float = when {
    v < 0f -> 0f
    v > 1f -> 1f
    else -> v
}

fun clampSigned(v: Float): Float = when {
    v < -1f -> -1f
    v > 1f -> 1f
    else -> v
}

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = clamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3f - 2f * t)
}

fun normalize01InPlace(grid: Field): Field01 {
    val width = grid.size
    val height = grid[0].size
    var mn = Float.POSITIVE_INFINITY
    var mx = Float.NEGATIVE_INFINITY
    for (x in 0 until width) for (y in 0 until height) {
        mn = min(mn, grid[x][y])
        mx = max(mx, grid[x][y])
    }
    val range = max(1e-6f, mx - mn)
    for (x in 0 until width) for (y in 0 until height) {
        grid[x][y] = (grid[x][y] - mn) / range
    }
    return grid
}

fun blurOnce(grid: Field) {
    val width = grid.size
    val height = grid[0].size
    val copy = Array(width) { FloatArray(height) }
    for (x in 0 until width) {
        for (y in 0 until height) {
            var sum = 0f
            var count = 0
            for (dx in -1..1) for (dy in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                sum += grid[nx][ny]
                count++
            }
            copy[x][y] = sum / count
        }
    }
    for (x in 0 until width) for (y in 0 until height) grid[x][y] = copy[x][y]
}

fun addFractalNoiseSeeded(
    grid: Field,
    strength: Float,
    seed: Int
) {
    val width = grid.size
    val height = grid[0].size
    val octaves = 4
    var amp = strength
    var freq = 1f / 24f // larger = noisier; tune

    repeat(octaves) { o ->
        val octaveSeed = seed + o * 999
        for (x in 0 until width) {
            for (y in 0 until height) {
                val n = valueNoise2D(x.toFloat() * freq, y.toFloat() * freq, octaveSeed)
                grid[x][y] += (n - 0.5f) * 2f * amp // signed
            }
        }
        amp *= 0.5f
        freq *= 2f
    }
}

fun valueNoise2D(x: Float, y: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val xf = x - xi
    val yf = y - yi

    val v00 = hash01(xi, yi, seed)
    val v10 = hash01(xi + 1, yi, seed)
    val v01 = hash01(xi, yi + 1, seed)
    val v11 = hash01(xi + 1, yi + 1, seed)

    val u = smoothstep(0f, 1f, xf)
    val v = smoothstep(0f, 1f, yf)

    val x1 = lerp(v00, v10, u)
    val x2 = lerp(v01, v11, u)
    return lerp(x1, x2, v)
}

fun hash01(x: Int, y: Int, seed: Int): Float {
    var n = x * 374761393 + y * 668265263 + seed * 1442695041
    n = (n xor (n shr 13)) * 1274126177
    n = n xor (n shr 16)
    // map to 0..1
    return ((n ushr 1) and 0x7fffffff).toFloat() / Int.MAX_VALUE.toFloat()
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun zerosField(width: Int, height: Int): Field =
    Array(width) { FloatArray(height) { 0f } }


/** Normalize in-place to [-1, +1] by max absolute value. */
fun normalizeSignedInPlace(a: Field): FieldSigned {
    val width = a.size
    val height = a[0].size
    var maxAbs = 0f
    for (x in 0 until width) for (y in 0 until height) {
        val v = abs(a[x][y])
        if (v > maxAbs) maxAbs = v
    }
    if (maxAbs < 1e-6f) return a
    val inv = 1f / maxAbs
    for (x in 0 until width) for (y in 0 until height) {
        a[x][y] *= inv
    }
    return a
}

fun signedTo01(s: FieldSigned): Field01 {
    val width = s.size
    val height = s[0].size
    val out = zerosField(width, height)
    for (x in 0 until width) for (y in 0 until height) {
        out[x][y] = (s[x][y] * 0.5f + 0.5f).coerceIn(0f, 1f)
    }
    return out
}

fun addScaledInPlace(dst: Field, src: Field, weight: Float) {
    val width = dst.size
    val height = dst[0].size
    for (x in 0 until width) for (y in 0 until height) {
        dst[x][y] += src[x][y] * weight
    }
}
