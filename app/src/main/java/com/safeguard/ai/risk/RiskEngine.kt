package com.safeguard.ai.risk

import com.safeguard.ai.core.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs every rule on each new snapshot and hands the results to the [Arbiter].
 * Adding a feature means adding a [RiskRule]; nothing else changes.
 *
 * Rules are also re-run every [TICK_MS] on the latest snapshot, because some decisions depend on
 * time passing rather than on a signal changing (holding over a speed, a wet road drying).
 */
class RiskEngine(
    private val scope: CoroutineScope,
    private val snapshots: StateFlow<VehicleSnapshot>,
    private val arbiter: Arbiter,
    private val rules: List<RiskRule>,
) {
    private val lock = Mutex()

    fun start() {
        scope.launch { snapshots.collect { evaluate(it) } }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                evaluate(snapshots.value)
            }
        }
    }

    /** Events that don't come from a rule (scenario injection, later the crowd server). */
    fun inject(event: RiskEvent) = arbiter.submit(event)

    /** Rules keep state, so evaluations never overlap. */
    private suspend fun evaluate(snapshot: VehicleSnapshot) = lock.withLock {
        val now = System.currentTimeMillis()
        rules.forEach { rule -> rule.evaluate(snapshot, now).forEach(arbiter::submit) }
    }

    companion object {
        private const val TICK_MS = 1_000L
    }
}
