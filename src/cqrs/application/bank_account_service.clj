(ns cqrs.application.bank-account-service
  "Application service that orchestrates commands, events, and projections"
  (:require
   [cqrs.handlers.command-handler :as cmd]
   [cqrs.infrastructure.event-store :as event-store]
   [cqrs.infrastructure.dynamodb-projections :as dynamo-proj]
   [cqrs.infrastructure.postgres-projections :as pg-proj]
   [cqrs.queries.query-handler :as query]
   [cqrs.messages.commands :as commands]
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

;; Helper function to process events through both projections

(defn process-events
  "Process events through event store and projections"
  [service events]
  (doseq [event events]
    ;; 1. Append to event store (DynamoDB)
    (event-store/append-event (:ddb-client service) (:event-store-table service) event)

    ;; 2. Project to DynamoDB simple projections (real-time)
    (dynamo-proj/project-event (:ddb-client service) (:balance-table service) (:tx-table service) event)

    ;; 3. Project to Postgres read models (complex queries)
    (pg-proj/project-event-to-postgres (:pg-datasource service) event)))

;; Command Execution Functions

(defn open-account
  "Open a new bank account"
  [service account-id account-holder account-type opening-balance]
  (let [base-message (msg/->BaseMessage account-id (Date.) {})
        base-command (commands/->BaseCommand base-message)
        command (commands/->OpenAccountCommand base-command account-holder account-type opening-balance)

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
        result (cmd/dispatch-command command event-store-map)
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
        base-command (commands/->BaseCommand base-message)
        command (commands/->DepositFundsCommand base-command amount)

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
        result (cmd/dispatch-command command event-store-map)
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
        base-command (commands/->BaseCommand base-message)
        command (commands/->WithdrawFundsCommand base-command amount)

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
        result (cmd/dispatch-command command event-store-map)
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
        base-command (commands/->BaseCommand base-message)
        command (commands/->CloseAccountCommand base-command)

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
        result (cmd/dispatch-command command event-store-map)
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
        base-command (commands/->BaseCommand base-message)
        command (commands/->TransferFundsCommand base-command from-account-id to-account-id amount)

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
        result (cmd/dispatch-command command event-store-map)
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
  ;; Example usage
  (require '[cqrs.db.write :as write])
  (require '[cqrs.db.read :as read])

  ;; Initialize databases
  (def ddb (write/init {:local? true}))
  (def pg-db (read/init {:local? true}))

  (dynamo-proj/create-account-balance-table ddb "AccountBalance")
  (dynamo-proj/create-transaction-history-table ddb "TransactionHistory")

  ;; Create service
  (def service (create-service ddb pg-db))

  ;; Open an account
  (open-account service "acc-123" "John Doe" "CHECKING" 1000.0)

  ;; Deposit funds
  (deposit-funds service "acc-123" 500.0)

  ;; Withdraw funds
  (withdraw-funds service "acc-123" 200.0)

  ;; Get balance (fast - from DynamoDB)
  (get-account-balance service "acc-123")

  ;; Get recent transactions (fast - from DynamoDB)
  (get-recent-transactions service "acc-123" 10)

  ;; Get account summary (complex - from Postgres)
  (get-account-summary service "acc-123")


  (open-account service "acc-456" "Christina Aguilera" "CHECKING" 1000.0)
  ;; Transfer funds
  (transfer-funds service "acc-123" "acc-456" 100.0)

  ;; Close account
  (close-account service "acc-123")
  )
