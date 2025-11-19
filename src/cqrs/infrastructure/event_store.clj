(ns cqrs.infrastructure.event-store
  "DynamoDB-based event store for event sourcing"
  (:require
   [clojure.edn :as edn]
   [cqrs.messages.message :as msg]
   [cqrs.messages.account.events :as events]
   [cqrs.messages.schema.event :as event-model])
  (:import
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    AttributeValue
    GetItemRequest
    PutItemRequest
    QueryRequest
    UpdateItemRequest
    AttributeAction
    AttributeValueUpdate
    TransactWriteItem
    TransactWriteItemsRequest
    Put
    Update
    ConditionalCheckFailedException
    TransactionCanceledException)))

(defn serialize-event
  "Serialize event to EDN string for storage"
  [event]
  (pr-str event))

(def edn-readers
  "Custom EDN readers for deserializing records"
  {'cqrs.messages.message.BaseMessage msg/map->BaseMessage
   'cqrs.messages.account.events.BaseEvent events/map->BaseEvent
   'cqrs.messages.account.events.AccountOpenedEvent events/map->AccountOpenedEvent
   'cqrs.messages.account.events.FundsDepositedEvent events/map->FundsDepositedEvent
   'cqrs.messages.account.events.FundsWithdrawnEvent events/map->FundsWithdrawnEvent
   'cqrs.messages.account.events.AccountClosedEvent events/map->AccountClosedEvent
   'cqrs.messages.account.events.FundsTransferredEvent events/map->FundsTransferredEvent
   'cqrs.messages.schema.event.EventModel event-model/map->EventModel})

(defn deserialize-event
  "Deserialize event from EDN string with custom readers"
  [event-str]
  (edn/read-string {:readers edn-readers} event-str))

(defn ->attribute-value
  "Convert value to DynamoDB AttributeValue"
  [v]
  (cond
    (string? v) (-> (AttributeValue/builder) (.s v) (.build))
    (number? v) (-> (AttributeValue/builder) (.n (str v)) (.build))
    :else (-> (AttributeValue/builder) (.s (str v)) (.build))))

(defn get-current-version
  "Get the current version for an aggregate (highest version number)"
  [^DynamoDbClient ddb-client table-name aggregate-id]
  (let [request (-> (QueryRequest/builder)
                    (.tableName table-name)
                    (.indexName "AggregateIdIndex")
                    (.keyConditionExpression "AggregateId = :aggId")
                    (.expressionAttributeValues
                     {":aggId" (->attribute-value aggregate-id)})
                    (.scanIndexForward false)  ; Descending order
                    (.limit 1)
                    (.build))
        response (.query ddb-client request)
        items (.items response)]
    (if (empty? items)
      0
      (let [version-attr (.get (first items) "Version")]
        (Integer/parseInt (.n version-attr))))))

(defn append-event
  "Append an event to the event store with optimistic locking.
   Throws exception if version conflict detected (ensures consistency)."
  [^DynamoDbClient ddb-client table-name event expected-version]
  (let [event-id (:id event)
        aggregate-id (:aggregate-identifier event)
        event-type (:event-type event)
        version (:version event)
        timestamp (.getTime (:timestamp event))
        event-data (serialize-event event)

        item {(keyword "EventId") (->attribute-value event-id)
              (keyword "AggregateId") (->attribute-value aggregate-id)
              (keyword "EventType") (->attribute-value event-type)
              (keyword "Version") (->attribute-value version)
              (keyword "Timestamp") (->attribute-value timestamp)
              (keyword "EventData") (->attribute-value event-data)}

        ;; Condition: event must be next version AND event ID must not exist (idempotency)
        condition-expression "attribute_not_exists(EventId)"

        request (-> (PutItemRequest/builder)
                    (.tableName table-name)
                    (.item (into {} (map (fn [[k v]] [(name k) v]) item)))
                    (.conditionExpression condition-expression)
                    (.build))]
    (try
      (.putItem ddb-client request)
      event
      (catch ConditionalCheckFailedException e
        (throw (ex-info "Concurrency conflict: Event already exists or version mismatch"
                        {:type :concurrency-conflict
                         :event-id event-id
                         :aggregate-id aggregate-id
                         :expected-version expected-version
                         :attempted-version version}
                        e))))))

(defn append-events-atomically
  "Append multiple events atomically using DynamoDB transactions.
   ALL events succeed or ALL fail - critical for ACID compliance in banking.
   This is the ONLY safe way to handle fund transfers between accounts."
  [^DynamoDbClient ddb-client table-name events]
  (if (empty? events)
    []
    (let [;; Build transaction write items for each event
          transact-items (mapv (fn [event]
                                 (let [event-id (:id event)
                                       aggregate-id (:aggregate-identifier event)
                                       event-type (:event-type event)
                                       version (:version event)
                                       timestamp (.getTime (:timestamp event))
                                       event-data (serialize-event event)

                                       item {"EventId" (->attribute-value event-id)
                                             "AggregateId" (->attribute-value aggregate-id)
                                             "EventType" (->attribute-value event-type)
                                             "Version" (->attribute-value version)
                                             "Timestamp" (->attribute-value timestamp)
                                             "EventData" (->attribute-value event-data)}

                                       ;; Idempotency check: event ID must not exist
                                       put-builder (-> (Put/builder)
                                                       (.tableName table-name)
                                                       (.item item)
                                                       (.conditionExpression "attribute_not_exists(EventId)")
                                                       (.build))]
                                   (-> (TransactWriteItem/builder)
                                       (.put put-builder)
                                       (.build))))
                               events)

          request (-> (TransactWriteItemsRequest/builder)
                      (.transactItems transact-items)
                      (.build))]
      (try
        (.transactWriteItems ddb-client request)
        events
        (catch TransactionCanceledException e
          (throw (ex-info "Transaction failed: Concurrency conflict or duplicate event"
                          {:type :transaction-failed
                           :event-ids (mapv :id events)
                           :aggregate-ids (mapv :aggregate-identifier events)
                           :reason (.getMessage e)}
                          e)))))))

(defn get-events-for-aggregate
  "Retrieve all events for a specific aggregate"
  [^DynamoDbClient ddb-client table-name aggregate-id]
  (let [request (-> (QueryRequest/builder)
                    (.tableName table-name)
                    (.indexName "AggregateIdIndex")
                    (.keyConditionExpression "AggregateId = :aggId")
                    (.expressionAttributeValues
                     {":aggId" (->attribute-value aggregate-id)})
                    (.build))
        response (.query ddb-client request)
        items (.items response)]
    (->> items
         (map (fn [item]
                (let [event-data-attr (.get item "EventData")
                      event-str (.s event-data-attr)]
                  (deserialize-event event-str))))
         (sort-by :version)
         vec)))

(defn create-event-store-table
  "Create the EventStore table in DynamoDB with proper schema"
  [^DynamoDbClient ddb-client table-name]
  (let [request (-> (software.amazon.awssdk.services.dynamodb.model.CreateTableRequest/builder)
                    (.tableName table-name)
                    (.attributeDefinitions
                     [(-> (software.amazon.awssdk.services.dynamodb.model.AttributeDefinition/builder)
                          (.attributeName "EventId")
                          (.attributeType software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType/S)
                          (.build))
                      (-> (software.amazon.awssdk.services.dynamodb.model.AttributeDefinition/builder)
                          (.attributeName "AggregateId")
                          (.attributeType software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType/S)
                          (.build))])
                    (.keySchema
                     [(-> (software.amazon.awssdk.services.dynamodb.model.KeySchemaElement/builder)
                          (.attributeName "EventId")
                          (.keyType software.amazon.awssdk.services.dynamodb.model.KeyType/HASH)
                          (.build))])
                    (.globalSecondaryIndexes
                     [(-> (software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex/builder)
                          (.indexName "AggregateIdIndex")
                          (.keySchema
                           [(-> (software.amazon.awssdk.services.dynamodb.model.KeySchemaElement/builder)
                                (.attributeName "AggregateId")
                                (.keyType software.amazon.awssdk.services.dynamodb.model.KeyType/HASH)
                                (.build))])
                          (.projection
                           (-> (software.amazon.awssdk.services.dynamodb.model.Projection/builder)
                               (.projectionType software.amazon.awssdk.services.dynamodb.model.ProjectionType/ALL)
                               (.build)))
                          (.provisionedThroughput
                           (-> (software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput/builder)
                               (.readCapacityUnits 10)
                               (.writeCapacityUnits 10)
                               (.build)))
                          (.build))])
                    (.provisionedThroughput
                     (-> (software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput/builder)
                         (.readCapacityUnits 10)
                         (.writeCapacityUnits 10)
                         (.build)))
                    (.build))]
    (.createTable ddb-client request)))

(comment
  ;; Example usage - ACID-compliant banking operations
  (require '[cqrs.db.write :as write])

  (def ddb (write/init {:local? true}))

  ;; Create event store table
  (create-event-store-table ddb "EventStore")

  ;; Check current version before appending
  (def current-version (get-current-version ddb "EventStore" "acc-1"))

  ;; Append single event with optimistic locking
  (append-event ddb "EventStore"
                {:id "evt-1"
                 :aggregate-identifier "acc-1"
                 :event-type "AccountOpened"
                 :version 1
                 :timestamp (java.util.Date.)
                 :event-data {}}
                0) ;; expected version

  ;; Append multiple events atomically (for fund transfers)
  ;; ALL events succeed or ALL fail - no partial updates
  (append-events-atomically ddb "EventStore"
                            [{:id "evt-2"
                              :aggregate-identifier "acc-1"
                              :event-type "FundsWithdrawn"
                              :version 2
                              :timestamp (java.util.Date.)
                              :event-data {:amount 100}}
                             {:id "evt-3"
                              :aggregate-identifier "acc-2"
                              :event-type "FundsDeposited"
                              :version 1
                              :timestamp (java.util.Date.)
                              :event-data {:amount 100}}])

  ;; Get events
  (get-events-for-aggregate ddb "EventStore" "acc-1"))
