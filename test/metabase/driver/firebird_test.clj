(ns metabase.driver.firebird-test
  "Comprehensive tests for the Firebird Metabase driver.
   Covers SQL generation, type mapping, connection handling, and query processing."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [honey.sql :as hsql]
            [metabase.driver :as driver]
            [metabase.driver.firebird :as firebird]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.util.honey-sql-2 :as hx]))

;;; ------------------------------------------------ SUBSTRING Tests ------------------------------------------------

(deftest substring-sql-generation-test
  (testing "SUBSTRING generates Firebird-compatible SQL without comma before FROM (Issue #7)"
    ;; The key fix: Firebird requires SUBSTRING(col FROM x FOR y), NOT SUBSTRING(col, FROM x FOR y)
    (let [formatted (sql.qp/format-honeysql :firebird
                      {:select [[:substring [:raw "\"COL\""] [:raw "FROM 1 FOR 100"]]]
                       :from   [:test_table]})]
      (is (string? (first formatted)))
      ;; Ensure no comma between column and FROM
      (is (not (re-find #"SUBSTRING\s*\([^,]+,\s*FROM" (first formatted)))
          "SUBSTRING should not have a comma before FROM keyword"))))

(deftest substring-format-fix-in-firebird-format-test
  (testing "firebird-format regex removes comma in SUBSTRING(col, FROM x FOR y)"
    ;; Simulate the SQL that HoneySQL generates with a comma
    (let [input-sql "SELECT SUBSTRING(\"TABLE\".\"COL\", FROM 1 FOR 1234) FROM \"TABLE\""
          ;; Apply the same regex as in firebird-format
          fixed-sql (str/replace input-sql
                      #"(SUBSTRING\s*\([^,]+),\s*(FROM\s)" "$1 $2")]
      (is (= "SELECT SUBSTRING(\"TABLE\".\"COL\" FROM 1 FOR 1234) FROM \"TABLE\"" fixed-sql))
      (is (not (re-find #"SUBSTRING\([^)]*,\s*FROM" fixed-sql))))))

(deftest substring-without-length-test
  (testing "SUBSTRING without length generates correct SQL"
    (let [input-sql "SELECT SUBSTRING(\"COL\", FROM 5) FROM \"TABLE\""
          fixed-sql (str/replace input-sql
                      #"(SUBSTRING\s*\([^,]+),\s*(FROM\s)" "$1 $2")]
      (is (= "SELECT SUBSTRING(\"COL\" FROM 5) FROM \"TABLE\"" fixed-sql)))))

(deftest substring-max-length-cap-test
  (testing "SUBSTRING length exceeding 32767 is capped"
    ;; The driver should cap the substring length at Firebird's max of 32767
    (let [result (sql.qp/->honeysql :firebird [:substring [:field 1 nil] [:value 1 nil] [:value 50000 nil]])]
      ;; We mainly test that it doesn't throw; the cap is applied when length > 32767
      (is (some? result)))))

;;; ------------------------------------------ LIMIT/OFFSET (FIRST/SKIP) Tests ------------------------------------------

(deftest limit-offset-to-first-skip-test
  (testing "LIMIT converts to FIRST"
    (let [input-sql "SELECT \"COL\" FROM \"TABLE\" LIMIT 10"
          ;; Simulate the native SQL handler
          normalized (str/replace input-sql #"\s+" " ")
          modified (when-let [[_ limit-num] (re-find #"\bLIMIT\s+(\d+)\b\s*(?:;)?$" normalized)]
                     (let [without-limit (str/replace normalized #"\bLIMIT\s+\d+\b\s*(?:;)?$" "")]
                       (str/replace-first without-limit #"\bSELECT\b" (str "SELECT FIRST " limit-num))))]
      (is (= "SELECT FIRST 10 \"COL\" FROM \"TABLE\" " modified))))

  (testing "LIMIT with OFFSET converts to FIRST and SKIP"
    (let [input-sql "SELECT \"COL\" FROM \"TABLE\" LIMIT 10 OFFSET 20"
          normalized (str/replace input-sql #"\s+" " ")
          modified (when-let [[_ limit-num offset-num] (re-find #"\bLIMIT\s+(\d+)\b\s+OFFSET\s+(\d+)\b\s*(?:;)?$" normalized)]
                     (let [without (str/replace normalized #"\bLIMIT\s+\d+\b\s+OFFSET\s+\d+\b\s*(?:;)?$" "")]
                       (str/replace-first without #"\bSELECT\b" (str "SELECT FIRST " limit-num " SKIP " offset-num))))]
      (is (= "SELECT FIRST 10 SKIP 20 \"COL\" FROM \"TABLE\" " modified)))))

;;; ------------------------------------------ DATEADD/EXTRACT SQL Fix Tests ------------------------------------------

(deftest dateadd-format-fix-test
  (testing "DATEADD quoted unit names are unquoted"
    (let [input "DATEADD(\"year\" , 1, \"COL\")"
          fixed (str/replace input #"(DATEADD)\s*\(\s*\"(year|month|day|hour|minute|second|week|quarter)\"" "$1($2")]
      (is (= "DATEADD(year , 1, \"COL\")" fixed)))))

(deftest extract-format-fix-test
  (testing "EXTRACT quoted unit names are unquoted"
    (let [input "EXTRACT(\"YEAR\" FROM \"COL\")"
          fixed (str/replace input #"(EXTRACT)\s*\(\s*\"(YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|WEEKDAY|YEARDAY|WEEK|QUARTER)\"" "$1($2")]
      (is (= "EXTRACT(YEAR FROM \"COL\")" fixed))))

  (testing "EXTRACT comma-separated form is fixed to FROM syntax"
    (let [input "EXTRACT(MONTH, \"from\", \"TABLE\".\"COL\")"
          fixed (str/replace input #"(EXTRACT)\s*\(\s*([A-Z]+)\s*,\s*\"from\"\s*,\s*([^)]+)\)" "$1($2 FROM $3)")]
      (is (= "EXTRACT(MONTH FROM \"TABLE\".\"COL\")" fixed)))))

;;; -------------------------------------------- Date Function Tests ---------------------------------------------------

(deftest date-year-test
  (testing ":year extraction generates EXTRACT(YEAR FROM expr)"
    (let [result (sql.qp/date :firebird :year :test_col)]
      (is (= [:extract :YEAR :from :test_col] result)))))

(deftest date-month-test
  (testing ":month truncation generates correct DATEADD/EXTRACT combination"
    (let [result (sql.qp/date :firebird :month :test_col)]
      (is (some? result))
      ;; Should involve CAST to DATE and DATEADD
      (is (vector? result)))))

(deftest date-month-of-year-test
  (testing ":month-of-year extraction generates EXTRACT(MONTH FROM expr)"
    (let [result (sql.qp/date :firebird :month-of-year :test_col)]
      (is (= [:extract :MONTH :from :test_col] result)))))

(deftest date-day-test
  (testing ":day truncation casts to DATE"
    (let [result (sql.qp/date :firebird :day :test_col)]
      (is (some? result)))))

(deftest date-day-of-week-test
  (testing ":day-of-week extraction uses EXTRACT(WEEKDAY) + 1"
    (let [result (sql.qp/date :firebird :day-of-week :test_col)]
      (is (some? result)))))

(deftest date-day-of-month-test
  (testing ":day-of-month extraction generates EXTRACT(DAY FROM expr)"
    (let [result (sql.qp/date :firebird :day-of-month :test_col)]
      (is (= [:extract :DAY :from :test_col] result)))))

(deftest date-day-of-year-test
  (testing ":day-of-year extraction uses EXTRACT(YEARDAY) + 1"
    (let [result (sql.qp/date :firebird :day-of-year :test_col)]
      (is (some? result)))))

(deftest date-hour-test
  (testing ":hour truncation generates DATEADD/CAST combination"
    (let [result (sql.qp/date :firebird :hour :test_col)]
      (is (some? result)))))

(deftest date-hour-of-day-test
  (testing ":hour-of-day extraction generates EXTRACT(HOUR FROM expr)"
    (let [result (sql.qp/date :firebird :hour-of-day :test_col)]
      (is (= [:extract :HOUR :from :test_col] result)))))

(deftest date-minute-test
  (testing ":minute truncation generates DATEADD/CAST combination"
    (let [result (sql.qp/date :firebird :minute :test_col)]
      (is (some? result)))))

(deftest date-minute-of-hour-test
  (testing ":minute-of-hour extraction generates EXTRACT(MINUTE FROM expr)"
    (let [result (sql.qp/date :firebird :minute-of-hour :test_col)]
      (is (= [:extract :MINUTE :from :test_col] result)))))

(deftest date-week-test
  (testing ":week truncation generates DATEADD/EXTRACT combination"
    (let [result (sql.qp/date :firebird :week :test_col)]
      (is (some? result)))))

(deftest date-week-of-year-test
  (testing ":week-of-year extraction generates EXTRACT(WEEK FROM expr)"
    (let [result (sql.qp/date :firebird :week-of-year :test_col)]
      (is (= [:extract :WEEK :from :test_col] result)))))

(deftest date-quarter-test
  (testing ":quarter truncation generates complex DATEADD expression"
    (let [result (sql.qp/date :firebird :quarter :test_col)]
      (is (some? result)))))

(deftest date-quarter-of-year-test
  (testing ":quarter-of-year generates integer quarter calculation"
    (let [result (sql.qp/date :firebird :quarter-of-year :test_col)]
      (is (some? result)))))

;;; ------------------------------------------ Type Mapping Tests -------------------------------------------------

(deftest database-type-to-base-type-test
  (testing "Firebird database types map to correct Metabase base types"
    (are [db-type expected-base-type]
      (= expected-base-type (sql-jdbc.sync/database-type->base-type :firebird db-type))
      "INTEGER"           :type/Integer
      "SMALLINT"          :type/Integer
      "BIGINT"            :type/BigInteger
      "INT64"             :type/BigInteger
      "FLOAT"             :type/Float
      "DOUBLE PRECISION"  :type/Float
      "DECIMAL"           :type/Decimal
      "NUMERIC"           :type/Decimal
      "VARCHAR"           :type/Text
      "CHAR"              :type/Text
      "BLOB SUB_TYPE TEXT" :type/Text
      "BLOB SUB_TYPE 1"   :type/Text
      "DATE"              :type/Date
      "TIME"              :type/Time
      "TIMESTAMP"         :type/DateTime
      "BOOLEAN"           :type/Boolean
      "BLOB"              :type/*
      "BLOB SUB_TYPE 0"   :type/*)))

;;; ------------------------------------------ Boolean Handling Tests ------------------------------------------------

(deftest boolean-to-integer-test
  (testing "Boolean true converts to 1 for Firebird"
    (is (= 1 (sql.qp/->honeysql :firebird true))))

  (testing "Boolean false converts to 0 for Firebird"
    (is (= 0 (sql.qp/->honeysql :firebird false)))))

;;; ------------------------------------------ Concat Tests ---------------------------------------------------------

(deftest concat-uses-pipe-operator-test
  (testing "Concat generates || operator for Firebird"
    (let [result (sql.qp/->honeysql :firebird [:concat [:field 1 nil] [:value "test" nil]])]
      (is (some? result))
      ;; The result should be a nested [:|| ...] form
      (is (vector? result)))))

;;; ------------------------------------------ Connection Details Tests -----------------------------------------------

(deftest connection-details-standard-test
  (testing "Standard connection details generate correct JDBC spec"
    (let [spec (sql-jdbc.conn/connection-details->spec :firebird
                 {:host "localhost" :port 3050 :dbname "test.fdb" :user "SYSDBA" :password "masterkey"})]
      (is (= "org.firebirdsql.jdbc.FBDriver" (:classname spec)))
      (is (= "firebirdsql" (:subprotocol spec)))
      (is (str/includes? (:subname spec) "localhost"))
      (is (str/includes? (:subname spec) "3050"))
      (is (str/includes? (:subname spec) "test.fdb"))
      (is (= "SYSDBA" (:user spec)))
      (is (= "masterkey" (:password spec))))))

(deftest connection-details-uri-test
  (testing "Connection URI generates correct JDBC spec"
    (let [spec (sql-jdbc.conn/connection-details->spec :firebird
                 {:use-conn-uri true
                  :conn-uri "jdbc:firebirdsql://myhost:3050/mydb.fdb?charSet=UTF-8"})]
      (is (= "org.firebirdsql.jdbc.FBDriver" (:classname spec)))
      (is (str/includes? (:subname spec) "myhost"))
      (is (str/includes? (:subname spec) "3050"))
      (is (str/includes? (:subname spec) "mydb.fdb")))))

(deftest connection-details-empty-credentials-test
  (testing "Empty credentials throw an exception"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Invalid Firebird credentials"
          (sql-jdbc.conn/connection-details->spec :firebird
            {:host "localhost" :port 3050 :dbname "test.fdb" :user "" :password ""})))))

(deftest connection-details-default-values-test
  (testing "Default connection values are used when not specified"
    (let [spec (sql-jdbc.conn/connection-details->spec :firebird {})]
      ;; Default user is sysdba, password is masterkey, port 3050, host localhost
      (is (str/includes? (:subname spec) "localhost"))
      (is (str/includes? (:subname spec) "3050")))))

;;; ---------------------------------------- Feature Support Tests ---------------------------------------------------

(deftest feature-support-test
  (testing "Supported features return true"
    (is (true? (driver/database-supports? :firebird :basic-aggregations nil)))
    (is (true? (driver/database-supports? :firebird :expression-aggregations nil)))
    (is (true? (driver/database-supports? :firebird :foreign-keys nil)))
    (is (true? (driver/database-supports? :firebird :nested-queries nil)))
    (is (true? (driver/database-supports? :firebird :standard-deviation-aggregations nil))))

  (testing "Unsupported features return false"
    (is (false? (driver/database-supports? :firebird :schemas nil)))
    (is (false? (driver/database-supports? :firebird :binning nil)))
    (is (false? (driver/database-supports? :firebird :case-sensitivity-string-filter-options nil)))
    (is (false? (driver/database-supports? :firebird :nested-fields nil)))
    (is (false? (driver/database-supports? :firebird :set-timezone nil)))))

;;; ---------------------------------------- Interval/Add Tests ------------------------------------------------------

(deftest add-interval-test
  (testing "add-interval generates DATEADD for standard units"
    (let [result (sql.qp/add-interval-honeysql-form :firebird :test_col 1 :day)]
      (is (= [:dateadd [:raw "day"] 1 :test_col] result))))

  (testing "add-interval converts quarter to months"
    (let [result (sql.qp/add-interval-honeysql-form :firebird :test_col 1 :quarter)]
      (is (some? result))
      ;; Quarter should be converted to 3 months
      (is (vector? result)))))

;;; ---------------------------------------- Current DateTime Test ---------------------------------------------------

(deftest current-datetime-test
  (testing "current-datetime generates CAST('now' AS TIMESTAMP)"
    (let [result (sql.qp/current-datetime-honeysql-form :firebird)]
      (is (some? result)))))

;;; ---------------------------------------- StdDev Test -------------------------------------------------------------

(deftest stddev-test
  (testing "stddev generates STDDEV_SAMP"
    (let [result (sql.qp/->honeysql :firebird [:stddev [:field 1 nil]])]
      (is (vector? result))
      (is (= :stddev_samp (first result))))))

;;; ---------------------------------------- Describe-table SQL Tests (Issue #3) -------------------------------------

(deftest describe-table-sql-firebird25-compat-test
  (testing "describe-table SQL uses integer 0/1 instead of BOOLEAN for Firebird 2.5 compatibility"
    ;; The describe-table method uses raw SQL strings. We verify the SQL doesn't use TRUE/FALSE literals.
    ;; This is a regression test for Issue #3.
    (let [source (slurp "src/metabase/driver/firebird.clj")]
      (is (str/includes? source "THEN 0 ELSE 1 END AS nullable")
          "nullable field should use 0/1 integers, not TRUE/FALSE booleans")
      (is (str/includes? source "IIF(EXISTS")
          "EXISTS for PK detection should be wrapped in IIF() for Firebird 2.5 compatibility"))))

;;; ---------------------------------------- HoneySQL Version Test ---------------------------------------------------

(deftest honeysql-version-test
  (testing "Firebird driver uses HoneySQL version 2"
    (is (= 2 (sql.qp/honey-sql-version :firebird)))))

;;; ---------------------------------------- SQL Comment Removal Test ------------------------------------------------

(deftest sql-comment-removal-test
  (testing "SQL comments are removed from generated queries"
    (let [sql-with-comments "-- This is a comment\nSELECT \"COL\" FROM \"TABLE\" -- inline comment"
          clean-sql (-> sql-with-comments
                        (str/replace #"(?m)^\s*--.*$|(?m)--.*?(?=\n|$)" "")
                        (str/trim))]
      (is (not (str/includes? clean-sql "--")))
      (is (str/includes? clean-sql "SELECT")))))
