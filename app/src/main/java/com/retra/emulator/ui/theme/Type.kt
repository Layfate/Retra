package com.retra.emulator.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.retra.emulator.R

// Inter variable font (res/font/inter.ttf, SIL OFL 1.1); each weight is an instance of its wght axis.
// Glyphs Inter lacks (e.g. the Kannada in the "ಥ_ಥ" empty state) fall back to the system font.
@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private fun TextStyle.inter() = copy(fontFamily = Inter)

/** Material 3's type scale, all in Inter. */
val Typography = Typography().run {
    copy(
        displayLarge = displayLarge.inter(), displayMedium = displayMedium.inter(), displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.inter(), headlineMedium = headlineMedium.inter(), headlineSmall = headlineSmall.inter(),
        titleLarge = titleLarge.inter(), titleMedium = titleMedium.inter(), titleSmall = titleSmall.inter(),
        bodyLarge = bodyLarge.inter(), bodyMedium = bodyMedium.inter(), bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(), labelMedium = labelMedium.inter(), labelSmall = labelSmall.inter(),
    )
}
