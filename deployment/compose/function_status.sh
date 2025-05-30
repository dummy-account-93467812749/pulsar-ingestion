for fn in $(pulsar-admin functions list --tenant public --namespace default); do
  echo "Status of function: $fn"
  pulsar-admin functions status --tenant public --namespace default --name "$fn"
  echo
done
