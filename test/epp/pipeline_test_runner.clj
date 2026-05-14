(ns epp.pipeline-test-runner
  (:require [clojure.test :as test]
            [epp.pipeline-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'epp.pipeline-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
