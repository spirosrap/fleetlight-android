package app.fleetlight.mobile.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the Fleet tab lists machines: the chosen sort and, for Custom, the hand-made order of machine IDs. */
data class FleetViewSettings(
    val sort: FleetSort = FleetSort.PRIORITY,
    val order: List<String> = emptyList(),
)

/** Phone-local, synchronous store for the Fleet tab's sort and custom order. */
class FleetViewPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("fleet-view", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())

    val settings: StateFlow<FleetViewSettings> = mutable.asStateFlow()

    fun update(transform: (FleetViewSettings) -> FleetViewSettings) {
        val next = transform(mutable.value)
        preferences.edit()
            .putString(KEY_SORT, next.sort.name)
            .putString(KEY_ORDER, next.order.joinToString("\n"))
            .apply()
        mutable.value = next
    }

    private fun load(): FleetViewSettings = FleetViewSettings(
        sort = preferences.getString(KEY_SORT, null)
            ?.let { raw -> FleetSort.entries.firstOrNull { it.name == raw } }
            ?: FleetSort.PRIORITY,
        order = preferences.getString(KEY_ORDER, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty(),
    )

    companion object {
        private const val KEY_SORT = "sort"
        private const val KEY_ORDER = "order"

        @Volatile
        private var shared: FleetViewPreferences? = null

        fun get(context: Context): FleetViewPreferences =
            shared ?: synchronized(this) { shared ?: FleetViewPreferences(context).also { shared = it } }
    }
}
