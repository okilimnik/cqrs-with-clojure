(ns cqrs.messages.dispatcher 
  (:require
   [cqrs.messages.events :refer [BaseEvent]]))

(defn send! [^BaseEvent event & args]
  "Not implemented")