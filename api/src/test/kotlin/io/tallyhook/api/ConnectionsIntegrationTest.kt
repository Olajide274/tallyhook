// This test verifies real PostgreSQL, Kafka, and RabbitMQ connections using Testcontainers.
package io.tallyhook.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectionsIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `connection health checks are available`() {
        val healthResponse = restTemplate.getForEntity("/health/connections", Map::class.java)

        assertEquals(HttpStatus.OK, healthResponse.statusCode, "Connection health response: ${healthResponse.body}")
        assertEquals(
            mapOf("postgres" to "UP", "kafka" to "UP", "rabbitmq" to "UP"),
            healthResponse.body,
            "Connection health response: ${healthResponse.body}",
        )
    }

    companion object {
        private const val RABBITMQ_USER = "tallyhook"
        private const val RABBITMQ_PASSWORD = "tallyhook"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))

        @Container
        @JvmStatic
        val rabbitmq =
            object : RabbitMQContainer("rabbitmq:3-management-alpine") {
                override fun configure() {
                    super.configure()
                    withEnv("RABBITMQ_DEFAULT_USER", RABBITMQ_USER)
                    withEnv("RABBITMQ_DEFAULT_PASS", RABBITMQ_PASSWORD)
                }
            }

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("spring.rabbitmq.host", rabbitmq::getHost)
            registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort)
            registry.add("spring.rabbitmq.username") { RABBITMQ_USER }
            registry.add("spring.rabbitmq.password") { RABBITMQ_PASSWORD }
        }
    }
}
