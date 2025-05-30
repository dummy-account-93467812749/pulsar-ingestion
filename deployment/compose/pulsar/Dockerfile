ARG PULSAR_IMAGE_TAG
FROM apachepulsar/pulsar-all:${PULSAR_IMAGE_TAG:-4.0.5}

USER root

# Copy custom scripts and give execute permissions
COPY docker-pulsar-entrypoint.sh /pulsar/docker-pulsar-entrypoint.sh
COPY bootstrap.sh /pulsar/conf/bootstrap.sh
COPY build/ /pulsar/build/
RUN chmod +x /pulsar/docker-pulsar-entrypoint.sh /pulsar/conf/bootstrap.sh

# Pulsar runs as 'pulsar' user by default in the base image
USER pulsar

# The original entrypoint is already set in the base image,
# but we override it in docker-compose.yml if we need to use our custom one.
# ENTRYPOINT ["bash", "/pulsar/docker-pulsar-entrypoint.sh"]
