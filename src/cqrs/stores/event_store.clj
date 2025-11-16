(ns cqrs.stores.event-store
  (:import
   (java.net URI)
   (software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider)
   (software.amazon.awssdk.regions Region)
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    AttributeDefinition
    CreateTableRequest
    DescribeTableRequest
    KeySchemaElement
    KeyType
    ProvisionedThroughput
    ResourceNotFoundException
    ScalarAttributeType)))

(defn get-table [^DynamoDbClient ddb table-name]
  (try
    (let [request (-> (DescribeTableRequest/builder)
                      (.tableName table-name)
                      (.build))
          response (.describeTable ddb request)]
      (.. response (table) (tableName)))
    (catch ResourceNotFoundException _ nil)))

(defn create-table [^DynamoDbClient ddb table-name key!]
  (let [db-waiter (.waiter ddb)
        request (-> (CreateTableRequest/builder)
                    (.attributeDefinitions [(-> (AttributeDefinition/builder)
                                                (.attributeName key!)
                                                (.attributeType ScalarAttributeType/S)
                                                (.build))])
                    (.keySchema [(-> (KeySchemaElement/builder)
                                     (.attributeName key!)
                                     (.keyType KeyType/HASH)
                                     (.build))])
                    (.provisionedThroughput (-> (ProvisionedThroughput/builder)
                                                (.readCapacityUnits 10)
                                                (.writeCapacityUnits 10)
                                                (.build)))
                    (.tableName table-name)
                    (.build))
        response (.createTable ddb request)
        table-request (-> (DescribeTableRequest/builder)
                          (.tableName table-name)
                          (.build))
        waiter-response (.waitUntilTableExists db-waiter table-request)
        _ (.. waiter-response (matched) (response) (ifPresent prn))]
    (.. response (tableDescription) (tableName))))

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
         table-name "EventStore"
         key! "Event"]
     (if (get-table ddb table-name)
       (prn "Table EventStore already exists")
       (do
         (prn "Creating EventStore table")
         (create-table ddb table-name key!))))))