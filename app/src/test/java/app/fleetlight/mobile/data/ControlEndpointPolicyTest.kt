package app.fleetlight.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlEndpointPolicyTest {
    @Test
    fun preservesFeedPathPrefix() {
        assertEquals(
            "https://observer.example/fleetlight/control/v1",
            ControlEndpointPolicy.baseForFeed("https://observer.example/fleetlight/mobile-feed.json"),
        )
        assertEquals(
            "https://observer.example/control/v1",
            ControlEndpointPolicy.baseForFeed("https://observer.example/mobile-feed.json"),
        )
    }

    @Test
    fun derivesOnlyCredentialFreeHttpsControllers() {
        assertNull(ControlEndpointPolicy.baseForFeed("http://observer.example/fleetlight/mobile-feed.json"))
        assertNull(ControlEndpointPolicy.baseForFeed("https://user:secret@observer.example/feed.json"))
        assertEquals("observer.example", ControlEndpointPolicy.authorityForFeed("https://observer.example/feed.json"))
    }

    @Test
    fun acceptsExactlyEightDigitPairingCodes() {
        assertEquals("12345678", ControlEndpointPolicy.validPairingCode(" 12345678 "))
        assertNull(ControlEndpointPolicy.validPairingCode("1234567"))
        assertNull(ControlEndpointPolicy.validPairingCode("1234567x"))
    }
}
