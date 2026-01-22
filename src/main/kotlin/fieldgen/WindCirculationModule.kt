package dev.nda.fieldgen

import dev.nda.math.*
import kotlin.math.*

object WindCirculationModule {

    data class Params(
//        val globalDir: Vec2 = Vec2(1f, 0f),

        val hadleyMax: Float = 0.33f,
        val ferrelMax: Float = 0.72f,
        val bandTransition: Float = 0.06f,
        val bandMix: Float = 0.65f,
        val meridionalStrength: Float = 0.18f,

        // Speed regime multipliers
        val hadleySpeedMul: Float = 0.85f,
        val ferrelSpeedMul: Float = 1.20f,
        val polarSpeedMul: Float = 0.80f,
        val jetStrength: Float = 0.20f,
        val jetCenterDistEq: Float = 0.55f,
        val jetSigma: Float = 0.12f,

        val coriolisStrength: Float = 0.55f,    // 0..1-ish
        val coriolisMaxDegrees: Float = 35f,    // max turn near poles
        val coriolisCurvePower: Float = 1.25f   // emphasize mid/high lats
    )

    data class Fields(
        val windDirXSigned: FieldSigned, // [-1,+1]
        val windDirYSigned: FieldSigned,  // [-1,+1]
        val bandSpeedMul01: Field01
    )

    fun buildWindCirculationFields(
        width: Int,
        height: Int,
        latitudeFields: LatitudeModule.Fields,
        params: Params = Params()
    ): Fields {
        val outX: Field = zerosField(width, height)
        val outY: Field = zerosField(width, height)
        val speedMul01: Field = zerosField(width, height)

//        val g = normalize(params.globalDir)

        fun smoothBand(t: Float): Float = smoothstep(0f, 1f, t.coerceIn(0f, 1f))

        fun gauss(x: Float, mu: Float, sigma: Float): Float {
            val z = (x - mu) / max(1e-6f, sigma)
            return exp(-0.5f * z * z)
        }

        // degrees -> radians
        val maxTurnRad = params.coriolisMaxDegrees * (Math.PI.toFloat() / 180f)

        for (x in 0 until width) for (y in 0 until height) {
            val latSigned = latitudeFields.latitudeSigned[x][y].coerceIn(-1f, 1f)
            val lat01 = latSigned * 0.5f + 0.5f
            val distEq = abs(lat01 - 0.5f) * 2f // 0 equator, 1 pole

            // --- Direction bands (continuous X) ---
            val tH = (distEq - params.hadleyMax) / params.bandTransition
            val tF = (distEq - params.ferrelMax) / params.bandTransition
            val hadleyToFerrel = smoothBand(tH)
            val ferrelToPolar = smoothBand(tF)

            val xHF = lerp(-1f, +1f, hadleyToFerrel)   // Hadley -> Ferrel
            val xFP = lerp(+1f, -1f, ferrelToPolar)    // Ferrel -> Polar
            val bandX = if (distEq <= params.ferrelMax) xHF else xFP

            // Meridional tilt (simple “cells” feel)
            val bandYDir = when {
                distEq <= params.hadleyMax -> equatorwardY(latSigned)
                distEq <= params.ferrelMax -> polewardY(latSigned)
                else -> equatorwardY(latSigned)
            }
            val bandY = bandYDir * params.meridionalStrength

            // Base band direction before Coriolis
            val bandDir = normalize(Vec2(bandX, bandY))

            // --- Coriolis turn ---
            // Strength: 0 at equator, ~1 near poles (curve shapes where it “kicks in”)
            val coriolis01 = abs(latSigned).coerceIn(0f, 1f).pow(params.coriolisCurvePower)

            // Turn direction: right in north, left in south.
            // With +Y downward, “right turn” in north = clockwise rotation (negative radians).
            val turnSign = if (latSigned < 0f) -1f else +1f

            val turnRad = turnSign * maxTurnRad * coriolis01 * params.coriolisStrength

            val bandDirC = normalize(rotate(bandDir, turnRad))

            // Blend with global dir (keeps your “world has a general prevailing push” idea)
            val dir = bandDirC //normalize(lerpVec(g, bandDirC, params.bandMix))

            outX[x][y] = dir.x
            outY[x][y] = dir.y

            // --- Speed multiplier (continuous) ---
            val baseHF = lerp(params.hadleySpeedMul, params.ferrelSpeedMul, hadleyToFerrel)
            val baseFP = lerp(params.ferrelSpeedMul, params.polarSpeedMul, ferrelToPolar)
            var mul = if (distEq <= params.ferrelMax) baseHF else baseFP

            if (params.jetStrength > 0f) {
                val jet = gauss(distEq, params.jetCenterDistEq, params.jetSigma) // 0..1
                mul *= (1f + params.jetStrength * jet)
            }

            // Store absolute for now (we'll normalize after the loop)
            speedMul01[x][y] = mul.coerceIn(0.4f, 2.0f)
        }

        // Normalize multiplier to 0..1 for easier downstream mixing
        for (x in 0 until width) for (y in 0 until height) {
            speedMul01[x][y] = ((speedMul01[x][y] - 0.4f) / (2.0f - 0.4f)).coerceIn(0f, 1f)
        }

        return Fields(
            windDirXSigned = outX,
            windDirYSigned = outY,
            bandSpeedMul01 = speedMul01
        )
    }

    private fun rotate(v: Vec2, radians: Float): Vec2 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec2((v.x * c - v.y * s), (v.x * s + v.y * c))
    }

    // y grows downward
    private fun equatorwardY(latSigned: Float): Float =
        if (latSigned < 0f) +1f else -1f   // north hemi -> south (+Y), south hemi -> north (-Y)

    private fun polewardY(latSigned: Float): Float =
        -equatorwardY(latSigned)

    private fun lerpVec(a: Vec2, b: Vec2, t: Float): Vec2 =
        Vec2(lerp(a.x, b.x, t), lerp(a.y, b.y, t))
}