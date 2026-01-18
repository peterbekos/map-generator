package dev.nda.math

import kotlin.math.sqrt

data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
}

fun dot(a: Vec2, b: Vec2): Float = a.x * b.x + a.y * b.y
fun cross2(a: Vec2, b: Vec2): Float = a.x * b.y - a.y * b.x

fun length(v: Vec2): Float = sqrt(v.x * v.x + v.y * v.y)
fun normalize(v: Vec2): Vec2 {
    val len = length(v)
    return if (len < 1e-6f) Vec2(0f, 0f) else Vec2(v.x / len, v.y / len)
}