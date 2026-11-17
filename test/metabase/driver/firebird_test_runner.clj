(ns metabase.driver.firebird-test-runner
  (:require [clojure.test :as t]
            [metabase.driver.firebird-test]
            [metabase.driver.firebird-legacy-test]))

(def ^:private test-namespaces
  '[metabase.driver.firebird-test
    metabase.driver.firebird-legacy-test])

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (apply t/run-tests test-namespaces)]
    (println "Summary:" summary)
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
