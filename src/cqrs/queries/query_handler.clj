(ns cqrs.queries.query-handler
  "Query handlers for complex read operations on Postgres read models"
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]))

;; Query Models

(defrecord AccountDetailsQuery [account-id])
(defrecord AccountsByHolderQuery [account-holder])
(defrecord AccountsByStatusQuery [status])
(defrecord TransactionHistoryQuery [account-id from-date to-date limit offset])
(defrecord AccountSummaryQuery [account-id])
(defrecord TopAccountsByBalanceQuery [limit])
(defrecord DailyBalanceReportQuery [account-id from-date to-date])
(defrecord TransactionVolumeReportQuery [from-date to-date])
(defrecord AccountActivityReportQuery [account-holder])

;; Query Handlers

(defn get-account-details
  "Get detailed account information"
  [datasource account-id]
  (jdbc/execute-one! datasource
                     ["SELECT * FROM accounts WHERE account_id = ?" account-id]))

(defn get-accounts-by-holder
  "Get all accounts for a specific account holder"
  [datasource account-holder]
  (jdbc/execute! datasource
                 ["SELECT * FROM accounts WHERE account_holder = ? ORDER BY created_at DESC"
                  account-holder]))

(defn get-accounts-by-status
  "Get all accounts with a specific status"
  [datasource status]
  (jdbc/execute! datasource
                 ["SELECT * FROM accounts WHERE status = ? ORDER BY updated_at DESC"
                  status]))

(defn get-transaction-history
  "Get transaction history for an account with pagination"
  [datasource account-id from-date to-date limit offset]
  (jdbc/execute! datasource
                 ["SELECT * FROM transactions
                   WHERE account_id = ?
                   AND timestamp BETWEEN ? AND ?
                   ORDER BY timestamp DESC
                   LIMIT ? OFFSET ?"
                  account-id from-date to-date limit offset]))

(defn get-account-summary
  "Get comprehensive account summary with analytics"
  [datasource account-id]
  (jdbc/execute-one! datasource
                     ["SELECT
                        s.*,
                        a.created_at,
                        a.closed_at,
                        (SELECT COUNT(*) FROM transactions WHERE account_id = ? AND transaction_type = 'DEPOSIT') as deposit_count,
                        (SELECT COUNT(*) FROM transactions WHERE account_id = ? AND transaction_type = 'WITHDRAWAL') as withdrawal_count,
                        (SELECT AVG(amount) FROM transactions WHERE account_id = ? AND transaction_type = 'DEPOSIT') as avg_deposit_amount,
                        (SELECT AVG(amount) FROM transactions WHERE account_id = ? AND transaction_type = 'WITHDRAWAL') as avg_withdrawal_amount
                       FROM account_summary s
                       JOIN accounts a ON s.account_id = a.account_id
                       WHERE s.account_id = ?"
                      account-id account-id account-id account-id account-id]))

(defn get-top-accounts-by-balance
  "Get top accounts by balance for reporting"
  [datasource limit]
  (jdbc/execute! datasource
                 ["SELECT
                    a.account_id,
                    a.account_holder,
                    a.account_type,
                    a.balance,
                    a.status,
                    s.transaction_count,
                    s.total_deposits,
                    s.total_withdrawals
                   FROM accounts a
                   JOIN account_summary s ON a.account_id = s.account_id
                   WHERE a.status = 'active'
                   ORDER BY a.balance DESC
                   LIMIT ?"
                  limit]))

(defn get-daily-balance-report
  "Get daily balance report for time-series analysis"
  [datasource account-id from-date to-date]
  (jdbc/execute! datasource
                 ["SELECT
                    balance_date,
                    closing_balance,
                    daily_deposits,
                    daily_withdrawals,
                    transaction_count,
                    (closing_balance - daily_deposits + daily_withdrawals) as opening_balance
                   FROM daily_balances
                   WHERE account_id = ?
                   AND balance_date BETWEEN ?::date AND ?::date
                   ORDER BY balance_date ASC"
                  account-id from-date to-date]))

(defn get-transaction-volume-report
  "Get transaction volume report across all accounts"
  [datasource from-date to-date]
  (jdbc/execute-one! datasource
                     ["SELECT
                        COUNT(*) as total_transactions,
                        SUM(CASE WHEN transaction_type = 'DEPOSIT' THEN amount ELSE 0 END) as total_deposits,
                        SUM(CASE WHEN transaction_type = 'WITHDRAWAL' THEN amount ELSE 0 END) as total_withdrawals,
                        COUNT(DISTINCT account_id) as active_accounts,
                        AVG(amount) as avg_transaction_amount,
                        MAX(amount) as max_transaction_amount,
                        MIN(amount) as min_transaction_amount
                       FROM transactions
                       WHERE timestamp BETWEEN ? AND ?"
                      from-date to-date]))

(defn get-account-activity-report
  "Get comprehensive activity report for an account holder"
  [datasource account-holder]
  (jdbc/execute! datasource
                 ["SELECT
                    a.account_id,
                    a.account_type,
                    a.balance,
                    a.status,
                    a.created_at,
                    s.total_deposits,
                    s.total_withdrawals,
                    s.transaction_count,
                    s.last_transaction_date,
                    (SELECT COUNT(*) FROM transactions t WHERE t.account_id = a.account_id AND t.timestamp >= CURRENT_DATE - INTERVAL '30 days') as transactions_last_30_days,
                    (SELECT SUM(amount) FROM transactions t WHERE t.account_id = a.account_id AND t.transaction_type = 'DEPOSIT' AND t.timestamp >= CURRENT_DATE - INTERVAL '30 days') as deposits_last_30_days,
                    (SELECT SUM(amount) FROM transactions t WHERE t.account_id = a.account_id AND t.transaction_type = 'WITHDRAWAL' AND t.timestamp >= CURRENT_DATE - INTERVAL '30 days') as withdrawals_last_30_days
                   FROM accounts a
                   JOIN account_summary s ON a.account_id = s.account_id
                   WHERE a.account_holder = ?
                   ORDER BY a.created_at DESC"
                  account-holder]))

(defn get-accounts-with-low-balance
  "Get accounts with balance below threshold"
  [datasource threshold]
  (jdbc/execute! datasource
                 ["SELECT
                    a.account_id,
                    a.account_holder,
                    a.account_type,
                    a.balance,
                    a.status,
                    s.last_transaction_date
                   FROM accounts a
                   JOIN account_summary s ON a.account_id = s.account_id
                   WHERE a.balance < ?
                   AND a.status = 'active'
                   ORDER BY a.balance ASC"
                  threshold]))

(defn get-account-balance-trend
  "Get account balance trend over time"
  [datasource account-id days]
  (jdbc/execute! datasource
                 ["SELECT
                    balance_date,
                    closing_balance,
                    daily_deposits,
                    daily_withdrawals,
                    transaction_count,
                    closing_balance - LAG(closing_balance) OVER (ORDER BY balance_date) as balance_change
                   FROM daily_balances
                   WHERE account_id = ?
                   AND balance_date >= CURRENT_DATE - INTERVAL '? days'
                   ORDER BY balance_date DESC"
                  account-id days]))

(defn search-transactions
  "Search transactions with filters"
  [datasource {:keys [account-id transaction-type min-amount max-amount from-date to-date limit offset]
               :or {limit 50 offset 0}}]
  (let [base-query "SELECT * FROM transactions WHERE 1=1"
        conditions (cond-> []
                     account-id (conj "account_id = ?")
                     transaction-type (conj "transaction_type = ?")
                     min-amount (conj "amount >= ?")
                     max-amount (conj "amount <= ?")
                     (and from-date to-date) (conj "timestamp BETWEEN ? AND ?"))
        where-clause (if (seq conditions)
                       (str " AND " (clojure.string/join " AND " conditions))
                       "")
        query (str base-query where-clause " ORDER BY timestamp DESC LIMIT ? OFFSET ?")
        params (cond-> []
                 account-id (conj account-id)
                 transaction-type (conj transaction-type)
                 min-amount (conj min-amount)
                 max-amount (conj max-amount)
                 from-date (conj from-date)
                 to-date (conj to-date)
                 true (conj limit)
                 true (conj offset))]
    (jdbc/execute! datasource (into [query] params))))

;; Query Dispatcher

(defprotocol QueryHandler
  "Protocol for query handling"
  (execute-query [this query datasource] "Execute a query and return results"))

(defrecord AccountDetailsQueryHandler []
  QueryHandler
  (execute-query [_ query datasource]
    (get-account-details datasource (:account-id query))))

(defrecord AccountsByHolderQueryHandler []
  QueryHandler
  (execute-query [_ query datasource]
    (get-accounts-by-holder datasource (:account-holder query))))

(defrecord AccountsByStatusQueryHandler []
  QueryHandler
  (execute-query [_ query datasource]
    (get-accounts-by-status datasource (:status query))))

(defrecord TransactionHistoryQueryHandler []
  QueryHandler
  (execute-query [_ query datasource]
    (get-transaction-history datasource
                             (:account-id query)
                             (:from-date query)
                             (:to-date query)
                             (:limit query)
                             (:offset query))))

(defrecord AccountSummaryQueryHandler []
  QueryHandler
  (execute-query [_ query datasource]
    (get-account-summary datasource (:account-id query))))

(def query-handlers
  "Registry of query handlers"
  {::AccountDetailsQuery (->AccountDetailsQueryHandler)
   ::AccountsByHolderQuery (->AccountsByHolderQueryHandler)
   ::AccountsByStatusQuery (->AccountsByStatusQueryHandler)
   ::TransactionHistoryQuery (->TransactionHistoryQueryHandler)
   ::AccountSummaryQuery (->AccountSummaryQueryHandler)})

(defn dispatch-query
  "Dispatch a query to its handler"
  [query datasource]
  (let [query-type (type query)
        handler (get query-handlers query-type)]
    (if handler
      (execute-query handler query datasource)
      (throw (ex-info "No handler found for query" {:query-type query-type})))))

(comment
  ;; Example usage
  (require '[cqrs.db.read :as read])

  (def pg-db (read/init {:local? true}))

  ;; Get account details
  (get-account-details pg-db "acc-1")

  ;; Get accounts by holder
  (get-accounts-by-holder pg-db "John Doe")

  ;; Get transaction history
  (get-transaction-history pg-db "acc-1"
                           (java.sql.Timestamp. (.getTime (java.util.Date.)))
                           (java.sql.Timestamp. (.getTime (java.util.Date.)))
                           10 0)

  ;; Get account summary
  (get-account-summary pg-db "acc-1")

  ;; Get top accounts
  (get-top-accounts-by-balance pg-db 10)

  ;; Search transactions
  (search-transactions pg-db {:account-id "acc-1"
                              :transaction-type "DEPOSIT"
                              :min-amount 100.0
                              :limit 20}))
