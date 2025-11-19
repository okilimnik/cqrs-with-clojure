(ns cqrs.db.read
  (:require
   [next.jdbc :as jdbc]))

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


(comment
  ;; For local development
  (def db (init {:local? true}))

  ;; For Aurora Postgres
  (def db (init {:host
                 "your-aurora-endpoint.rds.amazonaws.com"
                 :port 5432
                 :dbname "readdb"
                 :user "your-user"
                 :password "your-password"}))
  )