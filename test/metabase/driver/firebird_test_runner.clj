(ns metabase.driver.firebird-test-runner
  (:require [clojure.test :as t]
            [metabase.driver.firebird-test]))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (t/run-tests 'metabase.driver.firebird-test)]
    (println "Summary:" summary)
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
