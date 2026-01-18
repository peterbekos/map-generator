package dev.nda.asciiprint

import kotlin.math.roundToInt

fun lerpRGB(a: RGB, b: RGB, t: Float): RGB =
    RGB(
        lerp(a.r, b.r, t),
        lerp(a.g, b.g, t),
        lerp(a.b, b.b, t)
    )

fun lerp(a: Int, b: Int, t: Float): Int =
    (a + (b - a) * t).roundToInt()

fun gradient(t0: Float, stops: List<Pair<Float, RGB>>): RGB {
    val t = t0.coerceIn(0f, 1f)
    if (stops.isEmpty()) return RGB(0, 0, 0)
    if (t <= stops.first().first) return stops.first().second
    if (t >= stops.last().first) return stops.last().second

    for (i in 0 until stops.size - 1) {
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        if (t in p0..p1) {
            val u = ((t - p0) / (p1 - p0)).coerceIn(0f, 1f)
            return lerpRGB(c0, c1, u)
        }
    }
    return stops.last().second
}