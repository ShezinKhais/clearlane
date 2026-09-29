package ae.clearlane.app

import ae.clearlane.app.ui.Palette
import ae.clearlane.app.ui.RouteScreen
import ae.clearlane.app.ui.drive.DriveScreen
import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
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

                val askForLocation = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted ->
                    model.onLocationPermissionResult(
                        granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
                    )
                }

                // Asked for at the point it is needed rather than on launch. A
                // permission dialog on first open, before the app has shown
                // what it does, is the dialog people decline.
                LaunchedEffect(state.screen, state.positionSource) {
                    if (state.screen == Screen.DRIVE &&
                        state.positionSource == PositionSource.SIMULATED &&
                        !model.locationGranted
                    ) {
                        askForLocation.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                }

                // A driving screen that goes dark halfway down Sheikh Zayed Road
                // is worse than no driving screen, so the display is held awake
                // while one is up and released as soon as it is not.
                LaunchedEffect(state.screen) {
                    if (state.screen == Screen.DRIVE) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Crossfade rather than a slide. The two screens share a map in
                // the same place, and sliding one over the other would throw
                // that continuity away.
                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = { fadeIn(tween) togetherWith fadeOut(tween) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.PLAN -> RouteScreen(state = state, model = model)
                        Screen.DRIVE -> DriveScreen(state = state, model = model)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

private val tween = androidx.compose.animation.core.tween<Float>(220)

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
