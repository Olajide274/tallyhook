// This file exposes the API and downstream connection health endpoints.
package io.tallyhook.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(
    private val connectionHealthService: ConnectionHealthService,
) {
    @GetMapping("/")
    fun hello(): Map<String, String> = mapOf("message" to "Hello from Tallyhook!")

    @GetMapping("/health/connections")
    fun connectionHealth(): ResponseEntity<Map<String, String>> {
        val checks = connectionHealthService.checkConnections()
        val status = if (checks.values.all { it == "UP" }) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(checks)
    }
}
