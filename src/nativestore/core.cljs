(ns nativestore.core
  (:require [goog.object :as obj]
            [purnam.native.functions :refer [js-lookup js-assoc js-dissoc
                                             js-merge js-map js-copy]]
            [derive.core :as d :refer-macros [with-tracked-dependencies defnd]]))

;;
;; Store protocols
;; ============================

;; + ILookup
(defprotocol IStore
  (insert! [store obj])  ;; shallow merge upsert of native objects
  (delete! [store id])) ;; delete, only need primary ID in submitted object

;; CompFn(KeyFn(obj)) -> value, obj
(defprotocol IIndex
  (key-fn [idx])
  (index! [idx obj])
  (unindex! [idx obj]))

(defprotocol ISortedIndex
  (comparator-fn [idx]))

(defprotocol IScannable
  (-get-cursor [idx] [idx start] [idx start end]))

(defprotocol IIndexedStore
  (add-index! [store name index])
  (rem-index! [store name])
  (get-index  [store name]))

(defprotocol ITransactionalStore
  (-transact! [store f args]))

;;
;; Native Dependencies
;; ============================

;; A dependency representation is:
;;
;; #js { root: #js [<id_i> ...] 
;;       <idx_name>: #js [start end] }
;;
;; The root is a sorted list of object IDs (reference traversal or direct lookups)
;; The remaining index lookups maintain value ranges traversed
;; These become set intersection and range overlap calculations when testing
;; for the impact of a transaction
;;
;; The left side dependency is mutated and returned by all operations
;;

(defn- sorted-insert!
  "Mutates r1.  Keep list of merged IDs in sorted order"
  [r1 r2]
  (goog.array.forEach r2 (fn [v i a] (goog.array.binaryInsert r1 v))))

(defn- merge-range!
  "Mutates r1. The updated range becomes the union of the two ranges"
  [compfn range1 range2]
  (let [r1s (aget range1 0)
        r1e (aget range1 1)
        r2s (aget range2 0)
        r2e (aget range2 1)]
    (when (< (compfn r2s r1s) 0)
      (aset range1 0 r2s))
    (when (> (compfn r2e r1e) 0)
      (aset range1 1 r2e))))

(defn- merge-index!
  "Merge the index range or root set"
  [nset idx range1 range2]
  #_(println "idx: " (type idx) "r1: " range1 "r2: " range2 "\n")
  (if (nil? idx) ; root?
    (sorted-insert! range1 range2)
    (merge-range! (comparator-fn idx) range1 range2)))

(defn- intersect?
  "Do two sorted sets of integers intersect?"
  [set1 set2]
  #_(println "Intersect? " set1 set2)
  (let [len1 (if (nil? set1) 0 (alength set1))
        len2 (if (nil? set2) 0 (alength set2))]
    (loop [i 0 j 0]
      (if (or (== i len1) (== j len2))
        false
        (let [v1 (aget set1 i)
              v2 (aget set2 j)]
          (cond (= v1 v2) true
                (> (compare v1 v2) 0) (recur i (inc j))
                :default (recur (inc i) j)))))))

;; (0 10) (2 2) => true
;; (10 20) (5 5) => false
;; (10 20) (0 10) => true
;; (10 20) (0 nil) => true
;; (10 nil) (20 20) => true
(defn- overlap?
  "Does the interval of other overlap this?"
  [compfn range1 range2]
  (let [r1s (aget range1 0)
        r1e (aget range1 1)
        r2s (aget range2 0)
        r2e (aget range2 1)
        res (not (or (if (nil? r1e) (< (compfn r2e r1s) 0) (> (compfn r2s r1e) 0))
                     (if (nil? r2e) (< (compfn r1e r2s) 0) (< (compfn r2e r1s) 0))))]
    #_(println "Overlap? " range1 range2 res)
    res))
    
             
(defn- match-index?
  [nset idx this-range other-range]
  #_(println "Matching index: " this-range " " other-range "\n")
  (if (nil? idx) ; root?
    (intersect? this-range other-range)
    (overlap? (comparator-fn idx) this-range other-range)))

(deftype NativeDependencySet [store deps]
  IPrintWithWriter
  (-pr-writer [native writer opts]
    (-write writer (str "#ndep [" (pr-str deps) "]")))

  IHash
  (-hash [o]
    (goog/getUid o))

  IEquiv
  (-equiv [o other]
    (if (instance? NativeDependencySet other)
      (== (-hash o) (-hash other))
      false))
  
  d/IDependencySet
  (merge-deps [nset other]
    #_(println "NSet merge: " (type store) deps other "\n")
    (let [fdeps (if (nil? (.-deps other)) other (.-deps other))]
      (goog.object.forEach
       fdeps (fn [v k]
               (if-let [mine (aget deps k)]
                 (merge-index! nset (get-index store k) mine v)
                 (aset deps k (js-copy v)))))
      nset))
  
  (match-deps [nset other]
    (let [fdeps (if (nil? (.-deps other)) other (.-deps other))]
      #_(println "Matching: " deps fdeps "\n")
      (goog.object.some 
       fdeps (fn [v k o] #_(println "matching-key: " k "\n")
               (when-let [local (aget deps k)]
                 (match-index? nset (get-index store k) local v)))))))


(defn make-dependencies
  ([store] (NativeDependencySet. store #js {}))
  ([store init] (NativeDependencySet. store init)))

(defn inform-tracker
  ([store deps]
     (when (d/tracking?)
       (inform-tracker d/*tracker* store deps)))
  ([tracker store deps]
     (let [dset (make-dependencies store deps)]
       #_(.log js/console "Informing tracker: " dset " t? " d/*tracker* "\n")
       (d/depends! tracker store dset))))

;;
;; Instance protocols
;; ============================

(def ^{:doc "Inside a transaction?"
       :dynamic true}
  *transaction* nil)

;; A reference wraps a lookup into a store
;; Objects implementing ILookup can test for a
;; IReference and dereference it.

(defprotocol IReference
  (resolve-ref [ref])
  (reference-id [ref])
  (reference-db [ref]))

(deftype NativeReference [store id]
  IPrintWithWriter
  (-pr-writer [native writer opts]
    (-write writer (str "#ref [" id "]")))

  IEquiv
  (-equiv [ref other]
    (and (= store (.-store other))
         (= id (.-id other))))
  
  IReference
  (resolve-ref [_] (get store id))
  (reference-id [_] id)
  (reference-db [_] store))

(declare native?)

(defn reference [db obj]
  (NativeReference.
   db (if (or (string? obj) (number? obj) (not (native? obj)))
        obj
        ((key-fn (.-root db)) obj))))

(defn identity? [n1 n2]
  (= (aget n1 "id") (aget n2 "id")))

          
;; Mutation can only be done on Natives in
;; a transaction or on copies of Natives generated
;; via assoc, etc. or store/clone

(defprotocol IReadOnly
  (-read-only? [_]))

(defprotocol INative)
  
(declare clone-native)

(deftype Native [^:mutable __ro]
  INative

  IEmptyableCollection
  (-empty [_] (Native. false))

  IReadOnly
  (-read-only? [_] __ro)

  IPrintWithWriter
  (-pr-writer [native writer opts]
    (-write writer "#native ")
    (print-map
     (->> (js-keys native)
          (keep (fn [k]
                  (let [v (aget native k)
                        k (keyword k)]
                    (when-not (or (fn? v)
                                  (#{:cljs$lang$protocol_mask$partition0$ :cljs$lang$protocol_mask$partition1$ :__ro :nativestore$core$IReadOnly$} k))
                      [k v])))))
     pr-writer writer opts))

  ICounted
  (-count [native]
    (alength (js-keys native)))

  ILookup
  (-lookup [native k]
    (-lookup native k nil))
  (-lookup [native k not-found]
    (let [key (if (keyword? k) (name k) k)
          res (aget native key)]
      (cond (nil? res)
            not-found
            (array? res)
            (if (satisfies? IReference (aget res 0))
              (amap res i ret (resolve-ref (aget res i)))
              res)
            (satisfies? IReference res)
            (resolve-ref res)
            :default
            res)))

  ITransientAssociative
  (-assoc! [native k v]
    (when (and (-read-only? native) (not *transaction*))
      (throw (js/Error. "Cannot mutate store values outside transact!: ")))
    (aset native (if (keyword? k) (name k) k) v)
    native)

  ITransientCollection
  (-conj! [native [k v]]
    (when (and (-read-only? native) (not *transaction*))
      (throw (js/Error. "Cannot mutate store values outside transact!: ")))
    (aset native (if (keyword? k) (name k) k) v)
    native)
  
  ITransientMap
  (-dissoc! [native k]
    (when (and (-read-only? native) (not *transaction*))
      (throw (js/Error. "Cannot mutate store values outside transact!: ")))
    (cljs.core/js-delete native (if (keyword? k) (name k) k))
    native)

  IAssociative
  (-assoc [native k v]
    (let [new (clone-native native)]
      (aset new (if (keyword? k) (name k) k) v)
      new))
  
  IMap
  (-dissoc [native k]
    (let [new (clone-native native)]
      (cljs.core/js-delete new (if (keyword? k) (name k) k))
      new))
    
  ICollection
  (-conj [native [k v]]
    (let [new (clone-native native)]
      (aset new (if (keyword? k) (name k) k) v)
      new))

  ISeqable
  (-seq [native]
    (let [res (array)]
      (goog.object.forEach
       native
       (fn [v k]
         (let [k (keyword k)]
           (when-not (or (fn? v)
                         (#{:cljs$lang$protocol_mask$partition0$ :cljs$lang$protocol_mask$partition1$ :__ro :nativestore$core$IReadOnly$ :nativestore$core$INative$} k))
             (.push res [k v])))))
      (seq res))))
  

(defn to-native
  "Copying version of to-native"
  [jsobj]
  (let [native (Native. false)]
    (goog.object.forEach jsobj (fn [v k] (aset native k v)))
    native))

(defn native? [native]
  (satisfies? INative native))

(defn read-only! [native]
  {:pre [(native? native)]}
  (set! (.-__ro native) true)
  native)

(defn writeable! [native]
  {:pre [(native? native)]}
  (set! (.-__ro native) false)
  native)

(defn- clone-native [native]
  {:pre [(native? native)]}
  (let [clone (new Native native)]
    (goog.object.forEach native (fn [v k] (aset clone k v)))
    (writeable! clone)))
    


;;
;; Native object store
;; ============================


(defn upsert-merge
  ([o1 o2]
     (doseq [k (js-keys o2)]
       (if-not (nil? (aget o2 k))
         (aset o1 k (aget o2 k))
         (js-delete o1 k)))
     o1)
  ([o1 o2 & more]
     (apply upsert-merge (upsert-merge o1 o2) more)))

;; Return a cursor for walking a range of the index
(deftype Cursor [idx start end ^:mutable valid? empty?]
  IReduce
  (-reduce [this f]
    (-reduce this f (f)))
  (-reduce [this f init]
    (if empty?
      init
      (let [a (or (.-arry idx) (aget idx "arry"))]
        (loop [i start ret init]
          (if (<= i end)
            (recur (inc i) (f ret (aget a i)))
            ret)))))

  ISeqable
  (-seq [this]
    (seq (into [] this))))


;(deftype WrappedCursor [idx start end ^:mutable valid?]
;  IReduce
;  (-reduce [this f]
;    (-reduce this f (f)))
;  (-reduce [this f init]
;    (let [a (.-arry idx)]
;      (loop [i start ret init]
;        (if (<= i end)
;          (recur (inc i) (f ret (aget a i)))
;          ret)))))

;; Hash KV Index, meant to be for a root store index (unique keys)
;; - Merging upsert against existing if keyfn output matches
;; - Nil values in provided object deletes keys
;; - Original object maintains identity
(deftype HashIndex [keyfn hashmap]
  ILookup
  (-lookup [idx val]
    (-lookup idx val nil))
  (-lookup [idx val not-found]
    (js-lookup hashmap val not-found))

  IFn
  (-invoke [idx k]
    (-lookup idx k))
  (-invoke [idx k not-found]
    (-lookup idx k not-found))

  ICounted
  (-count [idx] (alength (js-keys hashmap)))

  IIndex
  (key-fn [idx] keyfn)
  (index! [idx obj]
    (let [key (keyfn obj)
          old (js-lookup hashmap key)]
      (js-assoc hashmap key (if old (upsert-merge old obj) obj))))
  (unindex! [idx obj]
    (let [key (keyfn obj)]
      (js-dissoc hashmap key obj)))

  IScannable
  (-get-cursor [idx]
    (let [vals (js-obj "arry" (goog.object.getValues (.-hashmap idx)))]
      (Cursor. vals 0 (dec (alength (aget vals "arry"))) true false)))
  (-get-cursor [idx start]
    (assert false "Hash index does not support range queries"))
  (-get-cursor [idx start end]
    (assert false "Hash index does not support range queries")))

(defn root-index []
  (HashIndex. #(aget % "id") #js {}))

;; KV index using binary search/insert/remove on array
;; - Always inserts new objects in sorted order
;; - Matches on object identity for unindex!
(deftype BinaryIndex [keyfn compfn arry]
  ILookup
  (-lookup [idx val]
    (-lookup idx val nil))
  (-lookup [idx val not-found]
    (let [index (goog.array.binarySearch arry val #(compfn %1 (keyfn %2)))]
      (if (>= index 0)
        (loop [end index]
          (if (= (compfn val (keyfn (aget arry end))) 0)
            (recur (inc end))
            (goog.array.slice arry index end)))
        not-found)))

  IFn
  (-invoke [idx k]
    (-lookup idx k))
  (-invoke [idx k not-found]
    (-lookup idx k not-found))

  IIndex
  (key-fn [idx] keyfn)
  (index! [idx obj]
    (let [loc (goog.array.binarySearch arry obj #(compfn (keyfn %1) (keyfn %2)))]
      (if (>= loc 0)
        (goog.array.insertAt arry obj loc)
        (goog.array.insertAt arry obj (- (inc loc)))))
    idx)
  (unindex! [idx obj]
    (let [loc (goog.array.indexOf arry obj)]
      (when (>= loc 0)
        (goog.array.removeAt arry loc)))
    idx)

  ISortedIndex
  (comparator-fn [idx] compfn)

  IScannable
  (-get-cursor [idx]
    (Cursor. idx 0 (dec (alength (.-arry idx))) true false))
  (-get-cursor [idx start]
    (let [head (goog.array.binarySearch arry start #(compfn %1 (keyfn %2)))
          head (if (>= head 0) head (- (inc head)))]
      (Cursor. idx head (dec (alength (.-arry idx))) true false)))
  (-get-cursor [idx start end]
    (let [headidx (goog.array.binarySearch arry start #(compfn %1 (keyfn %2)))
          head (if (>= headidx 0) headidx (- (inc headidx)))
          tailidx (goog.array.binarySearch arry end #(compfn %1 (keyfn %2)))
          tail (if (>= tailidx 0) tailidx (- (inc tailidx)))
          tail (if (not (>= tail (alength (.-arry idx))))
                 (loop [tail tail]
                   (let [next (keyfn (aget arry tail))
                         c (compfn end next)]
                     (if (= c 0)
                       (if (not= (inc tail) (alength (.-arry idx)))
                         (recur (inc tail))
                         tail)
                       (dec tail))))
                 tail)]
      (let [empty? (and (= head tail) (and (< tailidx 0) (< headidx 0)))]
        (Cursor. idx head tail true empty?)))))

(defn ordered-index [keyfn compfn]
  (BinaryIndex. keyfn compfn (array)))

;; (deftype MultiIndex [keyfn bidx]
;;   IIndex
;;   (key-fn [idx] keyfn)
;;   (index! [idx obj]
;;     (let [vals (keyfn obj)
;;           len (alength vals)]
;;       (loop [i (alength vals)]
;;         (when (< i len)
;;           (index! bidx (array (aget vals i) obj))
;;           (recur (inc i))))))
;;   (unindex! [idx obj]
;;     (let [arry (aget bidx "arry")
;;           find (fn [obj] (goog.array.findIndex arry #(= (aget % 1) obj)))]
;;       (loop [loc (find obj)]
;;         (when (>= loc 0)
;;           (goog.array.removeAt arry loc)
;;           (recur (find obj))))))
    
;;   ISortedIndex
;;   (comparator-fn [idx]
;;     (comparator-fn bidx))

;;   IScannable
;;   (-get-cursor [idx]
;;     (let [cur (-get-cursor bidx)]
;;       (WrappedCursor. (aget cur "idx")
;;                       (aget cur "start")
;;                     (aget cur "end")
;;                     #(aget % 1)
;;                     (aget cur "valid?"))))
  
;; (-get-cursor [idx start]
;;   (let [cur (-get-cursor bidx start)]
;;     (WrappedCursor. (aget cur "idx")
;;                     (aget cur "start")
;;                     (aget cur "end")
;;                     #(aget % 1)
;;                     (aget cur "valid?"))))
  
;; (-get-cursor [idx start end]
;;   (let [cur (-get-cursor bidx start end)]
;;     (WrappedCursor. (aget cur "idx")
;;                     (aget cur "start")
;;                     (aget cur "end")
;;                     #(aget % 1)
;;                     (aget cur "valid?")))))
    
;; (defn multi-index [keyfn compfn]
;;   (MultiIndex. keyfn (ordered-index #(aget val 0) compfn)))

(defn compound-key-fn
  "Return a js array key for compound ordering"
  [keyfns]
  (let [cnt (count keyfns)]
    (fn [obj]
      (let [vals (new js/Array cnt)]
        (loop [i 0 keyfns keyfns]
          (if (empty? keyfns)
            vals
            (if-let [val ((first keyfns) obj)]
              (do (aset vals i val)
                  (recur (inc i) (rest keyfns)))
              nil)))))))

(defn compound-comparator
  "Compare two compound keys using the array of comparators"
  [comps]
  (let [cnt (count comps)]
    (fn [akey1 akey2]
      (loop [i 0 comps comps ans 0]
        (if-not (empty? comps)
          (let [comp (first comps)
                res (comp (aget akey1 i) (aget akey2 i))]
            (if (= res 0)
              (recur (inc i) (rest comps) res)
              res))
          ans)))))
  
(defn compound-index [keyfns compfns]
  (BinaryIndex. (compound-key-fn keyfns) (compound-comparator compfns) (array)))

(defn as-native
  "Ensure submitted object is a native and set to read-only state"
  [obj]
  (if (native? obj)
    (do (set! (.-__ro obj) true) obj)
    (let [native (to-native obj)]
      (set! (.-__ro native) true)
      native)))

(defn- update-listeners
  "Use this to update store listeners when write dependencies
   have been accumulatd"
  [result dmap]
  #_(.log js/console "Notifying listeners" dmap)
  (let [[store deps] (first dmap)]
    #_(.log js/console "  Notifying store" store deps)
    (when store
      (d/notify-listeners store deps))))

;; A native, indexed, mutable/transactional store
;; - Always performs a merging upsert
;; - Secondary index doesn't index objects for key-fn -> nil
(deftype NativeStore [root indices tx-listeners ^:mutable listeners]
  IPrintWithWriter
  (-pr-writer [native writer opts]
    (-write writer (str "#NativeStore[]")))
  
  ILookup
  (-lookup [store id]
    (-lookup store id nil))
  (-lookup [store id not-found]
    (inform-tracker store (js-obj "root" (array id)))
    (-lookup root id not-found))

  ICounted
  (-count [store] (-count root))

  IFn
  (-invoke [store k]
    (-lookup store k nil))
  (-invoke [store k not-found]
    (-lookup store k not-found))

  IStore
  (insert! [store obj]
    ;; 1) Transactional by default or participates in wrapping transaction
    ;; Transaction listeners get a log of all side effects per transaction
    ;;
    ;; 2) Track side effects against indices, etc and forward to enclosing
    ;; transaction if present or notify active dependency listeners
    #_(.log js/console "Called insert!\n")
    ;; reuse this for writes instead of reads
    (with-tracked-dependencies [update-listeners]
      (let [obj (as-native obj)]
        (let [key ((key-fn root) obj)
              _ (assert key "Must have an ID field")
              names (js-keys indices)
              old (get root key)
              oldref (when old (js-copy old))]
          ;; Unindex 
          (when old
            (doseq [iname names]
              (let [idx (aget indices iname)
                    ikey ((key-fn idx) old)]
                (when-not (or (nil? ikey) (= ikey false))
                  (inform-tracker store (js-obj (name iname) (array ikey ikey)))
                  (unindex! idx old)))))
          ;; Merge-update the root
          #_(println "Informing tracker of root: " (js-obj "root" (array key)) "\n")
          (inform-tracker store (js-obj "root" (array key)))
          (index! root obj) ;; merging upsert
          (let [new (get root key)]
            ;; Re-insert
            (doseq [iname names]
              (let [idx (aget indices iname)
                    ikey ((key-fn idx) new)]
                (when-not (or (nil? ikey) (= ikey false))
                  (inform-tracker store (js-obj (name iname) (array ikey ikey)))
                  (index! idx new))))
            ;; Update listeners
            (if *transaction*
              (.push *transaction* #js [:insert oldref new])
              (-notify-watches store nil #js [#js [:insert oldref new]]))))
        store)))

  (delete! [store id]
    (assert (contains? root id) "Exists")
    (with-tracked-dependencies [update-listeners]
      (let [old (get root id)]
        (doseq [iname (js-keys indices)]
          (let [idx (aget indices iname)
                ikey ((key-fn idx) old)]
            (when-not (or (nil? ikey) (= ikey false))
              (inform-tracker store (js-obj (name iname) (array ikey ikey)))
              (unindex! idx old))))
        (inform-tracker store (js-obj "root" (array id)))
        (unindex! root old)
        (if *transaction*
          (.push *transaction* #js [:delete old])
          (-notify-watches store nil #js [:delete old]))
        store)))

  IIndexedStore
  (add-index! [store iname index]
    (assert (not (get-index store iname)))
    (js-assoc indices iname index)
    store)
  (rem-index! [store iname]
    (assert (get-index store iname))
    (js-dissoc indices iname)
    store)
  (get-index [store iname]
    (if (or (string? iname) (keyword? iname))
      (js-lookup indices iname)
      iname))

  ITransactionalStore
  (-transact! [store f args]
    ;; TODO: separate process so we ignore read deps in transactions?
    (with-tracked-dependencies [update-listeners true]
      (binding [*transaction* #js []]
        (let [result (apply f store args)]
          (-notify-watches store nil *transaction*)))))
        
  IWatchable
  (-notify-watches [store _ txs]
    (doseq [name (js-keys tx-listeners)]
      (let [listener (get tx-listeners name)]
        (listener nil txs)))
    store)
  (-add-watch [store key f]
    (js-assoc tx-listeners key f)
    store)
  (-remove-watch [store key]
    (js-dissoc tx-listeners key)
    store)

  d/IDependencySource
  (subscribe! [this listener]
    (set! listeners (update-in listeners [nil] (fnil conj #{}) listener)))
  (subscribe! [this listener deps]
    (set! listeners (update-in listeners [deps] (fnil conj #{}) listener)))
  (unsubscribe! [this listener]
    (let [old-set (get listeners nil)
          new-set (disj listeners listener)]
      (if (empty? new-set)
        (set! listeners (dissoc listeners nil))
        (set! listeners (assoc listeners nil new-set)))))
  (unsubscribe! [this listener deps]
    (let [old-set (get listeners deps)
          new-set (when (set? old-set) (disj old-set listener))]
      (if (empty? new-set)
        (set! listeners (dissoc listeners deps))
        (set! listeners (assoc listeners deps new-set)))))
  (empty-deps [this] (make-dependencies this)))


(defn transact! [store f & args]
  (-transact! store f args))

(defn create []
  (NativeStore. (root-index) #js {} #js {} {}))


;;
;; External interface
;;

(defn fetch 
  ([store key]
     (get store key))
  ([store index key]
     (-> (get-index store index) (get key))))

(defn- first-val [idx]
  ((key-fn idx) (aget (.-arry idx) 0)))

(defn- last-val [idx]
  ((key-fn idx) (aget (.-arry idx) (- (alength (.-arry idx)) 1))))

(defn cursor
  "Walk the entire store, or an index"
  ([store]
     ;; NOTE: No dependencies on whole DB
     (-get-cursor (.-root store)))
  ([store index]
     (let [iname (name index)
           index (get-index store iname)]
       (assert index (str "Index " iname " is not defined."))
       (inform-tracker store (js-obj iname (array)))
       (-get-cursor index)))
  ([store index start]
     (let [iname (name index)
           index (get-index store index)]
       (assert index (str "Index " iname " is not defined."))
       (inform-tracker store (js-obj iname (array start))) ;; shorthand
       (-get-cursor index start)))
  ([store index start end]
     (let [iname (name index)
           index (get-index store index)]
       (assert index (str "Index " iname " is not defined."))
       (inform-tracker store (js-obj iname (array start end)))
       (-get-cursor index start end))))

(defn field-key [field]
  (let [f (name field)]
    (fn [obj]
      (aget obj f))))

(defn type-field-key [type field]
  (let [t (name type)
        f (name field)]
    (fn [obj]
      (when (= t (aget obj "type"))
        (aget obj f)))))

(defn ensure-index
  ([store iname key comp]
     (when-not (get-index store iname)
       (add-index! store iname (ordered-index (if (fn? key) key (field-key key)) comp))))
  ([store iname key-or-idx]
     (if (or (keyword? key-or-idx) (symbol? key-or-idx))
       (ensure-index store iname key-or-idx compare)
       (when-not (get-index store iname)
         (add-index! store iname key-or-idx))))) ;; key is an index

(comment
  (def store (native-store))
  (add-index! store :name (ordered-index (field-key :name) compare))
  (insert! store #js {:id 1 :type "user" :name "Fred"})
  (insert! store #js {:id 2 :type "user" :name "Zoe"})
  (insert! store #js {:id 3 :type "user" :name "Apple"})
  (insert! store #js {:id 4 :type "user" :name "Flora"})
  (insert! store #js {:id 5 :type "user" :name "Flora"})
  (insert! store #js {:id 6 :type "tracker" :measure 2700})
  (println "Get by ID" (get store 1))
  (println "Get by index" (-> (get-index store :name) (get "Flora")))
  (println (js->clj (r/reduce (cursor store :name) (d/map :id))))) ;; object #6 is not indexed!
 


