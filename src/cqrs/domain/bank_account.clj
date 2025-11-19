(ns cqrs.domain.bank-account
  "BankAccount aggregate root - maintains consistency boundaries"
  (:require
   [cqrs.domain.aggregate :as agg]
   [cqrs.messages.account.events :as events]
   [cqrs.messages.message :as msg]
   [cqrs.messages.schema.event :refer [->EventModel]])
  (:import
   [java.util Date UUID]))

(defrecord BankAccount [id account-holder account-type balance status created-date version uncommitted-events])

(defn create-event
  "Create an event with metadata"
  [aggregate-id event-type event-data version]
  (->EventModel
   (str (UUID/randomUUID))
   (Date.)
   aggregate-id
   "BankAccount"
   version
   event-type
   event-data))

(defn apply-account-opened
  "Apply AccountOpened event to aggregate state"
  [account event]
  (map->BankAccount
   {:id (:aggregate-identifier event)
    :account-holder (get-in event [:event-data :account-holder])
    :account-type (get-in event [:event-data :account-type])
    :balance (get-in event [:event-data :opening-balance])
    :status :active
    :created-date (get-in event [:event-data :created-date])
    :version (:version event)
    :uncommitted-events []}))

(defn apply-funds-deposited
  "Apply FundsDeposited event to aggregate state"
  [account event]
  (-> account
      (update :balance + (get-in event [:event-data :amount]))
      (assoc :version (:version event))))

(defn apply-funds-withdrawn
  "Apply FundsWithdrawn event to aggregate state"
  [account event]
  (-> account
      (update :balance - (get-in event [:event-data :amount]))
      (assoc :version (:version event))))

(defn apply-account-closed
  "Apply AccountClosed event to aggregate state"
  [account event]
  (-> account
      (assoc :status :closed)
      (assoc :version (:version event))))

(defn apply-event
  "Apply an event to the bank account aggregate"
  [account event]
  (case (:event-type event)
    "AccountOpened" (apply-account-opened account event)
    "FundsDeposited" (apply-funds-deposited account event)
    "FundsWithdrawn" (apply-funds-withdrawn account event)
    "AccountClosed" (apply-account-closed account event)
    account))

(defn load-from-history
  "Reconstitute aggregate from event history"
  [events]
  (reduce apply-event nil events))

;; Command validation and business logic

(defn open-account
  "Open a new bank account"
  [account-id account-holder account-type opening-balance]
  (when (< opening-balance 0)
    (throw (ex-info "Opening balance cannot be negative" {:balance opening-balance})))

  (let [base-message (msg/->BaseMessage (str (UUID/randomUUID)) (Date.) {})
        base-event (events/->BaseEvent base-message 1)
        event-data (events/->AccountOpenedEvent
                    base-event
                    account-holder
                    account-type
                    (Date.)
                    opening-balance)
        event (create-event account-id "AccountOpened" event-data 1)]
    {:account (apply-account-opened nil event)
     :events [event]}))

(defn deposit-funds
  "Deposit funds into account"
  [account amount]
  (when-not account
    (throw (ex-info "Account does not exist" {})))
  (when (= :closed (:status account))
    (throw (ex-info "Cannot deposit to closed account" {:account-id (:id account)})))
  (when (<= amount 0)
    (throw (ex-info "Deposit amount must be positive" {:amount amount})))

  (let [new-version (inc (:version account))
        base-message (msg/->BaseMessage (str (UUID/randomUUID)) (Date.) {})
        base-event (events/->BaseEvent base-message new-version)
        event-data (events/->FundsDepositedEvent base-event amount)
        event (create-event (:id account) "FundsDeposited" event-data new-version)
        updated-account (apply-funds-deposited account event)]
    {:account (assoc updated-account :uncommitted-events [event])
     :events [event]}))

(defn withdraw-funds
  "Withdraw funds from account"
  [account amount]
  (when-not account
    (throw (ex-info "Account does not exist" {})))
  (when (= :closed (:status account))
    (throw (ex-info "Cannot withdraw from closed account" {:account-id (:id account)})))
  (when (<= amount 0)
    (throw (ex-info "Withdrawal amount must be positive" {:amount amount})))
  (when (< (:balance account) amount)
    (throw (ex-info "Insufficient funds" {:balance (:balance account) :amount amount})))

  (let [new-version (inc (:version account))
        base-message (msg/->BaseMessage (str (UUID/randomUUID)) (Date.) {})
        base-event (events/->BaseEvent base-message new-version)
        event-data (events/->FundsWithdrawnEvent base-event amount)
        event (create-event (:id account) "FundsWithdrawn" event-data new-version)
        updated-account (apply-funds-withdrawn account event)]
    {:account (assoc updated-account :uncommitted-events [event])
     :events [event]}))

(defn close-account
  "Close a bank account"
  [account]
  (when-not account
    (throw (ex-info "Account does not exist" {})))
  (when (= :closed (:status account))
    (throw (ex-info "Account is already closed" {:account-id (:id account)})))
  (when (> (:balance account) 0)
    (throw (ex-info "Cannot close account with positive balance. Withdraw funds first."
                    {:balance (:balance account)})))

  (let [new-version (inc (:version account))
        base-message (msg/->BaseMessage (str (UUID/randomUUID)) (Date.) {})
        base-event (events/->BaseEvent base-message new-version)
        event-data (events/->AccountClosedEvent base-event)
        event (create-event (:id account) "AccountClosed" event-data new-version)
        updated-account (apply-account-closed account event)]
    {:account (assoc updated-account :uncommitted-events [event])
     :events [event]}))
