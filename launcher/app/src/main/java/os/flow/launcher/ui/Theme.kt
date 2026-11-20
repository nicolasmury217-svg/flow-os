package os.flow.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FlowBlue = Color(0xFF2F5BFF)
val FlowInk = Color(0xFF14161A)
val FlowMist = Color(0xFFF5F7F8)

private val scheme = lightColorScheme(
    primary = FlowBlue,
    onPrimary = Color.White,
    surface = Color.White,
    onSurface = FlowInk,
    background = FlowMist,
    onBackground = FlowInk,
    surfaceVariant = Color(0xFFE9EDF2),
    onSurfaceVariant = Color(0xFF55606E),
)

@Composable
fun FlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
