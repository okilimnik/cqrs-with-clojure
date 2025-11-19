(ns cqrs.messages.schema.event)

(defrecord EventModel [id timestamp aggregate-identifier aggregate-type version event-type event-data])