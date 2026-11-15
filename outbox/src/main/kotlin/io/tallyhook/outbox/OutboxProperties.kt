// This file binds configurable polling, retry, lease, and Kafka delivery settings.
package io.tallyhook.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("tallyhook.outbox")
@Suppress("MagicNumber")
data class OutboxProperties(
    val enabled: Boolean = true,
    val topic: String = "tallyhook.ledger.events",
    val pollInterval: Duration = Duration.ofSeconds(1),
    val initialDelay: Duration = Duration.ofSeconds(1),
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val sendTimeout: Duration = Duration.ofSeconds(10),
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseBackoff: Duration = Duration.ofSeconds(1),
    val maxBackoff: Duration = Duration.ofMinutes(5),
    val backoffJitter: Double = 0.2,
    val claimLease: Duration = Duration.ofMinutes(1),
) {
    private companion object {
        const val DEFAULT_BATCH_SIZE = 50
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}
