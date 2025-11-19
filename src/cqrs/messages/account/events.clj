(ns cqrs.messages.account.events)

(defrecord BaseEvent [base-message version])

(defrecord AccountOpenedEvent [base-event account-holder account-type created-date opening-balance])

(defrecord FundsDepositedEvent [base-event amount])

(defrecord FundsWithdrawnEvent [base-event amount])

(defrecord AccountClosedEvent [base-event])

(defrecord FundsTransferredEvent [base-event from-account-id to-account-id amount])