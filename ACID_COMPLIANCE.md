# ACID Compliance for Banking Event Store

This document explains how the DynamoDB-based event store achieves ACID compliance for banking transactions.

## Overview

For a banking application, ACID compliance is **critical** to prevent:
- Lost money due to failed transfers
- Duplicate transactions
- Race conditions during concurrent operations
- Inconsistent balances between accounts

## ACID Guarantees

### ✅ Atomicity

**Implementation:** DynamoDB `TransactWriteItems` API

All events in a transaction succeed or all fail together. This is crucial for fund transfers where money must be withdrawn from one account and deposited to another atomically.

**Code:** [event_store.clj:111-157](src/cqrs/infrastructure/event_store.clj#L111-L157) - `append-events-atomically`

```clojure
;; Fund transfer: BOTH events succeed or BOTH fail
(append-events-atomically ddb "EventStore"
  [{:event-type "FundsWithdrawn" :aggregate-identifier "acc-1" ...}
   {:event-type "FundsDeposited" :aggregate-identifier "acc-2" ...}])
```

**What happens on failure:**
- Network failure → No events written, transaction rolled back
- Insufficient funds → Validation fails before write, no state change
- Concurrent write conflict → All events rejected, must retry

### ✅ Consistency

**Implementation:** Conditional writes with `attribute_not_exists(EventId)`

Every event has a unique ID. Duplicate events are rejected, preventing:
- Double-charging customers
- Duplicate deposits/withdrawals
- Event replay bugs

**Code:** [event_store.clj:73-109](src/cqrs/infrastructure/event_store.clj#L73-L109) - `append-event` with condition expressions

```clojure
;; Condition: Event ID must not already exist (idempotency)
(.conditionExpression "attribute_not_exists(EventId)")
```

**What happens on violation:**
- Duplicate event ID → `ConditionalCheckFailedException` thrown
- Client can safely retry with same event ID
- No duplicate transactions in the ledger

### ✅ Isolation

**Implementation:** Optimistic locking via version numbers

Each aggregate has a version number that increments with each event. Concurrent modifications are detected and rejected.

**Code:** [bank_account.clj:104-132](src/cqrs/domain/bank_account.clj#L104-L132) - Version tracking in domain

**Scenario:** Two users try to withdraw from the same account simultaneously

```
Time    User A                          User B
----    ------                          ------
T1      Read account (balance=100, v=5) Read account (balance=100, v=5)
T2      Withdraw 60 (new v=6)           Withdraw 50 (new v=6)
T3      Write event (v=6) ✓ SUCCESS     Write event (v=6) ✗ REJECTED
T4                                      Retry: Read account (balance=40, v=6)
T5                                      Withdraw 50 ✗ Insufficient funds
```

User B's transaction is rejected because:
1. Their write includes version 6, but account is already at version 6
2. They must re-read current state (version 6, balance 40)
3. Insufficient funds error prevents the withdrawal

### ✅ Durability

**Implementation:** DynamoDB persistence with replication

Once a transaction commits:
- Data is persisted to disk across multiple availability zones
- DynamoDB guarantees durability with 99.999999999% reliability
- Events can be replayed to rebuild projections if needed

**Architecture:**
```
Event Store (DynamoDB) ←── Source of truth (durable)
    ↓
Projections (DynamoDB + Postgres) ←── Can be rebuilt from events
```

**Code:** [bank_account_service.clj:28-66](src/cqrs/application/bank_account_service.clj#L28-L66) - Atomic event store write, then best-effort projections

## Critical Banking Operations

### Fund Transfer (Most Critical)

**File:** [bank_account_service.clj:166-199](src/cqrs/application/bank_account_service.clj#L166-L199)

```clojure
(transfer-funds service "acc-123" "acc-456" 100.0)
```

**ACID guarantees:**
1. **Atomicity:** Both withdrawal and deposit events written atomically
2. **Consistency:** Event IDs prevent duplicates, versions prevent conflicts
3. **Isolation:** Concurrent transfers don't interfere (version checks)
4. **Durability:** Events persisted before returning success

**What CAN'T happen:**
- ❌ Money withdrawn but not deposited
- ❌ Money deposited twice
- ❌ Concurrent transfers causing overdraft
- ❌ Lost transactions after system crash

### Withdrawal with Concurrency

**Scenario:** Account has $100, two users try to withdraw $80 each

```clojure
;; User A's transaction (happens first)
(withdraw-funds service "acc-123" 80.0) ;; ✓ Success, balance now $20

;; User B's transaction (happens concurrently)
(withdraw-funds service "acc-123" 80.0) ;; ✗ Fails: insufficient funds
```

**Why this is safe:**
1. User A reads events, reconstitutes account (balance=$100, version=5)
2. User A validates sufficient funds, generates withdrawal event (version=6)
3. User B reads events concurrently, sees same state (balance=$100, version=5)
4. User A writes event atomically ✓
5. User B attempts to write event, but version conflict detected
6. User B retries, reads updated events (balance=$20, version=6)
7. User B's withdrawal fails validation (insufficient funds)

## Error Handling

### Transaction Failures

**Code:** [event_store.clj:148-157](src/cqrs/infrastructure/event_store.clj#L148-L157)

```clojure
(catch TransactionCanceledException e
  (throw (ex-info "Transaction failed: Concurrency conflict or duplicate event"
                  {:type :transaction-failed
                   :event-ids (mapv :id events)
                   :aggregate-ids (mapv :aggregate-identifier events)
                   :reason (.getMessage e)}
                  e)))
```

**Handling in application:**
- Detect `:concurrency-conflict` or `:transaction-failed` error types
- Re-read aggregate state from event store
- Retry command with updated state
- Return user-friendly error if business rule violated

### Projection Failures

**Code:** [bank_account_service.clj:52-62](src/cqrs/application/bank_account_service.clj#L52-L62)

Projections are **NOT** part of the atomic transaction. Why?
- Event store is source of truth
- Projections can be rebuilt from events
- Don't want projection bugs to block critical banking operations

```clojure
(catch Exception e
  ;; Log projection error but don't fail the operation
  (println "WARNING: Projection failed for event" (:id event)))
```

## Testing ACID Compliance

### Test 1: Concurrent Withdrawals

```clojure
(require '[cqrs.application.bank-account-service :as svc])

(def service (svc/create-service ddb pg-db))

;; Open account with $100
(svc/open-account service "acc-123" "John Doe" "CHECKING" 100.0)

;; Simulate concurrent withdrawals
(future (svc/withdraw-funds service "acc-123" 80.0)) ;; Thread 1
(future (svc/withdraw-funds service "acc-123" 80.0)) ;; Thread 2

;; One succeeds, one fails with "Insufficient funds" or "Concurrency conflict"
;; Final balance is either $20 (one succeeded) or $100 (both failed)
;; NEVER $-60 (both succeeded)
```

### Test 2: Duplicate Event Prevention

```clojure
;; Try to append the same event twice
(def event {:id "evt-unique-123" ...})

(event-store/append-events-atomically ddb "EventStore" [event]) ;; ✓ Success
(event-store/append-events-atomically ddb "EventStore" [event]) ;; ✗ Fails: duplicate
```

### Test 3: Fund Transfer Atomicity

```clojure
;; Transfer between accounts
(svc/transfer-funds service "acc-123" "acc-456" 50.0)

;; Verify BOTH events exist in event store
(def acc123-events (event-store/get-events-for-aggregate ddb "EventStore" "acc-123"))
(def acc456-events (event-store/get-events-for-aggregate ddb "EventStore" "acc-456"))

;; Both must have matching events with same timestamp
;; Or neither has the events (if transaction failed)
```

## DynamoDB Configuration

### Event Store Table Requirements

**File:** [event_store.clj:179-222](src/cqrs/infrastructure/event_store.clj#L179-L222)

```hcl
# Terraform configuration
resource "aws_dynamodb_table" "event_store" {
  name         = "EventStore"
  billing_mode = "PAY_PER_REQUEST"  # Scales automatically

  hash_key = "EventId"  # Ensures unique events (idempotency)

  attribute {
    name = "EventId"
    type = "S"
  }

  attribute {
    name = "AggregateId"
    type = "S"
  }

  global_secondary_index {
    name            = "AggregateIdIndex"
    hash_key        = "AggregateId"
    projection_type = "ALL"
  }

  # Enable point-in-time recovery for durability
  point_in_time_recovery {
    enabled = true
  }
}
```

### Key Design Decisions

1. **EventId as Primary Key:** Guarantees uniqueness (idempotency)
2. **AggregateIdIndex GSI:** Efficiently retrieve all events for an account
3. **PAY_PER_REQUEST billing:** Handles traffic spikes without throttling
4. **Point-in-time recovery:** Additional durability guarantee

## Comparison with Other Approaches

### ❌ Non-ACID Approach (Dangerous for Banking)

```clojure
;; BAD: Sequential writes without transactions
(defn unsafe-transfer [from-id to-id amount]
  (withdraw from-id amount)  ;; Write 1
  (deposit to-id amount))    ;; Write 2

;; Problems:
;; 1. If Write 2 fails, money disappears
;; 2. No atomicity between withdrawals
;; 3. Concurrent operations can overdraw accounts
```

### ✅ ACID Approach (Current Implementation)

```clojure
;; GOOD: Atomic transaction with all guarantees
(defn safe-transfer [from-id to-id amount]
  (let [events [(create-withdrawal-event from-id amount)
                (create-deposit-event to-id amount)]]
    (append-events-atomically ddb "EventStore" events)))

;; Benefits:
;; 1. Both events succeed or both fail (atomicity)
;; 2. Unique event IDs prevent duplicates (consistency)
;; 3. Version checks prevent race conditions (isolation)
;; 4. DynamoDB replication ensures persistence (durability)
```

## Limitations and Trade-offs

### Transaction Limits

DynamoDB transactions support up to **100 items** per transaction. For banking:
- ✓ Fund transfers (2 events): Well within limit
- ✓ Batch deposits (50 accounts): Within limit
- ✗ Mass payroll (1000+ accounts): Requires multiple transactions

**Solution for large operations:** Saga pattern with compensating transactions

### Read Consistency

DynamoDB transactions provide **read-after-write consistency**:
- After successful write, immediate read returns updated data
- Perfect for banking where users expect instant balance updates

### Cost Considerations

Transactional writes cost **2x standard writes**:
- Standard write: 1 WCU per KB
- Transactional write: 2 WCU per KB

**Is it worth it?** Absolutely for banking. Data consistency is more valuable than cost savings.

## Monitoring and Alerting

### Key Metrics to Monitor

1. **ConditionalCheckFailedException rate:** High rate indicates concurrency issues
2. **TransactionCanceledException rate:** May indicate system issues
3. **Event store write latency:** Should be < 50ms for good UX
4. **Projection lag:** Time between event store write and projection update

### Recommended Alerts

```clojure
;; Alert if transaction failure rate > 5%
;; Alert if event store write latency > 200ms
;; Alert if projection lag > 30 seconds
```

## Future Enhancements

1. **Event versioning:** Schema evolution for events
2. **Snapshot support:** Optimize aggregate reconstitution for old accounts
3. **Audit logging:** Immutable audit trail for compliance
4. **Multi-region replication:** Disaster recovery across regions

## References

- [DynamoDB Transactions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transactions.html)
- [Event Sourcing Pattern](https://martinfowler.com/eaaDev/EventSourcing.html)
- [CQRS Pattern](https://martinfowler.com/bliki/CQRS.html)
