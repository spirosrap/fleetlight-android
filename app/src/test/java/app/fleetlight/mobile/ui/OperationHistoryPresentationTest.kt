package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.ControlJob
import app.fleetlight.mobile.data.ControlJobState
import app.fleetlight.mobile.data.ControlJobTarget
import app.fleetlight.mobile.data.ControlTargetState
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.receiptCounts
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationHistoryPresentationTest {
    @Test
    fun pinnedHostsSortBeforeIssuePriorityThenKeepIssueOrdering() {
        val hosts = listOf(
            FleetHost("offline", "Offline", state = HostState.OFFLINE),
            FleetHost("healthy-pinned", "Healthy pinned", state = HostState.ONLINE, isPinned = true),
            FleetHost("offline-pinned", "Offline pinned", state = HostState.OFFLINE, isPinned = true),
            FleetHost("slow", "Slow", state = HostState.SLOW),
        )

        assertEquals(
            listOf("offline-pinned", "healthy-pinned", "offline", "slow"),
            prioritizedFleetHosts(hosts).map(FleetHost::id),
        )
    }

    @Test
    fun receiptsAreTerminalNewestFirstDeduplicatedAndBounded() {
        val base = Instant.parse("2026-07-17T10:00:00Z")
        val completed = (0..7).map { index ->
            job(
                id = "job-$index",
                state = ControlJobState.SUCCEEDED,
                updatedAt = base.plusSeconds(index.toLong()),
            )
        }
        val duplicateOlder = completed[6].copy(updatedAt = base.minusSeconds(1))
        val running = job(
            id = "job-running",
            state = ControlJobState.RUNNING,
            updatedAt = base.plusSeconds(100),
        )

        val receipts = recentOperationReceipts(
            jobs = completed + duplicateOlder + running,
            activeJobId = "job-7",
        )

        assertEquals(listOf("job-6", "job-5", "job-4", "job-3", "job-2"), receipts.map(ControlJob::id))
    }

    @Test
    fun receiptSummaryKeepsEveryOutcomeDistinctAndCountsMissingProgress() {
        val targets = listOf(
            ControlJobTarget("success", state = ControlTargetState.SUCCEEDED),
            ControlJobTarget("failure", state = ControlTargetState.FAILED),
            ControlJobTarget("offline", state = ControlTargetState.OFFLINE),
            ControlJobTarget("skipped", state = ControlTargetState.SKIPPED),
            ControlJobTarget("cancelled", state = ControlTargetState.CANCELLED),
            ControlJobTarget("running", state = ControlTargetState.VERIFYING),
            ControlJobTarget("success", state = ControlTargetState.FAILED),
        )
        val receipt = ControlJob(
            id = "job-a",
            action = ControlAction.LINUX_OS,
            state = ControlJobState.PARTIAL,
            targetHostIds = listOf("success", "failure", "offline", "skipped", "cancelled", "running", "missing"),
            targets = targets,
            total = 7,
        )

        val counts = receipt.receiptCounts()
        assertEquals(1, counts.succeeded)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.offline)
        assertEquals(1, counts.skipped)
        assertEquals(1, counts.cancelled)
        assertEquals(2, counts.pending)
        assertEquals(
            "1 succeeded · 1 failed · 1 offline · 1 skipped · 1 cancelled · 2 pending",
            operationReceiptSummary(receipt),
        )
    }

    private fun job(id: String, state: ControlJobState, updatedAt: Instant): ControlJob = ControlJob(
        id = id,
        action = ControlAction.CODEX_CLI,
        state = state,
        updatedAt = updatedAt,
    )
}
