# CQRS Architecture with DynamoDB Streams

## Overview

This project implements a fully decoupled CQRS (Command Query Responsibility Segregation) architecture using DynamoDB Streams to separate the write side (commands/events) from the read side (projections/queries).

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         WRITE SIDE                              │
│                                                                 │
│  ┌─────────┐      ┌──────────────┐      ┌──────────────┐     │
│  │ Command │─────>│ Command      │─────>│  EventStore  │     │
│  │         │      │ Handler      │      │  (DynamoDB)  │     │
│  └─────────┘      └──────────────┘      └──────┬───────┘     │
│                                                  │             │
└──────────────────────────────────────────────────┼─────────────┘
                                                   │
                                    ┌──────────────▼─────────────┐
                                    │   DynamoDB Streams         │
                                    │  (Change Data Capture)     │
                                    └──────────────┬─────────────┘
                                                   │
┌──────────────────────────────────────────────────┼─────────────┐
│                         READ SIDE                │             │
│                                                  │             │
│                                    ┌─────────────▼────────┐    │
│                                    │ Stream Processor     │    │
│                                    │ (Background Thread)  │    │
│                                    └─────────┬────────────┘    │
│                                              │                 │
│                                   ┌──────────▼──────────┐      │
│                                   │ Projection Service  │      │
│                                   └──────────┬──────────┘      │
│                                              │                 │
│                       ┌──────────────────────┴─────────────┐   │
│                       │                                    │   │
│              ┌────────▼─────────┐              ┌──────────▼──┐ │
│              │ DynamoDB         │              │  Postgres   │ │
│              │ Projections      │              │ Projections │ │
│              │ (Fast queries)   │              │ (Complex)   │ │
│              └────────┬─────────┘              └──────────┬──┘ │
│                       │                                   │    │
│                       └───────────────┬───────────────────┘    │
│                                       │                        │
│                              ┌────────▼────────┐               │
│                              │ Query Handlers  │               │
│                              └─────────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

## Key Components

### Write Side

#### 1. Bank Account Service ([bank_account_service.clj](src/cqrs/application/bank_account_service.clj))
- Handles commands (OpenAccount, Deposit, Withdraw, Transfer, CloseAccount)
- Loads aggregates from event history
- Dispatches commands to command handlers
- **Only writes to EventStore** (no projections)
- Uses atomic DynamoDB transactions for ACID compliance

#### 2. Event Store ([event_store.clj](src/cqrs/infrastructure/event_store.clj))
- Stores all events in DynamoDB
- Provides atomicity guarantees for multi-event operations
- Source of truth for the entire system
- DynamoDB Streams enabled for change data capture

### Read Side

#### 3. Stream Processor ([stream_processor.clj](src/cqrs/infrastructure/stream_processor.clj))
- Polls DynamoDB Streams for new events
- Runs in background thread (simulates separate microservice)
- Deserializes events from stream records
- Dispatches events to projection handlers
- Handles multiple shards concurrently

#### 4. Projection Service ([projection_service.clj](src/cqrs/application/projection_service.clj))
- Receives events from stream processor
- Updates DynamoDB projections (fast, simple queries)
- Updates Postgres projections (complex queries, analytics)
- Handles projection failures gracefully

#### 5. Projections
- **DynamoDB Projections** ([dynamodb_projections.clj](src/cqrs/infrastructure/dynamodb_projections.clj))
  - AccountBalance: Current balance and account status
  - TransactionHistory: Recent transactions
  - Optimized for fast, simple queries

- **Postgres Projections** ([postgres_projections.clj](src/cqrs/infrastructure/postgres_projections.clj))
  - Accounts table with full details
  - Transactions with complete history
  - Daily balances for reporting
  - Account summaries with analytics

## Benefits of This Architecture

### 1. **Decoupling**
- Write side and read side are completely independent
- Can scale independently based on load
- Changes to read models don't affect write side
- Can add new projections without touching command logic

### 2. **Resilience**
- If projections fail, events are still safely stored
- Can rebuild projections from event store at any time
- Eventual consistency model is more fault-tolerant

### 3. **Performance**
- Commands execute faster (only write to EventStore)
- No need to update multiple projections synchronously
- Read models can be optimized for specific query patterns
- Can use different databases for different query needs

### 4. **Scalability**
- Write side (commands) can scale independently
- Read side (queries) can scale independently
- Projection service can be horizontally scaled
- Can use read replicas for queries

### 5. **Microservices Ready**
- Projection service can be deployed as separate microservice
- Uses standard patterns (event streaming)
- No direct coupling between services
- Can use different tech stacks for different services

## Data Flow

### Command Flow (Synchronous)
1. Client sends command to Bank Account Service
2. Service loads aggregate from event history
3. Command handler processes command, generates events
4. Events written atomically to EventStore
5. Command completes (fast!)

### Projection Flow (Asynchronous)
1. DynamoDB Streams captures EventStore changes
2. Stream Processor polls for new records
3. Events deserialized from stream records
4. Projection Service receives events
5. Both DynamoDB and Postgres projections updated
6. Read models now reflect the changes (eventually consistent)

### Query Flow (Synchronous)
1. Client sends query to Bank Account Service
2. Service queries appropriate projection
   - Fast queries → DynamoDB
   - Complex queries → Postgres
3. Results returned immediately from read model

## Eventual Consistency

This architecture uses **eventual consistency** between write and read models:

- Commands complete immediately after writing to EventStore
- Projections are updated asynchronously via streams
- Small delay (typically < 1 second) before projections reflect changes
- Trade-off: Better performance and resilience for slight delay

For use cases requiring strong consistency, you can:
- Wait for projections to update before returning
- Read directly from event store
- Use synchronous projection for critical queries

## Running the System

### Local Development

```clojure
;; Start DynamoDB Local
docker run -p 8000:8000 amazon/dynamodb-local

;; Run the example
(require '[cqrs.examples.decoupled-example :as example])
(example/run-example!)
```

### Production Deployment

1. **EventStore**: DynamoDB table with Streams enabled
2. **Projection Service**: Separate microservice or Lambda function
3. **Command Service**: API service handling commands
4. **Query Service**: API service handling queries (can be same as command service)

## Configuration

### Enabling DynamoDB Streams

When creating the EventStore table, enable streams:

```clojure
;; Streams are enabled by default in DynamoDB Local

;; For AWS DynamoDB, configure stream specification:
;; - StreamViewType: NEW_AND_OLD_IMAGES or NEW_IMAGE
;; - StreamEnabled: true
```

### Stream Processing Options

1. **Polling (Current Implementation)**
   - Stream processor polls for records
   - Good for development and testing
   - Simple to implement

2. **Lambda Functions (Production)**
   - Use AWS Lambda to process stream events
   - Automatic scaling
   - No infrastructure management

3. **Kinesis Data Streams**
   - Use DynamoDB Streams → Kinesis → Consumer
   - Better for high throughput
   - More processing options

## Testing

The architecture supports different testing strategies:

1. **Unit Tests**: Test command handlers and projections independently
2. **Integration Tests**: Test event store and projections
3. **End-to-End Tests**: Test complete flow with streams
4. **Eventual Consistency Tests**: Verify projections eventually match

## Monitoring

Key metrics to monitor:

- **Stream Lag**: Delay between event write and projection update
- **Projection Errors**: Failed projection updates
- **Event Store Throughput**: Commands per second
- **Query Latency**: Read model query performance

## Future Enhancements

1. **Saga Pattern**: For complex multi-aggregate transactions
2. **Snapshot**: Cache aggregate state for performance
3. **Replay**: Rebuild projections from event history
4. **Versioning**: Handle event schema evolution
5. **Dead Letter Queue**: For failed projections
6. **Idempotency**: Ensure projections handle duplicate events

## References

- [CQRS Pattern](https://martinfowler.com/bliki/CQRS.html)
- [Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- [DynamoDB Streams](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Streams.html)
- [Change Data Capture](https://en.wikipedia.org/wiki/Change_data_capture)
