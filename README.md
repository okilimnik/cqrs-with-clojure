# CQRS Bank Account System with Clojure

A comprehensive CQRS (Command Query Responsibility Segregation) system for managing bank accounts, built with Clojure, DynamoDB, and PostgreSQL.

## Architecture Overview

This system implements a complete CQRS pattern with Event Sourcing:

- **Write Side (DynamoDB)**: Event Store + Simple real-time projections
- **Read Side (PostgreSQL)**: Complex query projections and analytics
- **Event Sourcing**: Full audit trail and temporal queries
- **Dual Projections**: Fast simple queries (DynamoDB) and complex analytics (PostgreSQL)

### Components

```
┌─────────────────────────────────────────────────────────────┐
│                        Commands                             │
│  OpenAccount, DepositFunds, WithdrawFunds, TransferFunds    │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  Command Handlers                           │
│        Validate & Generate Domain Events                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              Event Store (DynamoDB)                         │
│         Immutable Event Log (Source of Truth)               │
└─────────────┬───────────────────────┬───────────────────────┘
              │                       │
              ▼                       ▼
┌──────────────────────┐    ┌────────────────────────────────┐
│ DynamoDB Projections │    │    Postgres Projections        │
│  - Account Balance   │    │  - Accounts                    │
│  - Recent Txns       │    │  - Transactions                │
│  (Fast Queries)      │    │  - Account Summary             │
└──────────────────────┘    │  - Daily Balances              │
                            │  (Complex Analytics)           │
                            └────────────────────────────────┘
```

## Domain Model

### Aggregates

- **BankAccount**: Root aggregate ensuring transactional consistency

### Commands

- `OpenAccountCommand`: Open a new bank account
- `DepositFundsCommand`: Deposit funds into account
- `WithdrawFundsCommand`: Withdraw funds from account
- `TransferFundsCommand`: Transfer funds between accounts
- `CloseAccountCommand`: Close an account

### Events

- `AccountOpenedEvent`: Account was created
- `FundsDepositedEvent`: Funds were deposited
- `FundsWithdrawnEvent`: Funds were withdrawn
- `FundsTransferredEvent`: Funds were transferred
- `AccountClosedEvent`: Account was closed

### Write Database (DynamoDB)

**EventStore Table**: Complete event history
- Primary Key: EventId
- GSI: AggregateIdIndex (for retrieving all events for an account)

**AccountBalance Table**: Fast balance lookups
- Primary Key: AccountId
- Attributes: Balance, Status, AccountHolder, AccountType

**TransactionHistory Table**: Recent transaction queries
- Primary Key: TransactionId
- GSI: AccountIdTimestampIndex (for time-ordered queries)

### Read Database (PostgreSQL)

**accounts**: Account master data
**transactions**: Complete transaction history
**account_summary**: Pre-computed analytics
**daily_balances**: Time-series balance snapshots

## Project Structure

```
src/cqrs/
├── domain/
│   ├── aggregate.clj              # Base aggregate functionality
│   └── bank_account.clj            # BankAccount aggregate & business logic
├── messages/
│   ├── message.clj                 # Base message types
│   ├── commands.clj                # Command definitions
│   └── account/
│       └── events.clj              # Event definitions
├── handlers/
│   └── command_handler.clj         # Command processing
├── infrastructure/
│   ├── event_store.clj             # DynamoDB event store
│   ├── dynamodb_projections.clj    # Simple real-time projections
│   └── postgres_projections.clj    # Complex query projections
├── queries/
│   └── query_handler.clj           # Query handlers for read models
├── application/
│   └── bank_account_service.clj    # Application orchestration
└── db/
    ├── write.clj                   # DynamoDB connection
    └── read.clj                    # PostgreSQL connection
```

## Getting Started

### Option 1: Run Locally

#### 1. Start DynamoDB Local

```bash
docker run -p 8000:8000 amazon/dynamodb-local
```

#### 2. Start PostgreSQL

**For Mac:**
```bash
brew install postgresql@15
brew services start postgresql@15
echo 'export PATH="/usr/local/opt/postgresql@15/bin:$PATH"' >> ~/.zshrc
createdb readdb
psql readdb
readdb=# CREATE USER postgres WITH ENCRYPTED PASSWORD 'postgres';
readdb=# GRANT ALL PRIVILEGES ON DATABASE "readdb" to postgres;
readdb=# GRANT ALL ON SCHEMA public TO postgres;
readdb=# \q
```

#### 3. Start a Clojure REPL

```bash
clj
```

#### 4. Initialize the System

```clojure
(require '[cqrs.infrastructure.event-store :as event-store])
(require '[cqrs.cqrs.infrastructure.postgres-projections :as pg-proj])
(require '[cqrs.application.bank-account-service :as service])

;; Initialize databases
(def ddb (event-store/init {:local? true}))
(def pg-db (pg-proj/init {:local? true}))

;; Create service
(def bank-service (service/create-service ddb pg-db))
```

### Option 2: Deploy to AWS

Use the provided Terraform configuration:

```bash
terraform init
terraform plan
terraform apply
```

Note: This will create AWS resources that may incur costs. Run `terraform destroy` when done.

## Usage Examples

### Commands (Write Operations)

```clojure
;; Open a new account
(service/open-account bank-service
                      "acc-001"
                      "John Doe"
                      "CHECKING"
                      1000.0)

;; Deposit funds
(service/deposit-funds bank-service "acc-001" 500.0)

;; Withdraw funds
(service/withdraw-funds bank-service "acc-001" 200.0)

;; Transfer funds between accounts
(service/transfer-funds bank-service
                        "acc-001"  ; from
                        "acc-002"  ; to
                        100.0)     ; amount

;; Close account
(service/close-account bank-service "acc-001")
```

### Queries (Read Operations)

#### Fast Queries (DynamoDB)

```clojure
;; Get current balance (optimized for speed)
(service/get-account-balance bank-service "acc-001")

;; Get recent transactions (last N transactions)
(service/get-recent-transactions bank-service "acc-001" 10)
```

#### Complex Queries (PostgreSQL)

```clojure
;; Get detailed account information
(service/get-account-details bank-service "acc-001")

;; Get comprehensive account summary with analytics
(service/get-account-summary bank-service "acc-001")

;; Get all accounts for a customer
(service/get-accounts-by-holder bank-service "John Doe")

;; Get top accounts by balance
(service/get-top-accounts bank-service 10)

;; Get transaction history with date range
(service/get-transaction-history bank-service
                                  "acc-001"
                                  #inst "2025-01-01"
                                  #inst "2025-12-31"
                                  50   ; limit
                                  0)   ; offset

;; Get daily balance report for time-series analysis
(service/get-daily-balance-report bank-service
                                   "acc-001"
                                   "2025-01-01"
                                   "2025-12-31")

;; Advanced transaction search
(service/search-transactions bank-service
                             {:account-id "acc-001"
                              :transaction-type "DEPOSIT"
                              :min-amount 100.0
                              :max-amount 1000.0
                              :limit 20})
```

## Key Features

### Event Sourcing
- Complete audit trail of all account changes
- Temporal queries (reconstruct state at any point in time)
- Event replay for debugging and testing
- Immutable event log

### CQRS Benefits
- **Write Optimization**: Simple, fast writes to DynamoDB
- **Read Optimization**: Complex queries pre-computed in PostgreSQL
- **Scalability**: Independent scaling of read and write workloads
- **Flexibility**: Multiple read models optimized for different use cases

### Business Logic Enforcement
- Aggregate ensures consistency boundaries
- Business rules validated before events are created
- Prevents invalid state transitions
- Domain-driven design patterns

### Projections

**DynamoDB Projections (Real-time)**:
- Account balance (single-item lookup)
- Recent transactions (time-ordered query)
- Optimized for low-latency reads

**PostgreSQL Projections (Analytics)**:
- Transaction history with complex filters
- Account summaries with aggregations
- Daily balance snapshots for trending
- Multi-table joins for reporting

## Testing

```clojure
;; Example: Create account, perform transactions, verify state

;; 1. Open account
(service/open-account bank-service "test-001" "Test User" "SAVINGS" 1000.0)

;; 2. Perform operations
(service/deposit-funds bank-service "test-001" 500.0)
(service/withdraw-funds bank-service "test-001" 200.0)

;; 3. Verify balance (should be 1300.0)
(service/get-account-balance bank-service "test-001")

;; 4. Check event history
(require '[cqrs.infrastructure.event-store :as es])
(es/get-events-for-aggregate ddb "EventStore" "test-001")

;; 5. Verify projections match
(service/get-account-summary bank-service "test-001")
```

## Error Handling

The system enforces business rules and throws exceptions for invalid operations:

```clojure
;; Insufficient funds
(service/withdraw-funds bank-service "acc-001" 999999.0)
;; => ExceptionInfo: Insufficient funds

;; Negative deposit
(service/deposit-funds bank-service "acc-001" -100.0)
;; => ExceptionInfo: Deposit amount must be positive

;; Close account with balance
(service/close-account bank-service "acc-001")
;; => ExceptionInfo: Cannot close account with positive balance
```

## Performance Considerations

- **Fast Queries**: Use DynamoDB projections for real-time balance and recent transactions
- **Complex Analytics**: Use PostgreSQL for reports, aggregations, and multi-table joins
- **Write Throughput**: DynamoDB provides consistent write performance
- **Event Replay**: Consider caching or snapshots for large event streams
- **Projections**: Can be rebuilt from event store if corrupted

## Future Enhancements

- [ ] Snapshots for large aggregates (optimization)
- [ ] Event versioning and upcasting
- [ ] Saga pattern for complex multi-account transactions
- [ ] Real-time event streaming (Kinesis/Kafka)
- [ ] CQRS read model rebuild functionality
- [ ] GraphQL API layer
- [ ] Event-driven microservices integration

## License

MIT