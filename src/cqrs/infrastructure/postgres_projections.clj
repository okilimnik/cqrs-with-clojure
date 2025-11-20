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


(defn create-accounts-table
  "Create the accounts projection table for complex queries"
  [datasource]
  (jdbc/execute! datasource
                 ["CREATE TABLE IF NOT EXISTS accounts (
                    account_id VARCHAR(255) PRIMARY KEY,
                    account_holder VARCHAR(255) NOT NULL,
                    account_type VARCHAR(50) NOT NULL,
                    balance DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    status VARCHAR(20) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    closed_at TIMESTAMP
                  )"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_accounts_holder ON accounts(account_holder)"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts(status)"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_accounts_type ON accounts(account_type)"])
  (prn "Created accounts table"))

(defn create-transactions-table
  "Create the transactions projection table for complex queries"
  [datasource]
  (jdbc/execute! datasource
                 ["CREATE TABLE IF NOT EXISTS transactions (
                    transaction_id VARCHAR(255) PRIMARY KEY,
                    account_id VARCHAR(255) NOT NULL,
                    transaction_type VARCHAR(50) NOT NULL,
                    amount DECIMAL(19, 4) NOT NULL,
                    balance_after DECIMAL(19, 4) NOT NULL,
                    timestamp TIMESTAMP NOT NULL,
                    description TEXT,
                    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                  )"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions(account_id)"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp)"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(transaction_type)"])
  (prn "Created transactions table"))

(defn create-account-summary-table
  "Create account summary view for analytics and reporting"
  [datasource]
  (jdbc/execute! datasource
                 ["CREATE TABLE IF NOT EXISTS account_summary (
                    account_id VARCHAR(255) PRIMARY KEY,
                    account_holder VARCHAR(255) NOT NULL,
                    account_type VARCHAR(50) NOT NULL,
                    current_balance DECIMAL(19, 4) NOT NULL,
                    total_deposits DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    total_withdrawals DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    transaction_count INTEGER NOT NULL DEFAULT 0,
                    last_transaction_date TIMESTAMP,
                    account_age_days INTEGER,
                    status VARCHAR(20) NOT NULL,
                    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                  )"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_summary_holder ON account_summary(account_holder)"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_summary_balance ON account_summary(current_balance)"])
  (prn "Created account_summary table"))

(defn create-daily-balance-table
  "Create daily balance snapshots for time-series analysis"
  [datasource]
  (jdbc/execute! datasource
                 ["CREATE TABLE IF NOT EXISTS daily_balances (
                    id SERIAL PRIMARY KEY,
                    account_id VARCHAR(255) NOT NULL,
                    balance_date DATE NOT NULL,
                    closing_balance DECIMAL(19, 4) NOT NULL,
                    daily_deposits DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    daily_withdrawals DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    transaction_count INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(account_id, balance_date),
                    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                  )"])
  (jdbc/execute! datasource
                 ["CREATE INDEX IF NOT EXISTS idx_daily_balances_account_date ON daily_balances(account_id, balance_date)"])
  (prn "Created daily_balances table"))

(defn init
  "Initialize the Read Database connection.
  Options:
    :host - Database host (default: localhost)
    :port - Database port (default: 5432)
    :dbname - Database name (default: readdb)
    :user - Database user (default: postgres)
    :password - Database password (default: postgres)
    :local? - If true, uses default local connection settings"
  ([] (init {}))
  ([{:keys [host port dbname user password local?]
     :or {host "localhost"
          port 5432
          dbname "readdb"
          user "postgres"
          password "postgres"
          local? false}}]
   (let [db-spec (if local?
                   {:dbtype "postgresql"
                    :host "localhost"
                    :port 5432
                    :dbname "readdb"
                    :user "postgres"
                    :password "postgres"}
                   {:dbtype "postgresql"
                    :host host
                    :port port
                    :dbname dbname
                    :user user
                    :password password})
         datasource (jdbc/get-datasource db-spec)]
     (prn "Creating read model tables if not exists")
     (create-accounts-table datasource)
     (create-transactions-table datasource)
     (create-account-summary-table datasource)
     (create-daily-balance-table datasource)
     datasource)))