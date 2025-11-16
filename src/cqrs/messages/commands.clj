(ns cqrs.messages.commands 
  (:require
   [cqrs.messages.message :refer [BaseMessage]]))

(defrecord BaseCommand [^BaseMessage base-message])

(defrecord OpenAccountCommand [^BaseCommand base-command account-holder account-type opening-balance])

(defrecord DepositFundsCommand [^BaseCommand base-command amount])

(defrecord WithdrawFundsCommand [^BaseCommand base-command amount])

(defrecord CloseAccountCommand [^BaseCommand base-command])