(ns cqrs.handlers.command-handler
  "Command handlers for processing commands and generating events"
  (:require
   [cqrs.domain.bank-account :as bank-account]
   [cqrs.messages.commands :as commands]))

(defprotocol CommandHandler
  "Protocol for command handling"
  (handle [this command event-store] "Handle a command and return events"))

(defn get-account-from-events
  "Reconstitute account from event store"
  [event-store account-id]
  (let [events (get event-store account-id [])]
    (when (seq events)
      (bank-account/load-from-history events))))

;; Open Account Command Handler

(defrecord OpenAccountCommandHandler []
  CommandHandler
  (handle [_ command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          existing-account (get-account-from-events event-store account-id)]
      (when existing-account
        (throw (ex-info "Account already exists" {:account-id account-id})))

      (bank-account/open-account
       account-id
       (:account-holder command)
       (:account-type command)
       (:opening-balance command)))))

;; Deposit Funds Command Handler

(defrecord DepositFundsCommandHandler []
  CommandHandler
  (handle [_ command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          account (get-account-from-events event-store account-id)]
      (bank-account/deposit-funds account (:amount command)))))

;; Withdraw Funds Command Handler

(defrecord WithdrawFundsCommandHandler []
  CommandHandler
  (handle [_ command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          account (get-account-from-events event-store account-id)]
      (bank-account/withdraw-funds account (:amount command)))))

;; Close Account Command Handler

(defrecord CloseAccountCommandHandler []
  CommandHandler
  (handle [_ command event-store]
    (let [account-id (get-in command [:base-command :base-message :id])
          account (get-account-from-events event-store account-id)]
      (bank-account/close-account account))))

;; Transfer Funds Command Handler

(defrecord TransferFundsCommandHandler []
  CommandHandler
  (handle [_ command event-store]
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
         :events (concat (:events withdrawal-result) (:events deposit-result))}))))

;; Command Dispatcher

(def command-handlers
  "Registry of command handlers"
  {::commands/OpenAccountCommand (->OpenAccountCommandHandler)
   ::commands/DepositFundsCommand (->DepositFundsCommandHandler)
   ::commands/WithdrawFundsCommand (->WithdrawFundsCommandHandler)
   ::commands/CloseAccountCommand (->CloseAccountCommandHandler)
   ::commands/TransferFundsCommand (->TransferFundsCommandHandler)})

(defn dispatch-command
  "Dispatch a command to its handler"
  [command event-store]
  (let [command-type (type command)
        handler (get command-handlers command-type)]
    (if handler
      (handle handler command event-store)
      (throw (ex-info "No handler found for command" {:command-type command-type})))))
