package de.lanewise

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lanewise.settings.ThemeMode
import de.lanewise.ui.MainViewModel
import de.lanewise.ui.Mode
import de.lanewise.ui.screens.MainScreen
import de.lanewise.ui.screens.SettingsScreen
import de.lanewise.ui.theme.LaneWiseTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.onLocationPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            vm.onLocationPermissionGranted()
        } else {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
                // The ongoing "navigating" notification (guidance with the screen off); optional.
                if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
        }

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val ui by vm.ui.collectAsStateWithLifecycle()
            // Automatic theme: sun elevation at the car (or Hannover before the first fix), re-checked every minute.
            val sunDark by androidx.compose.runtime.produceState(
                de.lanewise.core.geo.Sun.isDark(sunPosition(), System.currentTimeMillis()), settings.theme,
            ) {
                while (settings.theme == ThemeMode.AUTO) {
                    value = de.lanewise.core.geo.Sun.isDark(sunPosition(), System.currentTimeMillis())
                    kotlinx.coroutines.delay(60_000)
                }
            }
            val dark = when (settings.theme) {
                ThemeMode.AUTO -> sunDark
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showSettings) { showSettings = false }

            LaunchedEffect(ui.mode) {
                if (ui.mode == Mode.NAVIGATING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            LaneWiseTheme(dark) {
                androidx.compose.runtime.CompositionLocalProvider(
                    de.lanewise.ui.i18n.LocalStrings provides de.lanewise.ui.i18n.Strings.of(settings.language),
                ) {
                    // The map stays composed underneath settings so it is not torn down and reloaded.
                    Box(Modifier.fillMaxSize()) {
                        MainScreen(vm, ui, settings, dark, onOpenSettings = { showSettings = true }, covered = showSettings)
                        if (showSettings) SettingsScreen(vm, settings, ui, onBack = { showSettings = false })
                    }
                }
            }
        }
    }

    private fun sunPosition() = vm.ui.value.fix?.position ?: de.lanewise.core.geo.LatLon(52.3759, 9.7320)
}
