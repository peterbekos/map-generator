package dev.nda.asciiprint

object Palette {

    val rainbowGradient = listOf(
        0.0f to RGB(0, 0, 255),
        0.2f to RGB(0, 255, 255),
        0.4f to RGB(0, 255, 0),
        0.6f to RGB(255, 255, 0),
        0.8f to RGB(255, 0, 0),
        1.0f to RGB(255, 0, 255),
    )

    val rainbowDarkGradient = listOf(
        0.0f to RGB(0, 0, 128),
        0.2f to RGB(0, 128, 128),
        0.4f to RGB(0, 128, 0),
        0.6f to RGB(128, 128, 0),
        0.8f to RGB(128, 0, 0),
        1.0f to RGB(128, 0, 128),
    )

    val landGradient = listOf(
        // Water
        0.0f to RGB(0, 0, 64),
        0.2f to RGB(0, 0, 255),
        0.4f to RGB(0, 255, 225),
        // Beach
        0.42f to RGB(255, 255, 0),
        // Grass
        0.45f to RGB(0, 128, 0),
        0.65f to RGB(64, 196, 32),
        // Mountain
        0.8f to RGB(128, 96, 64),
        0.95f to RGB(96, 96, 96),
        // Snow
        1.0f to RGB(255, 255, 255),
    )
}