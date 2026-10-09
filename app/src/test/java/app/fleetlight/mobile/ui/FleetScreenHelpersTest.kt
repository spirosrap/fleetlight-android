package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.FeedObserver
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.FleetIncident
import app.fleetlight.mobile.data.FleetSummary
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.MobileFeed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetScreenHelpersTest {
    private val hosts = listOf(
        FleetHost("a", "Alpha", "macOS", HostState.ONLINE, "online", pingMs = 30.0, health = 90),
        FleetHost("b", "Bravo", "Linux", HostState.OFFLINE, "offline", pingMs = null, health = null),
        FleetHost("c", "Charlie", "Linux", HostState.ONLINE, "online", pingMs = 5.0, health = 100, isPinned = true),
        FleetHost("d", "Delta", "macOS", HostState.SLOW, "slow", issueTypes = listOf("High latency"), pingMs = 120.0, health = 70),
    )

    @Test
    fun `priority is issues first then name, ignoring observer pins`() {
        assertEquals(listOf("b", "d", "a", "c"), filterFleetHosts(hosts).map(FleetHost::id))
    }

    @Test
    fun `custom order follows the saved list and appends unknown machines`() {
        val ordered = filterFleetHosts(hosts, sort = FleetSort.CUSTOM, customOrder = listOf("d", "a", "zzz"))
        assertEquals(listOf("d", "a", "b", "c"), ordered.map(FleetHost::id))
        assertEquals(listOf("b", "d", "a", "c"), filterFleetHosts(hosts, sort = FleetSort.CUSTOM).map(FleetHost::id))
    }

    @Test
    fun `moving a machine swaps it with its neighbour and clamps at the ends`() {
        val shown = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedOrder(shown, "b", -1))
        assertEquals(listOf("a", "c", "b"), movedOrder(shown, "b", +1))
        assertEquals(shown, movedOrder(shown, "a", -1))
        assertEquals(shown, movedOrder(shown, "c", +1))
        assertEquals(shown, movedOrder(shown, "missing", +1))
    }

    @Test
    fun `issues filter drops healthy machines`() {
        assertEquals(listOf("b", "d"), filterFleetHosts(hosts, filter = FleetFilter.ISSUES).map(FleetHost::id))
    }

    @Test
    fun `platform filters match case-insensitively`() {
        assertEquals(setOf("a", "d"), filterFleetHosts(hosts, filter = FleetFilter.MACOS).map(FleetHost::id).toSet())
        assertEquals(setOf("b", "c"), filterFleetHosts(hosts, filter = FleetFilter.LINUX).map(FleetHost::id).toSet())
    }

    @Test
    fun `query matches names platforms and issue types`() {
        assertEquals(listOf("d"), filterFleetHosts(hosts, query = "latency").map(FleetHost::id))
        assertEquals(listOf("b", "c"), filterFleetHosts(hosts, query = " LINUX ", sort = FleetSort.NAME).map(FleetHost::id))
        assertTrue(filterFleetHosts(hosts, query = "zzz").isEmpty())
    }

    @Test
    fun `latency and health sorts put unknown values last`() {
        assertEquals(listOf("c", "a", "d", "b"), filterFleetHosts(hosts, sort = FleetSort.LATENCY).map(FleetHost::id))
        assertEquals(listOf("d", "a", "c", "b"), filterFleetHosts(hosts, sort = FleetSort.HEALTH).map(FleetHost::id))
    }

    @Test
    fun `fleet summary text lists machines with markers`() {
        val feed = MobileFeed(
            schemaVersion = 1,
            generatedAt = Instant.parse("2026-01-15T12:00:00Z"),
            observer = FeedObserver(name = "Observer"),
            summary = FleetSummary(total = 4, online = 2, offline = 1, slowConnections = 1),
            hosts = hosts,
            linuxUpdates = emptyList(),
            incidents = emptyList(),
            metrics = emptyList(),
        )
        val text = fleetStatusSummary(FleetUiState(feed = feed), now = Instant.parse("2026-01-15T12:05:00Z"))
        assertNotNull(text)
        assertTrue(text!!.startsWith("Fleetlight · Observer"))
        assertTrue(text.contains("[OFFLINE] Bravo · Linux"))
        assertTrue(text.contains("[SLOW] Delta · macOS · 120 ms · health 70 · High latency"))
        assertTrue(text.contains("2 of 4 online · 2 signals need attention"))
        assertNull(fleetStatusSummary(FleetUiState()))
    }

    @Test
    fun `host summary includes key rows`() {
        val text = hostSummary(hosts[3])
        assertTrue(text.startsWith("Delta · macOS · Slow"))
        assertTrue(text.contains("Ping: 120 ms"))
        assertTrue(text.contains("Signals: High latency"))
    }

    @Test
    fun `events group newest first by local day`() {
        val zone = ZoneId.of("UTC")
        val incidents = listOf(
            FleetIncident("1", title = "Old", startedAt = Instant.parse("2026-01-14T23:30:00Z")),
            FleetIncident("2", title = "Newer", startedAt = Instant.parse("2026-01-15T08:00:00Z")),
            FleetIncident("3", title = "Newest", startedAt = Instant.parse("2026-01-15T09:00:00Z"), severity = "critical"),
        )
        val groups = groupEventsByDay(incidents, zone)
        assertEquals(listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 14)), groups.map { it.first })
        assertEquals(listOf("3", "2"), groups.first().second.map(FleetIncident::id))
        assertEquals(EventSeverity.CRITICAL, eventSeverity(incidents[2]))
        assertEquals(EventSeverity.INFO, eventSeverity(incidents[0]))
        assertTrue(EventFilter.ATTENTION.matches(incidents[2]))
        assertTrue(!EventFilter.ATTENTION.matches(incidents[0]))
    }

    @Test
    fun `platform labels and kinds read naturally`() {
        val mac = FleetHost("m", "Mini", "Darwin", HostState.ONLINE, "online", operatingSystem = "Darwin")
        assertEquals("macOS", mac.platformLabel)
        assertNull(mac.distinctOperatingSystem)
        val linux = FleetHost("l", "Box", "Linux", HostState.ONLINE, "online", operatingSystem = "Pop!_OS 22.04")
        assertEquals("Linux", linux.platformLabel)
        assertEquals("Pop!_OS 22.04", linux.distinctOperatingSystem)
        assertEquals("Route changed", humanizeKind("RouteChanged"))
        assertEquals("Service recovered", humanizeKind("service_recovered"))
        assertEquals("Status", humanizeKind("status"))
    }

    @Test
    fun `day labels use today and yesterday`() {
        val today = LocalDate.of(2026, 1, 15)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today))
        assertEquals("Monday, Jan 12", dayLabel(LocalDate.of(2026, 1, 12), today))
        assertEquals("Wed, Dec 31, 2025", dayLabel(LocalDate.of(2025, 12, 31), today))
        assertTrue(ZoneOffset.UTC.id.isNotEmpty())
    }
}
