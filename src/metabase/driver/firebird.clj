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
           [_driver {:keys [user password dbname host port]
                     :or   {user "sysdba", password "masterkey", dbname "", port 3050, host "localhost"}
                     :as   details}]
           (-> {:classname   "org.firebirdsql.jdbc.FBDriver"
                :subprotocol "firebirdsql"
                :subname     (str "//" host ":" port "/" dbname)
                :user user
                :password password}
               (sql-jdbc.common/handle-additional-options details)))

(defmethod sql.qp/honey-sql-version :firebird
           [_driver]
           2)

(defmethod driver/can-connect? :firebird
           [driver details]
           (let [connection (sql-jdbc.conn/connection-details->spec driver details)]
                (= 1 (first (vals (first (jdbc/query connection ["SELECT 1 FROM RDB$DATABASE"])))))))

(doseq [[feature supported?] {; supported
                              :basic-aggregations                      true
                              :expression-aggregations                 true
                              :foreign-keys                            true
                              :nested-queries                          true
                              :standard-deviation-aggregations         true
                              ; not supported
                              :schemas                                 false
                              :binning                                 false
                              :case-sensitivity-string-filter-options  false
                              :nested-fields                           false
                              :set-timezone                            false}]
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
                                        {:name (str/trim (:name row))
                                         :schema nil}))
                               result)})))
             (catch Exception e
               (throw (Exception. (str "Error in describe-database: " (.getMessage e)) e)))))

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
    [[#"INT64"            :type/BigInteger]
     [#"DECIMAL"          :type/Decimal]
     [#"FLOAT"            :type/Float]
     [#"BLOB"             :type/*]
     [#"INTEGER"          :type/Integer]
     [#"NUMERIC"          :type/Decimal]
     [#"DOUBLE"           :type/Float]
     [#"SMALLINT"         :type/Integer]
     [#"CHAR"             :type/Text]
     [#"BIGINT"           :type/BigInteger]
     [#"TIMESTAMP"        :type/DateTime]
     [#"DATE"             :type/Date]
     [#"TIME"             :type/Time]
     [#"BLOB SUB_TYPE 0"  :type/*]
     [#"BLOB SUB_TYPE 1"  :type/Text]
     [#"BLOB SUB_TYPE TEXT" :type/Text]
     [#"DOUBLE PRECISION" :type/Float]
     [#"BOOLEAN"          :type/Boolean]]))

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
                                                "WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' "
                                                "WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' "
                                                "WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' "
                                                "WHEN f.RDB$FIELD_TYPE = 261 THEN 'BLOB SUB_TYPE TEXT' "
                                                "ELSE 'UNKNOWN' END AS database_type, "
                                                "rf.RDB$FIELD_POSITION AS \"position\", "
                                                "CASE WHEN rf.RDB$NULL_FLAG = 1 THEN FALSE ELSE TRUE END AS nullable, "
                                                "EXISTS (SELECT 1 FROM RDB$RELATION_CONSTRAINTS rc "
                                                "JOIN RDB$INDEX_SEGMENTS idx ON rc.RDB$INDEX_NAME = idx.RDB$INDEX_NAME "
                                                "WHERE rc.RDB$CONSTRAINT_TYPE = 'PRIMARY KEY' "
                                                "AND rc.RDB$RELATION_NAME = rf.RDB$RELATION_NAME "
                                                "AND idx.RDB$FIELD_NAME = rf.RDB$FIELD_NAME) AS pk "
                                                "FROM RDB$RELATION_FIELDS rf "
                                                "JOIN RDB$FIELDS f ON rf.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME "
                                                "WHERE rf.RDB$RELATION_NAME = ?") name])]
                      {:name name
                       :schema nil
                       :fields
                       (into #{}
                             (map (fn [row]
                                      {:name (str/trim (:field_name row))
                                       :database-type (:database_type row)
                                       :base-type (sql-jdbc.sync/database-type->base-type :firebird (:database_type row))
                                       :database-position (:position row)
                                       :database-required (not (:nullable row))
                                       :pk? (:pk row)}))
                             result)}))))

(defmethod sql-jdbc.sync/describe-fields-sql :firebird
           [driver & {:keys [table-names]}]
           (hsql/format
             {:select [[:%trim.rf.rdb$field_name :name]
                       [(hsql/call :case
                                   [:= :f.rdb$field_type 7] "SMALLINT"
                                   [:= :f.rdb$field_type 8] "INTEGER"
                                   [:= :f.rdb$field_type 10] "FLOAT"
                                   [:= :f.rdb$field_type 12] "DATE"
                                   [:= :f.rdb$field_type 13] "TIME"
                                   [:= :f.rdb$field_type 14] "CHAR"
                                   [:= :f.rdb$field_type 16] "BIGINT"
                                   [:= :f.rdb$field_type 27] "DOUBLE PRECISION"
                                   [:= :f.rdb$field_type 35] "TIMESTAMP"
                                   [:= :f.rdb$field_type 37] "VARCHAR"
                                   [:= :f.rdb$field_type 261] "BLOB SUB_TYPE TEXT"
                                   :else "UNKNOWN") :database-type]
                       [:- :rf.rdb$field_position [:inline 1] :database-position]
                       [:null :table-schema]
                       [:rf.rdb$relation_name :table-name]
                       [(hsql/call :exists
                                   {:select [:inline 1]
                                    :from [:rdb$relation_constraints :rc]
                                    :join [:rdb$index_segments :idx
                                           [:= :rc.rdb$index_name :idx.rdb$index_name]]
                                    :where [:and
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
                                    :from [:rdb$triggers :t]
                                    :join [:rdb$dependencies :d
                                           [:= :t.rdb$trigger_name :d.rdb$dependent_name]]
                                    :where [:and
                                            [:= :t.rdb$relation_name :rf.rdb$relation_name]
                                            [:= :d.rdb$field_name :rf.rdb$field_name]
                                            [:= :t.rdb$trigger_type 1]
                                            [:like :t.rdb$trigger_source "%GEN_ID%"]]})
                        :database-is-auto-increment]]
              :from [[:rdb$relation_fields :rf]]
              :join [[:rdb$fields :f] [:= :rf.rdb$field_source :f.rdb$field_name]]
              :where [:and
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
             [sql & params] (try
                              (hsql/format query :dialect :ansi :quoting :ansi :allow-dashed-names true)
                              (catch Exception e
                                (log/errorf "Error in hsql/format: %s, query map: %s" (.getMessage e) query)
                                (throw (ex-info (str "Error in hsql/format: " (.getMessage e))
                                                {:query query} e))))
             clean-sql (-> sql
                           (str/replace #"(?m)^\s*--.*$|(?m)--.*?(?=\n|$)" "")
                           (str/trim))
             modified-sql (cond
                            (and limit offset)
                            (str/replace-first clean-sql #"\bSELECT\b" (str "SELECT FIRST " limit " SKIP " offset))
                            limit
                            (str/replace-first clean-sql #"\bSELECT\b" (str "SELECT FIRST " limit))
                            offset
                            (str/replace-first clean-sql #"\bSELECT\b" (str "SELECT SKIP " offset))
                            :else
                            clean-sql)]
            (log/debugf "Generated Firebird SQL: %s, params: %s" modified-sql params)
            (try
              (into [modified-sql] params)
              (catch Exception e
                (log/errorf "Error formatting Firebird query: %s, query map: %s, SQL: %s, params: %s" (.getMessage e) query modified-sql params)
                (throw (ex-info (str "Error formatting Firebird query: " (.getMessage e))
                                {:query query :sql modified-sql :params params} e))))))

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

(defmethod sql.qp/preprocess :firebird
           [driver query]
           (log/debugf "Preprocessing Firebird query with driver: %s, query: %s" driver query)
           (let [parent-method (get-method sql.qp/preprocess :sql)]
                (log/infof "Preprocessed Firebird MBQL query: %s" :sql)
                (if (:native query)
                  (let [native-sql (:query (:native query))
                        normalized-sql (str/replace native-sql #"\s+" " ")
                        modified-sql (cond
                                       (re-find #"\bLIMIT\s+(\d+)\b\s+OFFSET\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                                       (let [[_ limit-num offset-num] (re-find #"\bLIMIT\s+(\d+)\b\s+OFFSET\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                                             without-limit-offset (str/replace normalized-sql #"\bLIMIT\s+\d+\b\s+OFFSET\s+\d+\b\s*(?:;)?$" "")
                                             modified (str/replace-first without-limit-offset #"\bSELECT\b" (str "SELECT FIRST " limit-num " SKIP " offset-num))]
                                            (log/debugf "Preprocessed Firebird native SQL: %s -> %s" native-sql modified)
                                            modified)
                                       (re-find #"\bLIMIT\s+(\d+)\b\s*(?:;)?$" normalized-sql)
                                       (let [limit-num (second (re-find #"\bLIMIT\s+\d+\b\s*(?:;)?$" normalized-sql))
                                             without-limit (str/replace normalized-sql #"\bLIMIT\s+\d+\b\s*(?:;)?$" "")
                                             modified (str/replace-first without-limit #"\bSELECT\b" (str "SELECT FIRST " limit-num))]
                                            (log/debugf "Preprocessed Firebird native SQL: %s -> %s" native-sql modified)
                                            modified)
                                       :else
                                       (do
                                         (log/infof "No LIMIT clause found or unsupported position in native SQL: %s" native-sql)
                                         native-sql))]
                       (assoc-in query [:native :query] modified-sql))
                  (let [processed-query (parent-method driver query)]
                       (log/infof "Preprocessed Firebird MBQL query: %s" processed-query)
                       processed-query))))

(defmethod sql.qp/->honeysql [:firebird :substring]
           [driver [_ arg start length]]
           (let [col-name (sql.qp/->honeysql driver arg)]
                (if length
                  [:substring col-name [:raw (str "FROM " (sql.qp/->honeysql driver start) " FOR " (sql.qp/->honeysql driver length))]]
                  [:substring col-name [:raw (str "FROM " (sql.qp/->honeysql driver start))]])))

(defmethod sql.qp/date [:firebird :default] [_ _ expr] expr)
(defmethod sql.qp/date [:firebird :minute] [_ _ expr] (hx/cast :TIMESTAMP [:dateadd :minute 0 expr]))
(defmethod sql.qp/date [:firebird :minute-of-hour] [_ _ expr] [:extract :MINUTE expr])
(defmethod sql.qp/date [:firebird :hour] [_ _ expr] (hx/cast :TIMESTAMP [:dateadd :hour 0 expr]))
(defmethod sql.qp/date [:firebird :hour-of-day] [_ _ expr] [:extract :HOUR expr])
(defmethod sql.qp/date [:firebird :day] [_ _ expr] (hx/cast :DATE expr))
(defmethod sql.qp/date [:firebird :day-of-week] [_ _ expr] (hx/+ [:extract :WEEKDAY (hx/cast :DATE expr)] 1))
(defmethod sql.qp/date [:firebird :day-of-month] [_ _ expr] [:extract :DAY expr])
(defmethod sql.qp/date [:firebird :day-of-year] [_ _ expr] (hx/+ [:extract :YEARDAY expr] 1))
(defmethod sql.qp/date [:firebird :week] [_ _ expr] [:dateadd [:raw "DAY"] (hx/- 0 [:extract :WEEKDAY (hx/cast :DATE expr)]) (hx/cast :DATE expr)])
(defmethod sql.qp/date [:firebird :week-of-year] [_ _ expr] [:extract :WEEK expr])
(defmethod sql.qp/date [:firebird :month] [_ _ expr] (hx/cast :DATE [:dateadd :month 0 [:dateadd :day (hx/- 1 [:extract :DAY expr]) expr]]))
(defmethod sql.qp/date [:firebird :month-of-year] [_ _ expr] [:extract :MONTH expr])
(defmethod sql.qp/date [:firebird :quarter] [_ _ expr] [:dateadd [:raw "MONTH"] (hx/* (hx// (hx/- [:extract :MONTH expr] 1) 3) 3) (hx/cast :DATE [:dateadd :month 0 [:dateadd :day (hx/- 1 [:extract :DAY expr]) expr]])])
(defmethod sql.qp/date [:firebird :quarter-of-year] [_ _ expr] (hx/+ (hx// (hx/- [:extract :MONTH expr] 1) 3) 1))
(defmethod sql.qp/date [:firebird :year] [_ _ expr] [:extract :YEAR expr])

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
