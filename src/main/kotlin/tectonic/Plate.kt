package dev.nda.tectonic

import dev.nda.math.Vec2

data class Plate(
    val id: Int,
    val seed: Vec2,
    val velocity: Vec2,
    val continentalBias: Float // 0..1, higher = tends land
)