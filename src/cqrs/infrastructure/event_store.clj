(ns cqrs.infrastructure.event-store
  "DynamoDB-based event store for event sourcing"
  (:require
   [clojure.edn :as edn])
  (:import
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    AttributeValue
    GetItemRequest
    PutItemRequest
    QueryRequest
    UpdateItemRequest
    AttributeAction
    AttributeValueUpdate)))

(defn serialize-event
  "Serialize event to EDN string for storage"
  [event]
  (pr-str event))

(defn deserialize-event
  "Deserialize event from EDN string"
  [event-str]
  (edn/read-string event-str))

(defn ->attribute-value
  "Convert value to DynamoDB AttributeValue"
  [v]
  (cond
    (string? v) (-> (AttributeValue/builder) (.s v) (.build))
    (number? v) (-> (AttributeValue/builder) (.n (str v)) (.build))
    :else (-> (AttributeValue/builder) (.s (str v)) (.build))))

(defn append-event
  "Append an event to the event store"
  [^DynamoDbClient ddb-client table-name event]
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

        request (-> (PutItemRequest/builder)
                    (.tableName table-name)
                    (.item (into {} (map (fn [[k v]] [(name k) v]) item)))
                    (.build))]
    (.putItem ddb-client request)
    event))

(defn get-events-for-aggregate
  "Retrieve all events for a specific aggregate"
  [^DynamoDbClient ddb-client table-name aggregate-id]
  (let [request (-> (QueryRequest/builder)
                    (.tableName table-name)
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
  ;; Example usage
  (require '[cqrs.db.write :as write])

  (def ddb (write/init {:local? true}))

  ;; Create event store table
  (create-event-store-table ddb "EventStore")

  ;; Append event
  (append-event ddb "EventStore" {:id "evt-1"
                                   :aggregate-identifier "acc-1"
                                   :event-type "AccountOpened"
                                   :version 1
                                   :timestamp (java.util.Date.)
                                   :event-data {}})

  ;; Get events
  (get-events-for-aggregate ddb "EventStore" "acc-1"))
