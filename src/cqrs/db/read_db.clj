(ns cqrs.db.read-db
  (:require
   [next.jdbc :as jdbc]))

(defn create-users-table
  "Create the users projection table for the read model"
  [datasource]
  (jdbc/execute! datasource
                 ["CREATE TABLE IF NOT EXISTS users (
                    id UUID PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    email VARCHAR(255) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                  )"])
  (prn "Created users table"))

(defn init
  "Initialize the Read Database connection.
  Options:
    :host - Database host (default: localhost)
    :port - Database port (default: 5432)
    :dbname - Database name (default: readdb)
    :user - Database user (default: postgres)
    :password - Database password (default: postgres)
    :local? - If true, uses default local connection settings"
  ([] (init {}))
  ([{:keys [host port dbname user password local?]
     :or {host "localhost"
          port 5432
          dbname "readdb"
          user "postgres"
          password "postgres"
          local? false}}]
   (let [db-spec (if local?
                   {:dbtype "postgresql"
                    :host "localhost"
                    :port 5432
                    :dbname "readdb"
                    :user "postgres"
                    :password "postgres"}
                   {:dbtype "postgresql"
                    :host host
                    :port port
                    :dbname dbname
                    :user user
                    :password password})
         datasource (jdbc/get-datasource db-spec)
         table-name "users"]
     (prn (str "Creating " table-name " table if not exists"))
     (create-users-table datasource)
     datasource)))


(comment
  ;; For local development
  (def db (init {:local? true}))

  ;; For Aurora Postgres
  (def db (init {:host
                 "your-aurora-endpoint.rds.amazonaws.com"
                 :port 5432
                 :dbname "readdb"
                 :user "your-user"
                 :password "your-password"}))
  )