package app.fleetlight.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointPolicyTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpsUrls() {
        assertEquals("https://observer.example/feed.json", EndpointPolicy.normalize(" https://observer.example/feed.json "))
        assertNull(EndpointPolicy.normalize("http://observer.example/feed.json"))
        assertNull(EndpointPolicy.normalize("https://user:secret@observer.example/feed.json"))
        assertNull(EndpointPolicy.normalize("https://observer.example/feed.json#private"))
        assertNull(EndpointPolicy.normalize("not a url"))
    }

    @Test
    fun normalizesAndDeduplicatesEndpointLists() {
        assertEquals(
            listOf("https://one.example/feed", "https://two.example/feed"),
            EndpointPolicy.normalizeAll(
                listOf("https://one.example/feed", "https://one.example/feed", "http://ignored.example", "https://two.example/feed"),
            ),
        )
    }

    @Test
    fun capsEndpointListsAtFourTrustedSources() {
        val endpoints = (1..6).map { "https://observer-$it.example/feed" }

        assertEquals(endpoints.take(EndpointPolicy.MAX_ENDPOINTS), EndpointPolicy.normalizeAll(endpoints))
    }
}
