(ns cqrs.infrastructure.dynamodb-projections
  "Simple real-time projections in DynamoDB for fast queries"
  (:import
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    AttributeValue
    GetItemRequest
    PutItemRequest
    QueryRequest
    CreateTableRequest
    AttributeDefinition
    KeySchemaElement
    KeyType
    GlobalSecondaryIndex
    Projection
    ProjectionType
    ProvisionedThroughput
    ScalarAttributeType)))

(defn ->attribute-value
  "Convert value to DynamoDB AttributeValue"
  [v]
  (cond
    (string? v) (-> (AttributeValue/builder) (.s v) (.build))
    (number? v) (-> (AttributeValue/builder) (.n (str v)) (.build))
    (boolean? v) (-> (AttributeValue/builder) (.bool v) (.build))
    :else (-> (AttributeValue/builder) (.s (str v)) (.build))))

;; Account Balance Projection (for fast balance lookups)

(defn create-account-balance-table
  "Create AccountBalance projection table"
  [^DynamoDbClient ddb-client table-name]
  (let [request (-> (CreateTableRequest/builder)
                    (.tableName table-name)
                    (.attributeDefinitions
                     [(-> (AttributeDefinition/builder)
                          (.attributeName "AccountId")
                          (.attributeType ScalarAttributeType/S)
                          (.build))])
                    (.keySchema
                     [(-> (KeySchemaElement/builder)
                          (.attributeName "AccountId")
                          (.keyType KeyType/HASH)
                          (.build))])
                    (.provisionedThroughput
                     (-> (ProvisionedThroughput/builder)
                         (.readCapacityUnits 10)
                         (.writeCapacityUnits 10)
                         (.build)))
                    (.build))]
    (.createTable ddb-client request)))

(defn update-account-balance
  "Update account balance projection when events occur"
  [^DynamoDbClient ddb-client table-name account-id balance status account-holder account-type]
  (let [item {"AccountId" (->attribute-value account-id)
              "Balance" (->attribute-value balance)
              "Status" (->attribute-value (name status))
              "AccountHolder" (->attribute-value account-holder)
              "AccountType" (->attribute-value account-type)
              "LastUpdated" (->attribute-value (.getTime (java.util.Date.)))}
        request (-> (PutItemRequest/builder)
                    (.tableName table-name)
                    (.item item)
                    (.build))]
    (.putItem ddb-client request)))

(defn get-account-balance
  "Get current account balance (fast query from projection)"
  [^DynamoDbClient ddb-client table-name account-id]
  (let [request (-> (GetItemRequest/builder)
                    (.tableName table-name)
                    (.key {"AccountId" (->attribute-value account-id)})
                    (.build))
        response (.getItem ddb-client request)
        item (.item response)]
    (when-not (.isEmpty item)
      {:account-id account-id
       :balance (Double/parseDouble (.n (.get item "Balance")))
       :status (keyword (.s (.get item "Status")))
       :account-holder (.s (.get item "AccountHolder"))
       :account-type (.s (.get item "AccountType"))
       :last-updated (Long/parseLong (.n (.get item "LastUpdated")))})))

;; Transaction History Projection (for recent transactions)

(defn create-transaction-history-table
  "Create TransactionHistory projection table"
  [^DynamoDbClient ddb-client table-name]
  (let [request (-> (CreateTableRequest/builder)
                    (.tableName table-name)
                    (.attributeDefinitions
                     [(-> (AttributeDefinition/builder)
                          (.attributeName "TransactionId")
                          (.attributeType ScalarAttributeType/S)
                          (.build))
                      (-> (AttributeDefinition/builder)
                          (.attributeName "AccountId")
                          (.attributeType ScalarAttributeType/S)
                          (.build))
                      (-> (AttributeDefinition/builder)
                          (.attributeName "Timestamp")
                          (.attributeType ScalarAttributeType/N)
                          (.build))])
                    (.keySchema
                     [(-> (KeySchemaElement/builder)
                          (.attributeName "TransactionId")
                          (.keyType KeyType/HASH)
                          (.build))])
                    (.globalSecondaryIndexes
                     [(-> (GlobalSecondaryIndex/builder)
                          (.indexName "AccountIdTimestampIndex")
                          (.keySchema
                           [(-> (KeySchemaElement/builder)
                                (.attributeName "AccountId")
                                (.keyType KeyType/HASH)
                                (.build))
                            (-> (KeySchemaElement/builder)
                                (.attributeName "Timestamp")
                                (.keyType KeyType/RANGE)
                                (.build))])
                          (.projection
                           (-> (Projection/builder)
                               (.projectionType ProjectionType/ALL)
                               (.build)))
                          (.provisionedThroughput
                           (-> (ProvisionedThroughput/builder)
                               (.readCapacityUnits 10)
                               (.writeCapacityUnits 10)
                               (.build)))
                          (.build))])
                    (.provisionedThroughput
                     (-> (ProvisionedThroughput/builder)
                         (.readCapacityUnits 10)
                         (.writeCapacityUnits 10)
                         (.build)))
                    (.build))]
    (.createTable ddb-client request)))

(defn record-transaction
  "Record a transaction in the history projection"
  [^DynamoDbClient ddb-client table-name transaction-id account-id transaction-type amount timestamp]
  (let [item {"TransactionId" (->attribute-value transaction-id)
              "AccountId" (->attribute-value account-id)
              "TransactionType" (->attribute-value transaction-type)
              "Amount" (->attribute-value amount)
              "Timestamp" (->attribute-value (.getTime timestamp))}
        request (-> (PutItemRequest/builder)
                    (.tableName table-name)
                    (.item item)
                    (.build))]
    (.putItem ddb-client request)))

(defn get-recent-transactions
  "Get recent transactions for an account (fast query from projection)"
  [^DynamoDbClient ddb-client table-name account-id limit]
  (let [request (-> (QueryRequest/builder)
                    (.tableName table-name)
                    (.indexName "AccountIdTimestampIndex")
                    (.keyConditionExpression "AccountId = :accId")
                    (.expressionAttributeValues
                     {":accId" (->attribute-value account-id)})
                    (.scanIndexForward false)  ;; Descending order (newest first)
                    (.limit (int limit))
                    (.build))
        response (.query ddb-client request)
        items (.items response)]
    (mapv (fn [item]
            {:transaction-id (.s (.get item "TransactionId"))
             :account-id (.s (.get item "AccountId"))
             :transaction-type (.s (.get item "TransactionType"))
             :amount (Double/parseDouble (.n (.get item "Amount")))
             :timestamp (Long/parseLong (.n (.get item "Timestamp")))})
          items)))

;; Projection Event Handlers

(defn handle-account-opened
  "Handle AccountOpened event for projections"
  [ddb-client balance-table-name tx-table-name event]
  (let [account-id (:aggregate-identifier event)
        event-data (:event-data event)
        account-holder (:account-holder event-data)
        account-type (:account-type event-data)
        opening-balance (:opening-balance event-data)]
    (update-account-balance ddb-client balance-table-name account-id opening-balance :active account-holder account-type)
    (when (> opening-balance 0)
      (record-transaction ddb-client tx-table-name (:id event) account-id "OPENING_DEPOSIT" opening-balance (:timestamp event)))))

(defn handle-funds-deposited
  "Handle FundsDeposited event for projections"
  [ddb-client balance-table-name tx-table-name event]
  (let [account-id (:aggregate-identifier event)
        amount (get-in event [:event-data :amount])
        current-balance-data (get-account-balance ddb-client balance-table-name account-id)
        new-balance (+ (:balance current-balance-data) amount)]
    (update-account-balance ddb-client balance-table-name account-id new-balance
                            (:status current-balance-data)
                            (:account-holder current-balance-data)
                            (:account-type current-balance-data))
    (record-transaction ddb-client tx-table-name (:id event) account-id "DEPOSIT" amount (:timestamp event))))

(defn handle-funds-withdrawn
  "Handle FundsWithdrawn event for projections"
  [ddb-client balance-table-name tx-table-name event]
  (let [account-id (:aggregate-identifier event)
        amount (get-in event [:event-data :amount])
        current-balance-data (get-account-balance ddb-client balance-table-name account-id)
        new-balance (- (:balance current-balance-data) amount)]
    (update-account-balance ddb-client balance-table-name account-id new-balance
                            (:status current-balance-data)
                            (:account-holder current-balance-data)
                            (:account-type current-balance-data))
    (record-transaction ddb-client tx-table-name (:id event) account-id "WITHDRAWAL" amount (:timestamp event))))

(defn handle-account-closed
  "Handle AccountClosed event for projections"
  [ddb-client balance-table-name _tx-table-name event]
  (let [account-id (:aggregate-identifier event)
        current-balance-data (get-account-balance ddb-client balance-table-name account-id)]
    (update-account-balance ddb-client balance-table-name account-id
                            (:balance current-balance-data)
                            :closed
                            (:account-holder current-balance-data)
                            (:account-type current-balance-data))))

(defn project-event
  "Project an event to DynamoDB read models"
  [ddb-client balance-table-name tx-table-name event]
  (case (:event-type event)
    "AccountOpened" (handle-account-opened ddb-client balance-table-name tx-table-name event)
    "FundsDeposited" (handle-funds-deposited ddb-client balance-table-name tx-table-name event)
    "FundsWithdrawn" (handle-funds-withdrawn ddb-client balance-table-name tx-table-name event)
    "AccountClosed" (handle-account-closed ddb-client balance-table-name tx-table-name event)
    nil))

(comment
  ;; Example usage
  (require '[cqrs.infrastructure.event-store :as event-store])

  (def ddb (event-store/init {:local? true}))

  ;; Create projection tables
  (create-account-balance-table ddb "AccountBalance")
  (create-transaction-history-table ddb "TransactionHistory")

  ;; Query account balance
  (get-account-balance ddb "AccountBalance" "acc-1")

  ;; Query recent transactions
  (get-recent-transactions ddb "TransactionHistory" "acc-1" 10))
