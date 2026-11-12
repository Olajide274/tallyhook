// This file exposes the API and downstream connection health endpoints.
package io.tallyhook.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/")
    fun hello(): Map<String, String> = mapOf("message" to "Hello from Tallyhook!")

    @GetMapping("/health/connections")
    fun connectionHealth(): Map<String, Any> =
        mapOf(
            "status" to "UP",
            "connections" to
                mapOf(
                    "database" to "NOT_CONFIGURED",
                    "kafka" to "NOT_CONFIGURED",
                    "rabbitmq" to "NOT_CONFIGURED",
                ),
        )
}
