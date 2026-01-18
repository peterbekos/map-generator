package dev.nda.asciiprint

fun printHeightmap(map: Array<FloatArray>) {
    val w = map.size
    val h = map[0].size

    for (y in 0 until h) {
        val sb = StringBuilder(w * 2)
        for (x in 0 until w) {
            sb.append(heightCellRGB(map[x][y]))
        }
        println(sb.toString() + ANSITrueColor.RST)
    }
    println(ANSITrueColor.RST)
}

fun heightCellRGB(h: Float, colors: List<Pair<Float, RGB>> = Palette.landGradient): String {
    val c = gradient(h, colors)
    return ANSITrueColor.bg(c) + " 　"
}