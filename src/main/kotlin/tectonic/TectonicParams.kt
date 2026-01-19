package dev.nda.tectonic

data class TectonicParams(
    val plateCount: Int = 14,
    val ridgeStrength: Float = 0.9f,
    val riftStrength: Float = 0.6f,
    val transformRoughness: Float = 0.2f,
    val boundaryFalloff: Float = 6.0f, // how far boundary effects spread
    val continentMaskStrength: Float = 0.9f,
    val plateBiasStrength: Float = 0.8f,
    val boundaryStrength: Float = 1.0f,
    val noiseStrength: Float = 0.25f,
    val smoothPasses: Int = 1
)