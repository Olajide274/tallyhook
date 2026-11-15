// This test verifies capped exponential backoff and jitter boundaries.
package io.tallyhook.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class RetryBackoffTest {
    @Test
    fun `delay grows exponentially with bounded jitter`() {
        val backoff = RetryBackoff(Duration.ofSeconds(10), Duration.ofMinutes(2), 0.2)

        assertEquals(Duration.ofSeconds(8), backoff.delayAfterFailure(1, random = 0.0))
        assertEquals(Duration.ofSeconds(12), backoff.delayAfterFailure(1, random = 1.0))
        assertEquals(Duration.ofSeconds(16), backoff.delayAfterFailure(2, random = 0.0))
        assertEquals(Duration.ofSeconds(24), backoff.delayAfterFailure(2, random = 1.0))
    }

    @Test
    fun `delay is capped after jitter`() {
        val backoff = RetryBackoff(Duration.ofSeconds(10), Duration.ofSeconds(25), 0.2)

        assertEquals(Duration.ofSeconds(25), backoff.delayAfterFailure(10, random = 1.0))
        assertTrue(backoff.delayAfterFailure(10, random = 0.0) <= Duration.ofSeconds(25))
    }
}
