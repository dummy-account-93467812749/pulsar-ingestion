#!/usr/bin/env python3

import argparse
import json
import logging
import time
import uuid
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from kafka.errors import KafkaError
from kafka import KafkaProducer
import pika

# --- Logging Setup ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# --- Payload Generation ---
def generate_payload(template_str: str) -> dict:
    """
    Generates a payload by substituting placeholders in the template string.
    Replaces '<uuid>' with a new UUID and '<iso-timestamp>' with the current UTC ISO timestamp.
    """
    payload_str = template_str.replace('<uuid>', str(uuid.uuid4()))
    payload_str = payload_str.replace('<iso-timestamp>', datetime.now(timezone.utc).isoformat())
    try:
        return json.loads(payload_str)
    except json.JSONDecodeError as e:
        logger.error(f"Error decoding payload JSON: {e}. Payload string: {payload_str}")
        # Return a default error payload or raise an exception
        return {"error": "Invalid payload template", "details": str(e)}


# --- HTTP Sender ---
def send_http_message(url: str, payload_data: dict, message_num: int, verbose: bool):
    """Sends a single message via HTTP POST."""
    try:
        response = requests.post(url, json=payload_data)
        response.raise_for_status()  # Raise an exception for bad status codes (4xx or 5xx)
        if verbose:
            logger.debug(f"HTTP message {message_num} sent successfully to {url}. Response: {response.status_code}")
        return True, f"HTTP message {message_num} sent"
    except requests.exceptions.RequestException as e:
        logger.error(f"Error sending HTTP message {message_num} to {url}: {e}")
        return False, f"HTTP message {message_num} failed: {e}"

# --- Kafka Sender ---
def send_kafka_message(brokers: str, topic: str, payload_data: dict, message_num: int, verbose: bool):
    """Sends a single message to a Kafka topic."""
    try:
        producer = KafkaProducer(
            bootstrap_servers=brokers.split(','),
            value_serializer=lambda v: json.dumps(v).encode('utf-8')
        )
        future = producer.send(topic, payload_data)
        future.get(timeout=10) # Block until message is sent or timeout
        producer.close()
        if verbose:
            logger.debug(f"Kafka message {message_num} sent successfully to topic {topic} via brokers {brokers}")
        return True, f"Kafka message {message_num} sent"
    except KafkaError as e:
        logger.error(f"Error sending Kafka message {message_num} to topic {topic}: {e}")
        return False, f"Kafka message {message_num} failed: {e}"
    except Exception as e:
        logger.error(f"An unexpected error occurred with Kafka producer for message {message_num}: {e}")
        return False, f"Kafka message {message_num} failed unexpectedly: {e}"

# --- RabbitMQ Sender ---
def send_rabbitmq_message(host: str, port: int, username: str, password: str, queue_name: str, payload_data: dict, message_num: int, verbose: bool):
    """Sends a single message to a RabbitMQ queue."""
    credentials = pika.PlainCredentials(username, password)
    parameters = pika.ConnectionParameters(host, port, '/', credentials)
    connection = None
    try:
        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()
        channel.queue_declare(queue=queue_name, durable=True)  # Idempotent
        
        message_body = json.dumps(payload_data).encode('utf-8')
        
        channel.basic_publish(
            exchange='',
            routing_key=queue_name,
            body=message_body,
            properties=pika.BasicProperties(
                delivery_mode=pika.spec.PERSISTENT_DELIVERY_MODE
            ))
        if verbose:
            logger.debug(f"RabbitMQ message {message_num} sent successfully to queue {queue_name} on {host}:{port}")
        return True, f"RabbitMQ message {message_num} sent"
    except pika.exceptions.AMQPConnectionError as e:
        logger.error(f"Error connecting to RabbitMQ or sending message {message_num} to queue {queue_name}: {e}")
        return False, f"RabbitMQ message {message_num} failed: {e}"
    except Exception as e:
        logger.error(f"An unexpected error occurred with RabbitMQ for message {message_num}: {e}")
        return False, f"RabbitMQ message {message_num} failed unexpectedly: {e}"
    finally:
        if connection and connection.is_open:
            connection.close()

# --- Main ---
def main():
    parser = argparse.ArgumentParser(description="Load testing script for various message connectors.")
    
    # Required arguments
    parser.add_argument('--connector', '-c', choices=['http', 'kafka', 'rabbitmq'], required=True,
                        help="The connector type to use.")
    parser.add_argument('--num-messages', '-n', type=int, required=True,
                        help="The total number of messages to send.")

    # Optional arguments
    parser.add_argument('--payload', '-p', type=str,
                        default='{"id": "test-message-<uuid>", "timestamp": "<iso-timestamp>", "data": "Sample load test message"}',
                        help="JSON string payload template. <uuid> and <iso-timestamp> will be replaced.")
    
    # HTTP specific arguments
    parser.add_argument('--http-url', default='http://pulsar:10999/',
                        help="HTTP endpoint URL. Note: 'pulsar' hostname is for within-Docker network. "
                             "External access requires port mapping for the Pulsar service in docker-compose.yml.")
    
    # Kafka specific arguments
    parser.add_argument('--kafka-topic', default='kafka-topic-source',
                        help="Kafka topic name.")
    parser.add_argument('--kafka-brokers', default='localhost:19092',
                        help="Comma-separated list of Kafka broker addresses.")
                        
    # RabbitMQ specific arguments
    parser.add_argument('--rabbitmq-queue', default='my-rabbitmq-queue',
                        help="RabbitMQ queue name.")
    parser.add_argument('--rabbitmq-host', default='localhost',
                        help="RabbitMQ host.")
    parser.add_argument('--rabbitmq-port', type=int, default=5672,
                        help="RabbitMQ port.")
    parser.add_argument('--rabbitmq-username', default='user',
                        help="RabbitMQ username.")
    parser.add_argument('--rabbitmq-password', default='password',
                        help="RabbitMQ password.")

    # General arguments
    parser.add_argument('--threads', type=int, default=1,
                        help="Number of threads to use for sending messages.")
    parser.add_argument('--verbose', '-v', action='store_true',
                        help="Enable verbose logging (DEBUG level).")

    args = parser.parse_args()

    if args.verbose:
        logger.setLevel(logging.DEBUG)
        logger.info("Verbose logging enabled.")
    
    logger.info(f"Starting load test with connector: {args.connector}, number of messages: {args.num_messages}, threads: {args.threads}")

    start_time = time.time()
    messages_sent_successfully = 0
    messages_failed = 0

    with ThreadPoolExecutor(max_workers=args.threads) as executor:
        futures = []
        for i in range(args.num_messages):
            payload_to_send = generate_payload(args.payload)
            if payload_to_send.get("error"): # Check if payload generation failed
                logger.error(f"Skipping message {i+1} due to payload generation error: {payload_to_send['details']}")
                messages_failed += 1
                continue

            if args.connector == 'http':
                futures.append(executor.submit(send_http_message, args.http_url, payload_to_send, i + 1, args.verbose))
            elif args.connector == 'kafka':
                futures.append(executor.submit(send_kafka_message, args.kafka_brokers, args.kafka_topic, payload_to_send, i + 1, args.verbose))
            elif args.connector == 'rabbitmq':
                futures.append(executor.submit(send_rabbitmq_message, args.rabbitmq_host, args.rabbitmq_port, 
                                               args.rabbitmq_username, args.rabbitmq_password, args.rabbitmq_queue, 
                                               payload_to_send, i + 1, args.verbose))
            
            # Small delay to avoid overwhelming the executor submission queue, especially with high message counts
            if args.num_messages > 1000 and i % 100 == 0:
                 time.sleep(0.01)


        for future in as_completed(futures):
            try:
                success, message = future.result()
                if success:
                    messages_sent_successfully += 1
                    logger.info(message)
                else:
                    messages_failed += 1
                    logger.warning(message) 
            except Exception as e:
                messages_failed += 1
                logger.error(f"An error occurred processing a future: {e}")

    end_time = time.time()
    total_time = end_time - start_time
    logger.info(f"Load test finished in {total_time:.2f} seconds.")
    logger.info(f"Successfully sent messages: {messages_sent_successfully}")
    logger.info(f"Failed messages: {messages_failed}")
    if messages_failed > 0 :
        logger.warning("There were failures during the load test.")

if __name__ == "__main__":
    main()
