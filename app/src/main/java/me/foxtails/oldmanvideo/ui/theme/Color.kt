package me.foxtails.oldmanvideo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import kotlin.random.Random

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

private fun randomAccent(random: Random): Color {
    val hue = random.nextFloat() * 360f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.9f)))
}

fun randomDarkColorScheme(): ColorScheme {
    val random = Random(System.nanoTime())
    val primary = randomAccent(random)
    val secondary = randomAccent(random)
    val tertiary = randomAccent(random)
    return darkColorScheme(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        background = Color(0xFF101014),
        surface = Color(0xFF17171D),
        surfaceVariant = Color(0xFF303038),
    )
}