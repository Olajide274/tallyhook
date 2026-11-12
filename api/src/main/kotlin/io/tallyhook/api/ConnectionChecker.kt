// This interface defines the external connection checks used by the health endpoint.
package io.tallyhook.api

interface ConnectionChecker {
    fun checkPostgres()

    fun checkKafka()

    fun checkRabbitmq()
}
