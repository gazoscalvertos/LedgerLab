package com.ledgerlab.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ledgerlab.xrpl.TxCategory

object LabColors {
    val Background = Color(0xFF05070B)
    val Surface = Color(0xFF0C1118)
    val SurfaceHigh = Color(0xFF131A24)
    val Outline = Color(0xFF1E2A38)
    val Matrix = Color(0xFF00FF9C)
    val Cyan = Color(0xFF00E5FF)
    val Magenta = Color(0xFFFF3DCB)
    val Gold = Color(0xFFFFC940)
    val Violet = Color(0xFF9D7BFF)
    val Orange = Color(0xFFFF8A3D)
    val Danger = Color(0xFFFF4D6D)
    val TextPrimary = Color(0xFFE8F1F8)
    val TextSecondary = Color(0xFF8A9BB0)
    val TextMuted = Color(0xFF55657A)

    fun category(category: TxCategory): Color = when (category) {
        TxCategory.PAYMENT -> Matrix
        TxCategory.DEX -> Cyan
        TxCategory.AMM -> Violet
        TxCategory.NFT -> Magenta
        TxCategory.TRUST -> Gold
        TxCategory.ESCROW -> Orange
        TxCategory.ACCOUNT -> Color(0xFF6FA8FF)
        TxCategory.OTHER -> Color(0xFF9AA7B8)
    }
}

val Mono = FontFamily.Monospace

private val LabTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
)

@Composable
fun LedgerLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LabColors.Matrix,
            onPrimary = LabColors.Background,
            secondary = LabColors.Cyan,
            tertiary = LabColors.Gold,
            background = LabColors.Background,
            onBackground = LabColors.TextPrimary,
            surface = LabColors.Surface,
            onSurface = LabColors.TextPrimary,
            surfaceVariant = LabColors.SurfaceHigh,
            onSurfaceVariant = LabColors.TextSecondary,
            outline = LabColors.Outline,
            error = LabColors.Danger,
        ),
        typography = LabTypography,
        content = content,
    )
}
