(ns nativestore.runner
  (:require [nativestore.internal]
            [nativestore.core]
            [cljs.test :refer-macros [run-tests] :as test]))

(set! *print-newline* false)
(set-print-fn! #(js/console.log %))

(def report (atom nil))

(defn run-all-tests
  []
  (.log js/console "Running all tests")
  (run-tests (test/empty-env)
             'nativestore.internal)
  (test/successful? @report))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (test/successful? m)
    (println "cljs.test/report -> Tests Succeeded!")
    (do
      (reset! report m)
      (println "cljs.test/report -> Tests Failed :(")
      (prn m))))

