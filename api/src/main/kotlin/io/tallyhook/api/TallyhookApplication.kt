// This file starts the Tallyhook Spring Boot API application.
package io.tallyhook.api

import io.tallyhook.outbox.OutboxConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(OutboxConfiguration::class)
class TallyhookApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator")
    runApplication<TallyhookApplication>(*args)
}
