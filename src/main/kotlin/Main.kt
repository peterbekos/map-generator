package dev.nda

import dev.nda.asciiprint.printHeightmap
import dev.nda.fieldgen.*
import dev.nda.worldgen.*
import kotlin.random.Random

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main() {
    val size = 96
    var w = 64
    var h = 32
    w = size
    h = size
    val seed = Random.nextLong()

    val worldFields = WorldBuilderModule.buildWorldFields(w, h, seed,
        params = WorldBuilderModule.Params()
    )

    worldFields.apply {
        printHeightmap(terrainElevationFields.elevation01)
    }

}