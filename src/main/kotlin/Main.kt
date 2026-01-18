package dev.nda

import dev.nda.asciiprint.printHeightmap
import dev.nda.tectonic.TectonicWorldGen
import kotlin.random.Random

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main() {
    val w = 64
    val h = 32
    val seed = Random.nextLong()
    val gen = TectonicWorldGen(w, h, seed)

    val heightMap = gen.generateHeightmap()

    printHeightmap(heightMap)
}