(ns nativestore.runner
  (:require [nativestore.internal]
            [nativestore.core]
            [cljs.test :refer-macros [run-tests]]))

(set! *print-newline* false)
(set-print-fn! js/print)

(def report (atom nil))

(defn run-all-tests
  []
  (println "Running tests")
  (run-tests 'nativestore.internal)
  (cljs.test/successful? @report))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (println "cljs.test/report -> Tests Succeeded!")
    (do
      (reset! report m)
      (println "cljs.test/report -> Tests Failed :(")
      (prn m))))

(defn ^:export main []
  (println "Testing Nativestore")
  (run-all-tests))
