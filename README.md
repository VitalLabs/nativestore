NativeStore
===========

NativeStore is an explicitly indexed in-memory datastore for managing
native objects.  It supports the
[Derive](http://github.com/vitalreactor/derive) library's dependency
tracking protocol.

NativeStore is best thought of as an in-memory heap with database-like
semantics on which one or more ordered indexes can be defined.
Objects can be insert!'ed and remove!'ed.  Insert operations have
upsert-merge semantics, combining the key-value set of the new object
with the existing state.  The transaction! function enables you to
perform a set of updates and inform listeners of the aggregate updates
rather than on each primitive insert! operation.

## Tutorial

The best way to become familiar with native store 

```clj
user=> (require '[nativestore.core :as store])
```

### Store Interface

NativeStore indexes a heap of native javascript objects uniquely
identified by a root key "id".  It has a simple public API.

Add, removing, and retrieve objects:

```clj
(let [store (store/create)]    ;; Returns a mutable store object
   (store/insert! store obj)   ;; Insert object in DB and return
   (store/delete! store id)    ;; Delete object by ID
   (store/transact! store fn)  ;; Batch multiple inserts/deletes
   (get store id)              ;; Return object for id
   (store id)                  ;; A store implements IKeywordLookup
```

Declare and use an index on the store. Objects that return nil when
passed to key-fn are not indexed. 

```clj
(store/ensure-index store name key-fn)     ;; Declare an index with default sorting on key
(store/ensure-index store name key-fn comparator-fn)
(store/ensure-index store name index)      ;; Add an explicit index (see below)
```

You'll want to use the explicit index form for things like compound
indexes. An ordered index is the default.

```clj
user=> (->> (store/ordered-index (store/field-key :lastname) compare)
            (store/ensure-index store :lastname))

user=> (->> (store/compound-index [:lastname :age] [compare >])
            (store/ensure-index store :name-age))
```

### Store Cursors

Indexes are based on the Closure library's binary search operation
over arrays or standard JS object hashes.  The only public interface
to indexes is a Cursor object.  A Cursor maintains pointers into the
materialized array index and provides read-only access to the collection.

Cursors are reducable (implement IReduce) so you can use them with
transducers and standard seq operations such as map, into, etc.  They
do not, however, implement the seq protocol as they are a wrapper
around a native read-only array.  Cursors are not guaranteed to be
valid across transaction/insert! boundaries so should be used in
contexts with finite extent.

```clj
(store/cursor store)                       ;; A collection over the whole store
(store/cursor store index-name)            ;; A collection over the whole index
(store/cursor store index-name start)      ;; Collection over index starting at start
(store/cursor store index-name start end)  ;; Range over the index
```

Some examples of use:

```clj
(store/insert! store #js {:id 1 :firstname "Joe" :lastname "Smith"})
(store/insert! store #js {:id 2 :firstname "Fred" :lastname "Savage"})
(store/insert! store #js {:id 3 :firstname "Larry" :lastname "Stooge"})
(store/insert! store #js {:id 4 :firstname "Curly" :lastname "Stooge"})
(store/insert! store #js {:id 5 :firstname "Mo" :lastname "Stooge"})
(store/insert! store #js {:id 6 :firstname "Reginald" :lastname "Quince"})

(->> (store/cursor :name)
     (into []))

(let [xform (comp (map :firstname) (map count))]
  (into [] xform (store/cursor :name)))

	 
(defn running-avg
  "Reducing function for computing the running average (mutates)"
  ([] (js-obj "sum" 0 "cnt" 0))
  ([avg] (/ (aget avg "sum") (aget avg "cnt")))
  ([avg val]
    (aset avg "sum" (+ (aget avg "sum") val))
    (aset avg "cnt" (inc (aget avg "cnt")))
	avg))
  
(defn td-avg-string-len
  "Transducer that computes average string length of a collection"	
  [key coll]
  (let [xform (comp (map key) (remove empty?) (map count))]
    (->> (transduce xform running-avg coll)
         (running-avg))))

(->> (store/cursor :name)
     (td-avg-string-len :firstname))
```

### The Native Type

When you add an object to a store, NativeStore upgrades its type to native
which operates just like a native object, but adds a few features
and protocol implementations.

```clj
user=> (def obj (as-native #js {:firstname "John" :lastname "Smith" :age 23}))
#native {:firstname "John" :lastname "Smith" :age 23}

user=> (:firstname obj)  ;; Keyword access
"John"

user=> (obj :firstname)  ;; Map-like interface
"John"

user=> (assoc obj :firstname "Fred")   ;; via shallow clone
#native {:firstname "Fred" :lastname "Smith" :age 23}

user=> obj
#native {:firstname "John" :lastname "Smith" :age 23}

user=> (assoc! obj :firstname "Johnny")  ;; mutating
#native {:firstname "Johnny" :lastname "Smith" :age 23}

user=> obj
#native {:firstname "Johnny" :lastname "Smith" :age 23}

user=> (store 1)
#native {:id 1 :firstname "Joe" :lastname "Smith"}

user=> (assoc! (store 1) :firstname "Joseph")
;; ERROR!  The store returns read only natives

user=> (assoc (store 1) :firstname "Joseph")
#native {:id 1 :firstname "Joeseph" :lastname "Smith"}

```

Native objects support the common clojurescript interfaces countable,
assocable, transient, and lookup.  Standard assoc operations return
_shallow_ clones of objects with the new change.  assoc! is
an abstraction over aset.

Native objects stored in the store are marked read-only on insertion.
When native objects are cloned the read-only setting is cleared,
allowing assoc'ed copies to be freely mutated by downstream code.  The
database state object can be updated by calling insert! on the
store with the mutated object.

The intent with Native objects is to encourage the use of Clojure
idioms on native objects so we can enforce constraints like read-only
(to avoid pernicious changes to the datastore) and dependency tracking
when accessing referenced objects.

## References

We also provide a Reference type to simplify modeling state that has a
relational or graph-like structure.  When the clojure API is used to
access a Native attribute, if the resulting value implements the
IReference protocol, the reference is followed and the referenced
value returned instead. This is implemented as a deftype which
maintains a reference to the store value and the root ID to lookup
("id").

References are usually setup at import time:

```clj
user=> (store/insert! store #js {:id 104 :firstname "Sally" :lastname "Smith"
                                 :father (store/reference 101)})
user=> (store/insert! store #js {:id 105 :firstname "Johnny" :lastname "Smith"
                                 :father (store/reference 101)})
user=> (store/insert! store
         #js {:id 101 :firstname "Joe" :lastname "Smith"
              :children [(store/reference store 104)
                         (store/reference store 105)]})
```

References are supported by the keyword access protocol and unpacked when
read by looking up the current value of the object in the database.

```clj
user=> (get-in (store 104) [:father :firstname])
  "Joe"

user=> (mapv :firstname (:children (store 101)))
  ["Sally" "Johnny"]
```

# Derive Integration

One of the design goals for NativeStore was to provide a foundation for
the concepts in the [Derive](http://github.com/vitalreactor/derive) library.
Because maintaining copies of mutable objects can be error prone, we would like
to carefully bound the lifecycle of a set of queried objects so they are not
used again unless they are guaranteed to be "current".  (Datomic-style immutable value
semantics are not a goal of NativeStore, although history and recoverability
may be -- see below).

Derive helps us define functions using the macro `defnd` that always
return a value computed from the latest state of the database, but
without recomputing the result each time. Instead, the function
subscribes to updates from the database and invalidates results iff
the query result would depend on the latest changes.

```clj
(defnd note-view [store note-id]
  (let [note (store note-id)]
    (-> note
        (update-in :content render-markdown) ;; make a copy
        (assoc! :last-edited (date-str (:last-edited note))))))
```

This function memoizes the result of the call along with the dependencies of
the query (here just the note-id) reported by the store during the execution
of the defnd's body. Callers of defnd, such as an Om render method, can use
`on-changes` to capture dynamic dependences and invoke a callback when any
of those dependencies change. e.g.

```clj
(render [_]
  (derive/on-changes [(derive/om-subscribe-handler owner) #(om/refresh! owner)]
    (render-note (note-view (om/get-shared owner :store) note-id))))
```

The Om subscription handler manages storing the handler and clearing
it when a new callback is being subscribed.  You can also implement a
will-unmount handler using `derive/om-unsubscribe-component` to
unregister the callback when the component is re-rendered or unmounted
to avoid memory leaks.  The refresh function is called when new
dependencies are written.

Here is a more complete derive function involving copying and mutation
of the retrieved data.

```
(defnd note-view [store note-id]
  (let [note   (db note-id)
        sender (:sender note)]                       ;; via native refs
    (-> note 
		(assoc      :note-class (f-of-note note)     ;; ensure a copy
		(update-in! :date       human-readable)      ;; mutation
		(assoc!     :content    escape-content)))))  ;; mutation
```

Overwriting the original note would pullute the DB in an untrackable
way so is an error (via the read-only flag on natives) but the copy is
mutable.  Attribute references that use the reference object also
participate in the dependency tracking protocol.


# Caveat emptor

The immutable abstraction of NativeStore is not rigorously enforced.
Some critical things you should keep in mind:

- Transactions are not ACID.  They are Consistent and Isolated (by
  virtue of javascript's single-threadedness).  While side effects are
  computed atomically, they are not strictly Atomic as errors will
  leave the database in an inconsistent state.  There are no
  persistence or Durability guarantees either.
- It is currently an error to mutate a database object within a transaction
  function without calling insert! to update the indices.  In the future
  we will track reads and conservatively assume that the transaction
  side effects any object it touches.
- Native objects may not be mutated outside transactions unless they are
  first copied.  This is to inhibit side effects to the DB outside of transaction
  functions and insert!/remove!.
- Object identity is retained across inserts (changes are written into
  the original object), but code should not depend on identity or '='
  remaining valid across transactions.
- Database cursors are invalidated by transactions.  There are
  currently no checks for cursor invalidation, so it is best to use
  cursors in contexts with finite extent.
- Conventions are only enforced if you stick to clojure interfaces.
  aget, aset, and direct member access bypass reference resolution,
  read-only enforcemenet, etc.  However, if you kow what you are doing
  you can still access values using constructs like (aget native
  "field") and get near-optimal read performance on natives.
- The store does not currently maintain historical versioning or a
  transaction log, although future versions are likely to support these
  features to enable efficient snapshotting and restoration of system
  state.

All these tradeoffs may be reconsidered in future revisions of the
NativeStore.  NativeStore should be considered at an Alpha level of
completeness.

Use NativeStore responsibly.  We emphasized performance with
reasonable flexiblity and safety.  Adherence to the conventions
mentioned above is crucial to avoid misusing the tool and creating
difficult to track down bugs.





