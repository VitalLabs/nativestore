(ns nativestore.internal
  (:require-macros [cemerick.cljs.test
                    :refer [is deftest with-test testing test-var]]
                   [derive.core :refer [defnd with-tracked-dependencies]])
  (:require [cemerick.cljs.test :as t]
            [clojure.set :as set]
            [derive.core :as d]
            [nativestore.core :as store]))

(defn insert-population [store]
  (store/insert! store #js {:id 1 :type "user" :name "Fred" :income 10})
  (store/insert! store #js {:id 2 :type "user" :name "Zoe" :income 10})
  (store/insert! store #js {:id 3 :type "user" :name "Apple" :income 20})
  (store/insert! store #js {:id 4 :type "user" :name "Flora" :income 20})
  (store/insert! store #js {:id 5 :type "user" :name "Flora" :income 10})
  (store/insert! store #js {:id 6 :type "super" :name "George" :income 5}))

(deftest basic-store
  (let [store (store/create)]
    (store/ensure-index store :name :name)
    (->> (store/compound-index [:income :name] [compare compare])
         (store/add-index! store :income-alpha))
    (insert-population store)
    (is (= (:name (get store 1)) "Fred"))
    (is (= (:name (store 1)) "Fred"))

    ;; With a simple index
    (is (= (set (mapv :id (store/cursor store :name "Flora" "Flora"))) #{4 5}))
    (is (= (set (mapv :id (store/fetch store :name "Flora"))) #{4 5}))

    ;; With a compound index
    (is (= (mapv :id (store/cursor store :income-alpha)) [6 5 1 2 3 4]))
    (is (= (mapv :id (store/cursor store :income-alpha #js [10 "Fred"] #js [20 "Apple"]))
           [1 2 3]))))

(defnd income [store value]
  (mapv :id (store/cursor store :income value value)))

(deftest derive-integration
  (let [store (store/create)]
    (store/ensure-index store :name :name)
    (store/ensure-index store :income :income)
    (insert-population store)
    (with-tracked-dependencies [identity]
      (println (mapv :id (store/cursor store :income 20 20)))
      (is (= (count (income store 10)) 3))
      (is (= (count (income store 20)) 2)))))
    

  
