(ns nativestore.core
  (:require [goog.object :as gobj]
            [goog.array :as garr]
            [derive.core :as d :refer-macros [with-tracked-dependencies defnd]]))

;;
;; Legacy JS wrappers
;;

(defn js-strkey [x]
  (cond
    (string? x) x
    (keyword? x) (name x)
    :else (str x)))

(defn js-lookup
  ([o k]
     (aget o (js-strkey k)))
  ([o k not-found]
     (let [s (js-strkey k)]
       (if-let [res (aget o s)]
         res
         not-found))))

(defn js-copy
  [o]
  (let [t (js/goog.typeOf o)]
    (cond (= t "array")  (garr/clone o)
          :else (gobj/clone o))))

(defn js-assoc
  ([o k v]
     (do (aset o (js-strkey k) v)
         o))
  ([o k v & more]
     (js-assoc o k v)
     (if more
       (recur o (first more) (second more) (nnext more))
       o)))

(defn js-dissoc
  [o k & more]
  (js-delete o (js-strkey k))
  (if more
    (recur o (first more) (next more))
    o))

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

(defprotocol IClearable
  (clear! [idx]))

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
   db (cond (or (string? obj) (number? obj) (not (native? obj)))
            obj
            (native? obj)
            (let [id ((key-fn (.-root db)) obj)]
              (assert id "native object must have an id")
              id))))

(defn identity? [n1 n2]
  (= (aget n1 "id") (aget n2 "id")))

;;
;; Low level methods
;;

;; Mutation can only be done on Natives in
;; a transaction or on copies of Natives generated
;; via copying assoc or clone

(defprotocol IReadOnly
  (-read-only? [_]))

(defprotocol INative)
  
(deftype Native [__keyset ^:mutable __ro]
  INative

  ICloneable
  (-clone [this]
    (let [clone (Native. (volatile! @__keyset) false)]
      (doseq [key @__keyset]
        (aset clone (name key) (aget this (name key))))
      clone))

  IEmptyableCollection
  (-empty [_] (Native. (volatile! #{}) false))

  IReadOnly
  (-read-only? [_] __ro)

  ICounted
  (-count [native]
    (count @__keyset))

  ILookup
  (-lookup [native k]
    (-lookup native k nil))
  (-lookup [native k not-found]
    (assert (keyword? k))
    (let [v (aget native (name k))]
      (cond
        (nil? v) not-found
        (array? v) (if (satisfies? IReference (aget v 0))
                     (amap v i ret (resolve-ref (aget v i)))
                     v)
        (satisfies? IReference v) (resolve-ref v)
        :default v)))

  ITransientAssociative
  (-assoc! [native k v]
    (assert (keyword? k))
    (when (and (-read-only? native) (not *transaction*))
      (throw (js/Error. "Cannot mutate store values outside transact!: ")))
    (vswap! __keyset conj k)
    (aset native (name k) v)
    native)

  ITransientCollection
  (-conj! [native [k v]]
    (assert (keyword? k))
    (-assoc! native k v))
  
  ITransientMap
  (-dissoc! [native k]
    (assert (keyword? k))
    (when (and (-read-only? native) (not *transaction*))
      (throw (js/Error. "Cannot mutate store values outside transact!: ")))
    (vswap! __keyset disj k)
    (js-delete native (name k))
    native)

  IAssociative
  (-assoc [native k v]
    (assert (keyword? k))
    (let [new (clone native)]
      (-assoc! new k v)))
  
  IMap
  (-dissoc [native k]
    (assert (keyword? k))
    (let [new (clone native)]
      (-dissoc! new k)))
    
  ICollection
  (-conj [native [k v]]
    (assert (keyword? k))
    (-assoc native k v))

  ISeqable
  (-seq [native]
    (map (fn [k] [(keyword k) (aget native (name k))]) @__keyset))

  IEncodeClojure
  (-js->clj [native opts]
    native)

  IPrintWithWriter
  (-pr-writer [native writer opts]
    (-write writer "#native ")
    (print-map (-seq native)
               pr-writer writer opts)))
  
(defn native
  "Return a fresh native, optionally with the read-only set"
  ([] (native false))
  ([ro?] (Native. (volatile! #{}) ro?)))

(defn native?
  "Is this object a #native?"
  [native]
  (satisfies? INative native))

(defn to-native
  "Copying conversion function, will return
   a fresh, writable, #native"
  [obj]
  (cond (native? obj)   (clone obj)
        (object? obj)   (let [native (native false)]
                          (goog.object.forEach obj (fn [v k] (-assoc! native (keyword k) v)))
                          native)
        (seqable? obj) (let [native (native false)]
                         (doseq [key (keys obj)]
                           (-assoc! native (keyword key) (get obj (name key)))))
        :default       (throw js/Error (str "Trying to convert unknown object type" (type obj)))))

(defn read-only!
  [native]
  {:pre [(native? native)]}
  (set! (.-__ro native) true)
  native)

(defn read-only?
  [native]
  {:pre [(native? native)]}
  (-read-only? native))

(defn writeable!
  [native]
  {:pre [(native? native)]}
  (set! (.-__ro native) false)
  native)
    


;;
;; Native object store
;; ============================

(defn- upsert-merge
  "Only called from internal methods"
  ([o1 o2]
   ;; allow updates
   (binding [*transaction* (if *transaction* *transaction* true)] 
     (doseq [k (keys o2)]
       (let [kstr (name k)]
         (if-not (nil? (aget o2 kstr))
           (-assoc! o1 k (aget o2 kstr))
           (-dissoc! o1 k)))))
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

  IClearable
  (clear! [idx]
    (goog.array.clear hashmap))

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

  IClearable
  (clear! [idx]
    (goog.array.clear arry))

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

(defn- as-ro-native
  "Ensure submitted object is a native and set to read-only state"
  [obj]
  (if (native? obj)
    (read-only! obj)
    (let [native (to-native obj)]
      (read-only! native))))

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
      (let [obj (as-ro-native obj)]
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
    (with-tracked-dependencies [update-listeners]
      (when-let [old (get root id)]
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
          (-notify-watches store nil #js [:delete old])))
      store))

  IClearable
  (clear! [store]
    ;; Invalidate all listeners
    (d/force-invalidation store)
    ;; Cleanly remove data by reverse-walking the root
    (doseq [obj (seq (-get-cursor root))]
      (delete! store ((key-fn root) obj)))
    ;; Ensure we've cleared everything (e.g. dependency ordering problems)
    (doseq [iname (js-keys indices)]
      (clear! (aget indices iname)))
    (clear! root)
    store)

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
      (js-lookup indices (name iname))
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
      (let [listener (aget tx-listeners name)]
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
 


