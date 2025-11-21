(ns cqrs.benchmarks.runner
  "Main benchmark runner and reporting"
  (:require [cqrs.benchmarks.core :as bench]
            [cqrs.benchmarks.command-benchmarks :as cmd-bench]
            [cqrs.benchmarks.event-store-benchmarks :as es-bench]
            [cqrs.benchmarks.query-benchmarks :as query-bench]
            [cqrs.benchmarks.projection-benchmarks :as proj-bench]
            [cqrs.benchmarks.aggregate-benchmarks :as agg-bench]
            [cqrs.benchmarks.load-tests :as load-tests]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(defn format-timestamp []
  (.format (LocalDateTime/now)
           (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")))

(defn print-banner []
  (println)
  (println "╔═══════════════════════════════════════════════════════════════════════╗")
  (println "║                                                                       ║")
  (println "║                    CQRS SYSTEM BENCHMARK SUITE                        ║")
  (println "║                                                                       ║")
  (println "║  Comprehensive performance testing for Event Sourced CQRS System     ║")
  (println "║                                                                       ║")
  (println "╚═══════════════════════════════════════════════════════════════════════╝")
  (println))

(defn print-suite-summary [suite-name results elapsed-ms]
  (println)
  (println "═══════════════════════════════════════════════════════════")
  (println (format "  %s COMPLETE" suite-name))
  (println "═══════════════════════════════════════════════════════════")
  (println (format "  Duration: %s" (bench/format-duration elapsed-ms)))
  (println (format "  Tests run: %d" (count results)))
  (println "═══════════════════════════════════════════════════════════")
  (println))

(defn run-benchmark-suite [suite-name suite-fn]
  (println (format "\n▶ Starting %s..." suite-name))
  (println "───────────────────────────────────────────────────────────")
  (let [start (bench/now-ms)
        results (suite-fn)
        elapsed (- (bench/now-ms) start)]
    (print-suite-summary suite-name results elapsed)
    {:suite suite-name
     :results results
     :duration-ms elapsed}))

(defn generate-summary-report [all-results]
  (println)
  (println "╔═══════════════════════════════════════════════════════════════════════╗")
  (println "║                                                                       ║")
  (println "║                       BENCHMARK SUMMARY REPORT                        ║")
  (println "║                                                                       ║")
  (println "╚═══════════════════════════════════════════════════════════════════════╝")
  (println)

  (let [total-duration (reduce + (map :duration-ms all-results))]
    (println (format "Total benchmark time: %s" (bench/format-duration total-duration)))
    (println)
    (println "Suite Results:")
    (println "───────────────────────────────────────────────────────────")
    (doseq [{:keys [suite duration-ms results]} all-results]
      (println (format "  %-30s %s (%d tests)"
                       suite
                       (bench/format-duration duration-ms)
                       (if (map? results) (count results) 1))))
    (println)))

(defn save-results-to-file [results filename]
  (let [output-dir "benchmark-results"
        _ (io/make-parents (str output-dir "/dummy"))
        filepath (str output-dir "/" filename)]
    (spit filepath (with-out-str (clojure.pprint/pprint results)))
    (println (format "\n📊 Results saved to: %s" filepath))
    filepath))

(defn generate-html-report [results timestamp]
  (let [html (str
"<!DOCTYPE html>
<html>
<head>
    <title>CQRS Benchmark Report - " timestamp "</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        h1 { color: #2c3e50; border-bottom: 3px solid #3498db; padding-bottom: 10px; }
        h2 { color: #34495e; margin-top: 30px; border-left: 4px solid #3498db; padding-left: 15px; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; }
        th { background: #3498db; color: white; padding: 12px; text-align: left; }
        td { padding: 10px; border-bottom: 1px solid #ddd; }
        tr:hover { background: #f8f9fa; }
        .metric { display: inline-block; margin: 10px 20px 10px 0; padding: 15px; background: #ecf0f1; border-radius: 5px; }
        .metric-label { font-size: 12px; color: #7f8c8d; text-transform: uppercase; }
        .metric-value { font-size: 24px; font-weight: bold; color: #2c3e50; }
        .warning { color: #e74c3c; font-weight: bold; }
        .good { color: #27ae60; font-weight: bold; }
        .timestamp { color: #7f8c8d; font-size: 14px; }
    </style>
</head>
<body>
    <div class='container'>
        <h1>CQRS Benchmark Report</h1>
        <p class='timestamp'>Generated: " timestamp "</p>

        <h2>Summary</h2>
        <div class='metric'>
            <div class='metric-label'>Total Duration</div>
            <div class='metric-value'>" (bench/format-duration (reduce + (map :duration-ms results))) "</div>
        </div>
        <div class='metric'>
            <div class='metric-label'>Test Suites</div>
            <div class='metric-value'>" (count results) "</div>
        </div>

        <h2>Suite Results</h2>
        <table>
            <tr><th>Suite</th><th>Duration</th><th>Tests</th></tr>"
            (apply str
                   (for [{:keys [suite duration-ms results]} results]
                     (str "<tr><td>" suite "</td><td>" (bench/format-duration duration-ms)
                          "</td><td>" (if (map? results) (count results) 1) "</td></tr>")))
"        </table>
    </div>
</body>
</html>")]

    (let [output-dir "benchmark-results"
          filepath (str output-dir "/report-" timestamp ".html")]
      (spit filepath html)
      (println (format "📊 HTML report saved to: %s" filepath))
      filepath)))

(defn run-all-benchmarks
  "Runs the complete benchmark suite"
  [& {:keys [n-accounts initial-balance n-iterations n-threads duration-ms
             quick-mode]
      :or {n-accounts 100
           initial-balance 10000
           n-iterations 100
           n-threads 10
           duration-ms 5000
           quick-mode false}}]

  (print-banner)

  (println "═══════════════════════════════════════════════════════════")
  (println "  CONFIGURATION")
  (println "═══════════════════════════════════════════════════════════")
  (println (format "  Mode:             %s" (if quick-mode "QUICK" "FULL")))
  (println (format "  Test Accounts:    %d" n-accounts))
  (println (format "  Initial Balance:  $%d" initial-balance))
  (println (format "  Iterations:       %d" n-iterations))
  (println (format "  Threads:          %d" n-threads))
  (println (format "  Duration:         %s" (bench/format-duration duration-ms)))
  (println "═══════════════════════════════════════════════════════════")

  (let [timestamp (format-timestamp)
        start-time (bench/now-ms)

        results
        (if quick-mode
          [(run-benchmark-suite
             "Quick Smoke Test"
             #(do
                (println "Running quick validation benchmarks...")
                (let [account-ids (cmd-bench/setup-test-accounts 20 initial-balance)
                      holders (mapv #(str "Holder-" %) (range 20))]
                  {:commands (cmd-bench/benchmark-deposits account-ids 20)
                   :queries (query-bench/benchmark-account-balance-query account-ids 20)
                   :load-test (load-tests/run-quick-load-test account-ids)})))]

          [(run-benchmark-suite
             "Command Processing Benchmarks"
             #(cmd-bench/run-all-command-benchmarks
                :n-accounts n-accounts
                :initial-balance initial-balance
                :n-iterations n-iterations
                :n-threads n-threads
                :duration-ms duration-ms))

           (run-benchmark-suite
             "Event Store Benchmarks"
             #(es-bench/run-all-event-store-benchmarks
                :n-iterations n-iterations
                :n-threads n-threads
                :duration-ms duration-ms))

           (run-benchmark-suite
             "Query Performance Benchmarks"
             (fn []
               (let [account-ids (cmd-bench/setup-test-accounts n-accounts initial-balance)
                     holders (mapv #(str "Holder-" %) (range (quot n-accounts 10)))]
                 (query-bench/run-all-query-benchmarks
                   :account-ids account-ids
                   :holders holders
                   :n-iterations n-iterations
                   :n-threads n-threads
                   :duration-ms duration-ms))))

           (run-benchmark-suite
             "Projection Benchmarks"
             #(proj-bench/run-all-projection-benchmarks
                :n-iterations n-iterations
                :n-threads n-threads))

           (run-benchmark-suite
             "Aggregate Reconstitution Benchmarks"
             #(agg-bench/run-all-aggregate-benchmarks
                :n-iterations n-iterations))

           (run-benchmark-suite
             "Load Tests"
             (fn []
               (let [account-ids (cmd-bench/setup-test-accounts n-accounts initial-balance)]
                 (load-tests/run-comprehensive-load-tests
                   :account-ids account-ids
                   :n-threads n-threads
                   :duration-ms duration-ms))))])

        total-time (- (bench/now-ms) start-time)]

    (generate-summary-report results)

    (let [output-data {:timestamp timestamp
                       :configuration {:n-accounts n-accounts
                                       :initial-balance initial-balance
                                       :n-iterations n-iterations
                                       :n-threads n-threads
                                       :duration-ms duration-ms
                                       :quick-mode quick-mode}
                       :results results
                       :total-duration-ms total-time}]

      (save-results-to-file output-data (str "benchmark-" timestamp ".edn"))
      (generate-html-report results timestamp)

      (println)
      (println "╔═══════════════════════════════════════════════════════════════════════╗")
      (println "║                                                                       ║")
      (println "║                    🎉 ALL BENCHMARKS COMPLETE! 🎉                     ║")
      (println "║                                                                       ║")
      (println "╚═══════════════════════════════════════════════════════════════════════╝")
      (println)
      (println (format "Total execution time: %s" (bench/format-duration total-time)))
      (println)

      output-data)))

(defn run-quick-benchmarks
  "Runs a quick subset of benchmarks for rapid iteration"
  []
  (run-all-benchmarks
    :n-accounts 20
    :initial-balance 5000
    :n-iterations 20
    :n-threads 5
    :duration-ms 3000
    :quick-mode true))

(defn run-standard-benchmarks
  "Runs the standard benchmark suite"
  []
  (run-all-benchmarks
    :n-accounts 100
    :initial-balance 10000
    :n-iterations 100
    :n-threads 10
    :duration-ms 5000))

(defn run-comprehensive-benchmarks
  "Runs an extensive benchmark suite"
  []
  (run-all-benchmarks
    :n-accounts 500
    :initial-balance 10000
    :n-iterations 200
    :n-threads 20
    :duration-ms 10000))

(defn -main
  "Main entry point for running benchmarks"
  [& args]
  (let [mode (or (first args) "standard")]
    (case mode
      "quick" (run-quick-benchmarks)
      "standard" (run-standard-benchmarks)
      "comprehensive" (run-comprehensive-benchmarks)
      (do
        (println "Usage: lein run -m cqrs.benchmarks.runner [mode]")
        (println "Modes:")
        (println "  quick         - Quick smoke test (~1 minute)")
        (println "  standard      - Standard benchmark suite (~10 minutes)")
        (println "  comprehensive - Comprehensive suite (~30 minutes)")
        (System/exit 1)))))

(comment
  (run-quick-benchmarks)

  (run-standard-benchmarks)

  (run-comprehensive-benchmarks)

  (run-all-benchmarks
    :n-accounts 50
    :n-iterations 50
    :n-threads 10
    :duration-ms 5000))
