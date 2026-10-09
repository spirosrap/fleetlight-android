package app.fleetlight.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fleetlight.mobile.data.EndpointPolicy
import app.fleetlight.mobile.data.ControlEndpointPolicy
import app.fleetlight.mobile.data.PendingPairing
import app.fleetlight.mobile.ui.FleetlightApp
import app.fleetlight.mobile.ui.FleetlightViewModel
import app.fleetlight.mobile.ui.theme.AppearancePreferences
import app.fleetlight.mobile.ui.theme.FleetlightTheme
import app.fleetlight.mobile.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private var viewModel: FleetlightViewModel? = null
    private var pendingDeepLinkEndpoints: List<String> = emptyList()
    private var pendingPairing: PendingPairing? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUri = intent.data
        pendingDeepLinkEndpoints = configuredEndpoints(initialUri)
        pendingPairing = configuredPairing(initialUri)
        intent.data = null
        setContent {
            val appearance = remember { AppearancePreferences.get(applicationContext) }
            val appearanceSettings by appearance.settings.collectAsStateWithLifecycle()
            val darkTheme = when (appearanceSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FleetlightTheme(darkTheme = darkTheme, dynamicColor = appearanceSettings.wallpaperColors) {
                val model: FleetlightViewModel = viewModel(factory = FleetlightViewModel.factory(application))
                viewModel = model
                LaunchedEffect(model) {
                    val endpoints = pendingDeepLinkEndpoints
                    pendingDeepLinkEndpoints = emptyList()
                    model.stageEndpoints(endpoints)
                    pendingPairing?.let { pairing ->
                        pendingPairing = null
                        model.stagePairing(pairing.endpoint, pairing.code)
                    }
                }
                FleetlightApp(
                    viewModel = model,
                    appearance = appearanceSettings,
                    onAppearanceChange = appearance::update,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data
        setIntent(Intent(intent).setData(null))
        configuredEndpoints(uri).takeIf { it.isNotEmpty() }?.let { endpoints ->
            viewModel?.stageEndpoints(endpoints)
                ?: run { pendingDeepLinkEndpoints = endpoints }
        }
        configuredPairing(uri)?.let { pairing ->
            viewModel?.stagePairing(pairing.endpoint, pairing.code)
                ?: run { pendingPairing = pairing }
        }
    }

    private fun configuredEndpoints(uri: Uri?): List<String> {
        if (uri?.scheme != "fleetlight" || uri.host != "configure") return emptyList()
        return EndpointPolicy.normalizeAll(uri.getQueryParameters("endpoint"))
    }

    private fun configuredPairing(uri: Uri?): PendingPairing? {
        if (uri?.scheme != "fleetlight" || uri.host != "pair") return null
        val endpoint = EndpointPolicy.normalize(uri.getQueryParameter("endpoint").orEmpty()) ?: return null
        val code = ControlEndpointPolicy.validPairingCode(uri.getQueryParameter("code").orEmpty()) ?: return null
        return PendingPairing(endpoint, code)
    }
}
