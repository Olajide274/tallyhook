// This test verifies the API's hello-world and connection health endpoints.
package io.tallyhook.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.tallyhook.outbox.OutboxMetrics
import io.tallyhook.outbox.OutboxRelay
import io.tallyhook.outbox.OutboxStore
import io.tallyhook.outbox.OutboxWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(HealthController::class)
class PackageTest {
    @MockBean
    lateinit var connectionHealthService: ConnectionHealthService

    @MockBean
    lateinit var outboxMetrics: OutboxMetrics

    @MockBean
    lateinit var outboxRelay: OutboxRelay

    @MockBean
    lateinit var outboxStore: OutboxStore

    @MockBean
    lateinit var outboxWriter: OutboxWriter

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `hello endpoint returns a greeting`() {
        val response =
            mockMvc
                .get("/")
                .andReturn()
                .response

        assertEquals(200, response.status)
        assertEquals("Hello from Tallyhook!", objectMapper.readTree(response.contentAsString)["message"].asText())
    }
}
