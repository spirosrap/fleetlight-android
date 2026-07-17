package app.fleetlight.mobile.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {
    private val parser = FeedParser()

    @Test
    fun parsesSchemaOneFixtureWithoutCollapsingSignals() {
        val raw = checkNotNull(javaClass.classLoader?.getResource("demo-feed.json")).readText()
        val feed = parser.parse(raw)

        assertEquals(1, feed.schemaVersion)
        assertEquals(Instant.parse("2026-01-15T12:00:00Z"), feed.generatedAt)
        assertEquals("Primary Observer", feed.observer.name)
        assertEquals(3, feed.hosts.size)
        assertEquals(1, feed.summary.offline)
        assertEquals(1, feed.summary.slowConnections)
        assertEquals(1, feed.summary.accessIssues)
        assertEquals(1, feed.summary.alerts)
        assertEquals(1, feed.summary.restartRequired)
        val workstation = feed.hosts.first()
        assertEquals(44.0, workstation.diskPercent ?: -1.0, 0.0)
        assertEquals("1.0", workstation.codexMacAppVersion)
        assertEquals("running", workstation.services.single().state)
        assertTrue(feed.linuxUpdates.single().restartRequired)
        assertEquals("Machine went offline", feed.incidents.single().title)
    }

    @Test
    fun toleratesMissingOptionalFieldsAndUnknownFields() {
        val feed = parser.parse(
            """{
              "schemaVersion": 1,
              "generatedAt": "2026-01-01T00:00:00Z",
              "futureField": {"anything": true},
              "hosts": [{"id": "generic", "name": "Generic Host", "future": 42}]
            }""",
        )

        assertEquals(1, feed.hosts.size)
        assertEquals(HostState.UNKNOWN, feed.hosts.single().state)
        assertFalse(feed.linuxUpdates.any())
    }

    @Test
    fun rejectsUnsupportedOrMalformedFeeds() {
        assertThrows(FeedParseException::class.java) {
            parser.parse("""{"schemaVersion":2,"generatedAt":"2026-01-01T00:00:00Z"}""")
        }
        assertThrows(FeedParseException::class.java) { parser.parse("not-json") }
    }
}
