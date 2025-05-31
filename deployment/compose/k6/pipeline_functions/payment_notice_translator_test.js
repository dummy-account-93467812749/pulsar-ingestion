// payment_notice_translator_test.js
// Requires k6 built with an xk6-pulsar extension.
// Example: xk6 build --with github.com/automatingeverything/xk6-pulsar@latest

import { check, sleep, group } from 'k6';
import * as pulsar from 'k6/x/pulsar'; // Assumed import for xk6-pulsar. API methods below are based on common patterns and may need adjustment for your chosen extension.
import { generatePayload } from '../common.js';
import { uuidv4 } from 'k6/crypto';

// --- k6 Options ---
export const options = {
    vus: parseInt(__ENV.K6_VUS) || 1,
    duration: __ENV.K6_DURATION || '10s',
    thresholds: {
        'pulsar_producer_errors': ['count<10'],
        'pulsar_consumer_errors': ['count<10'],
        'pulsar_producer_sends': ['count>0'],
        'pulsar_consumer_receives': ['count>0'],
    },
};

// --- Pulsar Configuration (from Environment Variables) ---
const pulsarURL = __ENV.PULSAR_URL || 'pulsar://localhost:6650';
const inputTopic = __ENV.INPUT_TOPIC || 'persistent://acme/ingest/raw-kafka-events';
const outputTopic = __ENV.OUTPUT_TOPIC || 'persistent://acme/ingest/common-events';
const subscriptionName = __ENV.K6_EXECUTION_ID ? `k6-translator-sub-${__ENV.K6_EXECUTION_ID}` : `k6-translator-sub-${uuidv4().substring(0,8)}`;

// --- VU-Scoped Variables for Pulsar Client, Producer, Consumer ---
let vuClient;
let vuProducer;
let vuConsumer;

// Function to initialize Pulsar resources for a VU
function setupPulsarForVU() {
    if (!vuClient) {
        console.log(`VU ${__VU}: Initializing Pulsar resources. URL: ${pulsarURL}, Input: ${inputTopic}, Output: ${outputTopic}, Sub: ${subscriptionName}`);
        try {
            // Assumed API: Verify pulsar.connect() based on your chosen xk6-pulsar extension.
            vuClient = pulsar.connect(pulsarURL);

            // Assumed API: Verify pulsar.createProducer() based on your chosen xk6-pulsar extension.
            vuProducer = pulsar.createProducer(vuClient, {
                topic: inputTopic,
                name: `producer-paymentnotice-vu${__VU}`,
                sendTimeoutMillis: parseInt(__ENV.PULSAR_SEND_TIMEOUT_MS) || 3000,
            });

            // Assumed API: Verify pulsar.createConsumer() based on your chosen xk6-pulsar extension.
            vuConsumer = pulsar.createConsumer(vuClient, {
                topics: [outputTopic],
                subscriptionName: subscriptionName,
                subscriptionType: 'Shared',
                receiverQueueSize: parseInt(__ENV.PULSAR_RECEIVER_QUEUE_SIZE) || 100,
                ackTimeoutMillis: parseInt(__ENV.PULSAR_ACK_TIMEOUT_MS) || 30000,
            });
            console.log(`VU ${__VU}: Pulsar producer and consumer initialized for topics ${inputTopic} and ${outputTopic}.`);
        } catch (e) {
            console.error(`VU ${__VU}: Error setting up Pulsar client/producer/consumer: ${e}. Stack: ${e.stack || 'N/A'}`);
            throw new Error(`Pulsar setup failed for VU ${__VU}: ${e}`);
        }
    }
}

export default function () {
    try {
        setupPulsarForVU();
    } catch (e) {
        console.error(`VU ${__VU}, iter ${__ITER}: Skipping iteration due to Pulsar setup failure.`);
        return;
    }

    group('Pulsar Function Test: payment-notice-translator', function () {
        const correlationId = uuidv4();
        const producedAt = Date.now();
        let messageSuccessfullySent = false;

        group('Producer', function () {
            try {
                const kafkaStylePayload = generatePayload('kafka');
                const message = {
                    payload: JSON.stringify(kafkaStylePayload),
                    properties: {
                        'correlationId': correlationId,
                        'producedAt': String(producedAt)
                    }
                };

                // Assumed API: Verify producer.send() based on your chosen xk6-pulsar extension.
                vuProducer.send(message.payload, message.properties);
                messageSuccessfullySent = true;
                check(null, { 'pulsar_producer_sent_ok': () => true }, { test_function: "payment_notice_translator", topic: inputTopic });
            } catch (e) {
                console.error(`VU ${__VU}, iter ${__ITER}: Error producing message to ${inputTopic}: ${e}. Stack: ${e.stack || 'N/A'}`);
                check(null, { 'pulsar_producer_sent_ok': () => false }, { test_function: "payment_notice_translator", topic: inputTopic });
            }
        });

        if (!messageSuccessfullySent) {
            sleep(parseFloat(__ENV.K6_SLEEP) || 1);
            return;
        }

        group('Consumer', function () {
            try {
                // Assumed API: Verify consumer.receive() based on your chosen xk6-pulsar extension.
                const receivedMsg = vuConsumer.receive(parseInt(__ENV.PULSAR_RECEIVE_TIMEOUT_MS) || 1000);

                if (receivedMsg && receivedMsg.data) {
                    check(receivedMsg, { 'pulsar_consumer_received_message': (msg) => msg != null && msg.data != null }, { test_function: "payment_notice_translator", topic: outputTopic });

                    // Assumed API: Verify consumer.ack() based on your chosen xk6-pulsar extension.
                    vuConsumer.ack(receivedMsg);
                } else {
                    check(null, { 'pulsar_consumer_receive_timeout_or_empty': () => true }, { test_function: "payment_notice_translator", topic: outputTopic });
                }
            } catch (e) {
                console.error(`VU ${__VU}, iter ${__ITER}: Error consuming/acking message from ${outputTopic}: ${e}. Stack: ${e.stack || 'N/A'}`);
                check(null, { 'pulsar_consumer_action_error': () => true }, { test_function: "payment_notice_translator", topic: outputTopic });
            }
        });
    });

    sleep(parseFloat(__ENV.K6_SLEEP) || 1);
}

export function teardown() {
    console.log(`VU ${__VU}: Tearing down Pulsar resources.`);
    if (vuConsumer) {
        try {
            // Assumed API: Verify consumer.close() based on your chosen xk6-pulsar extension.
            vuConsumer.close();
            console.log(`VU ${__VU}: Pulsar consumer closed.`);
        } catch (e) {
            console.error(`VU ${__VU}: Error closing Pulsar consumer: ${e}. Stack: ${e.stack || 'N/A'}`);
        }
    }
    if (vuProducer) {
        try {
            // Assumed API: Verify producer.close() based on your chosen xk6-pulsar extension.
            vuProducer.close();
            console.log(`VU ${__VU}: Pulsar producer closed.`);
        } catch (e)
            console.error(`VU ${__VU}: Error closing Pulsar producer: ${e}. Stack: ${e.stack || 'N/A'}`);
        }
    }
    if (vuClient) {
        try {
            // Assumed API: Verify pulsar.close(client) or client.close() based on your chosen xk6-pulsar extension.
            pulsar.close(vuClient);
            console.log(`VU ${__VU}: Pulsar client connection closed.`);
        } catch (e) {
            console.error(`VU ${__VU}: Error closing Pulsar client: ${e}. Stack: ${e.stack || 'N/A'}`);
        }
    }
}
