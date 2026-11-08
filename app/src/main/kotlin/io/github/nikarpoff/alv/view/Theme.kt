package io.github.nikarpoff.alv.view

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.nikarpoff.alv.R

// MOB-12: text is never smaller than 28 sp
private val AlvTypography = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp),
)

/** High-contrast theme of the app (MOB-12): white and yellow on black, large type. */
@Composable
fun AlvTheme(content: @Composable () -> Unit) {
    val black = colorResource(R.color.black)
    val white = colorResource(R.color.white)
    val yellow = colorResource(R.color.yellow)
    val colors = darkColorScheme(
        primary = yellow,
        onPrimary = black,
        background = black,
        onBackground = white,
        surface = black,
        onSurface = white,
    )
    MaterialTheme(colorScheme = colors, typography = AlvTypography, content = content)
}
