package app.fleetlight.mobile.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverViewTest {
    private val at = Instant.parse("2026-01-15T12:00:00Z")

    private fun feed(observer: String, generatedAt: Instant, dellState: HostState, dellStatus: String) = MobileFeed(
        schemaVersion = 1,
        generatedAt = generatedAt,
        observer = FeedObserver(name = observer),
        summary = FleetSummary(total = 2, online = 2),
        hosts = listOf(
            FleetHost("dell", "Dell", "Linux", dellState, dellStatus, issueTypes = if (dellState == HostState.ONLINE) emptyList() else listOf("alerts")),
            FleetHost("mini", "Mini", "macOS", HostState.ONLINE, "online"),
        ),
        linuxUpdates = emptyList(),
        incidents = emptyList(),
        metrics = emptyList(),
    )

    @Test
    fun `reports machines another fresh observer sees differently`() {
        val chosen = feed("macmini", at, HostState.ONLINE, "online")
        val other = feed("airspiros", at.minusSeconds(40), HostState.ATTENTION, "attention")
        val views = listOf(ObserverView.from("https://a/feed", chosen), ObserverView.from("https://b/feed", other))

        val result = observerDisagreements(chosen, views)

        assertEquals(setOf("dell"), result.keys)
        val only = result.getValue("dell").single()
        assertEquals("airspiros", only.observerName)
        assertEquals(HostState.ATTENTION, only.view.state)
        assertEquals(listOf("alerts"), only.view.issueTypes)
    }

    @Test
    fun `ignores the chosen observer itself and stale observers`() {
        val chosen = feed("macmini", at, HostState.ONLINE, "online")
        val stale = feed("airspiros", at.minusSeconds(3_600), HostState.OFFLINE, "offline")
        val views = listOf(ObserverView.from("https://a/feed", chosen), ObserverView.from("https://b/feed", stale))

        assertTrue(observerDisagreements(chosen, views).isEmpty())
        assertTrue(observerDisagreements(chosen, listOf(ObserverView.from("https://a/feed", chosen))).isEmpty())
    }
}
