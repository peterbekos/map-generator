package dev.nda.asciiprint

object ANSITrueColor {
    const val RST = "\u001B[0m"

    fun bg(r: Int, g: Int, b: Int) = "\u001B[48;2;${r};${g};${b}m"
    fun bg(rgb: RGB) = "\u001B[48;2;${rgb.r};${rgb.g};${rgb.b}m"
}