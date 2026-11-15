// This file enables scheduling and binds the outbox relay configuration.
package io.tallyhook.outbox

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@Import(OutboxMetrics::class, OutboxStore::class, OutboxWriter::class, OutboxRelay::class)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxConfiguration
