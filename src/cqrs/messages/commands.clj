(ns cqrs.messages.commands)

(defrecord BaseCommand [base-message])

(defrecord OpenAccountCommand [base-command account-holder account-type opening-balance])

(defrecord DepositFundsCommand [base-command amount])

(defrecord WithdrawFundsCommand [base-command amount])

(defrecord CloseAccountCommand [base-command])

(defrecord TransferFundsCommand [base-command from-account-id to-account-id amount])