(ns cqrs.benchmarks.event-store-benchmarks
  "Benchmarks for event store operations"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.infrastructure.event-store :as event-store]
            [cqrs.domain.bank-account.events :as events]))

(defn create-test-event
  "Creates a test event for benchmarking"
  [aggregate-id version event-type]
  (case event-type
    :deposited {:EventId (str (random-uuid))
                :AggregateId aggregate-id
                :EventType "FundsDeposited"
                :Version version
                :Timestamp (str (java.time.Instant/now))
                :EventData (pr-str {:amount 100 :description "Test deposit"})}
    :withdrawn {:EventId (str (random-uuid))
                :AggregateId aggregate-id
                :EventType "FundsWithdrawn"
                :Version version
                :Timestamp (str (java.time.Instant/now))
                :EventData (pr-str {:amount 50 :description "Test withdrawal"})}))

(defn benchmark-single-event-append
  "Benchmarks single event append operations"
  [n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Single Event Append                ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [aggregate-id (str "AGG-" (random-uuid))
        version (atom 0)
        append-fn (fn []
                    (let [v (swap! version inc)
                          event (create-test-event aggregate-id v :deposited)]
                      (event-store/append-event event)))
        stats (bench/benchmark-fn append-fn n :warmup 5)]
    (bench/print-stats "Single Event Append" stats)
    stats))

(defn benchmark-atomic-multi-event-append
  "Benchmarks atomic multi-event append (used in transfers)"
  [n event-count]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println (format "║   BENCHMARK: Atomic %d-Event Append              ║" event-count))
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [version (atom 0)
        append-fn (fn []
                    (let [v (swap! version inc)
                          events (mapv #(create-test-event
                                          (str "AGG-MULTI-" (random-uuid))
                                          (+ v %)
                                          (if (even? %) :deposited :withdrawn))
                                       (range event-count))]
                      (event-store/append-events-atomically events)))
        stats (bench/benchmark-fn append-fn n :warmup 5)]
    (bench/print-stats (format "Atomic %d-Event Append" event-count) stats)
    stats))

(defn benchmark-event-retrieval
  "Benchmarks event retrieval by aggregate ID"
  [aggregate-id-with-events]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Event Retrieval                     ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (doseq [[aggregate-id event-count] aggregate-id-with-events]
    (let [retrieve-fn (fn [] (event-store/get-events-for-aggregate aggregate-id))
          stats (bench/benchmark-fn retrieve-fn 50 :warmup 5)]
      (println (format "\nRetrieval for aggregate with %d events:" event-count))
      (bench/print-stats (format "Event Retrieval (%d events)" event-count) stats)))

  :completed)

(defn setup-aggregates-with-history
  "Creates aggregates with varying event history sizes"
  [event-counts]
  (println "\nSetting up aggregates with event history...")
  (mapv (fn [count]
          (println (format "  Creating aggregate with %d events..." count))
          (let [aggregate-id (str "AGG-HIST-" (random-uuid))
                events (mapv #(create-test-event aggregate-id (inc %) :deposited)
                             (range count))]
            (doseq [event events]
              (event-store/append-event event))
            [aggregate-id count]))
        event-counts))

(defn benchmark-concurrent-writes-same-aggregate
  "Tests optimistic locking under concurrent writes to same aggregate"
  [n-threads duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Concurrent Writes (Same Aggregate)   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing write conflicts with %d threads\n" n-threads))

  (let [aggregate-id (str "AGG-CONCURRENT-" (random-uuid))
        current-version (atom 0)
        write-fn (fn []
                   (let [v (swap! current-version inc)
                         event (create-test-event aggregate-id v :deposited)]
                     (try
                       (event-store/append-event event)
                       true
                       (catch Exception _
                         false))))
        results (bench/concurrent-benchmark write-fn n-threads duration-ms)]
    (bench/print-concurrent-results results)
    (println (format "\nConflict rate:    %.2f%%"
                     (* 100.0 (/ (:errors results) (+ (:operations results) (:errors results))))))
    results))

(defn benchmark-concurrent-writes-different-aggregates
  "Tests write throughput with different aggregates"
  [n-aggregates n-threads duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Concurrent Writes (Diff Aggregates)   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing throughput with %d threads across %d aggregates\n"
                   n-threads n-aggregates))

  (let [aggregate-ids (mapv #(str "AGG-PARALLEL-" %) (range n-aggregates))
        version-counters (mapv (fn [_] (atom 0)) (range n-aggregates))
        write-fn (fn []
                   (let [idx (rand-int n-aggregates)
                         aggregate-id (nth aggregate-ids idx)
                         version-counter (nth version-counters idx)
                         v (swap! version-counter inc)
                         event (create-test-event aggregate-id v :deposited)]
                     (try
                       (event-store/append-event event)
                       (catch Exception _
                         nil)))
        results (bench/concurrent-benchmark write-fn n-threads duration-ms)]
    (bench/print-concurrent-results results)
    results))

(defn benchmark-transaction-throughput
  "Benchmarks atomic transaction throughput"
  [n-transactions events-per-transaction]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Transaction Throughput              ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Executing %d transactions with %d events each\n"
                   n-transactions events-per-transaction))

  (let [start (bench/now-ms)
        futures (mapv
                  (fn [_]
                    (future
                      (let [aggregate-id (str "AGG-TX-" (random-uuid))
                            events (mapv #(create-test-event aggregate-id (inc %) :deposited)
                                         (range events-per-transaction))
                            op-start (bench/now-ms)]
                        (try
                          (event-store/append-events-atomically events)
                          (- (bench/now-ms) op-start)
                          (catch Exception e
                            {:error (.getMessage e)})))))
                  (range n-transactions))
        results (mapv deref futures)
        successful (filterv number? results)
        failed (filterv map? results)
        total-time (- (bench/now-ms) start)]

    (println (format "Total time:       %s" (bench/format-duration total-time)))
    (println (format "Successful:       %d" (count successful)))
    (println (format "Failed:           %d" (count failed)))
    (println (format "Throughput:       %s" (bench/format-throughput n-transactions total-time)))
    (println (format "Events/sec:       %s"
                     (bench/format-throughput (* n-transactions events-per-transaction) total-time)))

    (when (seq successful)
      (bench/print-stats "Transaction Latency" (bench/calculate-stats successful)))

    {:total-time total-time
     :successful (count successful)
     :failed (count failed)
     :stats (when (seq successful) (bench/calculate-stats successful))}))

(defn benchmark-write-amplification
  "Tests performance impact of increasing event count in atomic transactions"
  []
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Write Amplification (Event Count)    ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [event-counts [1 2 5 10 25 50]
        results (mapv (fn [count]
                        (println (format "\n--- Testing %d events per transaction ---" count))
                        (let [stats (benchmark-atomic-multi-event-append 50 count)]
                          (assoc stats :event-count count)))
                      event-counts)]

    (println "\n=== WRITE AMPLIFICATION SUMMARY ===\n")
    (println "Events | Mean Latency | P95 Latency | P99 Latency")
    (println "-------|--------------|-------------|-------------")
    (doseq [r results]
      (println (format "%-6d | %-12s | %-11s | %s"
                       (:event-count r)
                       (bench/format-duration (:mean r))
                       (bench/format-duration (:p95 r))
                       (bench/format-duration (:p99 r)))))
    results))

(defn run-all-event-store-benchmarks
  "Runs all event store benchmarks"
  [& {:keys [n-iterations n-threads duration-ms]
      :or {n-iterations 100
           n-threads 10
           duration-ms 5000}}]

  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║                                                   ║")
  (println "║        CQRS EVENT STORE BENCHMARK SUITE           ║")
  (println "║                                                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nConfiguration:"))
  (println (format "  Iterations:       %d" n-iterations))
  (println (format "  Threads:          %d" n-threads))
  (println (format "  Duration:         %s" (bench/format-duration duration-ms)))
  (println)

  (let [aggregates-with-history (setup-aggregates-with-history [10 50 100 500 1000])]

    {:single-event-append (benchmark-single-event-append n-iterations)
     :atomic-2-event-append (benchmark-atomic-multi-event-append n-iterations 2)
     :atomic-5-event-append (benchmark-atomic-multi-event-append n-iterations 5)
     :event-retrieval (benchmark-event-retrieval aggregates-with-history)
     :concurrent-writes-same (benchmark-concurrent-writes-same-aggregate n-threads duration-ms)
     :concurrent-writes-different (benchmark-concurrent-writes-different-aggregates
                                     100 n-threads duration-ms)
     :transaction-throughput (benchmark-transaction-throughput 100 2)
     :write-amplification (benchmark-write-amplification)}))
