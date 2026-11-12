// This file checks PostgreSQL, Kafka, and RabbitMQ using their real client APIs.
package io.tallyhook.api

import com.rabbitmq.client.ConnectionFactory
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Component
class DefaultConnectionChecker(
    private val dataSource: DataSource,
    @Value("\${spring.kafka.bootstrap-servers}") private val kafkaBootstrapServers: String,
    @Value("\${spring.rabbitmq.host}") private val rabbitmqHost: String,
    @Value("\${spring.rabbitmq.port}") private val rabbitmqPort: Int,
    @Value("\${spring.rabbitmq.username}") private val rabbitmqUsername: String,
    @Value("\${spring.rabbitmq.password}") private val rabbitmqPassword: String,
) : ConnectionChecker {
    override fun checkPostgres() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = CHECK_TIMEOUT_SECONDS
                statement.executeQuery("SELECT 1").use { result ->
                    check(result.next() && result.getInt(1) == 1) { "SELECT 1 returned no result" }
                }
            }
        }
    }

    override fun checkKafka() {
        val admin =
            AdminClient.create(
                mapOf(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaBootstrapServers,
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to CHECK_TIMEOUT_MILLISECONDS,
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to CHECK_TIMEOUT_MILLISECONDS,
                ),
            )
        try {
            val clusterId =
                admin
                    .describeCluster()
                    .clusterId()
                    .get(CHECK_TIMEOUT_MILLISECONDS.toLong(), TimeUnit.MILLISECONDS)
            check(clusterId != null) {
                "Kafka did not return a cluster id"
            }
        } finally {
            admin.close(Duration.ofSeconds(1))
        }
    }

    override fun checkRabbitmq() {
        val factory =
            ConnectionFactory().apply {
                host = rabbitmqHost
                port = rabbitmqPort
                username = rabbitmqUsername
                password = rabbitmqPassword
                connectionTimeout = CHECK_TIMEOUT_MILLISECONDS
                handshakeTimeout = CHECK_TIMEOUT_MILLISECONDS
            }
        factory.newConnection("tallyhook-health-check").close(CHECK_TIMEOUT_MILLISECONDS)
    }

    private companion object {
        const val CHECK_TIMEOUT_SECONDS = 3
        const val CHECK_TIMEOUT_MILLISECONDS = 3_000
    }
}
