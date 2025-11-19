(ns cqrs.handlers.command-handler
  "Command handlers for processing commands and generating events using multimethods"
  (:require
   [cqrs.domain.bank-account :as bank-account]
   [cqrs.messages.commands])
  (:import
   [cqrs.messages.commands
    OpenAccountCommand
    DepositFundsCommand
    WithdrawFundsCommand
    CloseAccountCommand
    TransferFundsCommand]))

;; Helper Functions

(defn get-account-from-events
  "Reconstitute account from event store"
  [event-store account-id]
  (let [events (get event-store account-id [])]
    (when (seq events)
      (bank-account/load-from-history events))))

;; Multimethod for Command Handling

(defmulti handle-command
  "Handle a command based on its type and return events"
  (fn [command _event-store] (type command)))

;; Open Account Command Handler

(defmethod handle-command OpenAccountCommand
  [command event-store]
  (let [account-id (get-in command [:base-command :base-message :id])
        existing-account (get-account-from-events event-store account-id)]
    (when existing-account
      (throw (ex-info "Account already exists" {:account-id account-id})))

    (bank-account/open-account
     account-id
     (:account-holder command)
     (:account-type command)
     (:opening-balance command))))

;; Deposit Funds Command Handler

(defmethod handle-command DepositFundsCommand
  [command event-store]
  (let [account-id (get-in command [:base-command :base-message :id])
        account (get-account-from-events event-store account-id)]
    (bank-account/deposit-funds account (:amount command))))

;; Withdraw Funds Command Handler

(defmethod handle-command WithdrawFundsCommand
  [command event-store]
  (let [account-id (get-in command [:base-command :base-message :id])
        account (get-account-from-events event-store account-id)]
    (bank-account/withdraw-funds account (:amount command))))

;; Close Account Command Handler

(defmethod handle-command CloseAccountCommand
  [command event-store]
  (let [account-id (get-in command [:base-command :base-message :id])
        account (get-account-from-events event-store account-id)]
    (bank-account/close-account account)))

;; Transfer Funds Command Handler

(defmethod handle-command TransferFundsCommand
  [command event-store]
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

    (let [withdrawal-result (bank-account/withdraw-funds from-account amount)
          deposit-result (bank-account/deposit-funds to-account amount)]
      {:account nil  ;; No single aggregate updated
       :events (concat (:events withdrawal-result) (:events deposit-result))})))

;; Default handler for unknown commands

(defmethod handle-command :default
  [command _event-store]
  (throw (ex-info "No handler found for command"
                  {:command-type (type command)
                   :command command})))

;; Public API

(defn dispatch-command
  "Dispatch a command to its handler using multimethod dispatch"
  [command event-store]
  (prn "command: " command)
  (handle-command command event-store))
