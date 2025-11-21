(ns cqrs.examples.benchmark-example
  "Examples demonstrating how to run the benchmark suite"
  (:require [cqrs.benchmarks.runner :as runner]
            [cqrs.benchmarks.command-benchmarks :as cmd-bench]
            [cqrs.benchmarks.query-benchmarks :as query-bench]
            [cqrs.benchmarks.load-tests :as load-tests]))

(comment
  ;; ========================================
  ;; QUICK START EXAMPLES
  ;; ========================================

  ;; 1. Run a quick smoke test (~1 minute)
  (runner/run-quick-benchmarks)

  ;; 2. Run the standard benchmark suite (~10 minutes)
  (runner/run-standard-benchmarks)

  ;; 3. Run comprehensive benchmarks (~30 minutes)
  (runner/run-comprehensive-benchmarks)


  ;; ========================================
  ;; CUSTOM CONFIGURATIONS
  ;; ========================================

  ;; Run with custom parameters
  (runner/run-all-benchmarks
    :n-accounts 50           ;; Number of test accounts to create
    :initial-balance 10000   ;; Initial balance per account
    :n-iterations 100        ;; Iterations for each benchmark
    :n-threads 10            ;; Thread count for concurrent tests
    :duration-ms 5000)       ;; Duration for time-based tests


  ;; ========================================
  ;; INDIVIDUAL BENCHMARK EXAMPLES
  ;; ========================================

  ;; Setup test accounts first
  (def test-accounts (cmd-bench/setup-test-accounts 20 10000))

  ;; Test command processing
  (cmd-bench/benchmark-deposits test-accounts 50)
  (cmd-bench/benchmark-withdrawals test-accounts 50)
  (cmd-bench/benchmark-transfers test-accounts 50)

  ;; Test concurrent operations on same account (tests optimistic locking)
  (cmd-bench/benchmark-concurrent-same-account
    (first test-accounts)   ;; Single account ID
    10                       ;; Number of threads
    5000)                    ;; Duration in ms

  ;; Test concurrent operations on different accounts (tests throughput)
  (cmd-bench/benchmark-concurrent-different-accounts
    test-accounts            ;; All account IDs
    20                       ;; Number of threads
    10000)                   ;; Duration in ms

  ;; Test bulk transfers
  (cmd-bench/benchmark-bulk-transfers test-accounts 100)


  ;; ========================================
  ;; QUERY BENCHMARK EXAMPLES
  ;; ========================================

  ;; Test fast DynamoDB queries (target: <10ms)
  (query-bench/benchmark-account-balance-query test-accounts 50)
  (query-bench/benchmark-recent-transactions-query test-accounts 50 10)

  ;; Test complex PostgreSQL queries (target: <100ms)
  (query-bench/benchmark-account-details-query test-accounts 50)
  (query-bench/benchmark-account-summary-query test-accounts 50)

  ;; Test query performance under concurrent load
  (query-bench/benchmark-concurrent-queries test-accounts 20 10000 :mixed)

  ;; Compare cold vs warm cache performance
  (query-bench/benchmark-query-cold-vs-warm
    (first test-accounts)
    #(cqrs.queries/get-account-details (first test-accounts))
    "Account Details")


  ;; ========================================
  ;; LOAD TEST EXAMPLES
  ;; ========================================

  ;; Quick load test (30 seconds)
  (load-tests/run-quick-load-test test-accounts)

  ;; Realistic workload mix (50% reads, 30% deposits, 15% withdrawals, 5% transfers)
  (def realistic-workload (load-tests/realistic-workload-mix test-accounts))
  (cqrs.benchmarks.core/concurrent-benchmark realistic-workload 10 10000)

  ;; Write-heavy workload (80% writes, 20% reads)
  (def write-heavy-workload (load-tests/write-heavy-workload test-accounts))
  (cqrs.benchmarks.core/concurrent-benchmark write-heavy-workload 10 10000)

  ;; Read-heavy workload (90% reads, 10% writes)
  (def read-heavy-workload (load-tests/read-heavy-workload test-accounts))
  (cqrs.benchmarks.core/concurrent-benchmark read-heavy-workload 10 10000)

  ;; Spike test (sudden load increase)
  (load-tests/spike-test
    test-accounts
    10    ;; Base threads
    30    ;; Spike threads
    5000) ;; Spike duration in ms

  ;; Stress test (gradually increasing load to find breaking point)
  (load-tests/stress-test
    test-accounts
    5      ;; Initial threads
    50     ;; Max threads
    5      ;; Step size
    5000)  ;; Duration per step in ms

  ;; Hot account test (tests optimistic locking under contention)
  (def hot-accounts (take 5 test-accounts))
  (def cold-accounts (drop 5 test-accounts))
  (load-tests/hot-account-test
    hot-accounts
    cold-accounts
    20     ;; Number of threads
    10000  ;; Duration in ms
    0.8)   ;; 80% of operations hit hot accounts


  ;; ========================================
  ;; SPECIFIC PERFORMANCE INVESTIGATIONS
  ;; ========================================

  ;; Investigate if snapshots would help (for aggregates with many events)
  (require '[cqrs.benchmarks.aggregate-benchmarks :as agg-bench])
  (agg-bench/benchmark-snapshot-benefit
    1000  ;; Total events in aggregate
    500)  ;; Snapshot every N events

  ;; Measure projection lag (eventual consistency)
  (require '[cqrs.benchmarks.projection-benchmarks :as proj-bench])
  (proj-bench/benchmark-projection-lag 20)

  ;; Test event store atomic transactions
  (require '[cqrs.benchmarks.event-store-benchmarks :as es-bench])
  (es-bench/benchmark-atomic-multi-event-append 50 2)
  (es-bench/benchmark-write-amplification)


  ;; ========================================
  ;; ANALYZING RESULTS
  ;; ========================================

  ;; Results are saved to benchmark-results/ directory:
  ;; - benchmark-YYYY-MM-DD_HH-mm-ss.edn (raw data)
  ;; - report-YYYY-MM-DD_HH-mm-ss.html (HTML report)

  ;; Load and analyze previous results
  (require '[clojure.edn :as edn])
  (def previous-results
    (edn/read-string
      (slurp "benchmark-results/benchmark-2024-01-01_12-00-00.edn")))

  ;; Compare specific metrics
  (:mean (:deposits (:results (first previous-results))))


  ;; ========================================
  ;; CONTINUOUS MONITORING
  ;; ========================================

  ;; Run quick benchmarks regularly to detect regressions
  (defn run-regression-check []
    (println "Running regression check...")
    (let [results (runner/run-quick-benchmarks)
          deposits-mean (get-in results [:results 0 :results :commands :mean])]
      (if (> deposits-mean 100)
        (println "⚠️ WARNING: Deposit performance regression detected!")
        (println "✓ Performance within acceptable range"))
      results))

  ;; Schedule this to run periodically
  (run-regression-check)


  ;; ========================================
  ;; PERFORMANCE TUNING WORKFLOW
  ;; ========================================

  ;; 1. Baseline measurement
  (def baseline (runner/run-quick-benchmarks))

  ;; 2. Make code changes
  ;; ... your changes here ...

  ;; 3. Re-run benchmarks
  (def after-changes (runner/run-quick-benchmarks))

  ;; 4. Compare results
  (defn compare-benchmarks [before after metric-path]
    (let [before-val (get-in before metric-path)
          after-val (get-in after metric-path)
          improvement (* 100 (/ (- before-val after-val) before-val))]
      (println (format "%s: %.2f%% %s"
                       (last metric-path)
                       (Math/abs improvement)
                       (if (pos? improvement) "faster" "slower")))))

  (compare-benchmarks
    baseline
    after-changes
    [:results 0 :results :commands :mean])

  )

;; ========================================
;; MAIN ENTRY POINT
;; ========================================

(defn -main
  "Run benchmarks from command line"
  [& args]
  (case (first args)
    "quick" (runner/run-quick-benchmarks)
    "standard" (runner/run-standard-benchmarks)
    "comprehensive" (runner/run-comprehensive-benchmarks)
    (runner/run-standard-benchmarks)))
