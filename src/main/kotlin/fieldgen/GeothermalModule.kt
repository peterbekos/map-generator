package dev.nda.fieldgen

import dev.nda.fieldgen.*
import dev.nda.math.*
import kotlin.math.*

object GeothermalModule {

    data class Params(
        val volcanoHeatStrength: Float = 1.0f,
        val faultHeatStrength: Float = 0.35f,

        // Dampening controls
        val waterDampenMul: Float = 0.45f,     // 0..1, how much heat remains under water
        val thinAirDampenMul: Float = 0.65f,   // 0..1, how much heat remains at very high altitude

        // Optional: tiny extra heat/insulation from ash blanket (usually very small)
        val ashHeatStrength: Float = 0.06f,

        val spreadBlurPasses: Int = 2
    )

    data class Fields(
        val geothermalHeat01: Field01,
        val geothermalRaw01: Field01 // helpful for debugging before dampening + blur
    )

    fun buildGeothermalFields(
        width: Int,
        height: Int,
        volcano: VolcanoModule.Fields,
        boundary: PlateBoundaryModule.Fields,
        fluidElevation: FluidElevationModule.Fields,
        params: Params = Params()
    ): Fields {

        val raw = zerosField(width, height)

        // 1) Base raw geothermal potential (no dampening yet)
        for (x in 0 until width) for (y in 0 until height) {
            val v = volcano.volcanoMask01[x][y].coerceIn(0f, 1f)
            val f = boundary.faultMask01[x][y].coerceIn(0f, 1f)
            val a = volcano.ashMask01[x][y].coerceIn(0f, 1f)

            raw[x][y] =
                v * params.volcanoHeatStrength +
                        f * params.faultHeatStrength +
                        a * params.ashHeatStrength
        }

        // Normalize raw for easy inspection
        val geothermalRaw01 = normalize01InPlace(raw)

        // 2) Apply environment dampening (water + thin air)
        val heat = zerosField(width, height)
        for (x in 0 until width) for (y in 0 until height) {

            val base = geothermalRaw01[x][y]

            val isWater = fluidElevation.isWater01[x][y].coerceIn(0f, 1f)      // 1 water
            val thinAir = fluidElevation.thinAirMask01[x][y].coerceIn(0f, 1f)  // 1 very thin air

            // Under water: geothermal still exists, but it shouldn't translate to "warm air"
            val waterMul = lerp(1f, params.waterDampenMul, isWater)

            // Thin air: less air mass, more mixing, less felt as temperature
            val thinMul = lerp(1f, params.thinAirDampenMul, thinAir)

            heat[x][y] = base * waterMul * thinMul
        }

        // 3) Spread slightly (vents warm nearby ground)
        repeat(params.spreadBlurPasses) { blurOnce(heat) }
        normalize01InPlace(heat)

        return Fields(
            geothermalHeat01 = heat,
            geothermalRaw01 = geothermalRaw01
        )
    }
}