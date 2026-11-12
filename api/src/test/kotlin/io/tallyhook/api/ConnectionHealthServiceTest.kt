// This test verifies that a failed dependency check makes the health endpoint unavailable.
package io.tallyhook.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ConnectionHealthServiceTest {
    @Test
    fun `failed dependency check returns service unavailable`() {
        val checker =
            object : ConnectionChecker {
                override fun checkPostgres() = Unit

                override fun checkKafka() {
                    error("broker unavailable")
                }

                override fun checkRabbitmq() = Unit
            }
        ConnectionHealthService(checker).use { service ->
            val response = HealthController(service).connectionHealth()

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
            assertEquals("UP", response.body?.get("postgres"))
            assertEquals("DOWN: broker unavailable", response.body?.get("kafka"))
            assertEquals("UP", response.body?.get("rabbitmq"))
        }
    }
}
