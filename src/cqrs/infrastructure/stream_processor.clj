(ns cqrs.infrastructure.stream-processor
  "DynamoDB Streams processor that listens to EventStore changes
   and asynchronously projects events to read models.

   This decouples the write side (commands -> events) from the read side
   (events -> projections), allowing each to scale independently."
  (:require
   [clojure.edn :as edn]
   [cqrs.domain.bank-account.events :as events]
   [cqrs.schemat-model])
  (:import
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model
    DescribeStreamRequest
    GetRecordsRequest
    GetShardIteratorRequest
    ShardIteratorType)
   (software.amazon.awssdk.services.dynamodbstreams DynamoDbStreamsClient)
   (software.amazon.awssdk.services.dynamodbstreams.model
    GetRecordsRequest
    GetShardIteratorRequest
    DescribeStreamRequest
    ShardIteratorType)))

(defn deserialize-event
  "Deserialize event from EDN string"
  [event-str]
  (edn/read-string {:readers events/edn-readers} event-str))

;; Stream Discovery

(defn get-table-stream-arn
  "Get the Stream ARN for a DynamoDB table"
  [^DynamoDbClient ddb-client table-name]
  (let [request (-> (software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest/builder)
                    (.tableName table-name)
                    (.build))
        response (.describeTable ddb-client request)
        table (.table response)
        stream-spec (.streamSpecification table)]
    (when (.streamEnabled stream-spec)
      (.latestStreamArn table))))

;; Stream Record Processing

(defn parse-stream-record
  "Parse a DynamoDB stream record to extract the event"
  [stream-record]
  (when (= "INSERT" (.eventName stream-record))
    (let [new-image (.newImage (.dynamodb stream-record))
          event-data-attr (.get new-image "EventData")]
      (when event-data-attr
        (try
          (deserialize-event (.s event-data-attr))
          (catch Exception e
            (println "ERROR: Failed to deserialize event:" (.getMessage e))
            nil))))))

(defn process-stream-records
  "Process a batch of stream records through projection handlers"
  [records projection-handler]
  (doseq [record records]
    (when-let [event (parse-stream-record record)]
      (try
        (println (str "Processing event: " (:event-type event) " for aggregate " (:aggregate-identifier event)))
        (projection-handler event)
        (catch Exception e
          (println (str "ERROR: Failed to project event " (:id event) " - " (.getMessage e))))))))

;; Shard Iterator Management

(defn get-shard-iterator
  "Get a shard iterator for reading from a stream"
  [^DynamoDbStreamsClient streams-client stream-arn shard-id iterator-type]
  (let [request (-> (GetShardIteratorRequest/builder)
                    (.streamArn stream-arn)
                    (.shardId shard-id)
                    (.shardIteratorType iterator-type)
                    (.build))
        response (.getShardIterator streams-client request)]
    (.shardIterator response)))

(defn get-stream-shards
  "Get all shards for a stream"
  [^DynamoDbStreamsClient streams-client stream-arn]
  (let [request (-> (DescribeStreamRequest/builder)
                    (.streamArn stream-arn)
                    (.build))
        response (.describeStream streams-client request)
        stream-description (.streamDescription response)]
    (.shards stream-description)))

;; Stream Polling Loop

(defn poll-shard
  "Poll a single shard for new records"
  [^DynamoDbStreamsClient streams-client shard-iterator projection-handler]
  (try
    (let [request (-> (GetRecordsRequest/builder)
                      (.shardIterator shard-iterator)
                      (.limit 100)
                      (.build))
          response (.getRecords streams-client request)
          records (.records response)
          next-iterator (.nextShardIterator response)]

      ;; Process records if any
      (when (seq records)
        (println (str "Received " (count records) " stream records"))
        (process-stream-records records projection-handler))

      ;; Return next iterator for continued polling
      next-iterator)
    (catch Exception e
      (println "ERROR polling shard:" (.getMessage e))
      nil)))

(defn create-stream-poller
  "Create a stream poller for a specific shard.
   Returns a function that when called, polls the shard once."
  [streams-client stream-arn shard-id projection-handler]
  (let [shard-iterator-atom (atom (get-shard-iterator
                                   streams-client
                                   stream-arn
                                   shard-id
                                   ShardIteratorType/LATEST))]
    (fn []
      (when-let [current-iterator @shard-iterator-atom]
        (when-let [next-iterator (poll-shard streams-client current-iterator projection-handler)]
          (reset! shard-iterator-atom next-iterator)
          true)))))

;; Stream Processor Lifecycle

(defrecord StreamProcessor [streams-client stream-arn projection-handler pollers running?])

(defn create-stream-processor
  "Create a stream processor for a DynamoDB table"
  [ddb-client streams-client table-name projection-handler]
  (let [stream-arn (get-table-stream-arn ddb-client table-name)]
    (if stream-arn
      (do
        (println (str "Found stream ARN: " stream-arn))
        (->StreamProcessor streams-client stream-arn projection-handler nil (atom false)))
      (throw (ex-info "DynamoDB Streams not enabled for table" {:table-name table-name})))))

(defn start-processor
  "Start the stream processor in a background thread"
  [^StreamProcessor processor]
  (when (compare-and-set! (:running? processor) false true)
    (println "Starting stream processor...")

    ;; Get all shards and create pollers
    (let [shards (get-stream-shards (:streams-client processor) (:stream-arn processor))
          pollers (mapv (fn [shard]
                          (create-stream-poller
                           (:streams-client processor)
                           (:stream-arn processor)
                           (.shardId shard)
                           (:projection-handler processor)))
                        shards)]

      (println (str "Created " (count pollers) " shard pollers"))

      ;; Start polling thread
      (future
        (try
          (while @(:running? processor)
            ;; Poll all shards
            (doseq [poller pollers]
              (poller))

            ;; Sleep between polls
            (Thread/sleep 1000))
          (catch Exception e
            (println "ERROR in stream processor:" (.getMessage e))
            (reset! (:running? processor) false))))

      (assoc processor :pollers pollers))))

(defn stop-processor
  "Stop the stream processor"
  [^StreamProcessor processor]
  (when (compare-and-set! (:running? processor) true false)
    (println "Stopping stream processor...")
    processor))

(comment
  ;; Example usage
  (require '[cqrs.infrastructure.event-store :as event-store])
  (require '[cqrs.infrastructure.dynamodb-projections :as dynamo-proj])
  (require '[cqrs.infrastructure.postgres-projections :as pg-proj])

  ;; Initialize clients
  (def ddb (event-store/init {:local? true}))
  (def pg-db (pg-proj/init {:local? true}))

  ;; Create streams client
  (def streams-client
    (-> (DynamoDbStreamsClient/builder)
        (.endpointOverride (java.net.URI. "http://localhost:8000"))
        (.build)))

  ;; Define projection handler that projects to both DynamoDB and Postgres
  (defn project-event [event]
    (try
      ;; Project to DynamoDB
      (dynamo-proj/project-event ddb "AccountBalance" "TransactionHistory" event)
      (catch Exception e
        (println "DynamoDB projection error:" (.getMessage e))))

    (try
      ;; Project to Postgres
      (pg-proj/project-event-to-postgres pg-db event)
      (catch Exception e
        (println "Postgres projection error:" (.getMessage e)))))

  ;; Create and start processor
  (def processor (create-stream-processor ddb streams-client "EventStore" project-event))
  (def running-processor (start-processor processor))

  ;; Stop processor
  (stop-processor running-processor))
