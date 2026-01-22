package dev.nda.asciiprint

import dev.nda.math.Field01
import dev.nda.math.FieldSigned
import kotlin.math.*

fun printFieldMap01(map: Field01, colors: List<Pair<Float, RGB>> = Palette.landGradient) {
    val w = map.size
    val h = map[0].size

    for (y in 0 until h) {
        val sb = StringBuilder(w * 2)
        for (x in 0 until w) {
            sb.append(heightCellRGB(map[x][y], colors))
        }
        println(sb.toString() + ANSITrueColor.RST)
    }
    println(ANSITrueColor.RST)
}

fun printFieldVectorMap01(map: Field01, dxSigned: FieldSigned, dySigned: FieldSigned, colors: List<Pair<Float, RGB>> = Palette.rainbowDarkGradient) {
    val w = map.size
    val h = map[0].size

    for (y in 0 until h) {
        val sb = StringBuilder(w * 2)
        for (x in 0 until w) {
            val dir = arrowToken16Pair(dxSigned[x][y], dySigned[x][y])
            sb.append(heightCellRGB(map[x][y], colors, dir))
        }
        println(sb.toString() + ANSITrueColor.RST)
    }
    println(ANSITrueColor.RST)
}

fun heightCellRGB(h: Float, colors: List<Pair<Float, RGB>> = Palette.landGradient, text: String = "  "): String {
    val c = gradient(h, colors)
    return ANSITrueColor.bg(c) + text
}

private val ARROW8 = arrayOf("→","↗","↑","↖","←","↙","↓","↘")

fun arrowToken16Pair(dx: Float, dy: Float): String {
    val len2 = dx*dx + dy*dy
    if (len2 < 1e-8f) return "··"   // calm

    // angle: 0 = east, +90 = north (invert dy)
    val ang = atan2(-dy, dx)
    val twoPi = (Math.PI * 2).toFloat()
    val a = (ang + twoPi) % twoPi   // [0..2pi)

    val sector = (a / twoPi * 16f).toInt().coerceIn(0,15)

    val base = sector / 2
    val next = (base + 1) % 8
    val half = (sector % 2) == 1

    return if (half) {
        ARROW8[base] + ARROW8[next]   // blend direction
    } else {
        ARROW8[base] + ARROW8[base]   // pure cardinal/diagonal
    }
}