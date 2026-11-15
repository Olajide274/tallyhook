// This file calculates capped exponential retry delays with bounded jitter.
package io.tallyhook.outbox

import java.time.Duration
import kotlin.math.min
import kotlin.random.Random

class RetryBackoff(
    private val baseDelay: Duration,
    private val maximumDelay: Duration,
    private val jitterFraction: Double,
    private val randomValue: () -> Double = { Random.nextDouble() },
) {
    init {
        require(!baseDelay.isNegative)
        require(!maximumDelay.isNegative)
        require(jitterFraction in 0.0..1.0)
    }

    fun delayAfterFailure(
        attempts: Int,
        random: Double = randomValue(),
    ): Duration {
        require(attempts > 0)
        require(random in 0.0..1.0)
        val exponent = (attempts - 1).coerceAtMost(MAX_EXPONENT)
        val factor = 1L shl exponent
        val baseNanos =
            try {
                Math.multiplyExact(baseDelay.toNanos(), factor)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
        val cappedNanos = min(baseNanos, maximumDelay.toNanos())
        val jitterMultiplier = 1.0 - jitterFraction + (2.0 * jitterFraction * random)
        val jitteredNanos = (cappedNanos.toDouble() * jitterMultiplier).toLong().coerceAtLeast(0)
        return Duration.ofNanos(min(jitteredNanos, maximumDelay.toNanos()))
    }

    private companion object {
        const val MAX_EXPONENT = 62
    }
}
