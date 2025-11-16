(ns cqrs.messages.schema.event 
  (:require
   [cqrs.messages.account.events :refer [BaseEvent]]) 
  (:import
   [java.util Date]))

(defrecord EventModel [^String id ^Date timestamp ^String aggregate-identifier ^String aggregate-type ^int version ^String event-type ^BaseEvent event-data])