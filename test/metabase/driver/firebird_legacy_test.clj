(ns metabase.driver.firebird-legacy-test
  "Regression tests for the Firebird Legacy driver (Jaybird 2.2.x for Firebird 1.5+).
   Focused on the Metabase 0.64+ compatibility fixes shared with the standard driver
   (issues #13/#14/#15). The legacy driver had no test coverage before these."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [metabase.driver :as driver]
            ;; requiring the namespace registers the :firebird-legacy driver as a side effect;
            ;; if the namespace failed to compile (e.g. the removed honey-sql-version var),
            ;; this require would throw and every test below would error at load time.
            [metabase.driver.firebird-legacy]
            [metabase.driver.sql.query-processor :as sql.qp]))

(deftest legacy-namespace-loads-and-registers-test
  (testing "the legacy namespace compiles fully and registers :firebird-legacy under :sql-jdbc"
    ;; Issue #13: a defmethod against the removed sql.qp/honey-sql-version var aborts
    ;; compilation of the whole namespace, so the driver never registers. This asserts
    ;; the opposite: the driver is present in the hierarchy.
    (is (isa? driver/hierarchy :firebird-legacy :sql-jdbc))))

(deftest legacy-database-routing-opt-out-test
  (testing ":firebird-legacy opts out of :database-routing (0.64+ defaults it true for :sql drivers, issue #13)"
    (is (false? (driver/database-supports? :firebird-legacy :database-routing nil)))))

(deftest legacy-feature-support-test
  (testing "feature flags still resolve after the 0.64+ fixes (proves the full doseq registered)"
    (is (true?  (driver/database-supports? :firebird-legacy :basic-aggregations nil)))
    (is (true?  (driver/database-supports? :firebird-legacy :nested-queries nil)))
    (is (false? (driver/database-supports? :firebird-legacy :nested-fields nil)))
    ;; Firebird 1.5 can't use EXISTS in SELECT, so bulk field sync is disabled.
    (is (false? (driver/database-supports? :firebird-legacy :describe-fields nil)))))

(deftest legacy-field-clause-arg-order-test
  (testing "both legacy [:field id opts] and Lib [:field opts id] shapes resolve the same column (issue #13)"
    (let [opts       {:metabase.query-processor.util.add-alias-info/source-alias "CUSTOMER_NAME"
                      :base-type  :type/Text
                      :lib/uuid   "11111111-1111-1111-1111-111111111111"}
          id-first   (sql.qp/->honeysql :firebird-legacy [:field 5503 opts])
          opts-first (sql.qp/->honeysql :firebird-legacy [:field opts 5503])
          rendered   (pr-str opts-first)]
      (is (= id-first opts-first)
          "options-first and id-first field clauses must produce identical HoneySQL")
      (is (str/includes? rendered "CUSTOMER_NAME")
          "the real column alias must appear in the identifier")
      (is (not (str/includes? rendered "lib/uuid"))
          "the options map must never be stringified into the column identifier"))))
