# Vehicle Telemetry Translators

This module contains Pulsar Functions designed to translate vehicle telemetry data from various source-specific formats into a standardized **Common Message Format (CMF)**. The goal of this CMF is to provide a unified structure for telemetry data, simplifying downstream processing, analytics, and storage.

## Overview

Vehicle manufacturers and telematics providers often have their own proprietary data formats. The translators in this module consume these raw, source-specific messages from dedicated input topics and transform them into the `CommonMessageFormat`.

The definition of the `CommonMessageFormat` and its associated data structures (like `CommonTelemetry`, `CommonEvents`, etc.) can be found in `libs/src/main/kotlin/com/example/pulsar/common/CommonMessageFormat.kt`.

## Implemented Translators

The following translators are currently available:

1.  **`GeotabTranslator`**:
    *   **Input Source:** Geotab devices/platform.
    *   **Input Topic:** Consumes raw Geotab messages from `raw-kinesis-events`.
    *   **Output:** Produces `CommonMessageFormat<GeotabInputMessage.SourceSpecificData>`.

2.  **`CalAmpTranslator`**:
    *   **Input Source:** CalAmp devices/platform.
    *   **Input Topic:** Consumes raw CalAmp messages from `raw-kafka-events`.
    *   **Output:** Produces `CommonMessageFormat<CalAmpInputMessage.SourceSpecificData>`.

3.  **`FordTranslator`**:
    *   **Input Source:** Ford vehicles (e.g., via Ford Pro Telematics).
    *   **Input Topic:** Consumes raw Ford messages from `raw-http-events`.
    *   **Output:** Produces `CommonMessageFormat<FordInputMessage.SourceSpecificData>`.

## Common Output Topic

All translators in this module publish the standardized CMF messages to the following central Pulsar topic:

*   `persistent://acme/ingest/vehicle-telemetry-common-format`

Downstream consumers can subscribe to this topic to receive normalized telemetry data from all supported sources.

## Adding New Translators

To support a new telemetry data source:
1.  Define the input message structure (data class) for the new source.
2.  Create a new translator class implementing Pulsar's `Function` interface.
3.  Implement the logic to map the source-specific fields to the `CommonMessageFormat`, populating `CommonTelemetry` and `sourceSpecificData` as appropriate.
4.  Configure the new translator in `deployment/pipeline.yaml` to listen to the appropriate raw input topic.
5.  Add integration tests for the new translator in the `translators-integration` module.
6.  Update this README and any relevant KDoc comments.
