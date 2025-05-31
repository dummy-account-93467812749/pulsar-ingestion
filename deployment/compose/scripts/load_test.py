#!/usr/bin/env python3

import argparse
import json
import logging
import random
import time
import uuid
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from kafka.errors import KafkaError, TopicAlreadyExistsError, NoBrokersAvailable
from kafka import KafkaProducer
from kafka.admin import KafkaAdminClient, NewTopic
import pika
import pika.exceptions
import boto3
import botocore.exceptions

# --- Logging Setup ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# --- Payload Generation ---
def generate_payload(connector_type: str, base_template_str: str = None) -> dict:
    _uuid = str(uuid.uuid4())
    _iso_timestamp = datetime.now(timezone.utc).isoformat()
    _epoch_seconds = str(int(datetime.now(timezone.utc).timestamp()))

    # CMF Geotab specific payload for Kinesis
    if connector_type == 'kinesis': # Assuming 'kinesis' connector will be used for Geotab
        return {
            "Device_ID": _uuid,
            "Record_DateTime": _iso_timestamp,
            "Latitude": round(random.uniform(-90.0, 90.0), 6),
            "Longitude": round(random.uniform(-180.0, 180.0), 6),
            "Odometer_mi": round(random.uniform(0.0, 500000.0), 1),
            "Ignition": random.choice([True, False]),
            "FuelLevel_percent": round(random.uniform(0.0, 100.0), 2),
            "customData": f"geotab_load_test_data_{_uuid}"
        }
    elif connector_type == 'kafka':
        template = '{"txnId": "<uuid>", "amount": 99.95, "currency": "USD", "time": "<iso-timestamp>"}'
    elif connector_type == 'http':
        template = '{"sku": "SKU-<uuid>", "qty": 42, "updateTime": "<epoch_seconds>"}'
    elif connector_type == 'rabbitmq':
        template = '{"orderId": "ORD-<uuid>", "items": ["item1", "item2"], "placedAt": "<iso-timestamp>"}'
    elif base_template_str:
        template = base_template_str
    else:
        template = '{"id": "test-message-<uuid>", "timestamp": "<iso-timestamp>", "data": "Sample load test message"}'

    payload_str = (template
                   .replace('<uuid>', _uuid)
                   .replace('<iso-timestamp>', _iso_timestamp)
                   .replace('<epoch_seconds>', _epoch_seconds))
    try:
        return json.loads(payload_str)
    except json.JSONDecodeError as e:
        logger.error(f"Error decoding payload JSON for {connector_type}: {e}. Payload: {payload_str}")
        return {"error": "Invalid payload", "details": str(e)}

# --- HTTP Sender ---
def send_http_message(url: str, payload: dict, num: int, verbose: bool):
    try:
        resp = requests.post(url, json=payload)
        resp.raise_for_status()
        if verbose:
            logger.debug(f"HTTP {num} -> {url} [{resp.status_code}]")
        return True, f"HTTP message {num} sent"
    except requests.RequestException as e:
        logger.error(f"HTTP {num} failed: {e}")
        return False, f"HTTP message {num} failed: {e}"

# --- Kafka Sender ---
def send_kafka_message(brokers: str, topic: str, payload: dict, num: int, verbose: bool):
    producer = None
    try:
        producer = KafkaProducer(
            bootstrap_servers=brokers.split(','),
            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        )
        producer.send(topic, payload).get(timeout=10)
        if verbose:
            logger.debug(f"Kafka {num} -> {topic}")
        return True, f"Kafka message {num} sent"
    except Exception as e:
        logger.error(f"Kafka {num} failed: {e}")
        return False, f"Kafka message {num} failed: {e}"
    finally:
        if producer:
            producer.close()

# --- RabbitMQ Sender ---
def send_rabbitmq_message(host, port, user, pwd, queue, payload: dict, num: int, verbose: bool):
    creds = pika.PlainCredentials(user, pwd)
    params = pika.ConnectionParameters(host, port, '/', creds)
    conn = None
    try:
        conn = pika.BlockingConnection(params)
        ch = conn.channel()
        ch.queue_declare(queue=queue, durable=False)
        ch.basic_publish(exchange='', routing_key=queue,
                         body=json.dumps(payload).encode('utf-8'),
                         properties=pika.BasicProperties(delivery_mode=2))
        if verbose:
            logger.debug(f"RabbitMQ {num} -> {queue}@{host}:{port}")
        return True, f"RabbitMQ message {num} sent"
    except pika.exceptions.ProbableAuthenticationError as e:
        logger.error(f"RabbitMQ {num} authentication failed for user '{user}': {e}. "
                     "Please check your RabbitMQ username/password. "
                     f"Current settings from script: user='{user}', password='{'*' * len(pwd) if pwd else ''}'. "
                     "Ensure the user has permissions. "
                     "You can provide credentials using --rabbitmq-username and --rabbitmq-password. "
                     "Also, check RabbitMQ server logs for details.")
        return False, f"RabbitMQ message {num} authentication failed: {e}"
    except pika.exceptions.AMQPConnectionError as e:
        logger.error(f"RabbitMQ {num} connection failed: {e}. "
                     f"Please check RabbitMQ server address ({host}:{port}), port, and network accessibility.")
        return False, f"RabbitMQ message {num} connection failed: {e}"
    except Exception as e:
        logger.error(f"RabbitMQ {num} failed with an unexpected error: {e}")
        return False, f"RabbitMQ message {num} failed with an unexpected error: {e}"
    finally:
        if conn and conn.is_open:
            conn.close()

# --- Kinesis Sender ---
def send_kinesis_message(stream, payload: dict, num: int, verbose: bool,
                          endpoint, region, access_key, secret_key):
    try:
        client = boto3.client('kinesis', endpoint_url=endpoint,
                              region_name=region,
                              aws_access_key_id=access_key,
                              aws_secret_access_key=secret_key)
        client.put_record(StreamName=stream,
                          Data=json.dumps(payload).encode('utf-8'),
                          PartitionKey=str(uuid.uuid4()))
        if verbose:
            logger.debug(f"Kinesis {num} -> {stream}")
        return True, f"Kinesis message {num} sent"
    except Exception as e:
        logger.error(f"Kinesis {num} failed: {e}")
        return False, f"Kinesis message {num} failed: {e}"

# --- Main ---
def main():
    parser = argparse.ArgumentParser("Load test various connectors")
    parser.add_argument('-c', '--connector', choices=['http','kafka','rabbitmq','kinesis'], required=True)
    parser.add_argument('-n', '--num-messages', type=int, required=True)

    # Defaults updated to match the provided Docker Compose where applicable
    parser.add_argument('--http-url', default='http://127.0.0.1:10999/',
                        help="HTTP endpoint (Netty source). Not specified in provided docker-compose.")

    parser.add_argument('--kafka-brokers', default='localhost:19092', # MODIFIED to match Docker Compose exposed port
                        help="Kafka brokers (e.g., localhost:19092 for the provided docker-compose)")
    parser.add_argument('--kafka-topic', default='my-kafka-topic', help="Kafka topic")

    parser.add_argument('--rabbitmq-host', default='localhost',
                        help="RabbitMQ host (e.g., localhost for the provided docker-compose)")
    parser.add_argument('--rabbitmq-port', type=int, default=5672,
                        help="RabbitMQ port (e.g., 5672 for the provided docker-compose)")
    parser.add_argument('--rabbitmq-username', default='user', # MODIFIED to match Docker Compose
                        help="RabbitMQ username (e.g., 'user' for the provided docker-compose)")
    parser.add_argument('--rabbitmq-password', default='password', # MODIFIED to match Docker Compose
                        help="RabbitMQ password (e.g., 'password' for the provided docker-compose)")
    parser.add_argument('--rabbitmq-queue', default='my-queue', help="RabbitMQ queue")

    parser.add_argument('--kinesis-stream-name', default='raw-kinesis-events', # UPDATED default for CMF Geotab
                        help="Kinesis stream name (defaulted to 'raw-kinesis-events' for CMF Geotab)")
    parser.add_argument('--kinesis-aws-endpoint-url', default='http://localhost:4566', # Aligns with Docker Compose LocalStack
                        help="Kinesis AWS endpoint URL (e.g., http://localhost:4566 for LocalStack)")
    parser.add_argument('--kinesis-aws-region', default='us-east-1', # Aligns with Docker Compose LocalStack DEFAULT_REGION
                        help="Kinesis AWS region")
    parser.add_argument('--kinesis-aws-access-key-id', default='test', # Standard LocalStack default
                        help="Kinesis AWS access key ID")
    parser.add_argument('--kinesis-aws-secret-access-key', default='test', # Standard LocalStack default
                        help="Kinesis AWS secret access key")

    parser.add_argument('--threads', type=int, default=1)
    parser.add_argument('-v', '--verbose', action='store_true')
    args = parser.parse_args()

    if args.verbose:
        logger.setLevel(logging.DEBUG)
        logger.info("Verbose logging enabled")
    else:
        # Silence Pika's INFO logs unless our script is in verbose mode
        logging.getLogger("pika").setLevel(logging.WARNING)


    # --- Resource Setup Phase ---
    if args.connector == 'kinesis':
        logger.info(f"Ensuring Kinesis stream '{args.kinesis_stream_name}' exists at '{args.kinesis_aws_endpoint_url}'.")
        kinesis_client = None
        try:
            kinesis_client = boto3.client(
                'kinesis',
                endpoint_url=args.kinesis_aws_endpoint_url,
                region_name=args.kinesis_aws_region,
                aws_access_key_id=args.kinesis_aws_access_key_id,
                aws_secret_access_key=args.kinesis_aws_secret_access_key
            )
            try:
                kinesis_client.describe_stream(StreamName=args.kinesis_stream_name)
                logger.info(f"Kinesis stream '{args.kinesis_stream_name}' already exists.")
            except kinesis_client.exceptions.ResourceNotFoundException:
                logger.info(f"Kinesis stream '{args.kinesis_stream_name}' not found. Creating it with 1 shard...")
                kinesis_client.create_stream(StreamName=args.kinesis_stream_name, ShardCount=1)
                logger.info(f"Waiting for stream '{args.kinesis_stream_name}' to become active...")
                waiter = kinesis_client.get_waiter('stream_exists')
                waiter.wait(
                    StreamName=args.kinesis_stream_name,
                    WaiterConfig={'Delay': 5, 'MaxAttempts': 24}
                )
                logger.info(f"Kinesis stream '{args.kinesis_stream_name}' created and active.")
        except botocore.exceptions.EndpointConnectionError as e:
            logger.error(f"Could not connect to Kinesis endpoint '{args.kinesis_aws_endpoint_url}' during stream setup: {e}")
            logger.error("Please ensure Kinesis service (e.g., LocalStack) is running and accessible.")
            return
        except Exception as e:
            logger.error(f"Failed to set up Kinesis stream '{args.kinesis_stream_name}': {e}")
            return

    elif args.connector == 'kafka':
        logger.info(f"Ensuring Kafka topic '{args.kafka_topic}' exists on brokers '{args.kafka_brokers}'.")
        admin_client = None
        try:
            admin_client = KafkaAdminClient(
                bootstrap_servers=args.kafka_brokers.split(','),
                client_id='load_test_admin_client_setup'
            )
            existing_topics = admin_client.list_topics()
            if args.kafka_topic in existing_topics:
                logger.info(f"Kafka topic '{args.kafka_topic}' already exists.")
            else:
                logger.info(f"Kafka topic '{args.kafka_topic}' not found. Creating it (1 partition, replication factor 1)...")
                topic = NewTopic(name=args.kafka_topic, num_partitions=1, replication_factor=1)
                admin_client.create_topics(new_topics=[topic], validate_only=False)
                logger.info(f"Kafka topic '{args.kafka_topic}' creation request sent.")
                time.sleep(2) # Give Kafka a moment
                logger.info(f"Kafka topic '{args.kafka_topic}' should now be available.")
        except NoBrokersAvailable as e:
            logger.error(f"Could not connect to Kafka brokers at '{args.kafka_brokers}' for topic setup: {e}")
            logger.error(f"Please ensure Kafka brokers are running and accessible (e.g., on {args.kafka_brokers} based on your Docker Compose).")
            if admin_client: admin_client.close()
            return
        except TopicAlreadyExistsError:
            logger.info(f"Kafka topic '{args.kafka_topic}' already exists (caught TopicAlreadyExistsError).")
        except Exception as e:
            logger.error(f"Failed to set up Kafka topic '{args.kafka_topic}': {e}")
            if admin_client: admin_client.close()
            return
        finally:
            if admin_client:
                admin_client.close()

    logger.info(f"Starting {args.connector} load test: {args.num_messages} msgs on {args.threads} threads")
    start_time = time.time()
    success_count, failed_count = 0, 0

    with ThreadPoolExecutor(max_workers=args.threads) as exe:
        futures = []
        for i in range(args.num_messages):
            payload = generate_payload(args.connector)
            if 'error' in payload:
                failed_count += 1
                logger.warning(f"Payload generation failed: {payload.get('details', 'Unknown error')}")
                continue

            n = i + 1
            if args.connector == 'http':
                futures.append(exe.submit(send_http_message, args.http_url, payload, n, args.verbose))
            elif args.connector == 'kafka':
                futures.append(exe.submit(send_kafka_message,
                                          args.kafka_brokers, args.kafka_topic, payload, n, args.verbose))
            elif args.connector == 'rabbitmq':
                futures.append(exe.submit(send_rabbitmq_message,
                                          args.rabbitmq_host, args.rabbitmq_port,
                                          args.rabbitmq_username, args.rabbitmq_password,
                                          args.rabbitmq_queue, payload, n, args.verbose))
            else:  # kinesis
                futures.append(exe.submit(send_kinesis_message,
                                          args.kinesis_stream_name, payload, n, args.verbose,
                                          args.kinesis_aws_endpoint_url,
                                          args.kinesis_aws_region,
                                          args.kinesis_aws_access_key_id,
                                          args.kinesis_aws_secret_access_key))
            if args.num_messages > 1000 and i > 0 and i % 100 == 0 :
                time.sleep(0.01)

        for f in as_completed(futures):
            ok, msg = f.result()
            if ok:
                success_count += 1
                if args.verbose or args.num_messages <= 10:
                     logger.info(msg)
            else:
                failed_count += 1
                logger.warning(msg)

    total_time = time.time() - start_time
    logger.info(f"Finished in {total_time:.2f}s; Success: {success_count}, Failed: {failed_count}")

if __name__ == '__main__':
    main()