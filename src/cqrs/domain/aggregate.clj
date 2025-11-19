(ns cqrs.domain.aggregate
  "Base aggregate functionality for event sourcing")

(defprotocol Aggregate
  "Protocol for aggregate root behavior"
  (apply-event [this event] "Apply an event to the aggregate state")
  (get-uncommitted-events [this] "Get events that haven't been persisted yet")
  (mark-events-committed [this] "Mark all uncommitted events as committed")
  (get-version [this] "Get the current version of the aggregate"))

(defrecord AggregateRoot [id version uncommitted-events]
  Aggregate
  (apply-event [_ _] false)
  (get-uncommitted-events [this] uncommitted-events)
  (mark-events-committed [this] (assoc this :uncommitted-events []))
  (get-version [this] version))

(defn create-aggregate
  "Create a new aggregate root"
  [id]
  (->AggregateRoot id 0 []))

(defn raise-event
  "Add an event to the aggregate's uncommitted events"
  [aggregate event]
  (-> aggregate
      (update :uncommitted-events conj event)
      (update :version inc)))
