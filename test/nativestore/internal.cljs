(ns nativestore.internal
  (:require-macros [cemerick.cljs.test
                    :refer [is deftest with-test testing test-var]]
                   [derive.core :refer [defnd with-tracked-dependencies]])
  (:require [cemerick.cljs.test :as t]
            [clojure.set :as set]
            [derive.core :as d]
            [nativestore.core :as store]))

(defn insert-population [store]
  (doto store
    (store/insert! #js {:id 1 :type "user" :name "Fred" :income 10})
    (store/insert! #js {:id 2 :type "user" :name "Zoe" :income 10})
    (store/insert! #js {:id 3 :type "user" :name "Apple" :income 20})
    (store/insert! #js {:id 4 :type "user" :name "Flora" :income 20})
    (store/insert! #js {:id 5 :type "user" :name "Flora" :income 10})
    (store/insert! #js {:id 6 :type "super" :name "George" :income 5})))

(deftest basic-store
  (let [store (store/create)]
    (store/ensure-index store :name :name)
    (->> (store/compound-index [:income :name] [compare compare])
         (store/ensure-index store :income-alpha))
    (insert-population store)
    (is (= (:name (get store 1)) "Fred"))
    (is (= (:name (store 1)) "Fred"))

    ;; With a DB index
    (is (= (set (mapv :id (store/cursor store))) #{1 2 3 4 5 6}))

    ;; With a simple index
    (is (= (set (mapv :id (store/cursor store :name "Flora" "Flora"))) #{4 5}))
    (is (= (set (mapv :id (store/fetch store :name "Flora"))) #{4 5}))

    ;; With a compound index
    (is (= (mapv :id (store/cursor store :income-alpha)) [6 5 1 2 3 4]))
    (is (= (mapv :id (store/cursor store :income-alpha #js [10 "Fred"] #js [20 "Apple"]))
           [1 2 3]))))

(deftest references
  (let [store (store/create)]
    (doto store
      (store/insert! #js {:id 1 :type :account :name "Ian" :friend (store/reference store 2)})
      (store/insert! #js {:id 2 :type :account :name "Fred" :friend (store/reference store 3)})
      (store/insert! #js {:id 3 :type :account :name "Pam"
                          :children #js [(store/reference store 4) (store/reference store 5)]})
      (store/insert! #js {:id 4 :type :account :name "Jack"})
      (store/insert! #js {:id 5 :type :account :name "Jill"}))
    (is (= (:name (:friend (store 1))) (:name (store 2))))
    (is (= (:name (:friend (store 2))) (:name (store 3))))
    (is (= (:name (get-in (store 1) [:friend :friend])) "Pam"))
    (is (= (mapv :name (:children (store 3))) ["Jack" "Jill"]))))
    

(defnd income [store value]
  (mapv :id (store/cursor store :income value value)))

(deftest derive-integration
  (let [store (store/create)]
    (store/ensure-index store :name :name)
    (store/ensure-index store :income :income)
    (insert-population store)
    (with-tracked-dependencies [identity]
      (is (= (count (income store 10)) 3))
      (is (= (count (income store 20)) 2))
      (is (= (count (income store 0)) 0)))))
