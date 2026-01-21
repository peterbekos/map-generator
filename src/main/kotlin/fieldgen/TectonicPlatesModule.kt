package dev.nda.fieldgen
import dev.nda.math.*
import kotlin.math.*
import kotlin.random.Random

object TectonicPlatesModule {

    data class Params(
        val plateCount: Int = 14,

        // Velocity magnitude range
        val velMin: Float = 0.2f,
        val velMax: Float = 1.0f,

        // Probability a plate is “continental-ish” vs “oceanic-ish”
        val continentalChance: Float = 0.55f,

        // Bias ranges (0..1)
        val continentalBiasMin: Float = 0.6f,
        val continentalBiasMax: Float = 1.0f,
        val oceanicBiasMin: Float = 0.0f,
        val oceanicBiasMax: Float = 0.4f,

        // Optional: salt so plate seeds are different from other modules’ rng usage
        val seedSalt: Long = 0x504C41544553L // "PLATES"
    )

    // Your model
    data class Plate(
        val id: Int,
        val seed: Vec2,
        val velocity: Vec2,
        val continentalBias: Float // 0..1, higher = tends land
    )

    data class Fields(
        val plates: List<Plate>,
        /** plateId[x][y] => owning plate id */
        val plateId: Array<IntArray>
    )

    fun buildTectonicPlates(
        width: Int,
        height: Int,
        worldSeed: Long,
        params: Params = Params()
    ): Fields {
        val rng = Random(worldSeed xor params.seedSalt)

        val plates = generatePlates(width, height, rng, params)
        val plateId = assignPlateOwnership(width, height, plates)

        return Fields(plates = plates, plateId = plateId)
    }

    private fun generatePlates(
        width: Int,
        height: Int,
        rng: Random,
        p: Params
    ): List<Plate> {
        val count = p.plateCount.coerceAtLeast(1)
        val out = ArrayList<Plate>(count)

        for (i in 0 until count) {
            val sx = rng.nextFloat() * (width - 1)
            val sy = rng.nextFloat() * (height - 1)

            // Velocity: random direction + magnitude
            val angle = rng.nextFloat() * (2f * Math.PI.toFloat())
            val mag = (p.velMin + rng.nextFloat() * (p.velMax - p.velMin)).coerceAtLeast(0f)
            val v = Vec2(cos(angle) * mag, sin(angle) * mag)

            // Continental bias: choose some plates more continental, others oceanic
            val isCont = rng.nextFloat() < p.continentalChance
            val bias = if (isCont) {
                p.continentalBiasMin + rng.nextFloat() * (p.continentalBiasMax - p.continentalBiasMin)
            } else {
                p.oceanicBiasMin + rng.nextFloat() * (p.oceanicBiasMax - p.oceanicBiasMin)
            }.coerceIn(0f, 1f)

            out.add(Plate(i, Vec2(sx, sy), v, bias))
        }

        return out
    }

    private fun assignPlateOwnership(
        width: Int,
        height: Int,
        plates: List<Plate>
    ): Array<IntArray> {
        val plateId = Array(width) { IntArray(height) }

        for (x in 0 until width) {
            for (y in 0 until height) {

                var bestId = 0
                var bestD2 = Float.POSITIVE_INFINITY

                // NOTE: O(width*height*plateCount); fine for 128^2 with ~10-20 plates.
                // If you ever go huge, we can accelerate with a grid / jump flood.
                for (pl in plates) {
                    val dx = x.toFloat() - pl.seed.x
                    val dy = y.toFloat() - pl.seed.y
                    val d2 = dx * dx + dy * dy
                    if (d2 < bestD2) {
                        bestD2 = d2
                        bestId = pl.id
                    }
                }

                plateId[x][y] = bestId
            }
        }

        return plateId
    }
}