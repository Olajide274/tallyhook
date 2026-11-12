// This test verifies the API's hello-world and connection health endpoints.
package io.tallyhook.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class PackageTest {
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

    @Test
    fun `connection health endpoint marks unconfigured services clearly`() {
        val response =
            mockMvc
                .get("/health/connections")
                .andReturn()
                .response

        assertEquals(200, response.status)
        val body = objectMapper.readTree(response.contentAsString)
        val connections = body["connections"]

        assertEquals("UP", body["status"].asText())
        assertEquals("NOT_CONFIGURED", connections["database"].asText())
        assertEquals("NOT_CONFIGURED", connections["kafka"].asText())
        assertEquals("NOT_CONFIGURED", connections["rabbitmq"].asText())
    }
}
