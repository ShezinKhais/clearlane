package ae.clearlane.app

import ae.clearlane.app.ui.Palette
import ae.clearlane.app.ui.RouteScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = scheme) {
                val model: RouteViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                RouteScreen(state = state, model = model)
            }
        }
    }
}

/**
 * Dark only. This is an app you look at in the car, and the palette in
 * [Palette] is built around dark so the congestion colours carry.
 */
private val scheme = darkColorScheme(
    primary = Palette.accent,
    background = Palette.background,
    surface = Palette.surface,
    onPrimary = Palette.background,
    onBackground = Palette.text,
    onSurface = Palette.text,
    error = Palette.severe,
)
