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
      [metabase.util.honey-sql-2 :as hx]
      [metabase.util.log :as log]
      [metabase.util.malli :as mu]
      [metabase.util.malli.registry :as mr])
    (:import [java.sql Connection DatabaseMetaData ResultSet]
      [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

(set! *warn-on-reflection* true)

(driver/register! :firebird, :parent :sql-jdbc)

;; Connection details
(defmethod sql-jdbc.conn/connection-details->spec :firebird
           [_driver {:keys [user password dbname host port]
                     :or   {user "sysdba", password "masterkey", dbname "", port 3050, host "localhost"}
                     :as   details}]
           (-> {:classname   "org.firebirdsql.jdbc.FBDriver"
                :subprotocol "firebird"
                :subname     (str "//" host ":" port "/" dbname)
                :user user
                :password password}
               (sql-jdbc.common/handle-additional-options details)))

;; Honey SQL version
(defmethod sql.qp/honey-sql-version :firebird
           [_driver]
           2)

;; Connection test
(defmethod driver/can-connect? :firebird
           [driver details]
           (let [connection (sql-jdbc.conn/connection-details->spec driver details)]
                (= 1 (first (vals (first (jdbc/query connection ["SELECT 1 FROM RDB$DATABASE"])))))))

;; Supported features
(doseq [[feature supported?] {; supported
                              :basic-aggregations                      true
                              :expression-aggregations                 true
                              :foreign-keys                            true
                              :nested-queries                          true
                              :standard-deviation-aggregations         true
                              :schemas                                 true
                              ; not supported
                              :binning                                 false
                              :case-sensitivity-string-filter-options  false
                              :nested-fields                           false
                              :set-timezone                            false}]
       (defmethod driver/database-supports? [:firebird feature] [_driver _feature _db] supported?))

;; Data type mapping
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

;; Schema syncing
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

(defmethod driver/describe-table :firebird
          [driver database {:keys [name]}]
          (try
            (sql-jdbc.execute/do-with-connection-with-options
              driver
              database
              nil
              (fn [^Connection conn]
                  (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)
                        result (jdbc/query spec
                                           ["SELECT TRIM(rf.RDB$FIELD_NAME) AS name, CASE WHEN f.RDB$FIELD_TYPE = 7 THEN 'SMALLINT' WHEN f.RDB$FIELD_TYPE = 8 THEN 'INTEGER' WHEN f.RDB$FIELD_TYPE = 10 THEN 'FLOAT' WHEN f.RDB$FIELD_TYPE = 12 THEN 'DATE' WHEN f.RDB$FIELD_TYPE = 13 THEN 'TIME' WHEN f.RDB$FIELD_TYPE = 14 THEN 'CHAR' WHEN f.RDB$FIELD_TYPE = 16 THEN 'BIGINT' WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' WHEN f.RDB$FIELD_TYPE = 261 THEN 'BLOB SUB_TYPE TEXT' ELSE 'UNKNOWN' END AS database-type, rf.RDB$FIELD_POSITION AS position, CASE WHEN rf.RDB$NULL_FLAG = 1 THEN FALSE ELSE TRUE END AS nullable, EXISTS (SELECT 1 FROM RDB$RELATION_CONSTRAINTS rc JOIN RDB$INDEX_SEGMENTS idx ON rc.RDB$INDEX_NAME = idx.RDB$INDEX_NAME WHERE rc.RDB$CONSTRAINT_TYPE = 'PRIMARY KEY' AND rc.RDB$RELATION_NAME = rf.RDB$RELATION_NAME AND idx.RDB$FIELD_NAME = rf.RDB$FIELD_NAME) AS pk FROM RDB$RELATION_FIELDS rf JOIN RDB$FIELDS f ON rf.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME WHERE rf.RDB$RELATION_NAME = ?" name])]
                       {:name name
                        :schema nil
                        :fields
                        (into #{}
                              (map (fn [row]
                                       {:name (str/trim (:name row))
                                        :database-type (:database-type row)
                                        :base-type (database-type->base-type (:database-type row))
                                        :database-position (:position row)
                                        :database-required (not (:nullable row))
                                        :pk? (:pk row)}))
                              result)})))
            (catch Exception e
              (throw (Exception. (str "Error in describe-table: " (.getMessage e)) e)))))

(defn simple-select-probe-query
      [driver _schema table]
      {:pre [(string? table)]}
      (let [honeysql {:select [:*]
                      :from   [(sql.qp/->honeysql driver (hx/identifier :table (str/trim table)))]
                      :where  [:not= 1 1]}
            honeysql (sql.qp/apply-top-level-clause driver :limit honeysql {:limit 0})]
           (sql.qp/format-honeysql driver honeysql)))

(defn execute-select-probe-query
      [driver ^Connection conn [sql & params]]
      {:pre [(string? sql)]}
      (try
        (with-open [stmt (sql-jdbc.sync.common/prepare-statement driver conn sql params)]
                   (.execute stmt)
                   true)
        (catch Exception e
          (log/errorf "Error executing probe query: %s" (.getMessage e))
          false)))

;; Query clauses
(defmethod sql.qp/apply-top-level-clause [:firebird :limit]
           [_ _ honeysql-form {value :limit}]
           (assoc honeysql-form :modifiers [(format "FIRST %d" value)]))

(defmethod sql.qp/apply-top-level-clause [:firebird :page]
           [_ _ honeysql-form {{:keys [items page]} :page}]
           (assoc honeysql-form :modifiers [(format "FIRST %d SKIP %d" items (* items (dec page)))]))

;; Substring support
(defmethod sql.qp/->honeysql [:firebird :substring]
           [driver [_ arg start length]]
           (let [col-name (sql.qp/->honeysql driver arg)]
                (if length
                  [:substring col-name [:raw (str "FROM " (sql.qp/->honeysql driver start) " FOR " (sql.qp/->honeysql driver length))]]
                  [:substring col-name [:raw (str "FROM " (sql.qp/->honeysql driver start))]])))

;; Date handling
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

;; Boolean handling
(defmethod sql.qp/->honeysql [:firebird Boolean] [_ bool] (if bool 1 0))

;; Interval handling
(defmethod sql.qp/add-interval-honeysql-form :firebird
           [driver hsql-form amount unit]
           (if (= unit :quarter)
             (recur driver hsql-form (hx/* amount 3) :month)
             [:dateadd [:raw (name unit)] amount hsql-form]))

;; Current timestamp
(defmethod sql.qp/current-datetime-honeysql-form :firebird [_]
           (hx/cast :timestamp (hx/literal :now)))

;; Standard deviation
(defmethod sql.qp/->honeysql [:firebird :stddev]
           [driver [_ field]]
           [:stddev_samp (sql.qp/->honeysql driver field)])

;; Date/time conversions
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
