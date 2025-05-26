rootProject.name = "pulsar-ingestion-mono"

include(
    ":common",
    ":test-kit",
    ":connectors:kinesis-source",
    ":connectors:rabbitmq-source",
    ":connectors:grpc-source",
    ":functions:splitter",
    ":functions:transforms:core",
    ":functions:transforms:translators"
)
