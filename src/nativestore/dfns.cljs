(ns derive.dfns
  (:require [clojure.core.reducers :as r])
  (:refer-clojure :exclude [filter map mapcat count remove sort sort-by]))

(extend-protocol IReduce
  array
  (-reduce
    ([coll f]
     (areduce coll i r (f) (f r (aget coll i))))
    ([coll f start]
     (areduce coll i r start (f r (aget coll i))))))

(defn js-conj
  ([] #js [])
  ([val] #js [val])
  ([arry val] (do (.push arry val) arry)))

(extend-protocol ITransientCollection
  array
  (-conj! [arry val] (js-conj arry val))
  (-persistent! [tcoll] tcoll))

(defn count
  ([f coll]
     (reduce #(inc %1) 0 coll)))

(defn reduce->>
  [coll & forms]
  (r/reduce (last forms) ((apply comp (reverse (butlast forms))) coll)))

(defn reducec->>
  [coll & forms]
  (if (> (cljs.core/count forms) 0)
    (r/reduce js-conj #js [] ((apply comp (reverse forms)) coll))
    (r/reduce js-conj #js [] coll)))

(defn sort
  ([coll] (.sort coll compare))
  ([comp coll] (.sort coll comp)))

(defn sort-by
  ([keyfn coll] (.sort coll #(compare (keyfn %1) (keyfn %2))))
  ([keyfn comp coll] (.sort coll #(comp (keyfn %1) (keyfn %2)))))


;;
;; Non-reduce native helpers
;;

(defn sort-in-place [arry f]
  (.sort arry f))
