package com.rdrgs.trens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColors(
    val background: Color = AppBackground,
    val cardBackground: Color = CardBackground,
    val cardBorder: Color = CardBorder,
    val inputBackground: Color = InputBackground,
    val inputBorder: Color = InputBorder,
    val buttonPrimaryBg: Color = ButtonPrimaryBg,
    val buttonPrimaryText: Color = ButtonPrimaryText,
    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary,
    val trainIncoming: Color = TrainIncoming,
    val statusUnavailable: Color = StatusUnavailable,
    val badgeWarningBg: Color = BadgeWarningBg,
    val badgeWarningText: Color = BadgeWarningText,
    val transferBg: Color = TransferBg,
    val transferBorder: Color = TransferBorder,
    val primary: Color = Purple40,
    val secondary: Color = PurpleGrey40,
    val tertiary: Color = Pink40
)

val LocalAppColors = staticCompositionLocalOf { AppColors() }

object TrensTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current

    val typography: AppTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTypography.current
}

@Composable
fun TrensTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAppColors provides AppColors(),
        LocalAppTypography provides AppTypography(),
        content = content
    )
}
