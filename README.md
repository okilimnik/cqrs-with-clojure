# CQRS with Clojure

## Prerequisites

### Required tools
Install docker, kubectl, minicube, helm

### Start cluster
```bash
minikube start
```

### Install helm charts for MongoDB, PostgreSQL, Kafka, Prometheus, Grafana
```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install my-postgresql bitnami/postgresql --version 18.1.1 -f deploy/postgres.yaml
helm install my-mongodb bitnami/mongodb --version 18.1.1 -f deploy/mongo.yaml
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus-ce prometheus-community/prometheus --version 27.42.0 --namespace monitoring
helm repo add grafana https://grafana.github.io/helm-charts
helm install grafana-ce grafana/grafana -f deploy/grafana.yaml --namespace monitoring
helm install my-kafka oci://quay.io/strimzi-helm/strimzi-kafka-operator --version 0.48.0
```

#### See docs/postgresql.md on how to connect to PostgreSQL from outside the cluster.
#### See docs/mongodb.md on how to connect to MongoDB from outside the cluster. Plus you need to create a user in the admin database, to create a database for storing events, a first collection, and put some data in it to make it visible from outside.

### Download [Metabase](https://downloads.metabase.com/latest/metabase.jar) as a common GUI for MongoDB and PostgreSQL (optional)
```bash
java --add-opens java.base/java.nio=ALL-UNNAMED -jar metabase.jar
```

### Launch cluster dashboard
```bash
minikube dashboard
```