(ns cqrs.examples.decoupled-example
  "Example demonstrating decoupled CQRS architecture with DynamoDB Streams.

   This example shows:
   1. Write side (commands) running in main thread
   2. Read side (projections) running in separate thread via DynamoDB Streams
   3. Eventual consistency between write and read models

   In production, the projection service would be a separate microservice,
   but here we simulate it with a background thread."
  (:require
   [cqrs.infrastructure.postgres-projections :as pg-proj]
   [cqrs.application.bank-account-service :as bank-service]
   [cqrs.application.projection-service :as proj-service]
   [cqrs.infrastructure.stream-processor :as stream]
   [cqrs.infrastructure.dynamodb-projections :as dynamo-proj]
   [cqrs.infrastructure.event-store :as event-store])
  (:import
   (software.amazon.awssdk.services.dynamodbstreams DynamoDbStreamsClient)
   (java.net URI)))

;; Configuration

(def config
  {:dynamodb-endpoint "http://localhost:8000"
   :postgres-db "cqrs_bank"})

;; Setup Functions

(defn setup-databases!
  "Initialize DynamoDB and Postgres databases"
  []
  (println "\n=== Setting up databases ===")
  (let [ddb (event-store/init {:local? true})
        pg-db (pg-proj/init {:local? true})]

    ;; Create EventStore table with streams enabled
    (try
      (event-store/create-event-store-table ddb "EventStore")
      (println "✓ Created EventStore table")
      (catch Exception e
        (println "EventStore table already exists")))

    ;; Enable DynamoDB Streams on EventStore
    ;; Note: In DynamoDB Local, streams are enabled by default
    ;; In AWS, you would enable streams via AWS Console or SDK

    ;; Create projection tables
    (try
      (dynamo-proj/create-account-balance-table ddb "AccountBalance")
      (println "✓ Created AccountBalance projection table")
      (catch Exception e
        (println "AccountBalance table already exists")))

    (try
      (dynamo-proj/create-transaction-history-table ddb "TransactionHistory")
      (println "✓ Created TransactionHistory projection table")
      (catch Exception e
        (println "TransactionHistory table already exists")))

    {:ddb ddb :pg-db pg-db}))

(defn start-projection-microservice!
  "Start the projection service as a separate 'microservice' (background thread)"
  [ddb pg-db]
  (println "\n=== Starting Projection Microservice ===")

  ;; Create DynamoDB Streams client
  (let [streams-client (-> (DynamoDbStreamsClient/builder)
                           (.endpointOverride (URI. (:dynamodb-endpoint config)))
                           (.build))

        ;; Create projection service
        proj-svc (proj-service/create-projection-service ddb pg-db)
        projection-handler (proj-service/create-projection-handler proj-svc)

        ;; Create and start stream processor
        processor (stream/create-stream-processor ddb streams-client "EventStore" projection-handler)
        running-processor (stream/start-processor processor)]

    (println "✓ Projection microservice started and listening to DynamoDB Streams")
    (println "  Events will be processed asynchronously in background thread")
    running-processor))

;; Example Scenarios

(defn run-basic-operations
  "Run basic banking operations"
  [service]
  (println "\n=== Running Basic Banking Operations ===")

  ;; Open account
  (println "\n1. Opening account for John Doe...")
  (let [result (bank-service/open-account service "acc-001" "John Doe" "CHECKING" 5000.0)]
    (println "   Result:" result))

  ;; Wait for eventual consistency
  (Thread/sleep 2000)

  ;; Check balance
  (println "\n2. Checking balance...")
  (let [balance (bank-service/get-account-balance service "acc-001")]
    (println "   Balance:" balance))

  ;; Deposit funds
  (println "\n3. Depositing $1000...")
  (let [result (bank-service/deposit-funds service "acc-001" 1000.0)]
    (println "   Result:" result))

  ;; Wait for eventual consistency
  (Thread/sleep 2000)

  ;; Withdraw funds
  (println "\n4. Withdrawing $500...")
  (let [result (bank-service/withdraw-funds service "acc-001" 500.0)]
    (println "   Result:" result))

  ;; Wait for eventual consistency
  (Thread/sleep 2000)

  ;; Check final balance
  (println "\n5. Checking final balance...")
  (let [balance (bank-service/get-account-balance service "acc-001")]
    (println "   Balance:" balance))

  ;; Get transaction history
  (println "\n6. Getting recent transactions...")
  (let [transactions (bank-service/get-recent-transactions service "acc-001" 10)]
    (println "   Transactions:")
    (doseq [tx transactions]
      (println "    -" (:transaction-type tx) (:amount tx)))))

(defn run-transfer-scenario
  "Demonstrate fund transfer between accounts"
  [service]
  (println "\n=== Running Transfer Scenario ===")

  ;; Open two accounts
  (println "\n1. Opening two accounts...")
  (bank-service/open-account service "acc-002" "Alice Smith" "CHECKING" 3000.0)
  (bank-service/open-account service "acc-003" "Bob Johnson" "SAVINGS" 2000.0)
  (println "   Accounts opened")

  ;; Wait for projections
  (Thread/sleep 2000)

  ;; Transfer funds
  (println "\n2. Transferring $500 from Alice to Bob...")
  (let [result (bank-service/transfer-funds service "acc-002" "acc-003" 500.0)]
    (println "   Result:" result))

  ;; Wait for projections
  (Thread/sleep 2000)

  ;; Check both balances
  (println "\n3. Checking balances after transfer...")
  (let [alice-balance (bank-service/get-account-balance service "acc-002")
        bob-balance (bank-service/get-account-balance service "acc-003")]
    (println "   Alice's balance:" (:balance alice-balance))
    (println "   Bob's balance:" (:balance bob-balance))))

(defn demonstrate-eventual-consistency
  "Demonstrate eventual consistency model"
  [service]
  (println "\n=== Demonstrating Eventual Consistency ===")

  (println "\n1. Opening account...")
  (bank-service/open-account service "acc-004" "Charlie Brown" "CHECKING" 1000.0)

  (println "\n2. Immediately checking balance (may not be available yet)...")
  (let [balance (bank-service/get-account-balance service "acc-004")]
    (if balance
      (println "   Balance already available (fast streams!):" balance)
      (println "   Balance not yet available (eventual consistency)")))

  (println "\n3. Waiting 2 seconds for projection to complete...")
  (Thread/sleep 2000)

  (println "\n4. Checking balance again (should be available now)...")
  (let [balance (bank-service/get-account-balance service "acc-004")]
    (println "   Balance:" balance)))

;; Main Example Runner

(defn run-example!
  "Run the complete decoupled CQRS example"
  []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║  Decoupled CQRS with DynamoDB Streams Example             ║")
  (println "║                                                            ║")
  (println "║  Architecture:                                             ║")
  (println "║  • Commands → EventStore (write side)                      ║")
  (println "║  • DynamoDB Streams → Projection Service (read side)       ║")
  (println "║  • Queries → Projections (eventually consistent)           ║")
  (println "╚════════════════════════════════════════════════════════════╝")

  ;; Setup
  (let [{:keys [ddb pg-db]} (setup-databases!)
        processor (start-projection-microservice! ddb pg-db)
        service (bank-service/create-service ddb pg-db)]

    (try
      ;; Run examples
      (run-basic-operations service)
      (run-transfer-scenario service)
      (demonstrate-eventual-consistency service)

      (println "\n=== Example Complete ===")
      (println "The projection microservice continues running in the background.")
      (println "Press Ctrl+C to stop.")

      ;; Keep running to show background processing
      (Thread/sleep 5000)

      ;; Cleanup
      (println "\n=== Shutting Down ===")
      (stream/stop-processor processor)
      (println "✓ Projection microservice stopped")

      (catch Exception e
        (println "ERROR:" (.getMessage e))
        (stream/stop-processor processor)))))

(comment
  ;; Run the complete example
  (run-example!)

  ;; Or run individual scenarios
  (let [{:keys [ddb pg-db]} (setup-databases!)
        processor (start-projection-microservice! ddb pg-db)
        service (bank-service/create-service ddb pg-db)]

    (run-basic-operations service)

    ;; Don't forget to stop the processor
    (stream/stop-processor processor)))
