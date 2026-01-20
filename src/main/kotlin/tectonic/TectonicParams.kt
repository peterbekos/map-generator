package dev.nda.tectonic

data class TectonicParams(
    val plateCount: Int = 14,
    val ridgeStrength: Float = 0.9f,
    val riftStrength: Float = 0.6f,
    val transformRoughness: Float = 0.2f,
    val boundaryFalloff: Float = 8.0f, // how far boundary effects spread
    val continentMaskStrength: Float = 0.9f,
    val plateBiasStrength: Float = 0.35f,
    val boundaryStrength: Float = 0.9f,
    val noiseStrength: Float = 0.5f,
    val smoothPasses: Int = 1,

    // --- Craters ---
    val craterStrength: Float = 0.85f,
    val craterCount: Int = 2,
    val craterRadiusMinMul: Float = 0.035f,   // * min(width,height)
    val craterRadiusMaxMul: Float = 0.12f,
    val craterDepth: Float = 0.25f,          // signed layer strength (unit-ish)
    val craterRim: Float = 0.33f,
    val craterEjecta: Float = 0.12f,
    val craterWarp: Float = 0.10f,
    val craterEjectaBias: Float = 0.75f,
    val craterBowlPower: Float = 0.65f,
    val craterInnerRadiusMul: Float = 0.90f, //0.62f,
    val craterRimStartMul: Float = 0.90f,
    val craterRimEndMul: Float = 1.18f,
    val craterEjectaEndMul: Float = 1.85f,
)