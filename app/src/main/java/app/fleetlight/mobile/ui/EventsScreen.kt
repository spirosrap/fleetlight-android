package app.fleetlight.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fleetlight.mobile.data.FleetIncident
import app.fleetlight.mobile.data.MobileFeed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class EventFilter(val label: String) {
    ALL("All"),
    ATTENTION("Needs attention"),
    INFO("Info"),
}

internal enum class EventSeverity { INFO, WARNING, CRITICAL }

internal fun eventSeverity(incident: FleetIncident): EventSeverity = when (incident.severity.trim().lowercase()) {
    "critical", "error", "fatal" -> EventSeverity.CRITICAL
    "warning", "warn" -> EventSeverity.WARNING
    else -> EventSeverity.INFO
}

internal fun EventFilter.matches(incident: FleetIncident): Boolean = when (this) {
    EventFilter.ALL -> true
    EventFilter.ATTENTION -> eventSeverity(incident) != EventSeverity.INFO
    EventFilter.INFO -> eventSeverity(incident) == EventSeverity.INFO
}

/** Newest first, grouped by calendar day in the device zone. */
internal fun groupEventsByDay(
    incidents: List<FleetIncident>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<LocalDate, List<FleetIncident>>> = incidents
    .sortedByDescending(FleetIncident::startedAt)
    .groupBy { it.startedAt.atZone(zone).toLocalDate() }
    .toList()

internal fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEEE, MMM d" else "EEE, MMM d, yyyy"))
}

private fun timeOfDay(instant: Instant): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(instant)

@Composable
internal fun EventsScreen(feed: MobileFeed?) {
    if (feed == null) {
        EmptyState(Icons.Outlined.Event, "No events yet", "Connect a feed to see confirmed incidents and recoveries.")
        return
    }
    var filter by rememberSaveable { mutableStateOf(EventFilter.ALL) }
    val attentionCount = remember(feed.incidents) { feed.incidents.count { eventSeverity(it) != EventSeverity.INFO } }
    val visible = remember(feed.incidents, filter) { feed.incidents.filter { filter.matches(it) } }
    val groups = remember(visible) { groupEventsByDay(visible) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionHeading(
                "Events",
                buildString {
                    append("${feed.incidents.size} recent event${if (feed.incidents.size == 1) "" else "s"}")
                    if (attentionCount > 0) append(" · $attentionCount need${if (attentionCount == 1) "s" else ""} attention")
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { filter = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item { InlineEmpty(if (feed.incidents.isEmpty()) "No confirmed incidents" else "No events match this filter") }
        }
        groups.forEach { (date, dayEvents) ->
            item(key = "day-$date") {
                Text(
                    dayLabel(date),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(dayEvents, key = FleetIncident::id) { EventCard(it) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun severityColor(severity: EventSeverity): Color = when (severity) {
    EventSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    EventSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    EventSeverity.INFO -> MaterialTheme.colorScheme.secondary
}

private fun severityIcon(severity: EventSeverity, kind: String): ImageVector = when {
    severity == EventSeverity.CRITICAL -> Icons.Outlined.ErrorOutline
    severity == EventSeverity.WARNING -> Icons.Outlined.WarningAmber
    kind.contains("recover", ignoreCase = true) || kind.contains("restor", ignoreCase = true) -> Icons.Outlined.CheckCircle
    else -> Icons.Outlined.Info
}

@Composable
private fun EventCard(event: FleetIncident) {
    val severity = eventSeverity(event)
    val color = severityColor(severity)
    FleetCard {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), modifier = Modifier.size(38.dp)) {
                Icon(
                    severityIcon(severity, event.kind),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        event.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timeOfDay(event.startedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${event.hostName} · ${humanizeKind(event.kind)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(event.severity.replaceFirstChar(Char::uppercase), color)
            }
        }
    }
}
