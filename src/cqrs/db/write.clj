(ns cqrs.db.write
  (:require
   [cqrs.infrastructure.event-store :as event-store])
  (:import
   (java.net URI)
   (software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider)
   (software.amazon.awssdk.regions Region)
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    DescribeTableRequest
    ResourceNotFoundException)))

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
         (event-store/create-event-store-table ddb table-name)))
     ddb)))