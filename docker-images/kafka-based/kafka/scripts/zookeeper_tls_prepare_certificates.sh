#!/usr/bin/env bash
set -e

# Combine all the certs in the cluster CA into one file
echo "Preparing truststore"
CA_CERTS=/tmp/zookeeper/cluster.ca.crt
rm -f "$CA_CERTS"
for cert in /opt/kafka/cluster-ca-certs/*.crt; do
  sed -z '$ s/\n$//' "$cert" >> "$CA_CERTS"
  echo "" >> "$CA_CERTS"
done
echo "Preparing truststore is complete"

# Prepare ZooKeeper keystore which contains both public and private key
echo "Preparing keystore"
KEYSTORE=/tmp/zookeeper/zookeeper.pem
rm -f "$KEYSTORE"
cat "/opt/kafka/zookeeper-node-certs/$HOSTNAME.crt" >> "$KEYSTORE"
cat "/opt/kafka/zookeeper-node-certs/$HOSTNAME.key" >> "$KEYSTORE"
echo "Preparing keystore is complete"
