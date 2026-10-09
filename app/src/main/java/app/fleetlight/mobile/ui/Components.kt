package app.fleetlight.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopMac
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostState

/** The app's standard card: soft surface, hairline border, large radius. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FleetCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}

/** Compact rounded label, optionally filled, used for states and signals. */
@Composable
internal fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
) {
    val container = if (filled) color else color.copy(alpha = 0.13f)
    val content = if (filled) contentColorFor(color) else color
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(999.dp), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF111111) else Color.White

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Value tile with a small label, used in detail grids. */
@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    hint: String? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Inline "Label value" chip for the dense metrics line on machine cards. */
@Composable
internal fun MiniMetric(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}

@Composable
internal fun usageColor(percent: Double): Color = when {
    percent >= 90.0 -> MaterialTheme.colorScheme.error
    percent >= 70.0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/** Labelled percentage bar for disk and memory. */
@Composable
internal fun UsageBar(label: String, percent: Double, modifier: Modifier = Modifier) {
    val color = usageColor(percent)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatDecimal(percent)}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
        LinearProgressIndicator(
            progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** Circular progress ring with centred content. */
@Composable
internal fun HealthRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 7.dp,
    content: @Composable () -> Unit,
) {
    val track = color.copy(alpha = 0.16f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

internal fun platformIcon(platform: String): ImageVector = when {
    platform.contains("mac", ignoreCase = true) || platform.contains("darwin", ignoreCase = true) -> Icons.Outlined.DesktopMac
    platform.contains("linux", ignoreCase = true) -> Icons.Outlined.Terminal
    else -> Icons.Outlined.Computer
}

/** Platform badge tinted by machine state, with a small state dot. */
@Composable
internal fun HostAvatar(host: FleetHost, size: Dp, modifier: Modifier = Modifier) {
    val color = stateColor(host.state)
    Box(modifier = modifier.size(size)) {
        Surface(modifier = Modifier.matchParentSize(), shape = CircleShape, color = color.copy(alpha = 0.14f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    platformIcon(host.platform),
                    contentDescription = host.platform,
                    tint = color,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.3f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(2.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** Section title with optional subtitle and trailing action, used by every screen. */
@Composable
internal fun SectionHeading(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

/** Small upper-case caption used above groups inside a card. */
@Composable
internal fun Overline(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

@Composable
internal fun KeyValueRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
internal fun stateColor(state: HostState): Color = when (state) {
    HostState.ONLINE -> MaterialTheme.colorScheme.secondary
    HostState.SLOW -> MaterialTheme.colorScheme.tertiary
    HostState.OFFLINE, HostState.ACCESS, HostState.ATTENTION -> MaterialTheme.colorScheme.error
    HostState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun stateLabel(host: FleetHost): String = host.status.trim().ifEmpty { host.state.name.lowercase() }
    .replace('_', ' ')
    .replace('-', ' ')
    .replaceFirstChar(Char::uppercase)

/** "Darwin" and friends read as macOS; other platform strings pass through. */
internal val FleetHost.platformLabel: String
    get() {
        val raw = platform.trim()
        return when {
            raw.isEmpty() -> "Unknown"
            raw.equals("darwin", ignoreCase = true) || raw.contains("macos", ignoreCase = true) || raw.contains("mac os", ignoreCase = true) -> "macOS"
            else -> raw
        }
    }

/** Operating system text only when it adds something beyond the platform label. */
internal val FleetHost.distinctOperatingSystem: String?
    get() = operatingSystem?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals(platform.trim(), ignoreCase = true) && !it.equals(platformLabel, ignoreCase = true)
    }

/** "RouteChanged" / "service_recovered" -> "Route changed" / "Service recovered". */
internal fun humanizeKind(kind: String): String = kind.trim()
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .replace('-', ' ')
    .lowercase()
    .replaceFirstChar(Char::uppercase)
