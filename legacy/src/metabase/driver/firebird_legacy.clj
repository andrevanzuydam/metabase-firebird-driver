(ns metabase.driver.firebird-legacy
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
      [metabase.driver-api.core :as driver-api]
      [metabase.util.honey-sql-2 :as hx]
      [metabase.util.log :as log]
      [metabase.util.malli :as mu]
      [metabase.util.malli.registry :as mr])
    (:import [java.sql Connection DatabaseMetaData PreparedStatement ResultSet Statement]
      [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

(set! *warn-on-reflection* true)

(driver/register! :firebird-legacy, :parent :sql-jdbc)

;; Firebird 1.5/Jaybird 2.2.x doesn't support CLOSE_CURSORS_AT_COMMIT holdability
;; Override prepared-statement to use simpler 2-arg prepareStatement
(defmethod sql-jdbc.execute/prepared-statement :firebird-legacy
           [driver ^Connection conn ^String sql params]
           (let [stmt (.prepareStatement conn sql
                                         ResultSet/TYPE_FORWARD_ONLY
                                         ResultSet/CONCUR_READ_ONLY)]
                (try
                  (try
                    (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
                    (catch Throwable _))
                  (sql-jdbc.execute/set-parameters! driver stmt params)
                  stmt
                  (catch Throwable e
                    (.close stmt)
                    (throw e)))))

(defmethod sql-jdbc.execute/statement :firebird-legacy
           [_ ^Connection conn]
           (let [stmt (.createStatement conn
                                        ResultSet/TYPE_FORWARD_ONLY
                                        ResultSet/CONCUR_READ_ONLY)]
                (try
                  (try
                    (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
                    (catch Throwable _))
                  stmt
                  (catch Throwable e
                    (.close stmt)
                    (throw e)))))

(defmethod sql-jdbc.conn/connection-details->spec :firebird-legacy
           [_driver {:keys [user pass dbname host port conn-uri use-conn-uri]
                     :or   {user "sysdba" pass "masterkey" dbname "" port 3050 host "localhost"}
                     :as   details}]
           (log/infof "Firebird Legacy connection details: user=%s, password=%s, host=%s, port=%s, dbname=%s, use-conn-uri=%s, conn-uri=%s"
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

(defmethod sql.qp/honey-sql-version :firebird-legacy
           [_driver]
           2)

;; Jaybird 2.2.x + Firebird 1.5 corrupts connection state when Statement.cancel() is called.
;; Metabase calls .cancel() in execute-reducible-query's finally block.
;; Override to use eager jdbc/query with raw connection, avoiding the cancel issue entirely.
(defmethod driver/execute-reducible-query :firebird-legacy
           [driver {{sql :query, params :params} :native, :as outer-query} _context respond]
           {:pre [(string? sql) (seq sql)]}
           (let [database (driver-api/database (driver-api/metadata-provider))]
                (sql-jdbc.execute/do-with-connection-with-options
                  driver
                  database
                  nil
                  (fn [^Connection conn]
                      (let [result (jdbc/query {:connection conn} (into [sql] params))]
                           (if (seq result)
                             (let [cols (mapv (fn [k] {:name (name k)}) (keys (first result)))
                                   rows (mapv vals result)]
                                  (respond {:cols cols} rows))
                             (respond {:cols []} [])))))))

(defmethod driver/can-connect? :firebird-legacy
           [driver details]
           (log/infof "Firebird Legacy can-connect? details: user=%s, host=%s, port=%s, dbname=%s"
                       (:user details) (:host details) (:port details) (:dbname details))
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
                              :set-timezone                           false
                              ;; Firebird 1.5 can't use EXISTS in SELECT — disable bulk field sync
                              :describe-fields                        false}]
       (defmethod driver/database-supports? [:firebird-legacy feature] [_driver _feature _db] supported?))

(defmethod driver/describe-database :firebird-legacy
           [driver database]
           (try
             (sql-jdbc.execute/do-with-connection-with-options
               driver
               database
               nil
               (fn [^Connection conn]
                   (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)
                         result (jdbc/query spec
                                            ;; Firebird 1.5 does NOT have RDB$RELATION_TYPE (added in 2.1) or TRIM() (added in 2.0)
                                            ;; Trimming is done in Clojure below via str/trim
                                            ["SELECT RDB$RELATION_NAME AS name FROM RDB$RELATIONS WHERE RDB$SYSTEM_FLAG = 0 ORDER BY RDB$RELATION_NAME"])]
                        {:tables
                         (into #{}
                               (map (fn [row]
                                        {:name   (str/trim (:name row))
                                         :schema nil}))
                               result)})))
             (catch Exception e
               (throw (Exception. (str "Error in describe-database: " (.getMessage e)) e)))))

;; Firebird 1.5 type mapping — no BOOLEAN type, no BLOB sub-type differentiation needed
;; Firebird 1.5 field types: 7=SMALLINT, 8=INTEGER, 10=FLOAT, 12=DATE, 13=TIME, 14=CHAR,
;;   16=BIGINT, 27=DOUBLE PRECISION, 35=TIMESTAMP, 37=VARCHAR, 261=BLOB
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
     [#"DOUBLE PRECISION" :type/Float]]))

(defmethod sql-jdbc.sync/database-type->base-type :firebird-legacy
           [_ column-type]
           (database-type->base-type column-type))

(defmethod driver/describe-table :firebird-legacy
           [driver database {:keys [name]}]
           (sql-jdbc.execute/do-with-connection-with-options
             driver
             database
             nil
             (fn [^Connection conn]
                 (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)
                       ;; Firebird 1.5: no TRIM(), no EXISTS in SELECT, no derived tables, no RDB$RELATION_TYPE
                       ;; Use direct LEFT JOINs for PK detection
                       result (jdbc/query spec
                                          [(str "SELECT rf.RDB$FIELD_NAME AS field_name, "
                                                "CASE WHEN f.RDB$FIELD_TYPE = 7 THEN 'SMALLINT' "
                                                "WHEN f.RDB$FIELD_TYPE = 8 THEN 'INTEGER' "
                                                "WHEN f.RDB$FIELD_TYPE = 10 THEN 'FLOAT' "
                                                "WHEN f.RDB$FIELD_TYPE = 12 THEN 'DATE' "
                                                "WHEN f.RDB$FIELD_TYPE = 13 THEN 'TIME' "
                                                "WHEN f.RDB$FIELD_TYPE = 14 THEN 'CHAR' "
                                                "WHEN f.RDB$FIELD_TYPE = 16 THEN 'BIGINT' "
                                                "WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' "
                                                "WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' "
                                                "WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' "
                                                "WHEN f.RDB$FIELD_TYPE = 261 AND f.RDB$FIELD_SUB_TYPE = 1 THEN 'BLOB SUB_TYPE TEXT' "
                                                "WHEN f.RDB$FIELD_TYPE = 261 THEN 'BLOB SUB_TYPE 0' "
                                                "ELSE 'VARCHAR' END AS database_type, "
                                                "rf.RDB$FIELD_POSITION AS \"position\", "
                                                "CASE WHEN rf.RDB$NULL_FLAG = 1 THEN 0 ELSE 1 END AS nullable, "
                                                "CASE WHEN idx_pk.RDB$FIELD_NAME IS NOT NULL THEN 1 ELSE 0 END AS pk "
                                                "FROM RDB$RELATION_FIELDS rf "
                                                "JOIN RDB$FIELDS f ON rf.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME "
                                                "LEFT JOIN RDB$RELATION_CONSTRAINTS rc_pk "
                                                "ON rc_pk.RDB$RELATION_NAME = rf.RDB$RELATION_NAME "
                                                "AND rc_pk.RDB$CONSTRAINT_TYPE = 'PRIMARY KEY' "
                                                "LEFT JOIN RDB$INDEX_SEGMENTS idx_pk "
                                                "ON idx_pk.RDB$INDEX_NAME = rc_pk.RDB$INDEX_NAME "
                                                "AND idx_pk.RDB$FIELD_NAME = rf.RDB$FIELD_NAME "
                                                "WHERE rf.RDB$RELATION_NAME = ?") name])]
                      {:name   name
                       :schema nil
                       :fields
                       (into #{}
                             (map (fn [row]
                                      {:name              (str/trim (:field_name row))
                                       :database-type     (:database_type row)
                                       :base-type         (sql-jdbc.sync/database-type->base-type :firebird-legacy (:database_type row))
                                       :database-position (:position row)
                                       :database-required (not (:nullable row))
                                       :pk?               (:pk row)}))
                             result)}))))

;; Firebird 1.5 doesn't support EXISTS in SELECT expressions.
;; Return nil to disable the bulk field-sync SQL and fall back to per-table describe-table.
(defmethod sql-jdbc.sync/describe-fields-sql :firebird-legacy
           [_driver & _args]
           nil)

(defn- firebird-legacy-format [query]
       (log/debugf "Formatting Firebird Legacy query with map: %s" query)
       (let [limit (when (:limit query) (second (:limit query)))
             offset (when (:offset query) (second (:offset query)))
             query (cond-> query
                           (:limit query) (dissoc :limit)
                           (:offset query) (dissoc :offset))
             [sql & params] (try
                              (hsql/format query :dialect :ansi :quoting :ansi :allow-dashed-names true)
                              (catch Exception e
                                (log/errorf "Error in hsql/format: %s, query map: %s" (.getMessage e) query)
                                (throw (ex-info (str "Error in hsql/format: " (.getMessage e))
                                                {:query query} e))))
             clean-sql (-> sql
                           (str/replace #"(?m)^\s*--.*$|(?m)--.*?(?=\n|$)" "")
                           (str/trim))
             modified-sql (-> clean-sql
                              (str/replace #"(DATEADD)\s*\(\s*\"(year|month|day|hour|minute|second|week|quarter)\"" "$1($2")
                              (str/replace #"(EXTRACT)\s*\(\s*\"(YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|WEEKDAY|YEARDAY|WEEK|QUARTER)\"" "$1($2")
                              (str/replace #"(EXTRACT)\s*\(\s*([A-Z]+)\s*,\s*\"from\"\s*,\s*([^)]+)\)" "$1($2 FROM $3)")
                              ;; Firebird 1.5 uses SUBSTRING(col, start, length) with commas — NOT FROM/FOR syntax
                              ;; No need to fix SUBSTRING here since we generate comma syntax for legacy
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
            (log/debugf "Generated Firebird Legacy SQL: %s, params: %s" modified-sql filtered-params)
            (try
              (into [modified-sql] filtered-params)
              (catch Exception e
                (log/errorf "Error formatting Firebird Legacy query: %s" (.getMessage e))
                (throw (ex-info (str "Error formatting Firebird Legacy query: " (.getMessage e))
                                {:query query :sql modified-sql :params filtered-params} e))))))

(defmethod sql.qp/format-honeysql :firebird-legacy
           [driver query]
           (log/debugf "Invoking Firebird Legacy format-honeysql for driver: %s" driver)
           (firebird-legacy-format query))

(defmethod sql.qp/format-honeysql :sql-jdbc
           [driver query]
           (if (= driver :firebird-legacy)
             (firebird-legacy-format query)
             (let [parent-method (get-method sql.qp/format-honeysql :sql)]
                  (parent-method driver query))))

(defmethod sql.qp/apply-top-level-clause [:firebird-legacy :limit]
           [_driver _ honeysql-query {value :limit}]
           {:pre [(pos-int? value)]}
           (assoc honeysql-query :limit [:raw value]))

(defmethod sql.qp/apply-top-level-clause [:firebird-legacy :page]
           [_driver _ honeysql-query {{:keys [items page]} :page}]
           {:pre [(pos-int? items) (pos-int? page)]}
           (let [offset (* (dec page) items)]
                (-> honeysql-query
                    (assoc :limit [:raw items])
                    (assoc :offset [:raw offset]))))

(defn- handle-native-sql
       "Transforms native SQL queries by converting LIMIT and OFFSET to Firebird FIRST and SKIP."
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
            (assoc-in native-query [:native :query] modified-sql)))

(defmethod sql.qp/->honeysql [:firebird-legacy :field]
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
                (hx/identifier :field table-name field-alias)))

(defmethod sql.qp/preprocess :firebird-legacy
           [driver query]
           (let [parent-method (get-method sql.qp/preprocess :sql)
                 processed-query (parent-method driver query)]
                (if (:native query)
                  (handle-native-sql query)
                  (do
                    (log/infof "Preprocessed Firebird Legacy MBQL query: %s" processed-query)
                    processed-query))))

(defmethod sql.qp/->honeysql [:firebird-legacy :concat]
           [driver [_ & args]]
           (let [converted-args (map (fn [arg]
                                         (cond
                                           (and (vector? arg) (= :value (first arg)))
                                           (let [val (second arg)]
                                                (if (string? val)
                                                  [:raw (str "'" (str/replace val "'" "''") "'")]
                                                  [:raw (str "'" (str/replace (str val) "'" "''") "'")]))
                                           (string? arg)
                                           [:raw (str "'" (str/replace arg "'" "''") "'")]
                                           :else
                                           (sql.qp/->honeysql driver arg)))
                                     args)]
                (reduce (fn [acc arg] [:|| acc arg])
                        (first converted-args)
                        (rest converted-args))))

;; Firebird 1.5 SUBSTRING uses comma syntax: SUBSTRING(col FROM start FOR length)
;; Actually, Firebird 1.5 DOES support SUBSTRING(col FROM x FOR y) — it was introduced in Firebird 1.0
;; But HoneySQL may generate commas, so we keep the FROM/FOR named-arg approach
(defmethod sql.qp/->honeysql [:firebird-legacy :substring]
           [driver [_ arg start length]]
           (let [col-name (sql.qp/->honeysql driver arg)
                 start-expr (sql.qp/->honeysql driver start)
                 length-expr (when length (sql.qp/->honeysql driver length))]
                (if length-expr
                  (let [length-expr (if (and (number? length) (> length 32767)) 32767 length-expr)]
                       [:substring col-name :!from start-expr :!for length-expr])
                  [:substring col-name :!from start-expr])))

(defmethod sql.qp/date [:firebird-legacy :default] [_ _ expr] expr)

(defmethod sql.qp/date [:firebird-legacy :minute]
           [_ _ expr]
           (hx/cast :TIMESTAMP [:dateadd :minute 0 expr]))

(defmethod sql.qp/date [:firebird-legacy :minute-of-hour]
           [_ _ expr]
           [:extract :MINUTE :from expr])

(defmethod sql.qp/date [:firebird-legacy :hour]
           [_ _ expr]
           (hx/cast :TIMESTAMP [:dateadd :hour 0 expr]))

(defmethod sql.qp/date [:firebird-legacy :hour-of-day]
           [_ _ expr]
           [:extract :HOUR :from expr])

(defmethod sql.qp/date [:firebird-legacy :day]
           [_ _ expr]
           (hx/cast :DATE expr))

(defmethod sql.qp/date [:firebird-legacy :day-of-week]
           [_ _ expr]
           (hx/+ [:extract :WEEKDAY :from (hx/cast :DATE expr)] 1))

(defmethod sql.qp/date [:firebird-legacy :day-of-month]
           [_ _ expr]
           [:extract :DAY :from expr])

(defmethod sql.qp/date [:firebird-legacy :day-of-year]
           [_ _ expr]
           (hx/+ [:extract :YEARDAY :from expr] 1))

(defmethod sql.qp/date [:firebird-legacy :week]
           [_ _ expr]
           [:dateadd :day (hx/- 0 [:extract :WEEKDAY :from (hx/cast :DATE expr)]) (hx/cast :DATE expr)])

(defmethod sql.qp/date [:firebird-legacy :week-of-year]
           [_ _ expr]
           [:extract :WEEK :from expr])

(defmethod sql.qp/date [:firebird-legacy :month]
           [_ _ expr]
           (hx/cast :DATE [:dateadd :month 0 [:dateadd :day (hx/- 1 [:extract :DAY :from expr]) expr]]))

(defmethod sql.qp/date [:firebird-legacy :month-of-year]
           [_ _ expr]
           [:extract :MONTH :from expr])

(defmethod sql.qp/date [:firebird-legacy :quarter]
           [_ _ expr]
           [:dateadd :month (hx/* (hx// (hx/- [:extract :MONTH :from expr] 1) 3) 3) (hx/cast :DATE [:dateadd :month 0 [:dateadd :day (hx/- 1 [:extract :DAY :from expr]) expr]])])

(defmethod sql.qp/date [:firebird-legacy :quarter-of-year]
           [_ _ expr]
           (hx/+ (hx// (hx/- [:extract :MONTH :from expr] 1) 3) 1))

(defmethod sql.qp/date [:firebird-legacy :year]
           [_ _ expr]
           [:extract :YEAR :from expr])

;; Firebird 1.5 has no BOOLEAN type — use 0/1 integers
(defmethod sql.qp/->honeysql [:firebird-legacy Boolean] [_ bool] (if bool 1 0))

(defmethod sql.qp/add-interval-honeysql-form :firebird-legacy
           [driver hsql-form amount unit]
           (if (= unit :quarter)
             (recur driver hsql-form (hx/* amount 3) :month)
             [:dateadd [:raw (name unit)] amount hsql-form]))

(defmethod sql.qp/current-datetime-honeysql-form :firebird-legacy [_]
           (hx/cast :timestamp (hx/literal :now)))

(defmethod sql.qp/->honeysql [:firebird-legacy :stddev]
           [driver [_ field]]
           [:stddev_samp (sql.qp/->honeysql driver field)])

(defn- zero-time? [t]
       (= (t/local-time t) (t/local-time 0)))

(defmethod sql.qp/->honeysql [:firebird-legacy LocalDate]
           [_ t]
           (hx/cast :DATE (t/format "yyyy-MM-dd" t)))

(defmethod sql.qp/->honeysql [:firebird-legacy LocalDateTime]
           [driver t]
           (if (zero-time? t)
             (sql.qp/->honeysql driver (t/local-date t))
             (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod sql.qp/->honeysql [:firebird-legacy LocalTime]
           [_ t]
           (hx/cast :TIME (t/format "HH:mm:ss.SSSS" t)))

(defmethod sql.qp/->honeysql [:firebird-legacy OffsetDateTime]
           [driver t]
           (if (zero-time? t)
             (sql.qp/->honeysql driver (t/local-date t))
             (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod sql.qp/->honeysql [:firebird-legacy OffsetTime]
           [_ t]
           (hx/cast :TIME (t/format "HH:mm:ss.SSSS" t)))

(defmethod sql.qp/->honeysql [:firebird-legacy ZonedDateTime]
           [driver t]
           (if (zero-time? t)
             (sql.qp/->honeysql driver (t/local-date t))
             (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))
