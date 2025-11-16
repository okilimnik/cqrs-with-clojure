(ns cqrs.messages.message)

(defrecord BaseMessage [id timestamp content])