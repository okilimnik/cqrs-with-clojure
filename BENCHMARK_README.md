# CQRS System Benchmark Suite

Comprehensive performance testing suite for the Event Sourced CQRS banking system.

## Overview

This benchmark suite provides extensive performance testing across all layers of the CQRS system:

- **Command Processing**: Deposits, withdrawals, transfers, and concurrent operations
- **Event Store**: Single/atomic writes, event retrieval, optimistic locking
- **Query Performance**: DynamoDB (fast queries) and PostgreSQL (complex queries)
- **Projections**: Event projection throughput and eventual consistency lag
- **Aggregate Reconstitution**: Event replay performance at different history sizes
- **Load Testing**: Realistic workloads, spike tests, stress tests, and endurance tests

## Quick Start

### Run Quick Smoke Test (~1 minute)

```clojure
(require '[cqrs.benchmarks.runner :as runner])
(runner/run-quick-benchmarks)
```

### Run Standard Benchmark Suite (~10 minutes)

```clojure
(runner/run-standard-benchmarks)
```

### Run Comprehensive Suite (~30 minutes)

```clojure
(runner/run-comprehensive-benchmarks)
```

## Benchmark Modules

### 1. Command Benchmarks

Tests command processing performance with different operations and concurrency patterns.

```clojure
(require '[cqrs.benchmarks.command-benchmarks :as cmd])

;; Run all command benchmarks
(cmd/run-all-command-benchmarks
  :n-accounts 100
  :initial-balance 10000
  :n-iterations 100)

;; Individual benchmarks
(cmd/benchmark-account-creation 100)
(cmd/benchmark-deposits account-ids 100)
(cmd/benchmark-withdrawals account-ids 100)
(cmd/benchmark-transfers account-ids 100)

;; Concurrency tests
(cmd/benchmark-concurrent-same-account account-id 10 5000)
(cmd/benchmark-concurrent-different-accounts account-ids 20 10000)
(cmd/benchmark-bulk-transfers account-ids 1000)

;; Load test with increasing concurrency
(cmd/run-command-load-test account-ids [1 5 10 20 50] 5000)
```

**Key Metrics:**
- Account creation latency
- Deposit/withdrawal throughput
- Transfer latency (critical for ACID guarantees)
- Optimistic locking conflict rates
- Throughput under concurrent load

### 2. Event Store Benchmarks

Tests the core event store operations including atomic transactions.

```clojure
(require '[cqrs.benchmarks.event-store-benchmarks :as es])

;; Run all event store benchmarks
(es/run-all-event-store-benchmarks
  :n-iterations 100
  :n-threads 10)

;; Individual benchmarks
(es/benchmark-single-event-append 100)
(es/benchmark-atomic-multi-event-append 100 2)
(es/benchmark-event-retrieval aggregate-ids-with-counts)

;; Concurrency tests
(es/benchmark-concurrent-writes-same-aggregate 10 5000)
(es/benchmark-concurrent-writes-different-aggregates 100 10 5000)

;; Transaction tests
(es/benchmark-transaction-throughput 100 2)
(es/benchmark-write-amplification)
```

**Key Metrics:**
- Single event write latency
- Atomic transaction latency (2-100 events)
- Event retrieval speed by aggregate size
- Concurrent write throughput
- Transaction conflict rates
- Write amplification impact

### 3. Query Benchmarks

Tests both fast queries (DynamoDB) and complex analytics (PostgreSQL).

```clojure
(require '[cqrs.benchmarks.query-benchmarks :as query])

;; Run all query benchmarks
(query/run-all-query-benchmarks
  :account-ids account-ids
  :holders holders
  :n-iterations 100)

;; Fast queries (DynamoDB) - Target: <10ms
(query/benchmark-account-balance-query account-ids 100)
(query/benchmark-recent-transactions-query account-ids 100 10)

;; Complex queries (PostgreSQL) - Target: <100ms
(query/benchmark-account-details-query account-ids 100)
(query/benchmark-account-summary-query account-ids 100)
(query/benchmark-transaction-history-query account-ids 100 30)
(query/benchmark-search-transactions-query account-ids 100)

;; Analytical queries
(query/benchmark-top-accounts-query 20 10)
(query/benchmark-transaction-volume-report-query 20)
(query/benchmark-daily-balance-report-query account-ids 20 30)

;; Concurrency tests
(query/benchmark-concurrent-queries account-ids 50 10000 :mixed)

;; Cache performance
(query/benchmark-query-cold-vs-warm
  account-id
  #(queries/get-account-details account-id)
  "Account Details")

;; Load test
(query/run-query-load-test account-ids [1 10 20 50 100] 5000)
```

**Key Metrics:**
- DynamoDB query latency (should be <10ms)
- PostgreSQL query latency (should be <100ms)
- Query throughput under load
- Cold vs warm cache performance
- Concurrent query handling

### 4. Projection Benchmarks

Tests projection processing speed and eventual consistency lag.

```clojure
(require '[cqrs.benchmarks.projection-benchmarks :as proj])

;; Run all projection benchmarks
(proj/run-all-projection-benchmarks
  :n-iterations 100
  :n-threads 10)

;; Individual event projections
(proj/benchmark-single-event-projection 100 :opened)
(proj/benchmark-single-event-projection 100 :deposited)
(proj/benchmark-single-event-projection 100 :withdrawn)

;; Throughput tests
(proj/benchmark-projection-throughput 100 10)

;; Critical: Eventual consistency lag
(proj/benchmark-projection-lag 20)

;; Projection rebuild from history
(proj/benchmark-projection-rebuild 100 10)

;; Error handling
(proj/benchmark-projection-error-handling 50)

;; Batch processing
(proj/benchmark-projection-batch-processing [1 10 50 100] 10)
```

**Key Metrics:**
- Single event projection latency
- Projection throughput (events/sec)
- **Eventual consistency lag** (critical)
- Projection rebuild time
- Error recovery performance
- Batch processing efficiency

### 5. Aggregate Reconstitution Benchmarks

Tests the performance of rebuilding aggregate state from event history.

```clojure
(require '[cqrs.benchmarks.aggregate-benchmarks :as agg])

;; Run all aggregate benchmarks
(agg/run-all-aggregate-benchmarks
  :event-counts [10 50 100 500 1000 5000]
  :n-iterations 50)

;; Reconstitution performance
(agg/benchmark-aggregate-reconstitution [10 100 1000] 50)

;; Event retrieval speed
(agg/benchmark-event-retrieval-speed [10 100 1000] 50)

;; State application performance
(agg/benchmark-state-application [10 100 1000])

;; Snapshot benefit analysis
(agg/benchmark-snapshot-benefit 1000 500)

;; Command processing overhead
(agg/benchmark-command-processing-overhead [10 100 500 1000] 50)
```

**Key Metrics:**
- Reconstitution time by event count
- Event retrieval latency
- State application speed (events/ms)
- Snapshot benefit analysis
- Command processing overhead breakdown
- **Recommendation**: Implement snapshots for aggregates >1000 events

### 6. Load Tests

Comprehensive load testing with realistic workload patterns.

```clojure
(require '[cqrs.benchmarks.load-tests :as load])

;; Run comprehensive load tests
(load/run-comprehensive-load-tests
  :account-ids account-ids
  :n-threads 20
  :duration-ms 10000)

;; Quick load test
(load/run-quick-load-test account-ids)

;; Workload comparison
(load/workload-comparison-test account-ids 10 10000)

;; Spike test (sudden load increase)
(load/spike-test account-ids 10 30 5000)

;; Stress test (gradually increasing load)
(load/stress-test account-ids 5 100 5 5000)

;; Endurance test (sustained load)
(load/endurance-test account-ids 10 5)

;; Hot account test (optimistic locking conflicts)
(load/hot-account-test hot-accounts cold-accounts 20 10000 0.8)
```

**Workload Types:**
- **Realistic**: 50% reads, 30% deposits, 15% withdrawals, 5% transfers
- **Write-Heavy**: 80% writes, 20% reads
- **Read-Heavy**: 90% reads, 10% writes

**Key Metrics:**
- Throughput at different concurrency levels
- System breaking point
- Error rates under stress
- Performance degradation patterns
- Recovery from spike loads
- Hot account contention impact

## Output and Reporting

### Console Output

All benchmarks provide detailed console output including:
- Statistical analysis (min, mean, median, P95, P99, max, stddev)
- Throughput metrics (ops/sec)
- Progress indicators
- Warnings for performance issues

### File Outputs

Results are saved to `benchmark-results/` directory:

1. **EDN File**: `benchmark-YYYY-MM-DD_HH-mm-ss.edn`
   - Complete benchmark results in EDN format
   - Suitable for further analysis

2. **HTML Report**: `report-YYYY-MM-DD_HH-mm-ss.html`
   - Human-readable HTML report
   - Tables and metrics visualization
   - Open in browser for viewing

## Performance Targets

### Command Processing
- Account operations: <50ms P95
- Transfers (atomic): <100ms P95
- Bulk operations: >100 ops/sec

### Event Store
- Single event write: <10ms P95
- Atomic transaction (2 events): <20ms P95
- Event retrieval (100 events): <50ms P95

### Queries
- DynamoDB queries: <10ms P95
- PostgreSQL simple: <50ms P95
- PostgreSQL complex: <100ms P95

### Projections
- Event projection: <20ms per event
- **Eventual consistency lag: <100ms P95** (critical!)

### Aggregate Reconstitution
- 100 events: <20ms
- 1000 events: <100ms
- 5000 events: Consider snapshots

## Interpreting Results

### Good Performance Indicators
- ✓ Mean latency within target ranges
- ✓ P95/P99 within 2x of mean
- ✓ Error rate <1% under load
- ✓ Linear throughput scaling up to breaking point
- ✓ Projection lag <100ms

### Performance Issues
- ⚠️ P99 >5x mean (high variance)
- ⚠️ Error rate >5% under normal load
- ⚠️ Throughput degradation with concurrency
- ⚠️ Projection lag >500ms
- ⚠️ Reconstitution >100ms for <1000 events

### Critical Issues
- 🔴 Error rate >10%
- 🔴 System throughput decreasing with load
- 🔴 Projection lag >1 second
- 🔴 Transaction failures under normal load
- 🔴 Query timeouts

## Customization

### Custom Benchmark Configuration

```clojure
(runner/run-all-benchmarks
  :n-accounts 200           ;; Number of test accounts
  :initial-balance 10000    ;; Initial balance per account
  :n-iterations 150         ;; Iterations per benchmark
  :n-threads 15             ;; Thread count for concurrent tests
  :duration-ms 8000         ;; Duration for time-based tests
  :quick-mode false)        ;; Quick mode flag
```

### Running Individual Benchmarks

Each benchmark module can be run independently:

```clojure
;; Just test command processing
(require '[cqrs.benchmarks.command-benchmarks :as cmd])
(cmd/run-all-command-benchmarks)

;; Just test queries
(require '[cqrs.benchmarks.query-benchmarks :as query])
(query/run-all-query-benchmarks :account-ids ["ACC-001" "ACC-002"])

;; Single specific test
(require '[cqrs.benchmarks.core :as bench])
(bench/benchmark-fn
  #(my-operation)
  100
  :warmup 10)
```

## Best Practices

### Before Running Benchmarks

1. **Warm up the system**: Run a quick test first
2. **Stable environment**: Close unnecessary applications
3. **Consistent resources**: Same hardware/network conditions
4. **Clean state**: Clear caches if testing cold performance

### During Benchmarking

1. **Don't interrupt**: Let tests complete fully
2. **Monitor resources**: Watch CPU, memory, disk I/O
3. **Check logs**: Monitor for errors or warnings
4. **Note conditions**: Document any anomalies

### After Benchmarking

1. **Compare results**: Track trends over time
2. **Identify regressions**: Compare against baselines
3. **Investigate outliers**: Understand P99 cases
4. **Document findings**: Keep benchmark history

## Troubleshooting

### High Error Rates
- Check DynamoDB/PostgreSQL connection limits
- Verify AWS credentials and permissions
- Review optimistic locking conflicts
- Check for resource contention

### Slow Performance
- Verify AWS region latency
- Check database indexes
- Review connection pool settings
- Monitor system resources

### Inconsistent Results
- Increase warmup iterations
- Reduce test parallelism
- Check for background processes
- Verify stable network connection

## Architecture Insights

The benchmark suite is designed to validate the CQRS architecture:

1. **Write Path**: Command → Event Store → DynamoDB Streams
2. **Read Path**: Queries → Projections (DynamoDB/PostgreSQL)
3. **Eventual Consistency**: Event → Stream → Projection → Query

### Critical Performance Paths

1. **Transfer operations**: ACID guarantees via atomic transactions
2. **Projection lag**: Impacts user experience of eventual consistency
3. **Aggregate reconstitution**: Affects command processing latency
4. **Query performance**: Impacts user-facing response times

## Future Enhancements

Potential additions to the benchmark suite:

- [ ] Network latency simulation
- [ ] Failure injection testing
- [ ] Multi-region performance
- [ ] Snapshot performance benchmarks
- [ ] Long-running aggregate tests (10K+ events)
- [ ] Memory profiling
- [ ] Query plan analysis
- [ ] Stream processing lag analysis

## Contributing

When adding new benchmarks:

1. Follow the existing structure in `cqrs.benchmarks.*`
2. Use utilities from `cqrs.benchmarks.core`
3. Provide clear console output with statistics
4. Document expected performance targets
5. Add warnings for performance issues
6. Update this README

## License

Same as the main CQRS project.
