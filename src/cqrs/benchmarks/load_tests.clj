(ns cqrs.benchmarks.load-tests
  "Comprehensive load testing suite for the CQRS system"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.application.bank-account-service :as service]
            [cqrs.queries :as queries]))

(defn realistic-workload-mix
  "Simulates a realistic banking workload distribution"
  [account-ids]
  (fn []
    (try
      (let [op-type (rand-int 100)
            account-id (bench/random-element account-ids)]
        (cond
          (< op-type 50)
          (service/get-account-balance account-id)

          (< op-type 70)
          (service/deposit-funds account-id (+ 10 (rand-int 1000)) "Load test deposit")

          (< op-type 85)
          (service/get-recent-transactions account-id 10)

          (< op-type 92)
          (service/withdraw-funds account-id (+ 5 (rand-int 200)) "Load test withdrawal")

          (< op-type 96)
          (let [to-id (bench/random-element account-ids)]
            (when (not= account-id to-id)
              (service/transfer-funds account-id to-id (+ 1 (rand-int 100)) "Load test transfer")))

          :else
          (queries/get-account-details account-id)))
      (catch Exception _
        nil))))

(defn write-heavy-workload
  "Simulates a write-heavy workload (80% writes)"
  [account-ids]
  (fn []
    (try
      (let [op-type (rand-int 100)
            account-id (bench/random-element account-ids)]
        (cond
          (< op-type 40)
          (service/deposit-funds account-id (+ 10 (rand-int 1000)) "Write heavy test")

          (< op-type 70)
          (service/withdraw-funds account-id (+ 5 (rand-int 200)) "Write heavy test")

          (< op-type 80)
          (let [to-id (bench/random-element account-ids)]
            (when (not= account-id to-id)
              (service/transfer-funds account-id to-id (+ 1 (rand-int 100)) "Write heavy test")))

          :else
          (service/get-account-balance account-id)))
      (catch Exception _
        nil))))

(defn read-heavy-workload
  "Simulates a read-heavy workload (90% reads)"
  [account-ids]
  (fn []
    (try
      (let [op-type (rand-int 100)
            account-id (bench/random-element account-ids)]
        (cond
          (< op-type 40)
          (service/get-account-balance account-id)

          (< op-type 70)
          (service/get-recent-transactions account-id 10)

          (< op-type 85)
          (queries/get-account-details account-id)

          (< op-type 90)
          (queries/get-account-summary account-id)

          (< op-type 95)
          (service/deposit-funds account-id (+ 10 (rand-int 500)) "Read heavy test")

          :else
          (service/withdraw-funds account-id (+ 5 (rand-int 100)) "Read heavy test")))
      (catch Exception _
        nil))))

(defn spike-test
  "Tests system behavior under sudden load spike"
  [account-ids base-threads spike-threads spike-duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   LOAD TEST: Spike Test                            ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Base load: %d threads" base-threads))
  (println (format "Spike load: %d threads for %s\n"
                   spike-threads (bench/format-duration spike-duration-ms)))

  (let [workload-fn (realistic-workload-mix account-ids)
        running (atom true)
        base-ops (atom 0)
        spike-ops (atom 0)
        errors (atom 0)

        base-workers (mapv (fn [_]
                             (future
                               (while @running
                                 (try
                                   (workload-fn)
                                   (swap! base-ops inc)
                                   (catch Exception _
                                     (swap! errors inc))))))
                           (range base-threads))

        _ (println "Base load established. Starting spike in 2 seconds...")
        _ (Thread/sleep 2000)
        spike-start (bench/now-ms)

        spike-workers (mapv (fn [_]
                              (future
                                (let [deadline (+ spike-start spike-duration-ms)]
                                  (while (< (bench/now-ms) deadline)
                                    (try
                                      (workload-fn)
                                      (swap! spike-ops inc)
                                      (catch Exception _
                                        (swap! errors inc)))))))
                            (range spike-threads))

        _ (doseq [f spike-workers] @f)
        spike-end (bench/now-ms)

        _ (println "Spike complete. Letting base load continue for 2 more seconds...")
        _ (Thread/sleep 2000)
        _ (reset! running false)
        _ (doseq [f base-workers] @f)

        total-time (- (bench/now-ms) spike-start)
        total-ops (+ @base-ops @spike-ops)]

    (println (format "\nSpike duration:   %s" (bench/format-duration (- spike-end spike-start))))
    (println (format "Total ops:        %d" total-ops))
    (println (format "Base ops:         %d" @base-ops))
    (println (format "Spike ops:        %d" @spike-ops))
    (println (format "Errors:           %d" @errors))
    (println (format "Error rate:       %.2f%%" (* 100.0 (/ @errors total-ops))))
    (println (format "Peak throughput:  %s"
                     (bench/format-throughput @spike-ops (- spike-end spike-start))))

    {:total-ops total-ops
     :spike-ops @spike-ops
     :errors @errors
     :spike-duration (- spike-end spike-start)}))

(defn endurance-test
  "Tests system stability under sustained load"
  [account-ids n-threads duration-minutes]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   LOAD TEST: Endurance Test                        ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing %d threads for %d minutes\n" n-threads duration-minutes))

  (let [duration-ms (* duration-minutes 60 1000)
        workload-fn (realistic-workload-mix account-ids)
        interval-ms 10000
        checkpoints (atom [])
        running (atom true)
        ops-count (atom 0)
        error-count (atom 0)

        checkpoint-thread (future
                            (let [start (bench/now-ms)]
                              (while @running
                                (Thread/sleep interval-ms)
                                (let [elapsed (- (bench/now-ms) start)
                                      ops @ops-count
                                      errors @error-count]
                                  (swap! checkpoints conj
                                         {:elapsed elapsed
                                          :ops ops
                                          :errors errors})
                                  (println (format "[%s] Ops: %d | Errors: %d | Throughput: %s"
                                                 (bench/format-duration elapsed)
                                                 ops
                                                 errors
                                                 (bench/format-throughput ops elapsed)))))))

        workers (mapv (fn [_]
                        (future
                          (let [deadline (+ (bench/now-ms) duration-ms)]
                            (while (< (bench/now-ms) deadline)
                              (try
                                (workload-fn)
                                (swap! ops-count inc)
                                (catch Exception _
                                  (swap! error-count inc)))))))
                      (range n-threads))

        _ (doseq [f workers] @f)
        _ (reset! running false)
        _ @checkpoint-thread]

    (println "\n=== ENDURANCE TEST SUMMARY ===")
    (println (format "Total duration:   %d minutes" duration-minutes))
    (println (format "Total ops:        %d" @ops-count))
    (println (format "Total errors:     %d" @error-count))
    (println (format "Avg throughput:   %s" (bench/format-throughput @ops-count duration-ms)))
    (println (format "Error rate:       %.2f%%" (* 100.0 (/ @error-count @ops-count))))

    {:duration-ms duration-ms
     :total-ops @ops-count
     :total-errors @error-count
     :checkpoints @checkpoints}))

(defn stress-test
  "Pushes system to its limits by gradually increasing load"
  [account-ids initial-threads max-threads step-size step-duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   LOAD TEST: Stress Test                           ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Ramping from %d to %d threads in steps of %d"
                   initial-threads max-threads step-size))
  (println (format "Step duration: %s\n" (bench/format-duration step-duration-ms)))

  (let [workload-fn (realistic-workload-mix account-ids)
        thread-levels (range initial-threads (inc max-threads) step-size)
        results (atom [])]

    (doseq [n-threads thread-levels]
      (println (format "\n--- Testing with %d threads ---" n-threads))
      (let [result (bench/concurrent-benchmark workload-fn n-threads step-duration-ms)]
        (swap! results conj (assoc result :threads n-threads))
        (bench/print-concurrent-results result)

        (when (> (:errors result) (* 0.1 (:operations result)))
          (println "\n⚠️  ERROR THRESHOLD EXCEEDED (>10%)")
          (println "⚠️  System breaking point may have been reached"))))

    (println "\n=== STRESS TEST SUMMARY ===\n")
    (println "Threads | Operations | Throughput      | Mean Latency | Errors | Error Rate")
    (println "--------|------------|-----------------|--------------|--------|------------")
    (doseq [r @results]
      (let [error-rate (* 100.0 (/ (:errors r) (max 1 (+ (:operations r) (:errors r)))))]
        (println (format "%-7d | %-10d | %-15s | %-12s | %-6d | %.2f%%"
                         (:threads r)
                         (:operations r)
                         (:throughput r)
                         (bench/format-duration (get-in r [:stats :mean] 0))
                         (:errors r)
                         error-rate))))

    @results))

(defn workload-comparison-test
  "Compares system performance under different workload types"
  [account-ids n-threads duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   LOAD TEST: Workload Comparison                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing %d threads for %s per workload\n"
                   n-threads (bench/format-duration duration-ms)))

  (let [workloads {:realistic (realistic-workload-mix account-ids)
                   :write-heavy (write-heavy-workload account-ids)
                   :read-heavy (read-heavy-workload account-ids)}]

    (doseq [[workload-name workload-fn] workloads]
      (println (format "\n--- Testing %s workload ---" (name workload-name)))
      (let [result (bench/concurrent-benchmark workload-fn n-threads duration-ms)]
        (bench/print-concurrent-results result)))

    :completed))

(defn hot-account-test
  "Tests performance when many threads access the same 'hot' accounts"
  [hot-accounts cold-accounts n-threads duration-ms hot-probability]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   LOAD TEST: Hot Account Test                      ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Hot accounts: %d (accessed %.0f%% of the time)"
                   (count hot-accounts) (* 100 hot-probability)))
  (println (format "Cold accounts: %d" (count cold-accounts)))
  (println (format "Testing with %d threads for %s\n"
                   n-threads (bench/format-duration duration-ms)))

  (let [workload-fn (fn []
                      (try
                        (let [use-hot? (< (rand) hot-probability)
                              account-id (if use-hot?
                                           (bench/random-element hot-accounts)
                                           (bench/random-element cold-accounts))]
                          (case (rand-int 3)
                            0 (service/deposit-funds account-id 100 "Hot test")
                            1 (service/withdraw-funds account-id 50 "Hot test")
                            2 (service/get-account-balance account-id)))
                        (catch Exception _
                          nil)))
        result (bench/concurrent-benchmark workload-fn n-threads duration-ms)]

    (bench/print-concurrent-results result)
    (println (format "\nNote: High error rate expected due to optimistic locking conflicts"))
    result))

(defn run-comprehensive-load-tests
  "Runs a comprehensive suite of load tests"
  [& {:keys [account-ids n-threads duration-ms]
      :or {n-threads 20
           duration-ms 10000}}]

  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║                                                   ║")
  (println "║       CQRS COMPREHENSIVE LOAD TEST SUITE          ║")
  (println "║                                                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nConfiguration:"))
  (println (format "  Accounts:         %d" (count account-ids)))
  (println (format "  Base Threads:     %d" n-threads))
  (println (format "  Test Duration:    %s" (bench/format-duration duration-ms)))
  (println)

  (let [hot-accounts (take 5 account-ids)
        cold-accounts (drop 5 account-ids)]

    {:workload-comparison (workload-comparison-test account-ids n-threads duration-ms)
     :spike-test (spike-test account-ids n-threads (* n-threads 2) duration-ms)
     :stress-test (stress-test account-ids 5 (* n-threads 3) 5 duration-ms)
     :hot-account-test (hot-account-test hot-accounts cold-accounts n-threads duration-ms 0.8)
     :realistic-load (bench/concurrent-benchmark
                       (realistic-workload-mix account-ids)
                       n-threads
                       duration-ms)}))

(defn run-quick-load-test
  "Runs a quick load test for rapid iteration"
  [account-ids]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   QUICK LOAD TEST (30 seconds)                     ║")
  (println "╚═══════════════════════════════════════════════════════╝\n")

  (let [workload-fn (realistic-workload-mix account-ids)
        result (bench/concurrent-benchmark workload-fn 10 30000)]
    (bench/print-concurrent-results result)
    result))
