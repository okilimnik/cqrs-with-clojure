Work in Progress

# CQRS with Clojure, Serverless Example

Configuration in this directory creates Aurora serverless clusters for Serverless V2 (PostgreSQL) and DynamoDB table.

## Option 1: Create resources in the cloud

To run this example you need to execute:

```bash
$ terraform init
$ terraform plan
$ terraform apply
```

Note that this example may create resources which cost money. Run `terraform destroy` when you don't need these resources.

## Option 2: Run locally

### Run DynamoDB (event store) locally
```bash
docker run -p 8000:8000 amazon/dynamodb-local
```

### Run Postgres locally
For Mac:
```bash
brew install postgresql@15
brew services start postgresql@15
echo 'export PATH="/usr/local/opt/postgresql@15/bin:$PATH"' >> ~/.zshrc
createdb readdb
psql readdb
readdb=# CREATE USER postgres WITH ENCRYPTED PASSWORD 'postgres';
CREATE ROLE
readdb=# GRANT ALL PRIVILEGES ON DATABASE "readdb" to postgres;
GRANT
GRANT ALL ON SCHEMA public TO postgres;
GRANT
readdb=# \q
```