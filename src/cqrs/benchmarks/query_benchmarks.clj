(ns cqrs.benchmarks.query-benchmarks
  "Benchmarks for query operations (both DynamoDB and PostgreSQL)"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.queries :as queries]))

(defn benchmark-account-balance-query
  "Benchmarks fast balance lookup (DynamoDB)"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Account Balance Query (DynamoDB)     ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println "Target: <10ms per query\n")

  (let [query-fn (fn []
                   (queries/get-account-balance (bench/random-element account-ids)))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Balance Query" stats)
    (when (> (:mean stats) 10)
      (println "\n⚠️  WARNING: Mean latency exceeds 10ms target!"))
    stats))

(defn benchmark-recent-transactions-query
  "Benchmarks recent transaction query (DynamoDB GSI)"
  [account-ids n limit]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Recent Transactions (DynamoDB GSI)     ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Query limit: %d transactions\n" limit))

  (let [query-fn (fn []
                   (queries/get-recent-transactions
                     (bench/random-element account-ids)
                     limit))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Recent Transactions Query" stats)
    stats))

(defn benchmark-account-details-query
  "Benchmarks account details query (PostgreSQL)"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Account Details (PostgreSQL)         ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [query-fn (fn []
                   (queries/get-account-details (bench/random-element account-ids)))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Account Details Query" stats)
    stats))

(defn benchmark-account-summary-query
  "Benchmarks account summary query with aggregations (PostgreSQL)"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Account Summary (PostgreSQL)         ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [query-fn (fn []
                   (queries/get-account-summary (bench/random-element account-ids)))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Account Summary Query" stats)
    stats))

(defn benchmark-transaction-history-query
  "Benchmarks transaction history query with date range (PostgreSQL)"
  [account-ids n days-back]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Transaction History (PostgreSQL)      ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Date range: Last %d days\n" days-back))

  (let [end-date (java.time.LocalDate/now)
        start-date (.minusDays end-date days-back)
        query-fn (fn []
                   (queries/get-transaction-history
                     (bench/random-element account-ids)
                     start-date
                     end-date
                     0
                     50))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Transaction History Query" stats)
    stats))

(defn benchmark-accounts-by-holder-query
  "Benchmarks accounts by holder query (PostgreSQL)"
  [holders n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Accounts by Holder (PostgreSQL)       ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [query-fn (fn []
                   (queries/get-accounts-by-holder (bench/random-element holders)))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Accounts by Holder Query" stats)
    stats))

(defn benchmark-top-accounts-query
  "Benchmarks top accounts by balance query (PostgreSQL)"
  [n limit]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║   BENCHMARK: Top Accounts by Balance (PostgreSQL) ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Top N: %d accounts\n" limit))

  (let [query-fn (fn []
                   (queries/get-top-accounts-by-balance limit))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Top Accounts Query" stats)
    stats))

(defn benchmark-daily-balance-report-query
  "Benchmarks daily balance report query (PostgreSQL)"
  [account-ids n days-back]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Daily Balance Report (PostgreSQL)     ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Date range: Last %d days\n" days-back))

  (let [end-date (java.time.LocalDate/now)
        start-date (.minusDays end-date days-back)
        query-fn (fn []
                   (queries/get-daily-balance-report
                     (bench/random-element account-ids)
                     start-date
                     end-date))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Daily Balance Report Query" stats)
    stats))

(defn benchmark-search-transactions-query
  "Benchmarks transaction search with filters (PostgreSQL)"
  [account-ids n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Search Transactions (PostgreSQL)      ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [filters {:transaction-type "DEPOSIT"
                 :min-amount 100
                 :max-amount 5000}
        query-fn (fn []
                   (queries/search-transactions
                     (bench/random-element account-ids)
                     filters))
        stats (bench/benchmark-fn query-fn n :warmup 10)]
    (bench/print-stats "Search Transactions Query" stats)
    stats))

(defn benchmark-transaction-volume-report-query
  "Benchmarks transaction volume report (PostgreSQL)"
  [n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Transaction Volume Report (PostgreSQL)║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [start-date (.minusDays (java.time.LocalDate/now) 30)
        end-date (java.time.LocalDate/now)
        query-fn (fn []
                   (queries/get-transaction-volume-report start-date end-date))
        stats (bench/benchmark-fn query-fn n :warmup 5)]
    (bench/print-stats "Transaction Volume Report Query" stats)
    stats))

(defn benchmark-account-activity-report-query
  "Benchmarks account activity report (PostgreSQL)"
  [n]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║  BENCHMARK: Account Activity Report (PostgreSQL)  ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [start-date (.minusDays (java.time.LocalDate/now) 30)
        end-date (java.time.LocalDate/now)
        query-fn (fn []
                   (queries/get-account-activity-report start-date end-date 10))
        stats (bench/benchmark-fn query-fn n :warmup 5)]
    (bench/print-stats "Account Activity Report Query" stats)
    stats))

(defn benchmark-concurrent-queries
  "Tests query throughput under concurrent load"
  [account-ids n-threads duration-ms query-type]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println (format "║   BENCHMARK: Concurrent %s Queries" (name query-type)))
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "Testing with %d concurrent threads\n" n-threads))

  (let [query-fn (case query-type
                   :balance
                   #(queries/get-account-balance (bench/random-element account-ids))

                   :details
                   #(queries/get-account-details (bench/random-element account-ids))

                   :summary
                   #(queries/get-account-summary (bench/random-element account-ids))

                   :mixed
                   (fn []
                     (case (rand-int 3)
                       0 (queries/get-account-balance (bench/random-element account-ids))
                       1 (queries/get-account-details (bench/random-element account-ids))
                       2 (queries/get-recent-transactions (bench/random-element account-ids) 10))))

        results (bench/concurrent-benchmark query-fn n-threads duration-ms)]
    (bench/print-concurrent-results results)
    results))

(defn benchmark-query-cold-vs-warm
  "Compares cold vs warm cache performance"
  [account-id query-fn query-name]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println (format "║   BENCHMARK: Cold vs Warm Cache - %s" query-name))
  (println "╚═══════════════════════════════════════════════════════╝")

  (println "\nMeasuring cold query (first run)...")
  (let [cold-start (bench/now-ms)
        _ (query-fn)
        cold-time (- (bench/now-ms) cold-start)]

    (println "\nMeasuring warm queries (after cache)...")
    (let [warm-times (atom [])
          _ (dotimes [_ 10]
              (let [start (bench/now-ms)
                    _ (query-fn)
                    duration (- (bench/now-ms) start)]
                (swap! warm-times conj duration)))
          warm-stats (bench/calculate-stats @warm-times)]

      (println (format "\nCold query:       %s" (bench/format-duration cold-time)))
      (println (format "Warm query mean:  %s" (bench/format-duration (:mean warm-stats))))
      (println (format "Speed-up:         %.2fx faster" (/ cold-time (:mean warm-stats))))

      {:cold-time cold-time
       :warm-stats warm-stats})))

(defn benchmark-query-scaling
  "Tests query performance with increasing data volumes"
  [query-fn query-name data-sizes]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println (format "║   BENCHMARK: Query Scaling - %s" query-name))
  (println "╚═══════════════════════════════════════════════════════╝")

  (println "\nTesting query performance at different data scales...\n")
  (let [results (mapv (fn [size]
                        (println (format "Testing with %d records..." size))
                        (let [stats (bench/benchmark-fn #(query-fn size) 20 :warmup 3)]
                          (assoc stats :data-size size)))
                      data-sizes)]

    (println "\n=== QUERY SCALING SUMMARY ===\n")
    (println "Data Size  | Mean Latency | P95 Latency")
    (println "-----------|--------------|-------------")
    (doseq [r results]
      (println (format "%-10d | %-12s | %s"
                       (:data-size r)
                       (bench/format-duration (:mean r))
                       (bench/format-duration (:p95 r)))))
    results))

(defn run-query-load-test
  "Runs comprehensive load test on queries"
  [account-ids concurrency-levels duration-per-level-ms]
  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║             QUERY LOAD TEST                        ║")
  (println "╚═══════════════════════════════════════════════════════╝")

  (let [mixed-query-fn (fn []
                         (try
                           (case (rand-int 10)
                             (0 1 2 3)
                             (queries/get-account-balance (bench/random-element account-ids))

                             (4 5)
                             (queries/get-recent-transactions (bench/random-element account-ids) 10)

                             (6 7)
                             (queries/get-account-details (bench/random-element account-ids))

                             8
                             (queries/get-account-summary (bench/random-element account-ids))

                             9
                             (queries/get-top-accounts-by-balance 10))
                           (catch Exception _
                             nil)))

        results (bench/run-load-test mixed-query-fn concurrency-levels duration-per-level-ms)]
    (bench/print-load-test-results results)
    results))

(defn run-all-query-benchmarks
  "Runs all query benchmarks"
  [& {:keys [account-ids holders n-iterations n-threads duration-ms concurrency-levels]
      :or {n-iterations 100
           n-threads 10
           duration-ms 5000
           concurrency-levels [1 5 10 20 50 100]}}]

  (println "\n╔═══════════════════════════════════════════════════════╗")
  (println "║                                                   ║")
  (println "║          CQRS QUERY BENCHMARK SUITE               ║")
  (println "║                                                   ║")
  (println "╚═══════════════════════════════════════════════════════╝")
  (println (format "\nConfiguration:"))
  (println (format "  Accounts:         %d" (count account-ids)))
  (println (format "  Iterations:       %d" n-iterations))
  (println (format "  Threads:          %d" n-threads))
  (println (format "  Duration:         %s" (bench/format-duration duration-ms)))
  (println)

  (let [test-account-id (first account-ids)]
    {:balance-query (benchmark-account-balance-query account-ids n-iterations)
     :recent-transactions (benchmark-recent-transactions-query account-ids n-iterations 10)
     :account-details (benchmark-account-details-query account-ids n-iterations)
     :account-summary (benchmark-account-summary-query account-ids n-iterations)
     :transaction-history (benchmark-transaction-history-query account-ids n-iterations 30)
     :accounts-by-holder (benchmark-accounts-by-holder-query holders n-iterations)
     :top-accounts (benchmark-top-accounts-query n-iterations 10)
     :daily-balance-report (benchmark-daily-balance-report-query account-ids n-iterations 30)
     :search-transactions (benchmark-search-transactions-query account-ids n-iterations)
     :transaction-volume-report (benchmark-transaction-volume-report-query 20)
     :account-activity-report (benchmark-account-activity-report-query 20)
     :concurrent-balance (benchmark-concurrent-queries account-ids n-threads duration-ms :balance)
     :concurrent-details (benchmark-concurrent-queries account-ids n-threads duration-ms :details)
     :concurrent-mixed (benchmark-concurrent-queries account-ids n-threads duration-ms :mixed)
     :cold-vs-warm (benchmark-query-cold-vs-warm
                     test-account-id
                     #(queries/get-account-details test-account-id)
                     "Account Details")
     :load-test (run-query-load-test account-ids concurrency-levels duration-ms)}))
