// This file starts the Tallyhook Spring Boot API application.
package io.tallyhook.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TallyhookApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator")
    runApplication<TallyhookApplication>(*args)
}
