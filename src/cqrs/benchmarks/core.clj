(ns cqrs.benchmarks.core
  "Core benchmark utilities and helpers for CQRS system benchmarking"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant Duration]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.atomic AtomicLong AtomicInteger]))

(defn now-ms []
  (System/currentTimeMillis))

(defn format-duration [millis]
  (cond
    (< millis 1) (format "%.3f μs" (* millis 1000))
    (< millis 1000) (format "%.2f ms" millis)
    (< millis 60000) (format "%.2f s" (/ millis 1000.0))
    :else (format "%.2f min" (/ millis 60000.0))))

(defn format-throughput [ops millis]
  (let [ops-per-sec (/ ops (/ millis 1000.0))]
    (cond
      (< ops-per-sec 1000) (format "%.2f ops/sec" ops-per-sec)
      (< ops-per-sec 1000000) (format "%.2f K ops/sec" (/ ops-per-sec 1000.0))
      :else (format "%.2f M ops/sec" (/ ops-per-sec 1000000.0)))))

(defn percentile [values p]
  (let [sorted (sort values)
        n (count sorted)
        idx (int (* n (/ p 100.0)))]
    (if (< idx n)
      (nth sorted idx)
      (last sorted))))

(defn calculate-stats [values]
  (let [n (count values)
        sum (reduce + values)
        mean (/ sum n)
        sorted (sort values)
        variance (/ (reduce + (map #(* (- % mean) (- % mean)) values)) n)
        stddev (Math/sqrt variance)]
    {:count n
     :min (first sorted)
     :max (last sorted)
     :mean mean
     :median (percentile values 50)
     :p95 (percentile values 95)
     :p99 (percentile values 99)
     :stddev stddev}))

(defn benchmark-fn
  "Runs a function n times and collects timing statistics"
  [f n & {:keys [warmup] :or {warmup 10}}]
  (println (format "Warming up... (%d iterations)" warmup))
  (dotimes [_ warmup] (f))

  (println (format "Running benchmark... (%d iterations)" n))
  (let [timings (atom [])]
    (dotimes [i n]
      (when (zero? (mod i (max 1 (quot n 10))))
        (println (format "  Progress: %d/%d" i n)))
      (let [start (now-ms)
            _ (f)
            duration (- (now-ms) start)]
        (swap! timings conj duration)))
    (calculate-stats @timings)))

(defn concurrent-benchmark
  "Runs a function concurrently with n threads for duration-ms milliseconds"
  [f n-threads duration-ms]
  (println (format "Running concurrent benchmark: %d threads for %s"
                   n-threads (format-duration duration-ms)))

  (let [start-latch (CountDownLatch. 1)
        done-latch (CountDownLatch. n-threads)
        start-time (atom nil)
        operations (AtomicLong. 0)
        errors (AtomicInteger. 0)
        timings (atom [])

        worker-fn (fn []
                    (.await start-latch)
                    (try
                      (loop []
                        (when (< (- (now-ms) @start-time) duration-ms)
                          (let [op-start (now-ms)]
                            (try
                              (f)
                              (.incrementAndGet operations)
                              (let [op-duration (- (now-ms) op-start)]
                                (swap! timings conj op-duration))
                              (catch Exception e
                                (.incrementAndGet errors)
                                (println "Error:" (.getMessage e)))))
                          (recur)))
                      (finally
                        (.countDown done-latch))))

        threads (mapv (fn [_] (Thread. worker-fn)) (range n-threads))]

    (doseq [t threads] (.start t))
    (Thread/sleep 100)
    (reset! start-time (now-ms))
    (.countDown start-latch)

    (.await done-latch)
    (let [total-time (- (now-ms) @start-time)
          ops-count (.get operations)
          error-count (.get errors)
          timing-stats (when (seq @timings) (calculate-stats @timings))]
      {:duration-ms total-time
       :operations ops-count
       :errors error-count
       :throughput (format-throughput ops-count total-time)
       :stats timing-stats})))

(defn run-load-test
  "Runs a load test with increasing concurrency levels"
  [f levels duration-per-level-ms]
  (println "\n=== LOAD TEST ===")
  (println (format "Testing concurrency levels: %s" (str/join ", " levels)))
  (println (format "Duration per level: %s\n" (format-duration duration-per-level-ms)))

  (mapv (fn [level]
          (println (format "\n--- Concurrency Level: %d ---" level))
          (let [result (concurrent-benchmark f level duration-per-level-ms)]
            (assoc result :concurrency level)))
        levels))

(defn print-stats [label stats]
  (println (format "\n=== %s ===" label))
  (println (format "  Count:      %d" (:count stats)))
  (println (format "  Min:        %s" (format-duration (:min stats))))
  (println (format "  Mean:       %s" (format-duration (:mean stats))))
  (println (format "  Median:     %s" (format-duration (:median stats))))
  (println (format "  P95:        %s" (format-duration (:p95 stats))))
  (println (format "  P99:        %s" (format-duration (:p99 stats))))
  (println (format "  Max:        %s" (format-duration (:max stats))))
  (println (format "  Std Dev:    %s" (format-duration (:stddev stats)))))

(defn print-concurrent-results [results]
  (println (format "\nDuration:     %s" (format-duration (:duration-ms results))))
  (println (format "Operations:   %d" (:operations results)))
  (println (format "Errors:       %d" (:errors results)))
  (println (format "Throughput:   %s" (:throughput results)))
  (when-let [stats (:stats results)]
    (print-stats "Latency Statistics" stats)))

(defn print-load-test-results [results]
  (println "\n=== LOAD TEST SUMMARY ===\n")
  (println "Concurrency | Operations | Throughput      | Mean Latency | P95 Latency | Errors")
  (println "------------|------------|-----------------|--------------|-------------|-------")
  (doseq [r results]
    (println (format "%-11d | %-10d | %-15s | %-12s | %-11s | %d"
                     (:concurrency r)
                     (:operations r)
                     (:throughput r)
                     (format-duration (get-in r [:stats :mean] 0))
                     (format-duration (get-in r [:stats :p95] 0))
                     (:errors r)))))

(defn generate-test-data
  "Generates test data for benchmarking"
  [n type]
  (case type
    :account-ids (mapv #(str "ACC-" (format "%06d" %)) (range n))
    :amounts (mapv #(+ 1 (rand-int 10000)) (range n))
    :descriptions (mapv #(str "Transaction " %) (range n))))

(defn random-element [coll]
  (nth coll (rand-int (count coll))))

(defmacro time-ms
  "Returns the execution time in milliseconds"
  [expr]
  `(let [start# (now-ms)
         result# ~expr]
     (- (now-ms) start#)))

(defn save-results
  "Saves benchmark results to a file"
  [filename results]
  (spit filename (pr-str results))
  (println (format "\nResults saved to: %s" filename)))
