(ns cqrs.messages.account.events
  (:require
   [cqrs.messages.message :refer [BaseMessage]]))

(defrecord BaseEvent [^BaseMessage base-message ^int version])

(defrecord AccountOpenedEvent [^BaseEvent base-event account-holder account-type created-date opening-balance])

(defrecord FundsDepositedEvent [^BaseEvent base-event amount])

(defrecord FundsWithdrawnEvent [^BaseEvent base-event amount])

(defrecord AccountClosedEvent [^BaseEvent base-event])

(defrecord FundsTransferredEvent [^BaseEvent base-event from-account-id to-account-id amount])