package app.fleetlight.mobile.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverViewTest {
    private val at = Instant.parse("2026-01-15T12:00:00Z")

    private fun feed(observer: String, generatedAt: Instant, nodeState: HostState, nodeStatus: String) = MobileFeed(
        schemaVersion = 1,
        generatedAt = generatedAt,
        observer = FeedObserver(name = observer),
        summary = FleetSummary(total = 2, online = 2),
        hosts = listOf(
            FleetHost("node-a", "Linux node", "Linux", nodeState, nodeStatus, issueTypes = if (nodeState == HostState.ONLINE) emptyList() else listOf("alerts")),
            FleetHost("node-b", "Mac node", "macOS", HostState.ONLINE, "online"),
        ),
        linuxUpdates = emptyList(),
        incidents = emptyList(),
        metrics = emptyList(),
    )

    @Test
    fun `reports machines another fresh observer sees differently`() {
        val chosen = feed("observer-a", at, HostState.ONLINE, "online")
        val other = feed("observer-b", at.minusSeconds(40), HostState.ATTENTION, "attention")
        val views = listOf(ObserverView.from("https://a/feed", chosen), ObserverView.from("https://b/feed", other))

        val result = observerDisagreements(chosen, views)

        assertEquals(setOf("node-a"), result.keys)
        val only = result.getValue("node-a").single()
        assertEquals("observer-b", only.observerName)
        assertEquals(HostState.ATTENTION, only.view.state)
        assertEquals(listOf("alerts"), only.view.issueTypes)
    }

    @Test
    fun `ignores the chosen observer itself and stale observers`() {
        val chosen = feed("observer-a", at, HostState.ONLINE, "online")
        val stale = feed("observer-b", at.minusSeconds(3_600), HostState.OFFLINE, "offline")
        val views = listOf(ObserverView.from("https://a/feed", chosen), ObserverView.from("https://b/feed", stale))

        assertTrue(observerDisagreements(chosen, views).isEmpty())
        assertTrue(observerDisagreements(chosen, listOf(ObserverView.from("https://a/feed", chosen))).isEmpty())
    }
}
