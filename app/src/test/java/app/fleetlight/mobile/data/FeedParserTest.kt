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
        assertEquals(24, feed.metricsWindowHours)
        assertEquals(1800, feed.metricsSampleIntervalSeconds)
        assertEquals("Primary Observer", feed.observer.name)
        assertEquals(3, feed.hosts.size)
        assertEquals(1, feed.summary.offline)
        assertEquals(1, feed.summary.slowConnections)
        assertEquals(1, feed.summary.accessIssues)
        assertEquals(1, feed.summary.alerts)
        assertEquals(1, feed.summary.restartRequired)
        val workstation = feed.hosts.first()
        assertTrue(workstation.isPinned)
        assertEquals(44.0, workstation.diskPercent ?: -1.0, 0.0)
        assertEquals("1.0", workstation.codexMacAppVersion)
        assertEquals("running", workstation.services.single().state)
        assertTrue(feed.linuxUpdates.single().restartRequired)
        assertEquals("Machine went offline", feed.incidents.single().title)
        val comparison = feed.timingComparisons.single()
        assertEquals("media-server", comparison.hostId)
        assertEquals("ping", comparison.metric)
        assertEquals(6, comparison.windowHours)
        assertEquals(74.0, comparison.currentAverageMs ?: -1.0, 0.0)
        assertEquals(12, comparison.currentSampleCount)
        assertEquals(16_200.0, comparison.currentCoverageSeconds ?: -1.0, 0.0)
        assertEquals(81.0, comparison.previousAverageMs ?: -1.0, 0.0)
        assertEquals(12, comparison.previousSampleCount)
        assertEquals(14_400.0, comparison.previousCoverageSeconds ?: -1.0, 0.0)
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
        assertFalse(feed.hosts.single().isPinned)
        assertEquals(null, feed.metricsWindowHours)
        assertEquals(null, feed.metricsSampleIntervalSeconds)
        assertTrue(feed.timingComparisons.isEmpty())
    }

    @Test
    fun ignoresMalformedTimingComparisonsIncludingNegativeCounts() {
        val feed = parser.parse(
            """{
              "schemaVersion": 1,
              "generatedAt": "2026-01-01T00:00:00Z",
              "timingComparisons": [
                {"hostId":"alpha","metric":"ping","windowHours":1,"currentAverageMs":12,"currentSampleCount":-2},
                {"hostId":"alpha","metric":"ping","windowHours":1,"currentAverageMs":12,"currentSampleCount":2,"currentCoverageSeconds":-1},
                {"hostId":"alpha","metric":"ping","windowHours":1,"currentAverageMs":12,"currentSampleCount":2,"previousCoverageSeconds":"wide"},
                {"hostId":"alpha","metric":"ping","windowHours":1,"currentAverageMs":12,"currentSampleCount":0},
                {"hostId":"alpha","metric":"futureMetric","windowHours":1},
                {"hostId":"alpha","metric":"checks","windowHours":0},
                {"metric":"ping","windowHours":1}
              ]
            }""",
        )

        assertTrue(feed.timingComparisons.isEmpty())
    }

    @Test
    fun keepsLegacyTimingComparisonsWithoutCoverageAsUnknown() {
        val feed = parser.parse(
            """{
              "schemaVersion": 1,
              "generatedAt": "2026-01-01T00:00:00Z",
              "timingComparisons": [{
                "hostId":"alpha",
                "metric":"ping",
                "windowHours":1,
                "currentAverageMs":12,
                "currentSampleCount":4,
                "previousAverageMs":14,
                "previousSampleCount":4
              }]
            }""",
        )

        val comparison = feed.timingComparisons.single()
        assertEquals(12.0, comparison.currentAverageMs ?: -1.0, 0.0)
        assertEquals(14.0, comparison.previousAverageMs ?: -1.0, 0.0)
        assertEquals(null, comparison.currentCoverageSeconds)
        assertEquals(null, comparison.previousCoverageSeconds)
    }

    @Test
    fun rejectsUnsupportedOrMalformedFeeds() {
        assertThrows(FeedParseException::class.java) {
            parser.parse("""{"schemaVersion":2,"generatedAt":"2026-01-01T00:00:00Z"}""")
        }
        assertThrows(FeedParseException::class.java) { parser.parse("not-json") }
    }
}
