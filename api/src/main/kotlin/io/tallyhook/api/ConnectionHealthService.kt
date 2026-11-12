// This service runs each external connection check with a strict three-second deadline.
package io.tallyhook.api

import org.springframework.stereotype.Service
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service
class ConnectionHealthService(
    private val connectionChecker: ConnectionChecker,
) : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun checkConnections(): Map<String, String> {
        val checks =
            linkedMapOf(
                "postgres" to executor.submit(connectionChecker::checkPostgres),
                "kafka" to executor.submit(connectionChecker::checkKafka),
                "rabbitmq" to executor.submit(connectionChecker::checkRabbitmq),
            )
        val deadline = System.nanoTime() + CHECK_TIMEOUT_NANOSECONDS
        return checks.mapValues { (_, future) -> resultFor(future, deadline) }
    }

    private fun resultFor(
        future: Future<*>,
        deadline: Long,
    ): String {
        val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0)
        return try {
            future.get(remainingNanos, TimeUnit.NANOSECONDS)
            "UP"
        } catch (_: TimeoutException) {
            future.cancel(true)
            "DOWN: check timed out after 3 seconds"
        } catch (exception: ExecutionException) {
            "DOWN: ${exception.cause?.message?.take(MAX_REASON_LENGTH) ?: "connection check failed"}"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            "DOWN: check interrupted"
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val CHECK_TIMEOUT_NANOSECONDS = 3_000_000_000L
        const val MAX_REASON_LENGTH = 120
    }
}
