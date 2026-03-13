(ns metabase.driver.firebird
    (:require [clojure.set :as set]
      [clojure.string :as str]
      [clojure.java.jdbc :as jdbc]
      [honey.sql :as hsql]
      [java-time :as t]
      [metabase.driver :as driver]
      [metabase.driver.common :as driver.common]
      [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
      [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
      [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
      [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
      [metabase.driver.sql-jdbc.sync.common :as sql-jdbc.sync.common]
      [metabase.driver.sql.query-processor :as sql.qp]
      [metabase.driver.sql.util :as sql.u]
      [metabase.util.honey-sql-2 :as hx]
      [metabase.util.log :as log]
      [metabase.util.malli :as mu]
      [metabase.util.malli.registry :as mr])
    (:import [java.sql Connection DatabaseMetaData ResultSet]
      [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

(set! *warn-on-reflection* true)

(driver/register! :firebird, :parent :sql-jdbc)

(defmethod sql-jdbc.conn/connection-details->spec :firebird
           [_driver {:keys [user pass dbname host port conn-uri use-conn-uri]
                     :or   {user "sysdba" pass "masterkey" dbname "" port 3050 host "localhost"}
                     :as   details}]
           (log/infof "Firebird connection details: user=%s, password=%s, host=%s, port=%s, dbname=%s, use-conn-uri=%s, conn-uri=%s"
                      user
                      (if (str/blank? pass) "<empty>" "<provided>")
                      host
                      port
                      dbname
                      use-conn-uri
                      (if use-conn-uri conn-uri "<not-used>"))
           (if (and use-conn-uri (not-empty conn-uri))
             (let [url (java.net.URL. (str/replace-first conn-uri "jdbc:firebirdsql:" "http:"))
                   host (.getHost url)
                   port (if (pos? (.getPort url)) (.getPort url) 3050)
                   dbname (str/replace-first (.getPath url) #"^/" "")
                   query (.getQuery url)
                   details (cond-> {:host   host
                                    :port   port
                                    :dbname dbname}
                                   query (assoc :additional-options query))]
                  (-> {:classname   "org.firebirdsql.jdbc.FBDriver"
                       :subprotocol "firebirdsql"
                       :subname     (str "//" host ":" port "/" dbname)}
                      (sql-jdbc.common/handle-additional-options details)))
             (do
               (when (or (str/blank? user) (str/blank? pass))
                     (throw (ex-info "Invalid Firebird credentials: username and password must be non-empty."
                                     {:user user :password (if (str/blank? pass) "<empty>" "<provided>")})))
               (-> {:classname   "org.firebirdsql.jdbc.FBDriver"
                    :subprotocol "firebirdsql"
                    :subname     (str "//" host ":" port "/" dbname)
                    :user        user
                    :password    (or pass "")}
                   (sql-jdbc.common/handle-additional-options details)))))

(defmethod sql.qp/honey-sql-version :firebird
           [_driver]
           2)

(defmethod driver/can-connect? :firebird
           [driver details]
           (log/infof "Firebird can-connect? details: user=%s, password=%s, host=%s, port=%s, dbname=%s, use-conn-uri=%s, conn-uri=%s"
                       (:user details)
                       (if (str/blank? (:password details)) "<empty>" "<provided>")
                       (:host details)
                       (:port details)
                       (:dbname details)
                       (:use-conn-uri details)
                       (if (:use-conn-uri details) (:conn-uri details) "<not-used>"))
           (let [connection (sql-jdbc.conn/connection-details->spec driver details)]
                (= 1 (first (vals (first (jdbc/query connection ["SELECT 1 FROM RDB$DATABASE"])))))))

(doseq [[feature supported?] {; supported
                              :basic-aggregations                     true
                              :expression-aggregations                true
                              :foreign-keys                           true
                              :nested-queries                         true
                              :standard-deviation-aggregations        true
                              ; not supported
                              :schemas                                false
                              :binning                                false
                              :case-sensitivity-string-filter-options false
                              :nested-fields                          false
                              :set-timezone                           false}]
       (defmethod driver/database-supports? [:firebird feature] [_driver _feature _db] supported?))

(defmethod driver/describe-database :firebird
           [driver database]
           (try
             (sql-jdbc.execute/do-with-connection-with-options
               driver
               database
               nil
               (fn [^Connection conn]
                   (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)
                         result (jdbc/query spec
                                            ["SELECT TRIM(RDB$RELATION_NAME) AS name FROM RDB$RELATIONS WHERE RDB$SYSTEM_FLAG = 0 AND RDB$RELATION_TYPE IN (0, 1) ORDER BY name"])]
                        {:tables
                         (into #{}
                               (map (fn [row]
                                        {:name   (str/trim (:name row))
                                         :schema nil}))
                               result)})))
             (catch Exception e
               (throw (Exception. (str "Error in describe-database: " (.getMessage e)) e)))))

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
    [[#"INT64" :type/BigInteger]
     [#"DECIMAL" :type/Decimal]
     [#"FLOAT" :type/Float]
     [#"BLOB" :type/*]
     [#"INTEGER" :type/Integer]
     [#"NUMERIC" :type/Decimal]
     [#"DOUBLE" :type/Float]
     [#"SMALLINT" :type/Integer]
     [#"CHAR" :type/Text]
     [#"BIGINT" :type/BigInteger]
     [#"TIMESTAMP" :type/DateTime]
     [#"DATE" :type/Date]
     [#"TIME" :type/Time]
     [#"BLOB SUB_TYPE 0" :type/*]
     [#"BLOB SUB_TYPE 1" :type/Text]
     [#"BLOB SUB_TYPE TEXT" :type/Text]
     [#"DOUBLE PRECISION" :type/Float]
     [#"BOOLEAN" :type/Boolean]]))

(defmethod sql-jdbc.sync/database-type->base-type :firebird
           [_ column-type]
           (database-type->base-type column-type))

(defmethod driver/describe-table :firebird
           [driver database {:keys [name]}]
           (sql-jdbc.execute/do-with-connection-with-options
             driver
             database
             nil
             (fn [^Connection conn]
                 (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)
                       result (jdbc/query spec
                                          [(str "SELECT TRIM(rf.RDB$FIELD_NAME) AS field_name, "
                                                "CASE WHEN f.RDB$FIELD_TYPE = 7 THEN 'SMALLINT' "
                                                "WHEN f.RDB$FIELD_TYPE = 8 THEN 'INTEGER' "
                                                "WHEN f.RDB$FIELD_TYPE = 10 THEN 'FLOAT' "
                                                "WHEN f.RDB$FIELD_TYPE = 12 THEN 'DATE' "
                                                "WHEN f.RDB$FIELD_TYPE = 13 THEN 'TIME' "
                                                "WHEN f.RDB$FIELD_TYPE = 14 THEN 'CHAR' "
                                                "WHEN f.RDB$FIELD_TYPE = 16 THEN 'BIGINT' "
                                                "WHEN f.RDB$FIELD_TYPE = 23 THEN 'BOOLEAN' "
                                                "WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' "
                                                "WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' "
                                                "WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' "
                                                "WHEN f.RDB$FIELD_TYPE = 261 AND f.RDB$FIELD_SUB_TYPE = 1 THEN 'BLOB SUB_TYPE TEXT' "
                                                "WHEN f.RDB$FIELD_TYPE = 261 THEN 'BLOB SUB_TYPE 0' "
                                                "ELSE 'VARCHAR' END AS database_type, "
                                                "rf.RDB$FIELD_POSITION AS \"position\", "
                                                "CASE WHEN rf.RDB$NULL_FLAG = 1 THEN 0 ELSE 1 END AS nullable, "
                                                "IIF(EXISTS (SELECT 1 FROM RDB$RELATION_CONSTRAINTS rc "
                                                "JOIN RDB$INDEX_SEGMENTS idx ON rc.RDB$INDEX_NAME = idx.RDB$INDEX_NAME "
                                                "WHERE rc.RDB$CONSTRAINT_TYPE = 'PRIMARY KEY' "
                                                "AND rc.RDB$RELATION_NAME = rf.RDB$RELATION_NAME "
                                                "AND idx.RDB$FIELD_NAME = rf.RDB$FIELD_NAME), 1, 0) AS pk "
                                                "FROM RDB$RELATION_FIELDS rf "
                                                "JOIN RDB$FIELDS f ON rf.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME "
                                                "WHERE rf.RDB$RELATION_NAME = ?") name])]
                      {:name   name
                       :schema nil
                       :fields
                       (into #{}
                             (map (fn [row]
                                      {:name              (str/trim (:field_name row))
                                       :database-type     (:database_type row)
                                       :base-type         (sql-jdbc.sync/database-type->base-type :firebird (:database_type row))
                                       :database-position (:position row)
                                       :database-required (not (:nullable row))
                                       :pk?               (:pk row)}))
                             result)}))))

(defmethod sql-jdbc.sync/describe-fields-sql :firebird
           [driver & {:keys [table-names]}]
           (hsql/format
             {:select   [[:%trim.rf.rdb$field_name :name]
                         [(hsql/call :case
                                     [:= :f.rdb$field_type 7] "SMALLINT"
                                     [:= :f.rdb$field_type 8] "INTEGER"
                                     [:= :f.rdb$field_type 10] "FLOAT"
                                     [:= :f.rdb$field_type 12] "DATE"
                                     [:= :f.rdb$field_type 13] "TIME"
                                     [:= :f.rdb$field_type 14] "CHAR"
                                     [:= :f.rdb$field_type 16] "BIGINT"
                                     [:= :f.rdb$field_type 23] "BOOLEAN"
                                     [:= :f.rdb$field_type 27] "DOUBLE PRECISION"
                                     [:= :f.rdb$field_type 35] "TIMESTAMP"
                                     [:= :f.rdb$field_type 37] "VARCHAR"
                                     [:and [:= :f.rdb$field_type 261] [:= :f.rdb$field_sub_type 1]] "BLOB SUB_TYPE TEXT"
                                     [:= :f.rdb$field_type 261] "BLOB SUB_TYPE 0"
                                     :else "VARCHAR") :database-type]
                         [:- :rf.rdb$field_position [:inline 1] :database-position]
                         [:null :table-schema]
                         [:rf.rdb$relation_name :table-name]
                         [(hsql/call :exists
                                     {:select [:inline 1]
                                      :from   [:rdb$relation_constraints :rc]
                                      :join   [:rdb$index_segments :idx
                                               [:= :rc.rdb$index_name :idx.rdb$index_name]]
                                      :where  [:and
                                               [:= :rc.rdb$constraint_type "PRIMARY KEY"]
                                               [:= :rc.rdb$relation_name :rf.rdb$relation_name]
                                               [:= :idx.rdb$field_name :rf.rdb$field_name]]})
                          :pk?]
                         [:null :field-comment]
                         [(hsql/call :case
                                     [:= :rf.rdb$null_flag 1] false
                                     :else true) :database-required]
                         [(hsql/call :exists
                                     {:select [:inline 1]
                                      :from   [:rdb$triggers :t]
                                      :join   [:rdb$dependencies :d
                                               [:= :t.rdb$trigger_name :d.rdb$dependent_name]]
                                      :where  [:and
                                               [:= :t.rdb$relation_name :rf.rdb$relation_name]
                                               [:= :d.rdb$field_name :rf.rdb$field_name]
                                               [:= :t.rdb$trigger_type 1]
                                               [:like :t.rdb$trigger_source "%GEN_ID%"]]})
                          :database-is-auto-increment]]
              :from     [[:rdb$relation_fields :rf]]
              :join     [[:rdb$fields :f] [:= :rf.rdb$field_source :f.rdb$field_name]]
              :where    [:and
                         [:not-like :rf.rdb$relation_name [:inline "RDB$%"]]
                         [:not-like :rf.rdb$relation_name [:inline "MON$%"]]
                         (when table-names [:in :rf.rdb$relation_name table-names])]
              :order-by [:table-name :database-position]}
             :dialect (sql.qp/quote-style driver)))

(defn- firebird-format [query]
       (log/debugf "Formatting Firebird query with map: %s" query)
       (let [limit (when (:limit query) (second (:limit query)))
             offset (when (:offset query) (second (:offset query)))
             query (cond-> query
                           (:limit query) (dissoc :limit)
                           (:offset query) (dissoc :offset))
             group-by (:group-by query)
             has-complex-expr? (fn [expr]
                                   (and (coll? expr)
                                        (some (fn [e]
                                                  (and (coll? e) (#{:dateadd :extract} (first e))))
                                              (tree-seq coll? seq expr))))
             complex-exprs (when group-by (filter has-complex-expr? group-by))
             modified-query (if (seq complex-exprs)
                              (let [cte-aliases (map-indexed (fn [idx _] (keyword (str "field_" idx))) complex-exprs)
                                    cte-def (map vector cte-aliases complex-exprs)
                                    cte-name :date_group
                                    new-select (mapcat (fn [sel]
                                                           (if (some #(= sel %) complex-exprs)
                                                             [(some #(when (= sel (second %)) (first %)) (map vector cte-aliases complex-exprs))]
                                                             [sel]))
                                                       (:select query))
                                    new-group-by cte-aliases
                                    new-order-by (map (fn [clause]
                                                          (if (some #(= (first clause) %) complex-exprs)
                                                            [(some #(when (= (first clause) (second %)) (first %)) (map vector cte-aliases complex-exprs)) (second clause)]
                                                            clause))
                                                      (or (:order-by query) []))]
                                   (assoc query
                                          :with [[cte-name {:select cte-def :from (:from query)}]]
                                          :select new-select
                                          :from [cte-name]
                                          :group-by new-group-by
                                          :order-by (if (seq new-order-by) new-order-by new-group-by)))
                              query)
             [sql & params] (try
                              (hsql/format modified-query :dialect :ansi :quoting :ansi :allow-dashed-names true)
                              (catch Exception e
                                (log/errorf "Error in hsql/format: %s, query map: %s" (.getMessage e) modified-query)
                                (throw (ex-info (str "Error in hsql/format: " (.getMessage e))
                                                {:query modified-query} e))))
             clean-sql (-> sql
                           (str/replace #"(?m)^\s*--.*$|(?m)--.*?(?=\n|$)" "")
                           (str/trim))
             modified-sql (-> clean-sql
                              (str/replace #"(DATEADD)\s*\(\s*\"(year|month|day|hour|minute|second|week|quarter)\"" "$1($2")
                              (str/replace #"(EXTRACT)\s*\(\s*\"(YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|WEEKDAY|YEARDAY|WEEK|QUARTER)\"" "$1($2")
                              (str/replace #"(EXTRACT)\s*\(\s*([A-Z]+)\s*,\s*\"from\"\s*,\s*([^)]+)\)" "$1($2 FROM $3)")
                              ;; Fix SUBSTRING: remove comma between column and FROM keyword
                              ;; HoneySQL generates SUBSTRING(col, FROM x FOR y) but Firebird requires SUBSTRING(col FROM x FOR y)
                              (str/replace #"(SUBSTRING\s*\([^,]+),\s*(FROM\s)" "$1 $2")
                              (#(cond
                                  (and limit offset)
                                  (str/replace-first % #"\bSELECT\b" (str "SELECT FIRST " limit " SKIP " offset))
                                  limit
                                  (str/replace-first % #"\bSELECT\b" (str "SELECT FIRST " limit))
                                  offset
                                  (str/replace-first % #"\bSELECT\b" (str "SELECT SKIP " offset))
                                  :else
                                  %)))
             filtered-params (filterv (fn [param]
                                          (not (and (string? param)
                                                    (re-matches #"^'.*'$" param))))
                                      params)]
            (log/debugf "Generated Firebird SQL: %s, params: %s" modified-sql filtered-params)
            (try
              (into [modified-sql] filtered-params)
              (catch Exception e
                (log/errorf "Error formatting Firebird query: %s, query map: %s, SQL: %s, params: %s" (.getMessage e) query modified-sql filtered-params)
                (throw (ex-info (str "Error formatting Firebird query: " (.getMessage e))
                                {:query query :sql modified-sql :params filtered-params} e))))))

(defmethod sql.qp/format-honeysql :firebird
           [driver query]
           (log/debugf "Invoking Firebird format-honeysql for driver: %s, query: %s" driver query)
           (firebird-format query))

(defmethod sql.qp/format-honeysql :sql-jdbc
           [driver query]
           (if (= driver :firebird)
             (do
               (log/debugf "Forcing Firebird format-honeysql for driver: %s, query: %s" driver query)
               (firebird-format query))
             (let [parent-method (get-method sql.qp/format-honeysql :sql)]
                  (log/debugf "Using parent :sql formatter for driver: %s, query: %s" driver query)
                  (let [[sql & params] (parent-method driver query)
                        clean-sql (str/replace sql #"(?m)^\s*--.*$|(?m)--.*?(?=\n|$)" "")]
                       (log/debugf "Cleaned SQL for driver: %s, SQL: %s, params: %s" driver clean-sql params)
                       (cons clean-sql params)))))

(defmethod sql.qp/apply-top-level-clause [:firebird :limit]
           [_driver _ honeysql-query {value :limit}]
           {:pre [(pos-int? value)]}
           (assoc honeysql-query :limit [:raw value]))

(defmethod sql.qp/apply-top-level-clause [:firebird :page]
           [_driver _ honeysql-query {{:keys [items page]} :page}]
           {:pre [(pos-int? items) (pos-int? page)]}
           (let [offset (* (dec page) items)]
                (-> honeysql-query
                    (assoc :limit [:raw items])
                    (assoc :offset [:raw offset]))))

(defn- handle-native-sql
       "Transforms native SQL queries by converting LIMIT and OFFSET clauses to Firebird's FIRST and SKIP syntax."
       [native-query]
       (let [native-sql (:query (:native native-query))
             normalized-sql (str/replace native-sql #"\s+" " ")
             modified-sql (cond
                            (re-find #"\bLIMIT\s+(\d+)\b\s+OFFSET\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                            (let [[_ limit-num offset-num] (re-find #"\bLIMIT\s+(\d+)\b\s+OFFSET\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                                  without-limit-offset (str/replace normalized-sql #"\bLIMIT\s+\d+\b\s+OFFSET\s+\d+\b\s*(?:;)?$" "")
                                  modified (str/replace-first without-limit-offset #"\bSELECT\b" (str "SELECT FIRST " limit-num " SKIP " offset-num))]
                                 modified)
                            (re-find #"\bLIMIT\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                            (let [[_ limit-num] (re-find #"\bLIMIT\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                                  without-limit (str/replace normalized-sql #"\bLIMIT\s+\d+\b\s*(?:;)?$" "")
                                  modified (str/replace-first without-limit #"\bSELECT\b" (str "SELECT FIRST " limit-num))]
                                 modified)
                            :else
                            normalized-sql)]
            (log/debugf "Transformed native SQL: %s -> %s" native-sql modified-sql)
            (assoc-in native-query [:native :query] modified-sql)))

(defmethod sql.qp/->honeysql [:firebird :field]
           [driver [_ field-id opts :as field]]
           (let [metadata-provider (metabase.query-processor.store/metadata-provider)
                 table-id (get-in opts [:metabase.query-processor.util.add-alias-info/source-table] "source")
                 table-name (cond
                              (integer? table-id)
                              (:name (metabase.lib.metadata/table metadata-provider table-id))
                              (= table-id :metabase.query-processor.util.add-alias-info/source)
                              "source"
                              :else
                              (str table-id))
                 field-alias (or (get-in opts [:metabase.query-processor.util.add-alias-info/source-alias]) (str field-id))]
                (log/debugf "Generating HoneySQL for field: field-id=%s, table-id=%s, table-name=%s, field-alias=%s" field-id table-id table-name field-alias)
                (hx/identifier :field table-name field-alias)))

(defn- build-cte-for-complex-exprs
       [driver group-by table-alias processed-query]
       (let [has-complex-expr? (fn [expr]
                                   (and (coll? expr)
                                        (some (fn [e]
                                                  (or (and (coll? e) (#{:dateadd :extract} (first e)))
                                                      (and (vector? e) (= :raw (first e)) (re-find #"\?" (str (second e))))))
                                              (tree-seq coll? seq expr))))
             complex-exprs (when group-by (filter has-complex-expr? group-by))]
            (when (seq complex-exprs)
                  (let [cte-aliases (map-indexed (fn [idx _] (keyword (str "field_" idx))) complex-exprs)
                        cte-def+types (map-indexed
                                        (fn [idx expr]
                                            (let [alias (nth cte-aliases idx)
                                                  temporal-unit (when (and (vector? expr) (= :field (first expr)))
                                                                      (get-in expr [2 :temporal-unit]))
                                                  field-alias (or (get-in (meta expr) [:metabase.query-processor.util.add-alias-info/source-alias]) "DATUM")
                                                  field-expr (hx/identifier :field table-alias field-alias)
                                                  [new-expr base-type database-type] (if temporal-unit
                                                                                       (case temporal-unit
                                                                                             :minute [[:lift {:database-type "TIMESTAMP"} [:cast [:dateadd :minute 0 field-expr] :TIMESTAMP]] :type/DateTime "TIMESTAMP"]
                                                                                             :minute-of-hour [[:extract :MINUTE :from field-expr] :type/Integer "INTEGER"]
                                                                                             :hour [[:lift {:database-type "TIMESTAMP"} [:cast [:dateadd :hour 0 field-expr] :TIMESTAMP]] :type/DateTime "TIMESTAMP"]
                                                                                             :hour-of-day [[:extract :HOUR :from field-expr] :type/Integer "INTEGER"]
                                                                                             :day [[:lift {:database-type "DATE"} [:cast field-expr :DATE]] :type/Date "DATE"]
                                                                                             :day-of-week [[:+ [:extract :WEEKDAY :from [:cast field-expr :DATE]] [:inline 1]] :type/Integer "INTEGER"]
                                                                                             :day-of-month [[:extract :DAY :from field-expr] :type/Integer "INTEGER"]
                                                                                             :day-of-year [[:+ [:extract :YEARDAY :from field-expr] [:inline 1]] :type/Integer "INTEGER"]
                                                                                             :week [[:lift {:database-type "DATE"} [:dateadd :day [:- 0 [:extract :WEEKDAY :from [:cast field-expr :DATE]]] [:cast field-expr :DATE]]] :type/Date "DATE"]
                                                                                             :week-of-year [[:extract :WEEK :from field-expr] :type/Integer "INTEGER"]
                                                                                             :month [[:lift {:database-type "DATE"} [:cast [:dateadd :month 0 [:dateadd :day [:- [:inline 1] [:extract :DAY :from field-expr]] field-expr]] :DATE]] :type/Date "DATE"]
                                                                                             :month-of-year [[:extract :MONTH :from field-expr] :type/Integer "INTEGER"]
                                                                                             :quarter [[:lift {:database-type "DATE"} [:dateadd :month [:* [:/ [:- [:extract :MONTH :from field-expr] [:inline 1]] [:inline 3]] 3] [:cast [:dateadd :month 0 [:dateadd :day [:- [:inline 1] [:extract :DAY :from field-expr]] field-expr]] :DATE]]] :type/Date "DATE"]
                                                                                             :quarter-of-year [[[:+ [:/ [:- [:extract :MONTH :from field-expr] [:inline 1]] [:inline 3]] [:inline 1]]] :type/Integer "INTEGER"]
                                                                                             :year [[:extract :YEAR :from field-expr] :type/Integer "INTEGER"])
                                                                                       [expr nil nil])]
                                                 [[alias new-expr] base-type database-type]))
                                        complex-exprs)
                        cte-def (map (fn [[alias-expr _ _]] alias-expr) cte-def+types)
                        cte-name :date_group
                        new-select (vec (concat
                                          (mapcat
                                            (fn [sel]
                                                (if-let [alias (some (fn [[a e]] (when (= sel e) a)) (map vector cte-aliases complex-exprs))]
                                                        [[alias [:metabase.util.honey-sql-2/identifier :field-alias [(or (get-in (meta sel) [:metabase.query-processor.util.add-alias-info/desired-alias]) "DATUM")]]]]
                                                        [sel]))
                                            group-by)
                                          (map
                                            (fn [agg]
                                                (let [agg-alias (or (get-in (meta agg) [:metabase.query-processor.util.add-alias-info/desired-alias]) "count")]
                                                     [(sql.qp/->honeysql driver agg) [:metabase.util.honey-sql-2/identifier :field-alias [agg-alias]]]))
                                            (get-in processed-query [:query :aggregation]))))
                        new-group-by cte-aliases
                        order-by (get-in processed-query [:query :order-by])
                        new-order-by (cond
                                       (or (nil? order-by) (keyword? order-by)) new-group-by
                                       (seq order-by) (map
                                                        (fn [clause]
                                                            (if-let [alias (some (fn [[a e]] (when (= (first clause) e) a)) (map vector cte-aliases complex-exprs))]
                                                                    [alias (second clause)]
                                                                    clause))
                                                        order-by)
                                       :else new-group-by)
                        fields-metadata (reduce
                                          (fn [acc [_ _ base-type database-type]]
                                              (if (and base-type database-type)
                                                (conj acc {:base-type base-type :database-type (str/trim database-type)})
                                                acc))
                                          []
                                          cte-def+types)]
                       {:cte-name cte-name
                        :cte-def cte-def
                        :new-select new-select
                        :new-group-by new-group-by
                        :new-order-by new-order-by
                        :fields-metadata fields-metadata}))))


(defn- process-mbql-query
       "Processes MBQL queries with aggregations, generating CTEs for complex date expressions if needed."
       [driver processed-query]
       (if (and (:query processed-query) (:aggregation (:query processed-query)))
         (let [group-by (:group-by (:query processed-query))
               table-alias (or (-> (:source-table (:query processed-query)) meta :metabase.query-processor.util.add-alias-info/source-alias) "ACCOUNTING_BLNCE_SHEET_REPORT")
               cte-info (build-cte-for-complex-exprs driver group-by table-alias processed-query)]
              (if cte-info
                (let [{:keys [cte-name cte-def new-select new-group-by new-order-by fields-metadata]} cte-info]
                     (log/infof "Generated CTE for complex date expressions: %s" cte-info)
                     (assoc-in processed-query [:query]
                               {:with                                                [[cte-name {:select cte-def :from [[:metabase.util.honey-sql-2/identifier :table [table-alias]]]}]]
                                :select                                              new-select
                                :from                                                [cte-name]
                                :group-by                                            new-group-by
                                :order-by                                            new-order-by
                                :metabase.query-processor.util.add-alias-info/fields fields-metadata}))
                processed-query))
         processed-query))

(defmethod sql.qp/preprocess :firebird
           [driver query]
           (log/debugf "Preprocessing Firebird query with driver: %s, query: %s" driver query)
           (let [parent-method (get-method sql.qp/preprocess :sql)
                 processed-query (parent-method driver query)]
                (if (:native query)
                  (handle-native-sql query)
                  (do
                    (log/infof "Preprocessed Firebird MBQL query: %s" processed-query)
                    (process-mbql-query driver processed-query)))))

(defmethod sql.qp/->honeysql [:firebird :concat]
           [driver [_ & args]]
           (let [converted-args (map (fn [arg]
                                         (cond
                                           (and (vector? arg) (= :value (first arg)))
                                           (let [val (second arg)]
                                                (if (string? val)
                                                  (do
                                                    (log/debugf "Converting string literal in concat: %s" val)
                                                    [:raw (str "'" (str/replace val "'" "''") "'")])
                                                  (do
                                                    (log/warnf "Non-string value in concat: %s. Casting to string." val)
                                                    [:raw (str "'" (str/replace (str val) "'" "''") "'")])))

                                           (string? arg)
                                           (do
                                             (log/debugf "Converting raw string literal in concat: %s" arg)
                                             [:raw (str "'" (str/replace arg "'" "''") "'")])

                                           :else
                                           (sql.qp/->honeysql driver arg)))
                                     args)]
                (let [result (reduce (fn [acc arg]
                                         [:|| acc arg])
                                     (first converted-args)
                                     (rest converted-args))]
                     (log/debugf "Generated concat HoneySQL form: %s" result)
                     result)))

(defmethod sql.qp/->honeysql [:firebird :substring]
           [driver [_ arg start length]]
           (let [col-name (sql.qp/->honeysql driver arg)
                 start-expr (sql.qp/->honeysql driver start)
                 length-expr (when length (sql.qp/->honeysql driver length))]
                (log/debugf "Generating SUBSTRING for arg=%s, start=%s, length=%s" arg start length)
                (if length-expr
                  (let [length-expr (if (and (number? length) (> length 32767))
                                      (do
                                        (log/warnf "Substring length %d exceeds Firebird maximum (32767); capping at 32767" length)
                                        32767)
                                      length-expr)]
                       ;; Use HoneySQL named arguments (:!from, :!for) to generate
                       ;; SUBSTRING(col FROM x FOR y) without comma — Firebird syntax
                       [:substring col-name :!from start-expr :!for length-expr])
                  [:substring col-name :!from start-expr])))

(defmethod sql.qp/date [:firebird :default] [_ _ expr] expr)

(defmethod sql.qp/date [:firebird :minute]
           [_ _ expr]
           (hx/cast :TIMESTAMP [:dateadd :minute 0 expr]))

(defmethod sql.qp/date [:firebird :minute-of-hour]
           [_ _ expr]
           [:extract :MINUTE :from expr])

(defmethod sql.qp/date [:firebird :hour]
           [_ _ expr]
           (hx/cast :TIMESTAMP [:dateadd :hour 0 expr]))

(defmethod sql.qp/date [:firebird :hour-of-day]
           [_ _ expr]
           [:extract :HOUR :from expr])

(defmethod sql.qp/date [:firebird :day]
           [_ _ expr]
           (hx/cast :DATE expr))

(defmethod sql.qp/date [:firebird :day-of-week]
           [_ _ expr]
           (hx/+ [:extract :WEEKDAY :from (hx/cast :DATE expr)] 1))

(defmethod sql.qp/date [:firebird :day-of-month]
           [_ _ expr]
           [:extract :DAY :from expr])

(defmethod sql.qp/date [:firebird :day-of-year]
           [_ _ expr]
           (hx/+ [:extract :YEARDAY :from expr] 1))

(defmethod sql.qp/date [:firebird :week]
           [_ _ expr]
           [:dateadd :day (hx/- 0 [:extract :WEEKDAY :from (hx/cast :DATE expr)]) (hx/cast :DATE expr)])

(defmethod sql.qp/date [:firebird :week-of-year]
           [_ _ expr]
           [:extract :WEEK :from expr])

(defmethod sql.qp/date [:firebird :month]
           [_ _ expr]
           (hx/cast :DATE [:dateadd :month 0 [:dateadd :day (hx/- 1 [:extract :DAY :from expr]) expr]]))

(defmethod sql.qp/date [:firebird :month-of-year]
           [_ _ expr]
           [:extract :MONTH :from expr])

(defmethod sql.qp/date [:firebird :quarter]
           [_ _ expr]
           [:dateadd :month (hx/* (hx// (hx/- [:extract :MONTH :from expr] 1) 3) 3) (hx/cast :DATE [:dateadd :month 0 [:dateadd :day (hx/- 1 [:extract :DAY :from expr]) expr]])])

(defmethod sql.qp/date [:firebird :quarter-of-year]
           [_ _ expr]
           (hx/+ (hx// (hx/- [:extract :MONTH :from expr] 1) 3) 1))

(defmethod sql.qp/date [:firebird :year]
           [_ _ expr]
           [:extract :YEAR :from expr])

(defmethod sql.qp/->honeysql [:firebird Boolean] [_ bool] (if bool 1 0))

(defmethod sql.qp/add-interval-honeysql-form :firebird
           [driver hsql-form amount unit]
           (if (= unit :quarter)
             (recur driver hsql-form (hx/* amount 3) :month)
             [:dateadd [:raw (name unit)] amount hsql-form]))

(defmethod sql.qp/current-datetime-honeysql-form :firebird [_]
           (hx/cast :timestamp (hx/literal :now)))

(defmethod sql.qp/->honeysql [:firebird :stddev]
           [driver [_ field]]
           [:stddev_samp (sql.qp/->honeysql driver field)])

(defn- zero-time? [t]
       (= (t/local-time t) (t/local-time 0)))

(defmethod sql.qp/->honeysql [:firebird LocalDate]
           [_ t]
           (hx/cast :DATE (t/format "yyyy-MM-dd" t)))

(defmethod sql.qp/->honeysql [:firebird LocalDateTime]
           [driver t]
           (if (zero-time? t)
             (sql.qp/->honeysql driver (t/local-date t))
             (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod sql.qp/->honeysql [:firebird LocalTime]
           [_ t]
           (hx/cast :TIME (t/format "HH:mm:ss.SSSS" t)))

(defmethod sql.qp/->honeysql [:firebird OffsetDateTime]
           [driver t]
           (if (zero-time? t)
             (sql.qp/->honeysql driver (t/local-date t))
             (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod sql.qp/->honeysql [:firebird OffsetTime]
           [_ t]
           (hx/cast :TIME (t/format "HH:mm:ss.SSSS" t)))

(defmethod sql.qp/->honeysql [:firebird ZonedDateTime]
           [driver t]
           (if (zero-time? t)
             (sql.qp/->honeysql driver (t/local-date t))
             (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))
