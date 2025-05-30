
pulsar-admin functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "event-type-splitter" \
  --classname "com.example.pulsar.filterer.EventTypeSplitter" \
  --jar "/pulsar/build/filterer.nar" \
  --inputs "persistent://public/default/common-events"