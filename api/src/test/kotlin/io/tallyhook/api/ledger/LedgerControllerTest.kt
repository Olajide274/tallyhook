// This test covers ledger HTTP headers, malformed input, validation, and problem mapping.
package io.tallyhook.api.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(LedgerController::class)
class LedgerControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var ledger: LedgerApplicationService

    @Test
    fun `tenant header is required`() {
        val result =
            mockMvc
                .perform(get("/v1/trial-balance"))
                .andExpect(status().isBadRequest)
                .andReturn()

        assertTrue(
            result.response.contentType
                .orEmpty()
                .startsWith("application/problem+json"),
        )
        assertEquals("missing_header", objectMapper.readTree(result.response.contentAsString)["code"].asText())
    }

    @Test
    fun `malformed json returns bad request problem`() {
        val result =
            mockMvc
                .perform(
                    post("/v1/accounts")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"),
                ).andExpect(status().isBadRequest)
                .andReturn()

        assertEquals("malformed_request", objectMapper.readTree(result.response.contentAsString)["code"].asText())
    }

    @Test
    fun `bean validation returns unprocessable problem`() {
        val request = """{"name":"","type":"ASSET","currency":"USD"}"""
        val result =
            mockMvc
                .perform(
                    post("/v1/accounts")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request),
                ).andExpect(status().isUnprocessableEntity)
                .andReturn()

        assertEquals("validation_failed", objectMapper.readTree(result.response.contentAsString)["code"].asText())
    }

    @Test
    fun `missing idempotency header returns bad request problem`() {
        val body =
            """
            {
              "description":"posting",
              "effectiveAt":"2026-01-01T00:00:00Z",
              "entries":[
                {"accountId":"asset","direction":"DEBIT","amountMinor":1,"currency":"USD"},
                {"accountId":"revenue","direction":"CREDIT","amountMinor":1,"currency":"USD"}
              ]
            }
            """.trimIndent()
        val result =
            mockMvc
                .perform(
                    post("/v1/transactions")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andReturn()

        assertEquals("missing_header", objectMapper.readTree(result.response.contentAsString)["code"].asText())
    }

    @Test
    fun `fractional amount is rejected as malformed input`() {
        val body =
            """
            {
              "description":"posting",
              "effectiveAt":"2026-01-01T00:00:00Z",
              "entries":[
                {"accountId":"asset","direction":"DEBIT","amountMinor":1.5,"currency":"USD"},
                {"accountId":"revenue","direction":"CREDIT","amountMinor":1,"currency":"USD"}
              ]
            }
            """.trimIndent()
        val result =
            mockMvc
                .perform(
                    post("/v1/transactions")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("Idempotency-Key", "fractional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andReturn()

        assertEquals("malformed_request", objectMapper.readTree(result.response.contentAsString)["code"].asText())
    }

    @Test
    fun `domain errors are returned as problem details without internals`() {
        val request = CreateAccountRequest("Cash", "ASSET", "usd")
        val exception =
            LedgerApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_currency",
                "Invalid currency.",
            )
        Mockito
            .doThrow(exception)
            .`when`(ledger)
            .createAccount("tenant-a", request)

        val result =
            mockMvc
                .perform(
                    post("/v1/accounts")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)),
                ).andExpect(status().isUnprocessableEntity)
                .andReturn()

        assertTrue(
            result.response.contentType
                .orEmpty()
                .startsWith("application/problem+json"),
        )
        assertEquals("invalid_currency", objectMapper.readTree(result.response.contentAsString)["code"].asText())
        assertTrue(!result.response.contentAsString.contains("stackTrace"))
    }
}
