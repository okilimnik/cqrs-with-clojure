(ns cqrs.infrastructure.postgres-projections
  "Event handlers that project events to Postgres read models for complex queries"
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql])
  (:import
   [java.sql Timestamp]))

(defn ->timestamp
  "Convert Date to SQL Timestamp"
  [date]
  (Timestamp. (.getTime date)))

;; Account Projections

(defn handle-account-opened-pg
  "Project AccountOpened event to Postgres"
  [datasource event]
  (let [account-id (:aggregate-identifier event)
        event-data (:event-data event)
        account-holder (:account-holder event-data)
        account-type (:account-type event-data)
        opening-balance (:opening-balance event-data)
        created-date (->timestamp (:created-date event-data))]

    ;; Insert into accounts table
    (sql/insert! datasource :accounts
                 {:account_id account-id
                  :account_holder account-holder
                  :account_type account-type
                  :balance opening-balance
                  :status "active"
                  :created_at created-date
                  :updated_at created-date})

    ;; Initialize account summary
    (sql/insert! datasource :account_summary
                 {:account_id account-id
                  :account_holder account-holder
                  :account_type account-type
                  :current_balance opening-balance
                  :total_deposits (if (> opening-balance 0) opening-balance 0)
                  :total_withdrawals 0
                  :transaction_count (if (> opening-balance 0) 1 0)
                  :last_transaction_date (when (> opening-balance 0) created-date)
                  :account_age_days 0
                  :status "active"})

    ;; Record opening transaction if there's an opening balance
    (when (> opening-balance 0)
      (sql/insert! datasource :transactions
                   {:transaction_id (:id event)
                    :account_id account-id
                    :transaction_type "OPENING_DEPOSIT"
                    :amount opening-balance
                    :balance_after opening-balance
                    :timestamp created-date
                    :description "Account opening deposit"}))))

(defn handle-funds-deposited-pg
  "Project FundsDeposited event to Postgres"
  [datasource event]
  (let [account-id (:aggregate-identifier event)
        amount (get-in event [:event-data :amount])
        timestamp (->timestamp (:timestamp event))

        ;; Get current balance
        current-account (jdbc/execute-one! datasource
                                           ["SELECT balance FROM accounts WHERE account_id = ?" account-id])
        new-balance (+ (:accounts/balance current-account) amount)]

    ;; Update account balance
    (jdbc/execute! datasource
                   ["UPDATE accounts SET balance = ?, updated_at = ? WHERE account_id = ?"
                    new-balance timestamp account-id])

    ;; Record transaction
    (sql/insert! datasource :transactions
                 {:transaction_id (:id event)
                  :account_id account-id
                  :transaction_type "DEPOSIT"
                  :amount amount
                  :balance_after new-balance
                  :timestamp timestamp
                  :description "Funds deposited"})

    ;; Update account summary
    (jdbc/execute! datasource
                   ["UPDATE account_summary
                     SET current_balance = ?,
                         total_deposits = total_deposits + ?,
                         transaction_count = transaction_count + 1,
                         last_transaction_date = ?,
                         account_age_days = EXTRACT(DAY FROM (? - (SELECT created_at FROM accounts WHERE account_id = ?)))
                     WHERE account_id = ?"
                    new-balance amount timestamp timestamp account-id account-id])

    ;; Update daily balances
    (let [balance-date (.toLocalDate (.toLocalDateTime timestamp))]
      (jdbc/execute! datasource
                     ["INSERT INTO daily_balances (account_id, balance_date, closing_balance, daily_deposits, daily_withdrawals, transaction_count)
                       VALUES (?, ?::date, ?, ?, 0, 1)
                       ON CONFLICT (account_id, balance_date)
                       DO UPDATE SET
                         closing_balance = ?,
                         daily_deposits = daily_balances.daily_deposits + ?,
                         transaction_count = daily_balances.transaction_count + 1"
                      account-id (str balance-date) new-balance amount new-balance amount]))))

(defn handle-funds-withdrawn-pg
  "Project FundsWithdrawn event to Postgres"
  [datasource event]
  (let [account-id (:aggregate-identifier event)
        amount (get-in event [:event-data :amount])
        timestamp (->timestamp (:timestamp event))

        ;; Get current balance
        current-account (jdbc/execute-one! datasource
                                           ["SELECT balance FROM accounts WHERE account_id = ?" account-id])
        new-balance (- (:accounts/balance current-account) amount)]

    ;; Update account balance
    (jdbc/execute! datasource
                   ["UPDATE accounts SET balance = ?, updated_at = ? WHERE account_id = ?"
                    new-balance timestamp account-id])

    ;; Record transaction
    (sql/insert! datasource :transactions
                 {:transaction_id (:id event)
                  :account_id account-id
                  :transaction_type "WITHDRAWAL"
                  :amount amount
                  :balance_after new-balance
                  :timestamp timestamp
                  :description "Funds withdrawn"})

    ;; Update account summary
    (jdbc/execute! datasource
                   ["UPDATE account_summary
                     SET current_balance = ?,
                         total_withdrawals = total_withdrawals + ?,
                         transaction_count = transaction_count + 1,
                         last_transaction_date = ?,
                         account_age_days = EXTRACT(DAY FROM (? - (SELECT created_at FROM accounts WHERE account_id = ?)))
                     WHERE account_id = ?"
                    new-balance amount timestamp timestamp account-id account-id])

    ;; Update daily balances
    (let [balance-date (.toLocalDate (.toLocalDateTime timestamp))]
      (jdbc/execute! datasource
                     ["INSERT INTO daily_balances (account_id, balance_date, closing_balance, daily_deposits, daily_withdrawals, transaction_count)
                       VALUES (?, ?::date, ?, 0, ?, 1)
                       ON CONFLICT (account_id, balance_date)
                       DO UPDATE SET
                         closing_balance = ?,
                         daily_withdrawals = daily_balances.daily_withdrawals + ?,
                         transaction_count = daily_balances.transaction_count + 1"
                      account-id (str balance-date) new-balance amount new-balance amount]))))

(defn handle-account-closed-pg
  "Project AccountClosed event to Postgres"
  [datasource event]
  (let [account-id (:aggregate-identifier event)
        timestamp (->timestamp (:timestamp event))]

    ;; Update account status
    (jdbc/execute! datasource
                   ["UPDATE accounts SET status = ?, closed_at = ?, updated_at = ? WHERE account_id = ?"
                    "closed" timestamp timestamp account-id])

    ;; Update account summary status
    (jdbc/execute! datasource
                   ["UPDATE account_summary SET status = ? WHERE account_id = ?"
                    "closed" account-id])))

;; Main projection function

(defn project-event-to-postgres
  "Project an event to Postgres read models"
  [datasource event]
  (try
    (case (:event-type event)
      "AccountOpened" (handle-account-opened-pg datasource event)
      "FundsDeposited" (handle-funds-deposited-pg datasource event)
      "FundsWithdrawn" (handle-funds-withdrawn-pg datasource event)
      "AccountClosed" (handle-account-closed-pg datasource event)
      nil)
    (catch Exception e
      (println "Error projecting event to Postgres:" (.getMessage e))
      (throw e))))

(comment
  ;; Example usage
  (require '[cqrs.db.read :as read])

  (def pg-db (read/init {:local? true}))

  ;; Project an event
  (project-event-to-postgres pg-db {:id "evt-1"
                                     :aggregate-identifier "acc-1"
                                     :event-type "AccountOpened"
                                     :timestamp (java.util.Date.)
                                     :event-data {:account-holder "John Doe"
                                                  :account-type "CHECKING"
                                                  :opening-balance 1000.0
                                                  :created-date (java.util.Date.)}}))
