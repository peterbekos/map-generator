package dev.nda.math

import kotlin.math.*
import kotlin.random.Random

fun clamp01(v: Float): Float = when {
    v < 0f -> 0f
    v > 1f -> 1f
    else -> v
}

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = clamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3f - 2f * t)
}

fun normalize01(grid: Array<FloatArray>): Array<FloatArray> {
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

fun blurOnce(grid: Array<FloatArray>) {
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

fun addFractalNoise(grid: Array<FloatArray>, strength: Float, rng: Random) {
    val width = grid.size
    val height = grid[0].size
    val baseSeed = rng.nextInt()
    val octaves = 4
    var amp = strength
    var freq = 1f / 24f // larger = noisier; tune
    repeat(octaves) { o ->
        for (x in 0 until width) {
            for (y in 0 until height) {
                val n = valueNoise2D(x.toFloat() * freq, y.toFloat() * freq, baseSeed + o * 999)
                grid[x][y] += (n - 0.5f) * 2f * amp
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

private fun hash01(x: Int, y: Int, seed: Int): Float {
    var n = x * 374761393 + y * 668265263 + seed * 1442695041
    n = (n xor (n shr 13)) * 1274126177
    n = n xor (n shr 16)
    // map to 0..1
    return ((n ushr 1) % 1_000_000) / 1_000_000f
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
