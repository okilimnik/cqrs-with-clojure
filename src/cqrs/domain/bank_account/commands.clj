(ns cqrs.domain.bank-account.commands
  (:require
   [cqrs.domain.bank-account.events :as events]))

(defprotocol BaseCommand
  (handle [this event-store] "Command handling code."))

(defn- get-account-from-events
  "Reconstitute account from event store"
  [event-store account-id]
  (let [events (get event-store account-id [])]
    (when (seq events)
      (events/load-from-history events))))

(defrecord OpenAccountCommand [base-message account-holder account-type opening-balance]
  BaseCommand
  (handle [command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          existing-account (get-account-from-events event-store account-id)]
      (when existing-account
        (throw (ex-info "Account already exists" {:account-id account-id})))

      (events/open-account
       account-id
       (:account-holder command)
       (:account-type command)
       (:opening-balance command)))))

(defrecord DepositFundsCommand [base-message amount]
  BaseCommand
  (handle [command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          account (get-account-from-events event-store account-id)]
      (events/deposit-funds account (:amount command)))))

(defrecord WithdrawFundsCommand [base-message amount]
  BaseCommand
  (handle [command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          account (get-account-from-events event-store account-id)]
      (events/withdraw-funds account (:amount command)))))

(defrecord CloseAccountCommand [base-message]
  BaseCommand
  (handle [command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          account (get-account-from-events event-store account-id)]
      (events/close-account account))))

(defrecord TransferFundsCommand [base-message from-account-id to-account-id amount]
  BaseCommand
  (handle [command event-store]
    (let [from-account-id (:from-account-id command)
          to-account-id (:to-account-id command)
          amount (:amount command)
          from-account (get-account-from-events event-store from-account-id)
          to-account (get-account-from-events event-store to-account-id)]

      ;; This is a saga/process manager scenario - creates two events
      (when-not from-account
        (throw (ex-info "Source account does not exist" {:account-id from-account-id})))
      (when-not to-account
        (throw (ex-info "Destination account does not exist" {:account-id to-account-id})))

      (let [withdrawal-result (events/withdraw-funds from-account amount)
            deposit-result (events/deposit-funds to-account amount)]
        {:account nil  ;; No single aggregate updated
         :events (concat (:events withdrawal-result) (:events deposit-result))}))))