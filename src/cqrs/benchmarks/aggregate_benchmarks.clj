(ns cqrs.benchmarks.aggregate-benchmarks
  "Benchmarks for aggregate reconstitution and state management"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.infrastructure.event-store :as event-store]
            [cqrs.domain.bank-account.events :as events]))

(defn create-aggregate-history
  "Creates an aggregate with specified number of events"
  [aggregate-id n-events]
  (let [events [(events/create-event
                  aggregate-id
                  1
                  :AccountOpened
                  {:account-holder (str "Holder-" aggregate-id)
                   :account-type "CHECKING"
                   :initial-balance 10000})]

        deposit-events (mapv (fn [i]
                               (events/create-event
                                 aggregate-id
                                 (+ 2 i)
                                 :FundsDeposited
                                 {:amount (* 100 (inc (mod i 10)))
                                  :description (str "Deposit " i)}))
                             (range (quot n-events 2)))

        withdrawal-events (mapv (fn [i]
                                  (events/create-event
                                    aggregate-id
                                    (+ 2 (quot n-events 2) i)
                                    :FundsWithdrawn
                                    {:amount (* 50 (inc (mod i 5)))
                                     :description (str "Withdrawal " i)}))
                                (range (- n-events (quot n-events 2) 1)))

        all-events (concat events deposit-events withdrawal-events)]

    (doseq [event all-events]
      (event-store/append-event
        {:EventId (:event-id event)
         :AggregateId (:aggregate-id event)
         :EventType (name (:event-type event))
         :Version (:version event)
         :Timestamp (:timestamp event)
         :EventData (pr-str (:event-data event))}))
    aggregate-id))

(defn benchmark-aggregate-reconstitution
  "Benchmarks reconstituting aggregate from event history"
  [event-counts n-iterations]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Aggregate Reconstitution              ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (println "\nCreating aggregates with varying history sizes...")
  (let [test-aggregates (mapv (fn [count]
                                (println (format "  Creating aggregate with %d events..." count))
                                (let [agg-id (str "RECON-" count "-" (random-uuid))]
                                  (create-aggregate-history agg-id count)
                                  [agg-id count]))
                              event-counts)]

    (println "\nBenchmarking reconstitution...\n")
    (let [results (mapv
                    (fn [[aggregate-id event-count]]
                      (println (format "Testing reconstitution of %d events..." event-count))
                      (let [recon-fn (fn []
                                       (let [event-history (event-store/get-events-for-aggregate
                                                             aggregate-id)]
                                         (events/load-from-history event-history)))
                            stats (bench/benchmark-fn recon-fn n-iterations :warmup 3)]

                        (println (format "  Mean: %s | P95: %s | P99: %s"
                                       (bench/format-duration (:mean stats))
                                       (bench/format-duration (:p95 stats))
                                       (bench/format-duration (:p99 stats))))

                        (assoc stats :event-count event-count)))
                    test-aggregates)]

      (println "\n=== AGGREGATE RECONSTITUTION SUMMARY ===\n")
      (println "Events | Mean Latency | P95 Latency | P99 Latency | Events/ms")
      (println "-------|--------------|-------------|-------------|----------")
      (doseq [r results]
        (let [events-per-ms (/ (:event-count r) (:mean r))]
          (println (format "%-6d | %-12s | %-11s | %-11s | %.2f"
                           (:event-count r)
                           (bench/format-duration (:mean r))
                           (bench/format-duration (:p95 r))
                           (bench/format-duration (:p99 r))
                           events-per-ms))))

      (when (some #(> (:mean %) 100) results)
        (println "\n⚠️  WARNING: Some aggregates take >100ms to reconstitute"))
        (println "⚠️  Consider implementing snapshotting for aggregates with >1000 events"))

      results)))

(defn benchmark-event-retrieval-speed
  "Benchmarks the speed of retrieving events from store"
  [event-counts n-iterations]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Event Retrieval Speed                 ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (println "\nCreating aggregates...")
  (let [test-aggregates (mapv (fn [count]
                                (let [agg-id (str "RETRIEVAL-" count "-" (random-uuid))]
                                  (create-aggregate-history agg-id count)
                                  [agg-id count]))
                              event-counts)]

    (println "\nBenchmarking event retrieval...\n")
    (let [results (mapv
                    (fn [[aggregate-id event-count]]
                      (println (format "Testing retrieval of %d events..." event-count))
                      (let [retrieve-fn (fn []
                                          (event-store/get-events-for-aggregate aggregate-id))
                            stats (bench/benchmark-fn retrieve-fn n-iterations :warmup 3)]

                        (println (format "  Mean: %s | P95: %s"
                                       (bench/format-duration (:mean stats))
                                       (bench/format-duration (:p95 stats))))

                        (assoc stats :event-count event-count)))
                    test-aggregates)]

      (println "\n=== EVENT RETRIEVAL SUMMARY ===\n")
      (println "Events | Mean Latency | P95 Latency | Throughput")
      (println "-------|--------------|-------------|------------------")
      (doseq [r results]
        (println (format "%-6d | %-12s | %-11s | %s"
                         (:event-count r)
                         (bench/format-duration (:mean r))
                         (bench/format-duration (:p95 r))
                         (bench/format-throughput (:event-count r) (:mean r)))))

      results)))

(defn benchmark-state-application
  "Benchmarks applying events to aggregate state"
  [event-counts]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: State Application Performance        ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [aggregate-id "STATE-APP-TEST"
        results (mapv
                  (fn [event-count]
                    (println (format "\nTesting state application for %d events..." event-count))
                    (let [event-data (mapv (fn [i]
                                             {:event-id (str (random-uuid))
                                              :aggregate-id aggregate-id
                                              :event-type (if (even? i) :FundsDeposited :FundsWithdrawn)
                                              :version (inc i)
                                              :timestamp (str (java.time.Instant/now))
                                              :event-data {:amount (if (even? i) 100 50)
                                                           :description "Test"}})
                                           (range event-count))

                          apply-fn (fn []
                                     (reduce events/apply-event
                                             events/empty-bank-account
                                             event-data))

                          stats (bench/benchmark-fn apply-fn 50 :warmup 5)]

                      (bench/print-stats (format "State Application (%d events)" event-count) stats)

                      (assoc stats :event-count event-count)))
                  event-counts)]

    (println "\n=== STATE APPLICATION SUMMARY ===\n")
    (println "Events | Mean Latency | Events/ms")
    (println "-------|--------------|----------")
    (doseq [r results]
      (let [events-per-ms (/ (:event-count r) (:mean r))]
        (println (format "%-6d | %-12s | %.2f"
                         (:event-count r)
                         (bench/format-duration (:mean r))
                         events-per-ms))))
    results))

(defn benchmark-snapshot-benefit
  "Measures potential benefit of snapshotting by comparing full vs partial reconstitution"
  [total-events snapshot-interval]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Snapshot Benefit Analysis            ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nTotal events: %d, Snapshot every: %d events\n" total-events snapshot-interval))

  (let [aggregate-id (str "SNAPSHOT-TEST-" (random-uuid))
        _ (create-aggregate-history aggregate-id total-events)

        full-recon-fn (fn []
                        (let [events (event-store/get-events-for-aggregate aggregate-id)]
                          (events/load-from-history events)))

        partial-recon-fn (fn []
                           (let [events (event-store/get-events-for-aggregate aggregate-id)
                                 events-after-snapshot (drop snapshot-interval events)]
                             (events/load-from-history events-after-snapshot)))

        full-stats (bench/benchmark-fn full-recon-fn 20 :warmup 3)
        partial-stats (bench/benchmark-fn partial-recon-fn 20 :warmup 3)

        time-saved (- (:mean full-stats) (:mean partial-stats))
        improvement-pct (* 100.0 (/ time-saved (:mean full-stats)))]

    (println "Full Reconstitution (from beginning):")
    (bench/print-stats "Full Reconstitution" full-stats)

    (println (format "\nPartial Reconstitution (from snapshot at event %d):" snapshot-interval))
    (bench/print-stats "Partial Reconstitution" partial-stats)

    (println "\n=== SNAPSHOT BENEFIT ===")
    (println (format "Time saved:       %s" (bench/format-duration time-saved)))
    (println (format "Improvement:      %.2f%%" improvement-pct))
    (println (format "Speed-up:         %.2fx faster" (/ (:mean full-stats) (:mean partial-stats))))

    (when (> improvement-pct 50)
      (println "\n✓ Snapshotting would provide significant performance improvement!"))

    {:full-stats full-stats
     :partial-stats partial-stats
     :time-saved time-saved
     :improvement-pct improvement-pct}))

(defn benchmark-command-processing-overhead
  "Measures overhead of aggregate reconstitution in command processing"
  [event-counts n-iterations]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Command Processing Overhead           ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println "\nMeasuring command processing time breakdown...\n")

  (let [test-aggregates (mapv (fn [count]
                                (let [agg-id (str "CMD-OVERHEAD-" (random-uuid))]
                                  (create-aggregate-history agg-id count)
                                  [agg-id count]))
                              event-counts)]

    (let [results (mapv
                    (fn [[aggregate-id event-count]]
                      (println (format "Testing command on aggregate with %d events..." event-count))

                      (let [timings (atom {:retrieval [] :reconstitution [] :total []})

                            test-fn (fn []
                                      (let [total-start (bench/now-ms)

                                            retrieval-start (bench/now-ms)
                                            events (event-store/get-events-for-aggregate aggregate-id)
                                            retrieval-time (- (bench/now-ms) retrieval-start)

                                            recon-start (bench/now-ms)
                                            _ (events/load-from-history events)
                                            recon-time (- (bench/now-ms) recon-start)

                                            total-time (- (bench/now-ms) total-start)]

                                        (swap! timings update :retrieval conj retrieval-time)
                                        (swap! timings update :reconstitution conj recon-time)
                                        (swap! timings update :total conj total-time)))

                            _ (dotimes [_ n-iterations]
                                (test-fn))

                            retrieval-stats (bench/calculate-stats (:retrieval @timings))
                            recon-stats (bench/calculate-stats (:reconstitution @timings))
                            total-stats (bench/calculate-stats (:total @timings))

                            retrieval-pct (* 100.0 (/ (:mean retrieval-stats) (:mean total-stats)))
                            recon-pct (* 100.0 (/ (:mean recon-stats) (:mean total-stats)))]

                        (println (format "  Retrieval:       %s (%.1f%%)"
                                       (bench/format-duration (:mean retrieval-stats))
                                       retrieval-pct))
                        (println (format "  Reconstitution:  %s (%.1f%%)"
                                       (bench/format-duration (:mean recon-stats))
                                       recon-pct))
                        (println (format "  Total overhead:  %s"
                                       (bench/format-duration (:mean total-stats))))

                        {:event-count event-count
                         :retrieval retrieval-stats
                         :reconstitution recon-stats
                         :total total-stats}))
                    test-aggregates)]

      (println "\n=== COMMAND PROCESSING OVERHEAD SUMMARY ===\n")
      (println "Events | Retrieval    | Reconstitution | Total Overhead")
      (println "-------|--------------|----------------|---------------")
      (doseq [r results]
        (println (format "%-6d | %-12s | %-14s | %s"
                         (:event-count r)
                         (bench/format-duration (get-in r [:retrieval :mean]))
                         (bench/format-duration (get-in r [:reconstitution :mean]))
                         (bench/format-duration (get-in r [:total :mean])))))

      results)))

(defn run-all-aggregate-benchmarks
  "Runs all aggregate reconstitution benchmarks"
  [& {:keys [event-counts n-iterations]
      :or {event-counts [10 50 100 500 1000 5000]
           n-iterations 50}}]

  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║                                                   ║")
  (println "║      CQRS AGGREGATE BENCHMARK SUITE               ║")
  (println "║                                                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nConfiguration:"))
  (println (format "  Event Counts:     %s" (clojure.string/join ", " event-counts)))
  (println (format "  Iterations:       %d" n-iterations))
  (println)

  {:reconstitution (benchmark-aggregate-reconstitution event-counts n-iterations)
   :event-retrieval (benchmark-event-retrieval-speed event-counts n-iterations)
   :state-application (benchmark-state-application event-counts)
   :snapshot-benefit (benchmark-snapshot-benefit 1000 500)
   :command-overhead (benchmark-command-processing-overhead [10 100 500 1000] n-iterations)})
