(ns cqrs.benchmarks.projection-benchmarks
  "Benchmarks for projection processing and eventual consistency lag"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.application.projection-service :as projection-service]
            [cqrs.infrastructure.event-store :as event-store]
            [cqrs.queries :as queries]))

(defn create-projection-test-event
  "Creates a test event for projection benchmarking"
  [aggregate-id version event-type]
  (case event-type
    :opened {:event-id (str (random-uuid))
             :aggregate-id aggregate-id
             :event-type :AccountOpened
             :version version
             :timestamp (str (java.time.Instant/now))
             :event-data {:account-holder (str "Holder-" aggregate-id)
                          :account-type "CHECKING"
                          :initial-balance 1000}}
    :deposited {:event-id (str (random-uuid))
                :aggregate-id aggregate-id
                :event-type :FundsDeposited
                :version version
                :timestamp (str (java.time.Instant/now))
                :event-data {:amount 100 :description "Test deposit"}}
    :withdrawn {:event-id (str (random-uuid))
                :aggregate-id aggregate-id
                :event-type :FundsWithdrawn
                :version version
                :timestamp (str (java.time.Instant/now))
                :event-data {:amount 50 :description "Test withdrawal"}}))

(defn benchmark-single-event-projection
  "Benchmarks projecting a single event to both stores"
  [n event-type]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println (format "║   BENCHMARK: Single Event Projection (%s)" (name event-type)))
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [project-fn (fn []
                     (let [aggregate-id (str "PROJ-" (random-uuid))
                           event (create-projection-test-event aggregate-id 1 event-type)]
                       (projection-service/project-event event)))
        stats (bench/benchmark-fn project-fn n :warmup 5)]
    (bench/print-stats (format "%s Projection" (name event-type)) stats)
    stats))

(defn benchmark-projection-throughput
  "Benchmarks projection throughput with concurrent events"
  [n-events n-threads]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Projection Throughput                 ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Projecting %d events with %d threads\n" n-events n-threads))

  (let [start (bench/now-ms)
        futures (mapv
                  (fn [i]
                    (future
                      (let [aggregate-id (str "PROJ-THROUGHPUT-" i)
                            event (create-projection-test-event aggregate-id 1 :deposited)
                            op-start (bench/now-ms)]
                        (try
                          (projection-service/project-event event)
                          (- (bench/now-ms) op-start)
                          (catch Exception e
                            {:error (.getMessage e)})))))
                  (range n-events))
        results (mapv deref futures)
        successful (filterv number? results)
        failed (filterv map? results)
        total-time (- (bench/now-ms) start)]

    (println (format "Total time:       %s" (bench/format-duration total-time)))
    (println (format "Successful:       %d" (count successful)))
    (println (format "Failed:           %d" (count failed)))
    (println (format "Throughput:       %s" (bench/format-throughput n-events total-time)))

    (when (seq successful)
      (bench/print-stats "Projection Latency" (bench/calculate-stats successful)))

    {:total-time total-time
     :successful (count successful)
     :failed (count failed)
     :stats (when (seq successful) (bench/calculate-stats successful))}))

(defn benchmark-projection-lag
  "Measures end-to-end lag from event write to projection availability"
  [n-operations]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Projection Lag (Eventual Consistency)║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Measuring lag for %d operations\n" n-operations))

  (let [lags (atom [])
        operations (mapv
                     (fn [i]
                       (let [aggregate-id (str "LAG-TEST-" (random-uuid))
                             initial-balance 1000
                             deposit-amount 500

                             write-start (bench/now-ms)
                             _ (event-store/append-event
                                 {:EventId (str (random-uuid))
                                  :AggregateId aggregate-id
                                  :EventType "AccountOpened"
                                  :Version 1
                                  :Timestamp (str (java.time.Instant/now))
                                  :EventData (pr-str {:account-holder "LagTest"
                                                      :account-type "CHECKING"
                                                      :initial-balance initial-balance})})
                             write-time (- (bench/now-ms) write-start)

                             projection-start (bench/now-ms)
                             max-wait-ms 10000
                             check-interval-ms 10]

                         (loop [elapsed 0]
                           (Thread/sleep check-interval-ms)
                           (let [balance (try
                                           (queries/get-account-balance aggregate-id)
                                           (catch Exception _ nil))
                                 current-elapsed (- (bench/now-ms) projection-start)]
                             (cond
                               balance
                               (do
                                 (swap! lags conj current-elapsed)
                                 {:aggregate-id aggregate-id
                                  :write-time write-time
                                  :projection-lag current-elapsed
                                  :success true})

                               (> current-elapsed max-wait-ms)
                               {:aggregate-id aggregate-id
                                :write-time write-time
                                :projection-lag current-elapsed
                                :success false
                                :error "Timeout waiting for projection"}

                               :else
                               (recur current-elapsed))))))
                     (range n-operations))

        successful (filterv :success operations)
        failed (filterv #(not (:success %)) operations)]

    (println (format "Successful:       %d" (count successful)))
    (println (format "Failed:           %d" (count failed)))

    (when (seq @lags)
      (let [lag-stats (bench/calculate-stats @lags)]
        (bench/print-stats "Projection Lag" lag-stats)
        (println (format "\n⚠️  Note: Projection lag directly impacts eventual consistency"))
        (when (> (:mean lag-stats) 100)
          (println "⚠️  WARNING: Mean projection lag exceeds 100ms!"))
        lag-stats))))

(defn benchmark-projection-rebuild
  "Benchmarks rebuilding projections from event history"
  [n-events-per-aggregate n-aggregates]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Projection Rebuild from History      ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Rebuilding %d aggregates with %d events each\n"
                   n-aggregates n-events-per-aggregate))

  (println "Creating event history...")
  (let [aggregates (mapv (fn [i]
                           (let [aggregate-id (str "REBUILD-" i)]
                             (dotimes [v n-events-per-aggregate]
                               (event-store/append-event
                                 {:EventId (str (random-uuid))
                                  :AggregateId aggregate-id
                                  :EventType (if (zero? v) "AccountOpened" "FundsDeposited")
                                  :Version (inc v)
                                  :Timestamp (str (java.time.Instant/now))
                                  :EventData (pr-str (if (zero? v)
                                                       {:account-holder "Rebuild"
                                                        :account-type "CHECKING"
                                                        :initial-balance 1000}
                                                       {:amount 100 :description "Rebuild"}))}))
                             aggregate-id))
                         (range n-aggregates))

        _ (println "Event history created. Starting rebuild...")
        start (bench/now-ms)
        rebuild-times (mapv
                        (fn [aggregate-id]
                          (let [rebuild-start (bench/now-ms)
                                events (event-store/get-events-for-aggregate aggregate-id)
                                _ (doseq [event events]
                                    (projection-service/project-event event))
                                rebuild-time (- (bench/now-ms) rebuild-start)]
                            rebuild-time))
                        aggregates)
        total-time (- (bench/now-ms) start)
        total-events (* n-events-per-aggregate n-aggregates)
        stats (bench/calculate-stats rebuild-times)]

    (println (format "\nTotal time:       %s" (bench/format-duration total-time)))
    (println (format "Total events:     %d" total-events))
    (println (format "Throughput:       %s" (bench/format-throughput total-events total-time)))
    (bench/print-stats "Per-Aggregate Rebuild Time" stats)

    {:total-time total-time
     :total-events total-events
     :per-aggregate-stats stats}))

(defn benchmark-projection-error-handling
  "Tests projection error handling and recovery"
  [n-events]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Projection Error Handling             ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [success-count (atom 0)
        error-count (atom 0)
        retry-count (atom 0)

        project-with-errors (fn [event retry-on-error?]
                              (try
                                (projection-service/project-event event)
                                (swap! success-count inc)
                                :success
                                (catch Exception e
                                  (swap! error-count inc)
                                  (when retry-on-error?
                                    (swap! retry-count inc)
                                    (try
                                      (projection-service/project-event event)
                                      (swap! success-count inc)
                                      :recovered
                                      (catch Exception _
                                        :failed)))
                                  :error)))

        start (bench/now-ms)
        results (mapv (fn [i]
                        (let [aggregate-id (str "ERROR-TEST-" (random-uuid))
                              event (create-projection-test-event aggregate-id 1 :deposited)]
                          (project-with-errors event true)))
                      (range n-events))
        total-time (- (bench/now-ms) start)]

    (println (format "\nTotal time:       %s" (bench/format-duration total-time)))
    (println (format "Total events:     %d" n-events))
    (println (format "Successful:       %d" @success-count))
    (println (format "Errors:           %d" @error-count))
    (println (format "Retries:          %d" @retry-count))
    (println (format "Error rate:       %.2f%%" (* 100.0 (/ @error-count n-events))))

    {:total-time total-time
     :successful @success-count
     :errors @error-count
     :retries @retry-count}))

(defn benchmark-projection-batch-processing
  "Benchmarks batch projection processing"
  [batch-sizes n-batches]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Projection Batch Processing          ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [results (mapv
                  (fn [batch-size]
                    (println (format "\nTesting batch size: %d" batch-size))
                    (let [start (bench/now-ms)
                          _ (dotimes [_ n-batches]
                              (let [events (mapv (fn [i]
                                                   (create-projection-test-event
                                                     (str "BATCH-" (random-uuid))
                                                     1
                                                     :deposited))
                                                 (range batch-size))]
                                (doseq [event events]
                                  (projection-service/project-event event))))
                          total-time (- (bench/now-ms) start)
                          total-events (* batch-size n-batches)
                          throughput (bench/format-throughput total-events total-time)]

                      (println (format "  Time:       %s" (bench/format-duration total-time)))
                      (println (format "  Throughput: %s" throughput))

                      {:batch-size batch-size
                       :total-time total-time
                       :throughput throughput}))
                  batch-sizes)]

    (println "\n=== BATCH PROCESSING SUMMARY ===\n")
    (println "Batch Size | Time         | Throughput")
    (println "-----------|--------------|------------------")
    (doseq [r results]
      (println (format "%-10d | %-12s | %s"
                       (:batch-size r)
                       (bench/format-duration (:total-time r))
                       (:throughput r))))
    results))

(defn run-all-projection-benchmarks
  "Runs all projection benchmarks"
  [& {:keys [n-iterations n-threads n-lag-tests]
      :or {n-iterations 100
           n-threads 10
           n-lag-tests 20}}]

  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║                                                   ║")
  (println "║       CQRS PROJECTION BENCHMARK SUITE             ║")
  (println "║                                                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nConfiguration:"))
  (println (format "  Iterations:       %d" n-iterations))
  (println (format "  Threads:          %d" n-threads))
  (println (format "  Lag Tests:        %d" n-lag-tests))
  (println)

  {:opened-projection (benchmark-single-event-projection n-iterations :opened)
   :deposited-projection (benchmark-single-event-projection n-iterations :deposited)
   :withdrawn-projection (benchmark-single-event-projection n-iterations :withdrawn)
   :projection-throughput (benchmark-projection-throughput (* n-threads 10) n-threads)
   :projection-lag (benchmark-projection-lag n-lag-tests)
   :projection-rebuild (benchmark-projection-rebuild 100 10)
   :error-handling (benchmark-projection-error-handling 50)
   :batch-processing (benchmark-projection-batch-processing [1 10 50 100] 10)})
