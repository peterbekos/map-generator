package dev.nda

import dev.nda.asciiprint.Palette
import dev.nda.asciiprint.printFieldMap01
import dev.nda.asciiprint.printFieldVectorMap01
import dev.nda.math.*
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

    /*
    To keep modules clean and prevent loops:
	•	TerrainElevationModule → elevation01
	•	FluidElevationModule (needs elevation01)
	•	PlanetFrameModule (lat only; no dependencies)
	•	TerrainMorphologyModule (needs elevation01 + isWater01)
	•	WindCirculationModule (needs latitudeSigned)
	•	WindModule (needs WindCirculation + TerrainMorphology + FluidElevation + maybe TerrainElevation if you still want peaks, etc.)

That keeps “planet physics-ish” and “terrain geometry-ish” distinct.
     */

    worldFields.apply {
        println("elevation")
        printFieldMap01(terrainElevationFields.elevation01)
        println("peak")
        printFieldMap01(terrainMorphologyFields.peak01, Palette.rainbowGradient)
        println("basin")
        printFieldMap01(terrainMorphologyFields.basin01, Palette.rainbowGradient)
        println("wind")
        printFieldMap01(windFields.windSpeed01, Palette.rainbowGradient)
        printFieldVectorMap01(windFields.windSpeed01, windFields.windDirXSigned, windFields.windDirYSigned)



    }

}