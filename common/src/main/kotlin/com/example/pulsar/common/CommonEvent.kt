package com.example.pulsar.common

import com.fasterxml.jackson.databind.JsonNode

/**
 * Represents a standardized event structure used across various Pulsar functions
 * after initial translation. This common schema facilitates consistent event processing
 * in downstream consumers and functions.
 *
 * @property eventId A unique identifier generated for this specific CommonEvent instance.
 *                   Typically a UUID.
 * @property source The originating system, service, or entity that produced the original event
 *                  (e.g., "user-service", "order-service").
 * @property eventType A string categorizing the type of event (e.g., "USER_PROFILE_EVENT",
 *                     "ORDER_EVENT"). This helps consumers route or filter events.
 * @property timestamp The ISO 8601 formatted UTC timestamp indicating when the original event
 *                     occurred or was captured by the source system. If the source provides
 *                     a timestamp, it's transformed to this format; otherwise, it might be
 *                     the time of translation.
 * @property data The original event payload from the source system, preserved as a JSON object
 *                ([JsonNode]). This allows downstream consumers to access the full context
 *                of the original event if needed.
 */
data class CommonEvent(
    val eventId: String,
    val source: String,
    val eventType: String,
    val timestamp: String, // ISO 8601 format, typically UTC
    val data: JsonNode,
)
