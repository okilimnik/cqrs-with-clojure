(ns cqrs.benchmarks.command-benchmarks
  "Benchmarks for command processing in the CQRS system"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.application.bank-account-service :as service]
            [cqrs.schema :as schema]))

(defn setup-test-accounts
  "Creates test accounts for benchmarking"
  [n initial-balance]
  (println (format "Setting up %d test accounts..." n))
  (let [account-ids (atom [])]
    (dotimes [i n]
      (when (zero? (mod i (max 1 (quot n 10))))
        (println (format "  Created: %d/%d" i n)))
      (let [holder (format "TestHolder-%06d" i)
            account-type (rand-nth ["CHECKING" "SAVINGS"])
            result (service/open-account holder account-type initial-balance "Initial deposit")]
        (swap! account-ids conj (:aggregate-id result))))
    (println "Setup complete!")
    @account-ids))

(defn benchmark-account-creation
  "Benchmarks account creation performance"
  [n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Account Creation                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [account-fn (fn []
                     (service/open-account
                       (str "BenchUser-" (random-uuid))
                       (rand-nth ["CHECKING" "SAVINGS"])
                       1000
                       "Initial deposit"))
        stats (bench/benchmark-fn account-fn n :warmup 5)]
    (bench/print-stats "Account Creation" stats)
    stats))

(defn benchmark-deposits
  "Benchmarks deposit operations"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Deposit Operations                 ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [deposit-fn (fn []
                     (service/deposit-funds
                       (bench/random-element account-ids)
                       (+ 10 (rand-int 1000))
                       "Benchmark deposit"))
        stats (bench/benchmark-fn deposit-fn n :warmup 10)]
    (bench/print-stats "Deposits" stats)
    stats))

(defn benchmark-withdrawals
  "Benchmarks withdrawal operations"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Withdrawal Operations              ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [withdraw-fn (fn []
                      (try
                        (service/withdraw-funds
                          (bench/random-element account-ids)
                          (+ 5 (rand-int 100))
                          "Benchmark withdrawal")
                        (catch Exception _
                          nil)))
        stats (bench/benchmark-fn withdraw-fn n :warmup 10)]
    (bench/print-stats "Withdrawals" stats)
    stats))

(defn benchmark-transfers
  "Benchmarks fund transfer operations (atomic 2-event transactions)"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║     BENCHMARK: Fund Transfers (CRITICAL)          ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [transfer-fn (fn []
                      (let [from-id (bench/random-element account-ids)
                            to-id (bench/random-element account-ids)]
                        (when (not= from-id to-id)
                          (try
                            (service/transfer-funds
                              from-id
                              to-id
                              (+ 1 (rand-int 50))
                              "Benchmark transfer")
                            (catch Exception _
                              nil)))))
        stats (bench/benchmark-fn transfer-fn n :warmup 10)]
    (bench/print-stats "Transfers" stats)
    stats))

(defn benchmark-concurrent-same-account
  "Tests optimistic locking by hammering a single account concurrently"
  [account-id n-threads duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Concurrent Ops on Same Account       ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing optimistic locking with %d threads on account: %s\n" n-threads account-id))

  (let [op-fn (fn []
                (try
                  (if (< (rand) 0.5)
                    (service/deposit-funds account-id 10 "Concurrent deposit")
                    (service/withdraw-funds account-id 5 "Concurrent withdrawal"))
                  (catch Exception _
                    nil)))
        results (bench/concurrent-benchmark op-fn n-threads duration-ms)]
    (bench/print-concurrent-results results)
    results))

(defn benchmark-concurrent-different-accounts
  "Tests throughput with concurrent operations on different accounts"
  [account-ids n-threads duration-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Concurrent Ops on Different Accounts ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing throughput with %d threads across %d accounts\n"
                   n-threads (count account-ids)))

  (let [op-fn (fn []
                (try
                  (let [account-id (bench/random-element account-ids)]
                    (case (rand-int 3)
                      0 (service/deposit-funds account-id 100 "Load test deposit")
                      1 (service/withdraw-funds account-id 10 "Load test withdrawal")
                      2 (let [to-id (bench/random-element account-ids)]
                          (when (not= account-id to-id)
                            (service/transfer-funds account-id to-id 5 "Load test transfer")))))
                  (catch Exception _
                    nil)))
        results (bench/concurrent-benchmark op-fn n-threads duration-ms)]
    (bench/print-concurrent-results results)
    results))

(defn benchmark-bulk-transfers
  "Tests atomic transaction performance with bulk transfers"
  [account-ids n-transfers]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Bulk Concurrent Transfers            ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Executing %d concurrent transfers...\n" n-transfers))

  (let [start (bench/now-ms)
        transfer-futures
        (mapv (fn [_]
                (future
                  (let [from-id (bench/random-element account-ids)
                        to-id (bench/random-element account-ids)]
                    (when (not= from-id to-id)
                      (try
                        (let [op-start (bench/now-ms)]
                          (service/transfer-funds from-id to-id 10 "Bulk transfer")
                          (- (bench/now-ms) op-start))
                        (catch Exception e
                          {:error (.getMessage e)}))))))
              (range n-transfers))

        results (mapv deref transfer-futures)
        successful (filterv number? results)
        failed (filterv map? results)
        total-time (- (bench/now-ms) start)]

    (println (format "Total time:       %s" (bench/format-duration total-time)))
    (println (format "Successful:       %d" (count successful)))
    (println (format "Failed:           %d" (count failed)))
    (println (format "Success rate:     %.2f%%" (* 100.0 (/ (count successful) n-transfers))))
    (println (format "Throughput:       %s" (bench/format-throughput n-transfers total-time)))

    (when (seq successful)
      (bench/print-stats "Transfer Latency" (bench/calculate-stats successful)))

    {:total-time total-time
     :successful (count successful)
     :failed (count failed)
     :stats (when (seq successful) (bench/calculate-stats successful))}))

(defn run-command-load-test
  "Runs a comprehensive load test on command processing"
  [account-ids concurrency-levels duration-per-level-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║          COMMAND PROCESSING LOAD TEST             ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [mixed-op-fn (fn []
                      (try
                        (let [account-id (bench/random-element account-ids)
                              op-type (rand-int 10)]
                          (cond
                            (< op-type 4)
                            (service/deposit-funds account-id (+ 10 (rand-int 500)) "Load test")

                            (< op-type 7)
                            (service/withdraw-funds account-id (+ 5 (rand-int 100)) "Load test")

                            :else
                            (let [to-id (bench/random-element account-ids)]
                              (when (not= account-id to-id)
                                (service/transfer-funds account-id to-id (+ 1 (rand-int 50)) "Load test")))))
                        (catch Exception _
                          nil)))

        results (bench/run-load-test mixed-op-fn concurrency-levels duration-per-level-ms)]
    (bench/print-load-test-results results)
    results))

(defn run-all-command-benchmarks
  "Runs all command benchmarks"
  [& {:keys [n-accounts initial-balance n-iterations n-threads duration-ms concurrency-levels]
      :or {n-accounts 100
           initial-balance 10000
           n-iterations 100
           n-threads 10
           duration-ms 5000
           concurrency-levels [1 5 10 20 50]}}]

  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║                                                   ║")
  (println "║          CQRS COMMAND BENCHMARK SUITE             ║")
  (println "║                                                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nConfiguration:"))
  (println (format "  Accounts:         %d" n-accounts))
  (println (format "  Initial Balance:  $%d" initial-balance))
  (println (format "  Iterations:       %d" n-iterations))
  (println (format "  Threads:          %d" n-threads))
  (println (format "  Duration:         %s" (bench/format-duration duration-ms)))
  (println)

  (let [account-ids (setup-test-accounts n-accounts initial-balance)
        test-account-id (first account-ids)]

    {:account-creation (benchmark-account-creation n-iterations)
     :deposits (benchmark-deposits account-ids n-iterations)
     :withdrawals (benchmark-withdrawals account-ids n-iterations)
     :transfers (benchmark-transfers account-ids n-iterations)
     :concurrent-same-account (benchmark-concurrent-same-account
                                 test-account-id n-threads duration-ms)
     :concurrent-different-accounts (benchmark-concurrent-different-accounts
                                      account-ids n-threads duration-ms)
     :bulk-transfers (benchmark-bulk-transfers account-ids (* n-threads 10))
     :load-test (run-command-load-test account-ids concurrency-levels duration-ms)}))
