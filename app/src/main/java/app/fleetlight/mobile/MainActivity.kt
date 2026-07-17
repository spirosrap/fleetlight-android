package app.fleetlight.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fleetlight.mobile.data.EndpointPolicy
import app.fleetlight.mobile.ui.FleetlightApp
import app.fleetlight.mobile.ui.FleetlightViewModel
import app.fleetlight.mobile.ui.theme.FleetlightTheme

class MainActivity : ComponentActivity() {
    private var viewModel: FleetlightViewModel? = null
    private var pendingDeepLinkEndpoints: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDeepLinkEndpoints = configuredEndpoints(intent.data)
        setContent {
            FleetlightTheme {
                val model: FleetlightViewModel = viewModel(factory = FleetlightViewModel.factory(application))
                viewModel = model
                LaunchedEffect(model) {
                    val endpoints = pendingDeepLinkEndpoints
                    pendingDeepLinkEndpoints = emptyList()
                    model.stageEndpoints(endpoints)
                }
                FleetlightApp(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configuredEndpoints(intent.data).takeIf { it.isNotEmpty() }?.let { endpoints ->
            viewModel?.stageEndpoints(endpoints)
                ?: run { pendingDeepLinkEndpoints = endpoints }
        }
    }

    private fun configuredEndpoints(uri: Uri?): List<String> {
        if (uri?.scheme != "fleetlight" || uri.host != "configure") return emptyList()
        return EndpointPolicy.normalizeAll(uri.getQueryParameters("endpoint"))
    }
}
