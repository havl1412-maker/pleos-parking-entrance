package com.lastmeter.parking.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lastmeter.parking.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Pleos Connect UI Design Guide 의 색상 토큰을 그대로 옮긴 것.
 * 출처: https://document.pleos.ai/en/docs/connect/guide/docs-design/foundations/colors
 * 낮/밤 모드는 시스템 설정을 따라간다 (가이드의 Night/Day Mode Support).
 */
data class PleosColors(
    val background: Color,      // background_primary (basic_50)
    val surface: Color,         // 카드 바탕
    val surfaceSunken: Color,   // 카드 안의 구분 영역 (basic_100)
    val outline: Color,         // basic_200
    val textPrimary: Color,
    val textSecondary: Color,
    val buttonFilled: Color,    // button_filled_enabled (basic_600)
    val onButtonFilled: Color,
    val positive: Color,        // informative_positive
    val negative: Color,        // informative_negative
    val active: Color,          // informative_active
    val guide: Color,           // 방향 카드 바탕. 흰 글씨와 4.5:1 이상이 되도록 낮/밤 모두 같은 파랑을 쓴다
    val onGuide: Color,
)

val PleosLight = PleosColors(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEDEEF2),
    outline = Color(0xFFE0E1E6),
    textPrimary = Color(0xFF131417),
    textSecondary = Color(0xFF686A72),
    buttonFilled = Color(0xFF44464E),
    onButtonFilled = Color(0xFFFFFFFF),
    positive = Color(0xFF0064FF),
    negative = Color(0xFFFE3D16),
    active = Color(0xFF02C265),
    guide = Color(0xFF0064FF),
    onGuide = Color(0xFFFFFFFF),
)

val PleosDark = PleosColors(
    background = Color(0xFF1D1E21),
    surface = Color(0xFF313236),
    surfaceSunken = Color(0xFF131417),
    outline = Color(0xFF44464E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC7C9CE),
    buttonFilled = Color(0xFF44464E),
    onButtonFilled = Color(0xFFFFFFFF),
    positive = Color(0xFF0082FF),
    negative = Color(0xFFFF4C28),
    active = Color(0xFF00E074),
    guide = Color(0xFF0064FF),
    onGuide = Color(0xFFFFFFFF),
)

val LocalPleosColors = staticCompositionLocalOf { PleosLight }

/*
 * Pleos 시스템 UI 글꼴 Asta Sans (42dot, SIL Open Font License 1.1).
 * 출처: https://github.com/42dot/Asta-Sans , 라이선스 원문: assets/licenses/AstaSans-OFL.txt
 */
val AstaSans = FontFamily(
    Font(R.font.asta_sans_regular, FontWeight.Normal),
    Font(R.font.asta_sans_bold, FontWeight.Bold),
)

private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun PleosTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) PleosDark else PleosLight
    MaterialTheme(typography = Typography().withFamily(AstaSans)) {
        CompositionLocalProvider(LocalPleosColors provides colors) {
            ProvideTextStyle(TextStyle(fontFamily = AstaSans), content)
        }
    }
}
