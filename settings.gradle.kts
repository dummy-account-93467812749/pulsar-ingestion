rootProject.name = "pulsar-ingestion-mono"

include(
    ":common",
    ":test-kit",
    ":connectors:kinesis-source",
    ":connectors:rabbitmq-source",
    ":connectors:grpc-source",
    ":functions:splitter",
    ":functions:transforms:stateless",
    ":functions:transforms:stateful",
    ":functions:transforms:translators"
)
