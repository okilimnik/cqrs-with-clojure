(ns cqrs.schema)

(defrecord BaseMessage [id timestamp content])

(defrecord BaseEvent [base-message version])

(defrecord EventModel [id timestamp aggregate-identifier aggregate-type version event-type event-data])