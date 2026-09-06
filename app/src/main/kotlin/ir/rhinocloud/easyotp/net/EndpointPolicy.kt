package ir.rhinocloud.easyotp.net

/** One front door. Both terminate at the same relay (DECISIONS D2). */
data class Endpoint(val id: String, val baseUrl: String)

/**
 * Chooses which front door to use, and whether to use both at once.
 *
 * Pure: no I/O, no clock of its own, no coroutines. Every decision is a function
 * of recorded outcomes and a caller-supplied timestamp, so the behaviour that
 * actually matters can be tested without a network. The HTTP client is a thin
 * shell over this.
 *
 * The strategy, from ARCHITECTURE section 2:
 *
 *  - **Urgent (an OTP): race both endpoints, first success wins.** A one-time
 *    code is worthless after about a minute, so spending twice the bandwidth on
 *    a sub-kilobyte request to halve tail latency is obviously correct. The relay
 *    dedupes, so a duplicate arrival costs nothing.
 *  - **Otherwise: sticky-best with immediate failover.** Send to the healthiest
 *    endpoint, fall over on failure, and re-probe the loser periodically so a
 *    recovered path gets picked up again.
 *
 * Never round-robin. Alternating halves the observations per endpoint, which is
 * exactly the signal needed to tell a blocked path from a slow one.
 */
class EndpointPolicy(
    endpoints: List<Endpoint>,
    private val probeIntervalMillis: Long = DEFAULT_PROBE_INTERVAL_MILLIS,
) {
    init {
        require(endpoints.isNotEmpty()) { "at least one endpoint is required" }
    }

    private val health = endpoints.associate { it.id to Health(it) }

    private class Health(val endpoint: Endpoint) {
        /** Starts optimistic so an untried endpoint is not written off before its first attempt. */
        var successRate: Double = 1.0
        var latencyMillis: Double = DEFAULT_LATENCY_MILLIS
        var lastAttemptAt: Long = 0
        var attempts: Long = 0
    }

    fun record(endpointId: String, success: Boolean, latencyMillis: Long, now: Long) {
        val h = health[endpointId] ?: return
        h.successRate = ewma(h.successRate, if (success) 1.0 else 0.0)
        // Only successes carry timing information. A failure's latency is a
        // timeout, and folding those in would make a dead endpoint look merely
        // slow rather than broken.
        if (success) h.latencyMillis = ewma(h.latencyMillis, latencyMillis.toDouble())
        h.lastAttemptAt = now
        h.attempts++
    }

    /**
     * Endpoints in the order they should be tried.
     *
     * An endpoint untried for longer than the probe interval is promoted to the
     * front regardless of its record. Without that, a path that fails during an
     * outage is never retried once the other one recovers, and the app quietly
     * degrades to a single front door -- which defeats the point of having two.
     */
    fun ranked(now: Long): List<Endpoint> {
        val (stale, fresh) = health.values.partition { due(it, now) }
        return (stale.sortedBy { it.lastAttemptAt } + fresh.sortedByDescending { score(it) })
            .map { it.endpoint }
    }

    /**
     * Whether to fire at every endpoint simultaneously.
     *
     * True for urgent traffic, and also when nothing is known to be healthy --
     * during an outage, spraying is strictly better than picking wrong and
     * waiting out a timeout.
     */
    fun shouldRace(urgent: Boolean): Boolean {
        if (health.size < 2) return false
        if (urgent) return true
        return health.values.none { it.successRate >= HEALTHY_THRESHOLD }
    }

    fun all(): List<Endpoint> = health.values.map { it.endpoint }

    /** Diagnostics for the status screen. Never includes message content. */
    fun snapshot(): List<EndpointStatus> =
        health.values
            .sortedByDescending { score(it) }
            .map {
                EndpointStatus(
                    id = it.endpoint.id,
                    successRate = it.successRate,
                    latencyMillis = it.latencyMillis.toLong(),
                    attempts = it.attempts,
                )
            }

    private fun due(h: Health, now: Long): Boolean =
        h.lastAttemptAt != 0L && now - h.lastAttemptAt >= probeIntervalMillis

    /**
     * Success rate dominates; latency only separates endpoints of equal
     * reliability. A fast path that drops one request in five is worse than a
     * slow one that always works, because a dropped OTP is a failed login and a
     * slow OTP is merely a slow login.
     */
    private fun score(h: Health): Double =
        h.successRate * 1000 - (h.latencyMillis / 1000.0).coerceAtMost(MAX_LATENCY_PENALTY)

    private fun ewma(current: Double, sample: Double): Double =
        current * (1 - ALPHA) + sample * ALPHA

    companion object {
        /**
         * Weight of the newest observation. High enough that a blocked endpoint
         * is demoted within a handful of attempts, since Iranian filtering
         * changes on the scale of minutes rather than days.
         */
        const val ALPHA = 0.3
        const val HEALTHY_THRESHOLD = 0.5
        const val DEFAULT_PROBE_INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val DEFAULT_LATENCY_MILLIS = 1000.0
        private const val MAX_LATENCY_PENALTY = 30.0
    }
}

data class EndpointStatus(
    val id: String,
    val successRate: Double,
    val latencyMillis: Long,
    val attempts: Long,
)
