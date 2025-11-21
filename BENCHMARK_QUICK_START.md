# Benchmark Suite - Quick Start

## 30 Second Start

```clojure
;; In your REPL
(require '[cqrs.benchmarks.runner :as runner])
(runner/run-quick-benchmarks)
```

Done! Results saved to `benchmark-results/`.

## 5 Minute Tour

### 1. Run Standard Suite
```clojure
(runner/run-standard-benchmarks)
```

### 2. View Results
- Open `benchmark-results/report-YYYY-MM-DD_HH-mm-ss.html` in browser
- Or check console output for statistics

### 3. Run Individual Tests
```clojure
(require '[cqrs.benchmarks.command-benchmarks :as cmd])

;; Setup
(def accounts (cmd/setup-test-accounts 20 10000))

;; Test deposits
(cmd/benchmark-deposits accounts 50)

;; Test transfers (critical ACID test)
(cmd/benchmark-transfers accounts 50)

;; Test concurrent load
(cmd/benchmark-concurrent-different-accounts accounts 10 5000)
```

## Common Commands

### Quick Tests (Fast Feedback)
```clojure
;; ~1 minute smoke test
(runner/run-quick-benchmarks)

;; Quick load test
(require '[cqrs.benchmarks.load-tests :as load])
(load/run-quick-load-test accounts)
```

### Production-Ready Tests
```clojure
;; ~10 minutes standard suite
(runner/run-standard-benchmarks)

;; ~30 minutes comprehensive
(runner/run-comprehensive-benchmarks)
```

### Custom Configuration
```clojure
(runner/run-all-benchmarks
  :n-accounts 100      ;; Number of test accounts
  :n-iterations 100    ;; Test iterations
  :n-threads 10        ;; Concurrent threads
  :duration-ms 5000)   ;; Time-based test duration
```

## Key Benchmarks by Use Case

### Before Deploying
```clojure
;; Full validation
(runner/run-standard-benchmarks)

;; Check critical path
(cmd/benchmark-transfers accounts 100)

;; Verify eventual consistency
(require '[cqrs.benchmarks.projection-benchmarks :as proj])
(proj/benchmark-projection-lag 20)
```

### Performance Investigation
```clojure
;; Should we add snapshots?
(require '[cqrs.benchmarks.aggregate-benchmarks :as agg])
(agg/benchmark-snapshot-benefit 1000 500)

;; Query performance analysis
(require '[cqrs.benchmarks.query-benchmarks :as query])
(query/benchmark-concurrent-queries accounts 20 10000 :mixed)
```

### Capacity Planning
```clojure
;; Find breaking point
(load/stress-test accounts 5 100 5 5000)

;; Test sustained load
(load/endurance-test accounts 20 5) ;; 5 minutes

;; Test spike handling
(load/spike-test accounts 10 50 5000)
```

### Regression Testing
```clojure
;; Before changes
(def baseline (runner/run-quick-benchmarks))

;; ... make code changes ...

;; After changes
(def current (runner/run-quick-benchmarks))

;; Compare (build your own comparison or just eyeball the stats)
```

## Understanding Output

### Statistics Explained
```
Count:      100        # Number of iterations
Min:        8.2 ms     # Best case
Mean:       15.3 ms    # Average
Median:     14.1 ms    # Middle value
P95:        22.4 ms    # 95% under this
P99:        28.7 ms    # 99% under this
Max:        45.2 ms    # Worst case
Std Dev:    3.4 ms     # Variance
```

### What's Good?
- ✅ Mean close to median (consistent)
- ✅ P95 < 2x mean (low variance)
- ✅ Low standard deviation
- ✅ Error rate < 1%

### What's Bad?
- ⚠️ P99 > 5x mean (high variance)
- ⚠️ Error rate > 5%
- ⚠️ Throughput decreasing with load
- 🔴 Error rate > 10% (critical)

## Performance Targets

Quick reference for expected performance:

| What | Target |
|------|--------|
| Balance Query (DynamoDB) | <10ms |
| Account Query (PostgreSQL) | <50ms |
| Deposit/Withdrawal | <50ms |
| Transfer (atomic) | <100ms |
| Event Write | <10ms |
| Projection Lag | <100ms |

## Files & Directories

```
benchmark-results/          # Auto-created output directory
├── benchmark-*.edn        # Raw data
└── report-*.html          # HTML reports

src/cqrs/benchmarks/       # Benchmark source code
├── core.clj              # Utilities
├── runner.clj            # Main runner
└── *_benchmarks.clj      # Individual suites
```

## Next Steps

1. **Read More**: See [BENCHMARK_README.md](BENCHMARK_README.md) for complete docs
2. **See Examples**: Check [benchmark_example.clj](src/cqrs/examples/benchmark_example.clj)
3. **Run Tests**: Start with `run-quick-benchmarks`
4. **Review Output**: Open HTML report in browser

## Troubleshooting

### "Connection refused" errors
- Check DynamoDB/PostgreSQL are running
- Verify AWS credentials configured

### High error rates
- Reduce concurrency (`n-threads`)
- Check resource limits (connections, memory)

### Slow performance
- Verify AWS region (latency)
- Check indexes on PostgreSQL
- Monitor system resources

### Inconsistent results
- Increase warmup iterations
- Close other applications
- Run during stable conditions

## One-Liners

```clojure
;; Quick check
(runner/run-quick-benchmarks)

;; Find if snapshots help
(agg/benchmark-snapshot-benefit 1000 500)

;; Measure consistency lag
(proj/benchmark-projection-lag 20)

;; Test transfers (ACID critical)
(cmd/benchmark-transfers accounts 100)

;; Find breaking point
(load/stress-test accounts 5 100 5 5000)

;; Test hot accounts
(load/hot-account-test (take 5 accounts) (drop 5 accounts) 20 10000 0.8)
```

## Help

For detailed documentation:
- `BENCHMARK_README.md` - Complete guide
- `BENCHMARK_SUMMARY.md` - Overview and insights
- `benchmark_example.clj` - Code examples

---

**Remember**: Start small, iterate, measure everything! 📊
