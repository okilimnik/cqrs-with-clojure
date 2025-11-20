(ns cqrs.infrastructure.event-store
  "DynamoDB-based event store for event sourcing"
  (:require
   [clojure.edn :as edn]
   [cqrs.domain.bank-account.events :as events]
   [cqrs.schemat-model])
  (:import
   [java.net URI]
   [software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider]
   (software.amazon.awssdk.regions Region)
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    DescribeTableRequest
    ResourceNotFoundException
    AttributeValue
    ConditionalCheckFailedException
    Put
    PutItemRequest
    QueryRequest
    TransactWriteItem
    TransactWriteItemsRequest
    TransactionCanceledException)))

(defn serialize-event
  "Serialize event to EDN string for storage"
  [event]
  (pr-str event))

(defn deserialize-event
  "Deserialize event from EDN string with custom readers"
  [event-str]
  (edn/read-string {:readers events/edn-readers} event-str))

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

(defn get-table [^DynamoDbClient ddb table-name]
  (try
    (let [request (-> (DescribeTableRequest/builder)
                      (.tableName table-name)
                      (.build))
          response (.describeTable ddb request)]
      (.. response (table) (tableName)))
    (catch ResourceNotFoundException _ nil)))

(defn init
  "Initialize the EventStore.
  Options:
    :local? - If true, connects to local DynamoDB on http://localhost:8000"
  ([] (init {}))
  ([{:keys [local?] :or {local? false}}]
   (let [builder (DynamoDbClient/builder)
         ddb (if local?
               (let [test-credentials (AwsBasicCredentials/create "test" "test")
                     credentials-provider (StaticCredentialsProvider/create test-credentials)]
                 (-> builder
                     (.region Region/US_EAST_1)
                     (.endpointOverride (URI/create "http://localhost:8000"))
                     (.credentialsProvider credentials-provider)
                     (.build)))
               (-> builder
                   (.region Region/US_EAST_1)
                   (.build)))
         table-name "EventStore"]
     (if (get-table ddb table-name)
       (prn "Table EventStore already exists")
       (do
         (prn "Creating EventStore table with GSI")
         (create-event-store-table ddb table-name)))
     ddb)))
