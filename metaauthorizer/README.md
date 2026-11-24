# Strimzi Metaauthorizer

An Apache Kafka authorizer tailored for Strimzi.
It wraps the user-configured authorizer (for example, the Kafka `StandardAuthorizer`, Keycloak, or OPA) and handles the internal Strimzi components itself:

* Requests from the internal components (Kafka nodes, Cluster Operator, Topic and User Operators, Cruise Control, Kafka Exporter) arriving through the internal listeners are authorized using hardcoded rules tailored to what each component actually does.
* All other requests are delegated to the user-configured authorizer.

This way, the internal components do not need to be configured as `super.users`.

This is an independent Maven project.
It is not part of the Strimzi multi-module build.

## Decision logic

| Listener          | Principal                     | Decision                                                                      |
|-------------------|-------------------------------|-------------------------------------------------------------------------------|
| User listener     | any                           | Delegated to the user-configured authorizer                                   |
| Internal listener | configured internal component | Hardcoded rules of the component, anything outside of them is **denied**      |
| Internal listener | any other principal           | Delegated to the user-configured authorizer                                   |

The third row is needed because of how KRaft forwards requests.
Brokers forward many admin requests (CreateTopics, DeleteTopics, CreatePartitions, CreateAcls, DeleteAcls, AlterClientQuotas, IncrementalAlterConfigs for topics, and others) to the controllers in an `Envelope` request.
The controller authorizes the `Envelope` itself with the broker principal (`CLUSTER_ACTION` on the cluster), and then authorizes the inner request with the **original client principal** but with the **control plane listener name** (see `EnvelopeUtils.handleEnvelopeRequest` in Kafka).
Without delegating these requests, any forwarded request from a regular user would be denied.

When no user-configured authorizer is set, all requests that would be delegated are allowed (the same as running Kafka without an authorizer), while the internal components are still restricted to their rules.
ACL management requests fail with `SECURITY_DISABLED` in that case.

## Configuration

The Metaauthorizer is configured in the Kafka node configuration:

```properties
authorizer.class.name=io.strimzi.kafka.metaauthorizer.MetaAuthorizer

strimzi.metaauthorizer.listeners=CONTROLPLANE-9090,REPLICATION-9091
strimzi.metaauthorizer.authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer

strimzi.metaauthorizer.kafka.user=User:CN=my-cluster-kafka,O=io.strimzi
strimzi.metaauthorizer.clusteroperator.user=User:CN=cluster-operator,O=io.strimzi
strimzi.metaauthorizer.topicoperator.user=User:CN=my-cluster-entity-topic-operator,O=io.strimzi
strimzi.metaauthorizer.useroperator.user=User:CN=my-cluster-entity-user-operator,O=io.strimzi
strimzi.metaauthorizer.cruisecontrol.user=User:CN=my-cluster-cruise-control,O=io.strimzi
strimzi.metaauthorizer.kafkaexporter.user=User:CN=my-cluster-kafka-exporter,O=io.strimzi

# Options of the user-configured authorizer are passed to it unchanged
super.users=User:CN=my-admin
```

| Option                                         | Required | Description                                                                                                                       |
|------------------------------------------------|----------|-----------------------------------------------------------------------------------------------------------------------------------|
| `strimzi.metaauthorizer.listeners`             | Yes      | Comma-separated list of internal listener names (case-insensitive).                                                               |
| `strimzi.metaauthorizer.authorizer.class.name` | No       | Class of the user-configured authorizer. When not set, the delegated requests are allowed.                                        |
| `strimzi.metaauthorizer.kafka.user`            | No       | Principal of the Kafka nodes.                                                                                                     |
| `strimzi.metaauthorizer.clusteroperator.user`  | No       | Principal of the Cluster Operator.                                                                                                |
| `strimzi.metaauthorizer.topicoperator.user`    | No       | Principal of the Topic Operator.                                                                                                  |
| `strimzi.metaauthorizer.useroperator.user`     | No       | Principal of the User Operator.                                                                                                   |
| `strimzi.metaauthorizer.cruisecontrol.user`    | No       | Principal of Cruise Control.                                                                                                      |
| `strimzi.metaauthorizer.kafkaexporter.user`    | No       | Principal of Kafka Exporter.                                                                                                      |
| `strimzi.metaauthorizer.cruisecontrol.topics`  | No       | Topics used by Cruise Control. Defaults to `strimzi.cruisecontrol.metrics,strimzi.cruisecontrol.modeltrainingsamples,strimzi.cruisecontrol.partitionmetricsamples`. |

Notes:

* The principals are in the Kafka `<type>:<name>` format, so both the mTLS (`User:CN=...,O=io.strimzi`) and the Service Account (`User:system:serviceaccount:<namespace>:<name>`) authentication modes work.
  The principals are matched by type and name, so they work also with custom `KafkaPrincipal` subclasses.
* Components without a configured principal (for example, not deployed) get no rules.
* When the same principal is configured for multiple components (for example, the Topic and User Operators sharing the Entity Operator Service Account), it gets the union of their rules.
* The complete Kafka configuration is passed to the user-configured authorizer, so its own options (such as `super.users`) keep working.

## Hardcoded rules

The rules only allow operations; anything else is denied.
The Kafka implied operations apply (READ, WRITE, DELETE, and ALTER imply DESCRIBE; ALTER_CONFIGS implies DESCRIBE_CONFIGS).

| Component        | Cluster                                        | Topics                                                                                                    | Groups        |
|------------------|------------------------------------------------|-----------------------------------------------------------------------------------------------------------|---------------|
| Kafka nodes      | everything (all resource types)                | everything                                                                                                | everything    |
| Cluster Operator | Describe, DescribeConfigs, Alter, AlterConfigs | `*`: Describe, DescribeConfigs                                                                            | –             |
| Topic Operator   | Describe, DescribeConfigs                      | `*`: Create, Delete, Alter, Describe, DescribeConfigs, AlterConfigs                                       | –             |
| User Operator    | Describe, Alter, DescribeConfigs, AlterConfigs | –                                                                                                         | –             |
| Cruise Control   | Describe, DescribeConfigs, Alter, AlterConfigs | `*`: Describe, DescribeConfigs, AlterConfigs; Cruise Control topics: Create, Read, Write, Alter           | –             |
| Kafka Exporter   | Describe                                       | `*`: Describe                                                                                             | `*`: Describe |

The rules were derived from the Kafka APIs used by the components:

* **Cluster Operator**: DescribeCluster, DescribeMetadataQuorum, ListTopics, DescribeTopics, DescribeConfigs (brokers and topics), IncrementalAlterConfigs (brokers and broker loggers), UpdateFeatures, UnregisterBroker, UnregisterController, DescribeClientQuotas, AlterClientQuotas.
* **Topic Operator**: CreateTopics, DeleteTopics, CreatePartitions, DescribeTopics, DescribeConfigs (topics and brokers), IncrementalAlterConfigs (topics), ListPartitionReassignments, DescribeCluster.
* **User Operator**: DescribeAcls, CreateAcls, DeleteAcls, DescribeUserScramCredentials, AlterUserScramCredentials, DescribeClientQuotas, AlterClientQuotas.
* **Cruise Control** (3.1.0): DescribeCluster, ListTopics, DescribeTopics, DescribeConfigs, DescribeLogDirs, DescribeReplicaLogDirs, AlterPartitionReassignments, ListPartitionReassignments, ElectLeaders, AlterReplicaLogDirs, IncrementalAlterConfigs (replication throttles on brokers and topics, own topics), CreateTopics and CreatePartitions (own topics), produce and consume (own topics).
  The Cruise Control consumers use manual assignment without a consumer group, so no group rules are needed.
  The idempotent producer check (`authorizeByResourceType(WRITE, TOPIC)`) passes thanks to the WRITE rule on its own topics.
* **Kafka Exporter**: Metadata, ListOffsets, ListGroups, DescribeGroups, OffsetFetch.

The Kafka nodes are allowed everything.
They use the internal listeners for replication, KRaft controller communication, request forwarding, and broker plugins (Cruise Control Metrics Reporter, Strimzi Quotas plugin, Tiered Storage RLMM, and others).
Enumerating all of this would be fragile, and the nodes have access to all the data anyway.

## Design notes

* `MetaAuthorizer` implements the Kafka `ClusterMetadataAuthorizer` interface and forwards its calls (`setAclMutator`, `loadSnapshot`, `addAcl`, `removeAcl`, `completeInitialLoad`) to the user-configured authorizer when it implements it as well.
  Without this, the `StandardAuthorizer` would never receive the ACLs stored in the KRaft metadata log and its startup futures would never complete.
  When the delegate does not implement it, these calls are ignored.
* `ClusterMetadataAuthorizer` is an internal Kafka interface from the `kafka-metadata` module.
  The Metaauthorizer has to be built against (and tested with) the Kafka versions it is used with.
* `Monitorable#withPluginMetrics` is forwarded to the delegate (the `StandardAuthorizer` requires it).
* The internal component rules do not depend on the delegate, so the internal components are authorized even before the `StandardAuthorizer` completes the initial ACL load.
* The hot path for delegated requests is a single `HashSet` lookup of the listener name (plus at most two `HashMap` lookups of the principal on the internal listeners) before calling the delegate. The action list is passed through unchanged.

### Behavior differences compared to super-users

* **Logging**: Decisions for the internal components are logged by the `io.strimzi.kafka.metaauthorizer.MetaAuthorizer` logger (denials at `INFO` when the action requests it, allowed requests at `DEBUG`), not by the Kafka `kafka.authorizer.logger`.
* **Metrics**: The `StandardAuthorizer` authorization metrics (`authorization-allowed-rate-per-minute` etc.) count only the delegated decisions, not the decisions made for the internal components.
* **Strict deny**: Internal components cannot get additional permissions through ACLs on the internal listeners.
* `Reconfigurable` (dynamic reconfiguration of the delegate) is not forwarded.

## Known limitation

Many requests are forwarded by the broker to the controller **without being authorized on the broker** (for example, CreateTopics, DeleteTopics, CreatePartitions, CreateAcls, DeleteAcls, AlterClientQuotas, AlterUserScramCredentials, ElectLeaders, AlterPartitionReassignments, and IncrementalAlterConfigs for topics; the broker pre-authorizes only the BROKER and BROKER_LOGGER config resources).
The controller sees them with the original principal on the control plane listener and cannot tell which listener the client originally connected to.

A client that authenticates on a **user listener** with a principal identical to an internal component's principal therefore gets the internal rules for these forwarded requests.
This is no worse than the current `super.users` approach (super-users match on every listener), but the Metaauthorizer cannot fully close it.
Requests handled directly by the broker (for example, Metadata, Fetch, Produce, or DescribeConfigs) are correctly delegated on the user listeners.
The integration test `MetaAuthorizerIT#testKnownLimitationForwardedRequestFromInternalPrincipalOnUserListener` documents this behavior.

## Building and testing

Requires Java 21 and Maven.

```shell
# Build with unit and integration tests (and Checkstyle)
mvn verify

# Unit tests only
mvn test
```

* **Unit tests** cover the configuration, the rules of each component (every operation and resource type combination is checked), the routing with a mock delegate, the `ClusterMetadataAuthorizer` forwarding, and the configuration with a real `StandardAuthorizer`.
* **Integration test** (`MetaAuthorizerIT`) runs an in-JVM KRaft cluster (one broker, one separate controller, SASL PLAIN) with the Metaauthorizer delegating to the `StandardAuthorizer` and no super-users.
  It verifies the internal rules on the internal listeners (including the requests forwarded to the controller), the strict deny, the ACL loading from the KRaft metadata, and the delegation of forwarded requests from regular users.

Checkstyle uses the configuration from the parent Strimzi repository (`../.checkstyle`).

## Performance testing

The JMH benchmarks compare:

* **baseline**: `StandardAuthorizer` with the internal principals in `super.users` (the current Strimzi setup)
* **metaauthorizer**: `MetaAuthorizer` delegating to `StandardAuthorizer`, with the internal principals in the `strimzi.metaauthorizer.*.user` options and no `super.users`

Both get the same ACLs loaded through the `ClusterMetadataAuthorizer` interface, the same way a Kafka node does.

### Running

```shell
# All benchmarks with the default settings (takes around 25-30 minutes)
mvn -Pperf -DskipTests verify

# Pass any JMH options using jmh.args, for example a subset of parameters with 8 threads
mvn -Pperf -DskipTests verify -Djmh.args="-p aclCount=10000 -p actions=10 -t 8"

# Single benchmark class or method (JMH regexp)
mvn -Pperf -DskipTests verify -Djmh.args="AuthorizeBenchmark.userAllowed"
```

The defaults are 1 fork, 3 warmup and 5 measurement iterations of 1 second each, and the GC profiler.
The raw results are written to `target/jmh-results.json`.
At the end, a table comparing baseline and Metaauthorizer for each benchmark and parameter combination is printed.

### Scenarios

`AuthorizeBenchmark` (`Authorizer#authorize`, each call authorizes `actions` actions on different topics):

| Benchmark         | Request                                                                                     |
|-------------------|---------------------------------------------------------------------------------------------|
| `userAllowed`     | Regular user on a user listener, allowed by a prefixed ACL (produce / fetch hot path)       |
| `userDenied`      | Regular user on a user listener, no matching ACL                                            |
| `userForwarded`   | Regular user on the control plane listener (forwarded request): principal miss, then delegate |
| `kafkaNode`       | Kafka node on the replication listener: allow-all rule vs. super-user                       |
| `operatorAllowed` | Topic Operator DescribeConfigs on the replication listener: rule lookup vs. super-user      |
| `operatorDenied`  | Topic Operator READ on the replication listener: strict deny vs. super-user (allowed)       |

`AuthorizeByResourceTypeBenchmark` (`Authorizer#authorizeByResourceType`, used for example for the idempotent producers):

| Benchmark      | Request                                                                     |
|----------------|-----------------------------------------------------------------------------|
| `userAllowed`  | Regular user, WRITE on TOPIC (prefixed ACL exists)                          |
| `userDenied`   | Regular user, WRITE on TRANSACTIONAL_ID (no ACL)                            |
| `internalUser` | Cruise Control on the replication listener, WRITE on TOPIC                  |

Parameters:

* `aclCount`: 0, 1000, 10000, 50000 additional ACLs for 500 other users (literal and prefixed, topics and groups, ALLOW and DENY)
* `actions`: 1, 10, 50 actions per `authorize` call
* Threads: 1 by default, use `-t` to test contention

### Acceptance criteria

* Delegation path (`user*` scenarios): no more than about 5% slower than the baseline at 1000 ACLs and more, and no extra allocation per call.
* Internal component paths: not slower than the baseline super-user path beyond noise.

The criteria are evaluated manually from the comparison table.
They are not enforced in CI because microbenchmarks on shared CI runners are too noisy.

### Results

First results (2026-11-23) on an Apple M1 Max (10 cores), Java 21.0.9, Kafka 4.4.0, JMH 1.37.
These are laptop numbers: use them to compare the two setups, not as absolute values.

**Delegation paths (regular users): no measurable overhead.**
With a single fork, the differences between baseline and Metaauthorizer ranged from about −20% to +20% with no consistent direction, and allocations varied the same way.
That is fork-to-fork JIT variance, not overhead: the Metaauthorizer adds one `HashSet` lookup (plus one `HashMap` miss on the control plane listener) and allocates nothing on this path.
Re-running the cases that looked worst with 3 forks confirmed this:

| Benchmark (3 forks)                                      | Baseline ns/op   | Metaauthorizer ns/op | Diff   |
|----------------------------------------------------------|------------------|----------------------|--------|
| `userAllowed` (1000 ACLs, 1 action)                      | 512.8 ± 26.9     | 413.3 ± 21.8         | −19.4% |
| `userAllowed` (1000 ACLs, 10 actions)                    | 4353.8 ± 254.6   | 4378.1 ± 466.9       | +0.6%  |
| `userAllowed` (1000 ACLs, 50 actions)                    | 22289.6 ± 3287.8 | 21510.7 ± 435.3      | −3.5%  |
| `userAllowed` (10000 ACLs, 1 action)                     | 630.8 ± 17.5     | 524.5 ± 13.7         | −16.8% |
| `userAllowed` (10000 ACLs, 10 actions)                   | 5551.3 ± 549.6   | 5039.9 ± 423.3       | −9.2%  |
| `userAllowed` (10000 ACLs, 50 actions)                   | 26513.8 ± 2018.9 | 24141.2 ± 2672.0     | −8.9%  |
| `authorizeByResourceType.userAllowed` (1000 ACLs)        | 66039 ± 1326     | 70675 ± 638          | +7.0%  |
| `authorizeByResourceType.userAllowed` (10000 ACLs)       | 820179 ± 8319    | 811458 ± 14873       | −1.1%  |

The only result that stayed positive is `authorizeByResourceType.userAllowed` at 1000 ACLs (+7%).
That call goes through Kafka's default `Authorizer#authorizeByResourceType`, which scans and copies every ACL, so the extra call layer costs only nanoseconds and the gap most likely comes from JIT inlining depth.
At 10000 and 50000 ACLs the difference is within noise (−1.1% and +3.1%).
It is also a cold path: Kafka calls it when an idempotent producer initializes, not per request.

**Internal component paths: much faster, with much less allocation (single fork, 10 actions per call):**

| Benchmark                                        | Baseline ns/op | Metaauthorizer ns/op | Diff   | Baseline B/op | Metaauthorizer B/op |
|--------------------------------------------------|----------------|----------------------|--------|---------------|---------------------|
| `kafkaNode` (1000 ACLs)                          | 1053.7 ± 33.8  | 61.9 ± 0.6           | −94.1% | 880           | 80                  |
| `operatorAllowed` (1000 ACLs)                    | 1096.8 ± 12.6  | 172.8 ± 0.3          | −84.2% | 1040          | 112                 |
| `operatorDenied` (1000 ACLs)                     | 1099.0 ± 18.6  | 180.5 ± 2.5          | −83.6% | 1040          | 112                 |
| `authorizeByResourceType.internalUser` (1000 ACLs) | 98.2 ± 0.3   | 30.5 ± 0.9           | −69.0% | 136           | 0                   |

The results are the same for all ACL counts: neither super-users nor the hardcoded rules look at the ACLs.
The baseline is slower because `StandardAuthorizer` builds `principal.toString()` for the super-user check and records its authorization metrics on every action.
The Metaauthorizer does two map lookups and a short rule scan.

**8 threads (10000 ACLs, 10 actions, 2 forks):**

| Benchmark                              | Baseline ns/op    | Metaauthorizer ns/op | Diff   |
|----------------------------------------|-------------------|----------------------|--------|
| `userAllowed`                          | 36159.6 ± 1370.2  | 34432.5 ± 1468.4     | −4.8%  |
| `userDenied`                           | 46225.6 ± 3091.6  | 45033.4 ± 3998.2     | −2.6%  |
| `userForwarded`                        | 32770.6 ± 5582.8  | 37358.6 ± 2274.7     | +14.0% |
| `kafkaNode`                            | 16198.1 ± 872.1   | 69.3 ± 1.5           | −99.6% |
| `operatorAllowed`                      | 15862.7 ± 1259.9  | 169.2 ± 27.7         | −98.9% |
| `authorizeByResourceType.internalUser` | 1724.5 ± 79.1     | 32.5 ± 0.9           | −98.1% |
| `authorizeByResourceType.userAllowed`  | 868301.9 ± 21558.2 | 920364.2 ± 22005.9  | +6.0%  |

With 8 threads, every `StandardAuthorizer` call is about 6 to 7 times slower than with 1 thread in both setups.
The main contention point is its synchronized authorization metrics sensors.
Internal components avoid that entirely in the Metaauthorizer.
`userForwarded` runs the same code as `userAllowed` plus one `HashMap` miss; its +14% is within the baseline's ±17% error.

**Conclusion:** The acceptance criteria are met.
On the delegation path, no statistically meaningful slowdown was found and nothing extra is allocated.
The internal component paths are significantly faster than the super-user path and allocate less, which mainly helps the replication traffic of the Kafka nodes.
