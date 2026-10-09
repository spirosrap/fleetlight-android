package app.fleetlight.mobile.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fleetlight.mobile.BuildConfig
import app.fleetlight.mobile.data.ControlEndpointPolicy
import app.fleetlight.mobile.data.EndpointPolicy
import app.fleetlight.mobile.ui.theme.AppearanceSettings
import app.fleetlight.mobile.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: FleetUiState,
    appearance: AppearanceSettings,
    onAppearanceChange: ((AppearanceSettings) -> AppearanceSettings) -> Unit,
    onSaveEndpoints: (List<String>) -> Unit,
    onStagePairing: (String, String) -> Unit,
    onForgetControl: () -> Unit,
) {
    var drafts by rememberSaveable(state.endpoints) { mutableStateOf(state.endpoints.ifEmpty { listOf("") }) }
    var validation by rememberSaveable { mutableStateOf<String?>(null) }
    var pairingEndpoint by rememberSaveable(state.controlEndpoint, state.activeEndpoint) {
        mutableStateOf(state.controlEndpoint ?: state.activeEndpoint ?: state.endpoints.firstOrNull().orEmpty())
    }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeading("Settings", "Sources, controller pairing and appearance")

        SettingsGroup(icon = Icons.Outlined.Link, title = "Feed endpoints", subtitle = "Every endpoint is tried; the freshest valid feed wins") {
            drafts.forEachIndexed { index, value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { updated -> drafts = drafts.toMutableList().also { it[index] = updated } },
                        modifier = Modifier.weight(1f),
                        label = { Text("HTTPS endpoint ${index + 1}") },
                        singleLine = true,
                        isError = value.isNotBlank() && EndpointPolicy.normalize(value) == null,
                    )
                    if (drafts.size > 1 || value.isNotEmpty()) {
                        IconButton(onClick = { drafts = drafts.toMutableList().also { it.removeAt(index) }.ifEmpty { listOf("") } }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove endpoint")
                        }
                    }
                }
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { drafts = drafts + "" },
                    enabled = drafts.size < EndpointPolicy.MAX_ENDPOINTS,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add endpoint")
                }
                FilledTonalButton(onClick = {
                    val nonBlank = drafts.filter(String::isNotBlank)
                    if (nonBlank.any { EndpointPolicy.normalize(it) == null }) {
                        validation = "Use complete HTTPS URLs without credentials or fragments."
                    } else {
                        validation = null
                        onSaveEndpoints(nonBlank)
                    }
                }) {
                    Text("Save & refresh")
                }
            }
            state.activeEndpoint?.let {
                Text("Active source: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingsGroup(icon = Icons.Outlined.Shield, title = "Update controller", subtitle = "The paired Mac runs allowlisted fleet jobs; credentials never leave it") {
            if (state.controlEndpoint != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.controlStatus?.controllerName ?: "Paired controller", fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            ControlEndpointPolicy.authorityForFeed(state.controlEndpoint),
                            if (state.controlStatus?.commandAuthorityEnabled == true && state.controlStatus.jobJournalAvailable) "Commands ready" else "Commands unavailable",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onForgetControl, enabled = state.activeJob?.state?.isTerminal != false) {
                    Text("Forget controller on this phone")
                }
                Text(
                    "To invalidate the controller token everywhere, revoke this Android device from Fleetlight on the Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = pairingEndpoint,
                    onValueChange = { pairingEndpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Observer feed endpoint") },
                    singleLine = true,
                    isError = pairingEndpoint.isNotBlank() && EndpointPolicy.normalize(pairingEndpoint) == null,
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("8-digit pairing code") },
                    singleLine = true,
                )
                FilledTonalButton(
                    onClick = { onStagePairing(pairingEndpoint, pairingCode) },
                    enabled = !state.pairing && EndpointPolicy.normalize(pairingEndpoint) != null && pairingCode.length == 8,
                ) {
                    if (state.pairing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.pairing) "Pairing…" else "Pair update controls")
                }
            }
            state.controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            controllerAvailabilityNotice(state, includeDetail = true)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        SettingsGroup(icon = Icons.Outlined.Palette, title = "Appearance", subtitle = "Theme and colour source") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = appearance.themeMode == mode,
                        onClick = { onAppearanceChange { it.copy(themeMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    ) {
                        Text(mode.label)
                    }
                }
            }
            val wallpaperSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wallpaper colours", fontWeight = FontWeight.Medium)
                    Text(
                        if (wallpaperSupported) {
                            "Use Material You colours from the wallpaper instead of the Fleetlight palette"
                        } else {
                            "Needs Android 12 or newer"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = appearance.wallpaperColors && wallpaperSupported,
                    onCheckedChange = { enabled -> onAppearanceChange { it.copy(wallpaperColors = enabled) } },
                    enabled = wallpaperSupported,
                )
            }
        }

        SettingsGroup(icon = Icons.Outlined.Info, title = "About", subtitle = "Fleetlight for Android ${BuildConfig.VERSION_NAME}") {
            KeyValueRow("Android app", BuildConfig.VERSION_NAME)
            KeyValueRow("Observer", state.feed?.observer?.appVersion ?: "not reported")
            state.feed?.observer?.name?.let { KeyValueRow("Observer name", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            InfoRow(
                icon = Icons.Outlined.Shield,
                title = "Scoped control by design",
                text = "Fleet status stays read-only until you explicitly pair. Update requests contain only an allowlisted action and machine IDs; SSH and sudo credentials remain on the controller Mac.",
            )
            InfoRow(
                icon = Icons.Outlined.Storage,
                title = "Resilient refresh",
                text = "All endpoints are checked every 60 seconds. The freshest schema 1 feed wins, and the last good response stays available offline.",
            )
            InfoRow(
                icon = Icons.Outlined.AccessTime,
                title = "Private configuration link",
                text = "Propose endpoints with fleetlight://configure?endpoint=https%3A%2F%2Fexample.invalid%2Ffeed.json. Fleetlight asks before saving or contacting them.",
            )
            InfoRow(
                icon = Icons.Outlined.Computer,
                title = "Companion to Fleetlight for macOS and Linux",
                text = "Status, insights and events come from an observer you run yourself. Nothing is sent to third parties.",
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    FleetCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
