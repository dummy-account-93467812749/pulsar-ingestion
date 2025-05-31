#!/usr/bin/env python3

import argparse
import json
import logging
import os
import subprocess
import time
from pathlib import Path

# Attempt to import optional dependencies for resource setup, fail gracefully if not found
try:
    import boto3
    import botocore.exceptions
    BOTO3_AVAILABLE = True
except ImportError:
    BOTO3_AVAILABLE = False

try:
    from kafka.admin import KafkaAdminClient, NewTopic
    from kafka.errors import KafkaError, TopicAlreadyExistsError, NoBrokersAvailable
    KAFKA_PYTHON_AVAILABLE = True
except ImportError:
    KAFKA_PYTHON_AVAILABLE = False

# --- Logging Setup ---
logger = logging.getLogger(__name__)

# --- Resource Setup Functions ---
def setup_kinesis_stream(stream_name: str, endpoint_url: str, region: str, access_key: str, secret_key: str, verbose: bool):
    if not BOTO3_AVAILABLE:
        logger.warning("boto3 library is not installed. Skipping Kinesis stream setup.")
        logger.warning("Please ensure the stream exists or install boto3 to enable setup.")
        return

    logger.info(f"Ensuring Kinesis stream '{stream_name}' exists at '{endpoint_url}'.")
    kinesis_client = None
    try:
        kinesis_client = boto3.client(
            'kinesis',
            endpoint_url=endpoint_url if endpoint_url else None,
            region_name=region,
            aws_access_key_id=access_key,
            aws_secret_access_key=secret_key
        )
        try:
            kinesis_client.describe_stream(StreamName=stream_name)
            logger.info(f"Kinesis stream '{stream_name}' already exists.")
        except kinesis_client.exceptions.ResourceNotFoundException:
            logger.info(f"Kinesis stream '{stream_name}' not found. Creating it with 1 shard...")
            kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)
            logger.info(f"Waiting for stream '{stream_name}' to become active...")
            waiter = kinesis_client.get_waiter('stream_exists')
            waiter.wait(
                StreamName=stream_name,
                WaiterConfig={'Delay': 5, 'MaxAttempts': 24}
            )
            logger.info(f"Kinesis stream '{stream_name}' created and active.")
        except botocore.exceptions.ClientError as e:
            if "ResourceInUseException" in str(e) and "another operation" in str(e).lower():
                 logger.warning(f"Kinesis stream '{stream_name}' is currently being modified. Assuming it will be ready.")
            else:
                raise
    except botocore.exceptions.EndpointConnectionError as e:
        logger.error(f"Could not connect to Kinesis endpoint '{endpoint_url}': {e}")
        raise
    except Exception as e:
        logger.error(f"Failed to set up Kinesis stream '{stream_name}': {e}")
        raise

def setup_kafka_topic(brokers_str: str, topic_name: str, verbose: bool):
    if not KAFKA_PYTHON_AVAILABLE:
        logger.warning("kafka-python library is not installed. Skipping Kafka topic setup.")
        logger.warning("Please ensure the topic exists or install kafka-python to enable setup.")
        return

    logger.info(f"Ensuring Kafka topic '{topic_name}' exists on brokers '{brokers_str}'.")
    admin_client = None
    try:
        brokers_list = brokers_str.split(',')
        admin_client = KafkaAdminClient(
            bootstrap_servers=brokers_list,
            client_id='run_k6_load_test_admin_setup'
        )
        existing_topics = admin_client.list_topics()
        if topic_name in existing_topics:
            logger.info(f"Kafka topic '{topic_name}' already exists.")
        else:
            logger.info(f"Kafka topic '{topic_name}' not found. Creating it (1 partition, replication factor 1)...")
            topic = NewTopic(name=topic_name, num_partitions=1, replication_factor=1)
            try:
                admin_client.create_topics(new_topics=[topic], validate_only=False)
                logger.info(f"Kafka topic '{topic_name}' creation request sent. Waiting briefly...")
                time.sleep(3)
                logger.info(f"Kafka topic '{topic_name}' should now be available.")
            except TopicAlreadyExistsError:
                 logger.info(f"Kafka topic '{topic_name}' created by another process.")
            except KafkaError as ke:
                logger.error(f"Kafka error during topic creation: {ke}")
                raise
    except NoBrokersAvailable as e:
        logger.error(f"Could not connect to Kafka brokers at '{brokers_str}': {e}")
        raise
    except Exception as e:
        logger.error(f"Failed to set up Kafka topic '{topic_name}': {e}")
        raise
    finally:
        if admin_client:
            admin_client.close()

# --- Main ---
def main():
    parser = argparse.ArgumentParser(description="Run k6 load tests for various connectors or Pulsar functions.")

    k6_group = parser.add_argument_group('k6 Execution Arguments')
    k6_group.add_argument('--k6-executable', default='k6', help="Path to the k6 executable (default: 'k6').")
    k6_group.add_argument('--k6-vus', type=int, default=1, help="Number of virtual users for k6 (default: 1).")
    k6_group.add_argument('--k6-duration', default='10s', help="Test duration for k6 (e.g., '30s', '1m') (default: '10s').")
    k6_group.add_argument('--k6-sleep', type=float, default=1.0, help="Sleep duration in seconds between iterations for k6 VUs (default: 1.0).")

    parser.add_argument('-c', '--connector',
                        choices=['http', 'kafka', 'rabbitmq', 'kinesis', 'pulsar-function'],
                        required=True,
                        help="Connector type or 'pulsar-function' to test.")

    connector_group = parser.add_argument_group('I/O Connector Arguments (for http, kafka, rabbitmq, kinesis)')
    connector_group.add_argument('--http-url', default='http://localhost:10999/', help="HTTP target URL.")
    connector_group.add_argument('--kafka-brokers', default='localhost:19092', help="Kafka brokers, comma-separated.")
    connector_group.add_argument('--kafka-topic', default='my-kafka-topic', help="Kafka topic.")
    connector_group.add_argument('--rabbitmq-url', default='amqp://user:password@localhost:5672/', help="RabbitMQ AMQP URL.")
    connector_group.add_argument('--rabbitmq-queue', default='my-queue', help="RabbitMQ queue name.")
    connector_group.add_argument('--kinesis-stream-name', default='my-kinesis-stream', help="Kinesis stream name.")
    connector_group.add_argument('--kinesis-aws-endpoint-url', default=None, help="Kinesis AWS endpoint URL (for LocalStack).")
    connector_group.add_argument('--kinesis-aws-region', default='us-east-1', help="Kinesis AWS region.")
    connector_group.add_argument('--kinesis-aws-access-key-id', default='test', help="Kinesis AWS access key ID.")
    connector_group.add_argument('--kinesis-aws-secret-access-key', default='test', help="Kinesis AWS secret access key.")

    pulsar_func_group = parser.add_argument_group('Pulsar Function Testing Arguments (if --connector is pulsar-function)')
    pulsar_func_group.add_argument('--pulsar-url', default='pulsar://localhost:6650', help="Pulsar service URL.")
    pulsar_func_group.add_argument('--pulsar-input-topic', help="Input Pulsar topic for the function test.")
    pulsar_func_group.add_argument('--pulsar-output-topic', help="Output Pulsar topic for the function test.")
    pulsar_func_group.add_argument('--pulsar-script', help="Name of the k6 script in 'deployment/compose/k6/pipeline_functions/' (e.g., 'payment_notice_translator_test.js').")
    pulsar_func_group.add_argument('--pulsar-send-timeout-ms', type=int, default=3000, help="Pulsar producer send timeout in ms.")
    pulsar_func_group.add_argument('--pulsar-receive-timeout-ms', type=int, default=1000, help="Pulsar consumer receive timeout in ms.")
    pulsar_func_group.add_argument('--pulsar-ack-timeout-ms', type=int, default=30000, help="Pulsar consumer ack timeout in ms.")
    pulsar_func_group.add_argument('--pulsar-receiver-queue-size', type=int, default=100, help="Pulsar consumer receiver queue size.")

    parser.add_argument('-v', '--verbose', action='store_true', help="Enable verbose logging for this script.")
    args = parser.parse_args()

    if args.connector == 'pulsar-function':
        if not all([args.pulsar_input_topic, args.pulsar_output_topic, args.pulsar_script]):
            parser.error("--pulsar-input-topic, --pulsar-output-topic, and --pulsar-script are required when --connector is 'pulsar-function'.")

    if args.verbose:
        logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    else:
        logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    logger.info(f"Starting k6 load test orchestrator for: {args.connector}" + (f" ({args.pulsar_script})" if args.connector == 'pulsar-function' else ""))

    try:
        if args.connector == 'kinesis':
            setup_kinesis_stream(args.kinesis_stream_name, args.kinesis_aws_endpoint_url, args.kinesis_aws_region,
                                 args.kinesis_aws_access_key_id, args.kinesis_aws_secret_access_key, args.verbose)
        elif args.connector == 'kafka':
            setup_kafka_topic(args.kafka_brokers, args.kafka_topic, args.verbose)
    except Exception as e:
        logger.error(f"Failed during resource setup for {args.connector}: {e}. Aborting k6 test.")
        # Use exit() directly as this is the main script body
        exit(1)

    script_dir = Path(__file__).parent.resolve()
    k6_base_script_dir = script_dir.parent / 'k6'

    if args.connector == 'pulsar-function':
        k6_script_name = args.pulsar_script
        k6_script_path = k6_base_script_dir / 'pipeline_functions' / k6_script_name
    else:
        k6_script_name = f"{args.connector}_test.js"
        k6_script_path = k6_base_script_dir / k6_script_name

    if not k6_script_path.is_file():
        logger.error(f"k6 script not found: {k6_script_path}")
        exit(1)

    k6_command = [args.k6_executable, 'run', str(k6_script_path)]
    k6_env = os.environ.copy()
    k6_env['K6_VUS'] = str(args.k6_vus)
    k6_env['K6_DURATION'] = args.k6_duration
    k6_env['K6_SLEEP'] = str(args.k6_sleep)

    if args.connector == 'http':
        k6_env['HTTP_URL'] = args.http_url
    elif args.connector == 'kafka':
        k6_env['KAFKA_BROKERS'] = args.kafka_brokers
        k6_env['KAFKA_TOPIC'] = args.kafka_topic
    elif args.connector == 'rabbitmq':
        k6_env['RABBITMQ_URL'] = args.rabbitmq_url
        k6_env['RABBITMQ_QUEUE'] = args.rabbitmq_queue
    elif args.connector == 'kinesis':
        k6_env['KINESIS_STREAM_NAME'] = args.kinesis_stream_name
        if args.kinesis_aws_endpoint_url:
             k6_env['AWS_KINESIS_ENDPOINT_URL'] = args.kinesis_aws_endpoint_url
        k6_env['AWS_REGION'] = args.kinesis_aws_region
        k6_env['AWS_ACCESS_KEY_ID'] = args.kinesis_aws_access_key_id
        k6_env['AWS_SECRET_ACCESS_KEY'] = args.kinesis_aws_secret_access_key
    elif args.connector == 'pulsar-function':
        k6_env['PULSAR_URL'] = args.pulsar_url
        k6_env['INPUT_TOPIC'] = args.pulsar_input_topic
        k6_env['OUTPUT_TOPIC'] = args.pulsar_output_topic
        k6_env['PULSAR_SEND_TIMEOUT_MS'] = str(args.pulsar_send_timeout_ms)
        k6_env['PULSAR_RECEIVE_TIMEOUT_MS'] = str(args.pulsar_receive_timeout_ms)
        k6_env['PULSAR_ACK_TIMEOUT_MS'] = str(args.pulsar_ack_timeout_ms)
        k6_env['PULSAR_RECEIVER_QUEUE_SIZE'] = str(args.pulsar_receiver_queue_size)
        # Pass K6_EXECUTION_ID if it's already set in the environment, k6 scripts might use it for unique subscription names
        if os.getenv('K6_EXECUTION_ID'):
            k6_env['K6_EXECUTION_ID'] = os.getenv('K6_EXECUTION_ID')


    logger.info(f"Running k6 command: {' '.join(k6_command)}")
    if args.verbose:
        log_env_keys = [
            'K6_VUS', 'K6_DURATION', 'K6_SLEEP', 'HTTP_URL', 'KAFKA_BROKERS', 'KAFKA_TOPIC',
            'RABBITMQ_URL', 'RABBITMQ_QUEUE', 'KINESIS_STREAM_NAME', 'AWS_KINESIS_ENDPOINT_URL',
            'AWS_REGION', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY',
            'PULSAR_URL', 'INPUT_TOPIC', 'OUTPUT_TOPIC',
            'PULSAR_SEND_TIMEOUT_MS', 'PULSAR_RECEIVE_TIMEOUT_MS',
            'PULSAR_ACK_TIMEOUT_MS', 'PULSAR_RECEIVER_QUEUE_SIZE', 'K6_EXECUTION_ID'
        ]
        log_env_display = {}
        for k in log_env_keys:
            if k in k6_env:
                if k == 'AWS_SECRET_ACCESS_KEY' and k6_env[k] != 'test':
                    log_env_display[k] = '***REDACTED***'
                else:
                    log_env_display[k] = k6_env[k]
        logger.debug(f"With environment subset for k6: {json.dumps(log_env_display, indent=2)}")

    return_code = 0
    try:
        process = subprocess.run(k6_command, env=k6_env, capture_output=True, text=True, check=False)
        logger.info(f"--- k6 script: {k6_script_name} stdout ---")
        if process.stdout: print(process.stdout) # Using print to ensure it goes to actual stdout
        logger.info(f"--- k6 script: {k6_script_name} stderr ---")
        if process.stderr: print(process.stderr) # Using print for stderr as well

        if process.returncode != 0:
            logger.error(f"k6 run for {args.connector} failed with exit code {process.returncode}")
            return_code = process.returncode
        else:
            logger.info(f"k6 run for {args.connector} completed successfully.")

    except FileNotFoundError:
        logger.error(f"k6 executable not found at '{args.k6_executable}'.")
        logger.error("Ensure k6 is installed and in PATH, or provide correct path via --k6-executable.")
        logger.error("For Kafka, RabbitMQ, Kinesis, or Pulsar function tests, a custom k6 build with appropriate extensions is required. "
                     "See deployment/compose/k6/README.md and deployment/compose/k6/pipeline_functions/README.md.")
        return_code = 1
    except Exception as e:
        logger.error(f"An error occurred while trying to run k6 for {args.connector}: {e}")
        return_code = 1

    return return_code

if __name__ == '__main__':
    # Ensure that logger output goes to stdout/stderr by default
    logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    exit_code = main()
    exit(exit_code)
