(ns cqrs.application.bank-account-service
  "Application service that orchestrates commands and events.
   Projections are now handled asynchronously via DynamoDB Streams."
  (:require
   [cqrs.infrastructure.event-store :as event-store]
   [cqrs.infrastructure.dynamodb-projections :as dynamo-proj]
   [cqrs.queries :as query]
   [cqrs.domain.bank-account.commands :as commands]
   [cqrs.messages.message :as msg])
  (:import
   [java.util Date UUID]))

(defrecord BankAccountService [ddb-client pg-datasource event-store-table balance-table tx-table])

(defn create-service
  "Create a new BankAccountService instance"
  [ddb-client pg-datasource]
  (->BankAccountService
   ddb-client
   pg-datasource
   "EventStore"
   "AccountBalance"
   "TransactionHistory"))

;; Helper function to process events

(defn process-events
  "Process events through event store ONLY.
   Projections are now handled asynchronously via DynamoDB Streams.

   CRITICAL FOR BANKING: Uses atomic DynamoDB transactions to ensure ALL events
   are written or NONE are written. This is the ONLY operation that happens
   synchronously - projections are eventually consistent via streams."
  [service events]
  ;; Write ALL events atomically to event store (source of truth)
  ;; This is ACID-compliant: all events succeed or all fail
  ;; DynamoDB Streams will pick up these events and trigger projections
  (event-store/append-events-atomically
   (:ddb-client service)
   (:event-store-table service)
   events))

;; Command Execution Functions

(defn open-account
  "Open a new bank account"
  [service account-id account-holder account-type opening-balance]
  (let [base-message (msg/->BaseMessage account-id (Date.) {}) 
        command (commands/->OpenAccountCommand base-message account-holder account-type opening-balance)

        ;; Get event history (for aggregate reconstitution)
        event-history (event-store/get-events-for-aggregate
                       (:ddb-client service)
                       (:event-store-table service)
                       account-id)

        ;; Build event store from history
        event-store-map (reduce (fn [acc event]
                                  (update acc (:aggregate-identifier event) (fnil conj []) event))
                                {}
                                event-history)

        ;; Handle command
        result (commands/handle command event-store-map)
        events (:events result)]

    ;; Process events through projections
    (process-events service events)

    {:success true
     :account-id account-id
     :events (count events)
     :message "Account opened successfully"}))

(defn deposit-funds
  "Deposit funds into an account"
  [service account-id amount]
  (let [base-message (msg/->BaseMessage account-id (Date.) {})
        command (commands/->DepositFundsCommand base-message amount)

        ;; Get event history
        event-history (event-store/get-events-for-aggregate
                       (:ddb-client service)
                       (:event-store-table service)
                       account-id)

        event-store-map (reduce (fn [acc event]
                                  (update acc (:aggregate-identifier event) (fnil conj []) event))
                                {}
                                event-history)

        ;; Handle command
        result (commands/handle command event-store-map)
        events (:events result)]

    ;; Process events
    (process-events service events)

    {:success true
     :account-id account-id
     :amount amount
     :events (count events)
     :message "Funds deposited successfully"}))

(defn withdraw-funds
  "Withdraw funds from an account"
  [service account-id amount]
  (let [base-message (msg/->BaseMessage account-id (Date.) {})
        command (commands/->WithdrawFundsCommand base-message amount)

        ;; Get event history
        event-history (event-store/get-events-for-aggregate
                       (:ddb-client service)
                       (:event-store-table service)
                       account-id)

        event-store-map (reduce (fn [acc event]
                                  (update acc (:aggregate-identifier event) (fnil conj []) event))
                                {}
                                event-history)

        ;; Handle command
        result (commands/handle command event-store-map)
        events (:events result)]

    ;; Process events
    (process-events service events)

    {:success true
     :account-id account-id
     :amount amount
     :events (count events)
     :message "Funds withdrawn successfully"}))

(defn close-account
  "Close an account"
  [service account-id]
  (let [base-message (msg/->BaseMessage account-id (Date.) {})
        command (commands/->CloseAccountCommand base-message)

        ;; Get event history
        event-history (event-store/get-events-for-aggregate
                       (:ddb-client service)
                       (:event-store-table service)
                       account-id)

        event-store-map (reduce (fn [acc event]
                                  (update acc (:aggregate-identifier event) (fnil conj []) event))
                                {}
                                event-history)

        ;; Handle command
        result (commands/handle command event-store-map)
        events (:events result)]

    ;; Process events
    (process-events service events)

    {:success true
     :account-id account-id
     :events (count events)
     :message "Account closed successfully"}))

(defn transfer-funds
  "Transfer funds between accounts"
  [service from-account-id to-account-id amount]
  (let [base-message (msg/->BaseMessage (str (UUID/randomUUID)) (Date.) {})
        command (commands/->TransferFundsCommand base-message from-account-id to-account-id amount)

        ;; Get event history for both accounts
        from-events (event-store/get-events-for-aggregate
                     (:ddb-client service)
                     (:event-store-table service)
                     from-account-id)
        to-events (event-store/get-events-for-aggregate
                   (:ddb-client service)
                   (:event-store-table service)
                   to-account-id)

        event-store-map (-> {}
                            (assoc from-account-id from-events)
                            (assoc to-account-id to-events))

        ;; Handle command
        result (commands/handle command event-store-map)
        events (:events result)]

    ;; Process events
    (process-events service events)

    {:success true
     :from-account-id from-account-id
     :to-account-id to-account-id
     :amount amount
     :events (count events)
     :message "Funds transferred successfully"}))

;; Query Functions (delegate to query handlers)

(defn get-account-balance
  "Get current account balance (from DynamoDB - fast)"
  [service account-id]
  (dynamo-proj/get-account-balance (:ddb-client service) (:balance-table service) account-id))

(defn get-recent-transactions
  "Get recent transactions (from DynamoDB - fast)"
  [service account-id limit]
  (dynamo-proj/get-recent-transactions (:ddb-client service) (:tx-table service) account-id limit))

(defn get-account-details
  "Get detailed account information (from Postgres - complex query)"
  [service account-id]
  (query/get-account-details (:pg-datasource service) account-id))

(defn get-account-summary
  "Get comprehensive account summary (from Postgres - complex query)"
  [service account-id]
  (query/get-account-summary (:pg-datasource service) account-id))

(defn get-transaction-history
  "Get transaction history with date range (from Postgres)"
  [service account-id from-date to-date limit offset]
  (query/get-transaction-history (:pg-datasource service) account-id from-date to-date limit offset))

(defn get-accounts-by-holder
  "Get all accounts for a holder (from Postgres)"
  [service account-holder]
  (query/get-accounts-by-holder (:pg-datasource service) account-holder))

(defn get-top-accounts
  "Get top accounts by balance (from Postgres)"
  [service limit]
  (query/get-top-accounts-by-balance (:pg-datasource service) limit))

(defn get-daily-balance-report
  "Get daily balance report (from Postgres)"
  [service account-id from-date to-date]
  (query/get-daily-balance-report (:pg-datasource service) account-id from-date to-date))

(defn search-transactions
  "Search transactions with filters (from Postgres)"
  [service filters]
  (query/search-transactions (:pg-datasource service) filters))

(comment
  ;; Example usage - Decoupled CQRS with DynamoDB Streams 
  (require '[cqrs.infrastructure.postgres-projections :as pg-proj])
  (require '[cqrs.application.projection-service :as proj-service])
  (require '[cqrs.infrastructure.stream-processor :as stream])
  (import '(software.amazon.awssdk.services.dynamodbstreams DynamoDbStreamsClient))

  ;; Initialize databases
  (def ddb (event-store/init {:local? true}))
  (def pg-db (pg-proj/init {:local? true}))

  ;; Create projection tables
  (dynamo-proj/create-account-balance-table ddb "AccountBalance")
  (dynamo-proj/create-transaction-history-table ddb "TransactionHistory")

  ;; STEP 1: Start the projection service in a separate thread (simulates microservice)
  (def streams-client
    (-> (DynamoDbStreamsClient/builder)
        (.endpointOverride (java.net.URI. "http://localhost:8000"))
        (.build)))

  (def proj-svc (proj-service/create-projection-service ddb pg-db))
  (def projection-handler (proj-service/create-projection-handler proj-svc))
  (def processor (stream/create-stream-processor ddb streams-client "EventStore" projection-handler))
  (def running-processor (stream/start-processor processor))
  ;; Now projections are handled asynchronously via DynamoDB Streams!

  ;; STEP 2: Create command service (write side only)
  (def service (create-service ddb pg-db))

  ;; Execute commands - events are written to EventStore
  ;; DynamoDB Streams will automatically trigger projections
  (open-account service "acc-123" "John Doe" "CHECKING" 1000.0)
  (deposit-funds service "acc-123" 500.0)
  (withdraw-funds service "acc-123" 200.0)

  ;; Query read models (eventually consistent)
  (get-account-balance service "acc-123")
  (get-recent-transactions service "acc-123" 10)
  (get-account-summary service "acc-123")

  ;; More commands
  (open-account service "acc-456" "Christina Aguilera" "CHECKING" 1000.0)
  (transfer-funds service "acc-123" "acc-456" 100.0)
  (close-account service "acc-123")

  ;; Stop the projection service
  (stream/stop-processor running-processor)
  )
