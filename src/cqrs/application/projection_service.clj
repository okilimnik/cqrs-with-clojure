(ns cqrs.application.projection-service
  "Projection service that runs independently to update read models.
   This service processes events from DynamoDB Streams and updates projections.
   It can run in a separate microservice or thread."
  (:require
   [cqrs.infrastructure.dynamodb-projections :as dynamo-proj]
   [cqrs.infrastructure.postgres-projections :as pg-proj]))

(defrecord ProjectionService [ddb-client pg-datasource balance-table tx-table])

(defn create-projection-service
  "Create a new ProjectionService instance"
  [ddb-client pg-datasource]
  (->ProjectionService
   ddb-client
   pg-datasource
   "AccountBalance"
   "TransactionHistory"))

(defn project-event
  "Project a single event to all read models (DynamoDB + Postgres).
   This function is called by the stream processor for each event."
  [service event]
  (let [event-type (:event-type event)
        event-id (:id event)
        aggregate-id (:aggregate-identifier event)]

    (println (str "[PROJECTION] Processing " event-type " event " event-id " for aggregate " aggregate-id))

    ;; Project to DynamoDB (fast, simple queries)
    (try
      (dynamo-proj/project-event
       (:ddb-client service)
       (:balance-table service)
       (:tx-table service)
       event)
      (println (str "[PROJECTION] DynamoDB projection completed for " event-id))
      (catch Exception e
        (println (str "[PROJECTION] ERROR: DynamoDB projection failed for " event-id " - " (.getMessage e)))))

    ;; Project to Postgres (complex queries, analytics)
    (try
      (pg-proj/project-event-to-postgres (:pg-datasource service) event)
      (println (str "[PROJECTION] Postgres projection completed for " event-id))
      (catch Exception e
        (println (str "[PROJECTION] ERROR: Postgres projection failed for " event-id " - " (.getMessage e)))))))

(defn create-projection-handler
  "Create a projection handler function for use with stream processor"
  [service]
  (fn [event]
    (project-event service event)))

(comment
  ;; Example usage
  (require '[cqrs.infrastructure.event-store :as event-store])
  (require '[cqrs.cqrs.infrastructure.postgres-projections :as pg-proj])

  ;; Initialize databases
  (def ddb (event-store/init {:local? true}))
  (def pg-db (pg-proj/init {:local? true}))

  ;; Create projection service
  (def proj-service (create-projection-service ddb pg-db))

  ;; Create projection handler for stream processor
  (def handler (create-projection-handler proj-service))

  ;; Process an event (would normally be called by stream processor)
  (handler {:id "evt-123"
            :aggregate-identifier "acc-123"
            :event-type "AccountOpened"
            :timestamp (java.util.Date.)
            :event-data {:account-holder "John Doe"
                         :account-type "CHECKING"
                         :opening-balance 1000.0
                         :created-date (java.util.Date.)}}))
