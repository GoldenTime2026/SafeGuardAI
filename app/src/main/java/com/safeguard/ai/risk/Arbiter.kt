package com.safeguard.ai.risk

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where an accepted event goes. Each channel must return promptly or suspend until it is done. */
interface RiskOutput {
    /** Show the event; suspends until it has been delivered (e.g. the sentence finished). */
    suspend fun present(event: RiskEvent)

    /** Cut the current delivery short because something more urgent arrived. */
    fun interrupt()
}

/**
 * Decides what the driver hears, one thing at a time (plan 5-3):
 * - only one warning is delivered at a time; a more urgent one interrupts a less urgent one
 * - the same situation ([RiskEvent.key]) is not repeated within its cooldown
 * - stale events ([RiskEvent.ttlMs]) are dropped instead of being read out late
 * - a short gap separates warnings, except for crash/critical ones
 */
class Arbiter(
    private val scope: CoroutineScope,
    private val output: RiskOutput,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableListOf<RiskEvent>()
    private val lastDelivered = mutableMapOf<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var current: RiskEvent? = null
    private var currentJob: Job? = null
    private var lastFinishedAt = 0L

    private val _active = MutableStateFlow<RiskEvent?>(null)
    /** The event being delivered right now, for the screen. */
    val active: StateFlow<RiskEvent?> = _active.asStateFlow()

    fun start() {
        scope.launch {
            while (true) {
                wake.receive()
                pump()
            }
        }
    }

    /** Forgets what was already said, so a rehearsed scenario plays the same way twice in a row. Scenarios only. */
    fun clearCooldowns() = synchronized(this) { lastDelivered.clear() }

    fun submit(event: RiskEvent) {
        synchronized(this) {
            val now = clock()
            val last = lastDelivered[event.key]
            if (last != null && now - last < event.cooldownMs) return
            if (current?.key == event.key || pending.any { it.key == event.key }) return
            pending += event
            Log.d(TAG, "queued ${event.level} ${event.key}")
        }
        wake.trySend(Unit)
    }

    private fun pump() {
        val next: RiskEvent
        synchronized(this) {
            val now = clock()
            pending.removeAll { it.isExpired(now) }
            val candidate = pending.minByOrNull { it.level.priority } ?: return
            val running = current
            if (running != null) {
                if (candidate.level.priority >= running.level.priority) return
                Log.i(TAG, "interrupt ${running.key} for ${candidate.key}")
                currentJob?.cancel()
                output.interrupt()
                // The interrupted warning is still true: say it after the urgent one if it hasn't gone stale.
                lastDelivered.remove(running.key)
                if (!running.isExpired(now)) pending += running
            } else if (!candidate.level.urgent && now - lastFinishedAt < GAP_MS) {
                scope.launch {
                    delay(GAP_MS - (now - lastFinishedAt))
                    wake.trySend(Unit)
                }
                return
            }
            pending.remove(candidate)
            current = candidate
            lastDelivered[candidate.key] = now
            next = candidate
        }
        _active.value = next
        Log.i(TAG, "deliver ${next.level} ${next.key}")
        currentJob = scope.launch {
            try {
                output.present(next)
                Log.i(TAG, "delivered ${next.key}")
            } finally {
                synchronized(this@Arbiter) {
                    if (current == next) {
                        current = null
                        lastFinishedAt = clock()
                    }
                }
                if (_active.value == next) _active.value = null
                wake.trySend(Unit)
            }
        }
    }

    companion object {
        private const val TAG = "SafeGuard/Arbiter"
        private const val GAP_MS = 3_000L
    }
}
