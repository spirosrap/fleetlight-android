package app.fleetlight.mobile.data

import java.time.Duration
import java.time.Instant

/** One observer's opinion of one machine, kept so the app can show when observers disagree. */
data class ObserverHostView(
    val state: HostState,
    val status: String,
    val detail: String?,
    val issueTypes: List<String>,
    val health: Int?,
)

/** A valid feed from one endpoint, reduced to what is needed to compare observers. */
data class ObserverView(
    val endpoint: String,
    val observerName: String,
    val generatedAt: Instant,
    val hosts: Map<String, ObserverHostView>,
) {
    companion object {
        fun from(endpoint: String, feed: MobileFeed): ObserverView = ObserverView(
            endpoint = endpoint,
            observerName = feed.observer.name,
            generatedAt = feed.generatedAt,
            hosts = feed.hosts.associate { host ->
                host.id to ObserverHostView(
                    state = host.state,
                    status = host.status,
                    detail = host.detail,
                    issueTypes = host.issueTypes,
                    health = host.health,
                )
            },
        )
    }
}

/** Another observer's differing opinion of a machine. */
data class ObserverDisagreement(
    val hostId: String,
    val observerName: String,
    val generatedAt: Instant,
    val view: ObserverHostView,
)

/**
 * Machines on which another observer, with a feed nearly as fresh as the chosen one, reports a
 * different state. Stale feeds are ignored: an observer that has not refreshed for a while is
 * expected to lag, not to disagree.
 */
fun observerDisagreements(
    chosen: MobileFeed,
    views: List<ObserverView>,
    maxAge: Duration = Duration.ofMinutes(10),
): Map<String, List<ObserverDisagreement>> {
    val comparable = views.filter { view ->
        view.observerName != chosen.observer.name &&
            Duration.between(view.generatedAt, chosen.generatedAt).abs() <= maxAge
    }
    if (comparable.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, List<ObserverDisagreement>>()
    chosen.hosts.forEach { host ->
        val differing = comparable.mapNotNull { view ->
            val other = view.hosts[host.id] ?: return@mapNotNull null
            if (other.state == host.state) return@mapNotNull null
            ObserverDisagreement(host.id, view.observerName, view.generatedAt, other)
        }
        if (differing.isNotEmpty()) result[host.id] = differing
    }
    return result
}
