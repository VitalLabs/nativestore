NativeStore
===========

NativeStore is a simple, explicitly indexed in-memory datastore for
managing native objects that supports the Derive library's dependency
tracking protocol (http://github.com/vitalreactor/derive).  The store
does not currently maintain historical versioning or a transaction
log, although future versions are likely to support these features to
enable efficient snapshotting and restoration of system state.

NativeStore is best thought of as an in-memory heap with database-like
semantics on which one or more ordered indexes can be defined.
Objects can be insert!'ed and remove!'ed.  Insert operations have
upsert-merge semantics, combining the key-value set of the new object
with the existing state.  The transaction! function enables you to
perform a set of updates and inform listeners of the aggregate updates
rather than on each primitive insert! operation.

## The Native Type

All objects added to the store are converted to the type
nativestore.core.Native.  Native objects support the common
clojurescript interfaces countable, assocable, transient, and lookup.

Standard assoc operations return _shallow_ clones of objects with the
new change.  assoc! is essential aset.

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

We provide a Reference type to simplify modeling state that has a
relational or graph-like structure.  When the clojure API is used to
access a Native attribute, if the resulting value implements the
IReference protocol, the reference is followed and the referenced
value returned instead. This is implemented as a deftype which
maintains a reference to the store value and the root ID to lookup
("id").

## Cursors

The state of the store is modelled as a heap indexed by the value in
the "id" slot with zero or more indices on values of the objects
accessed via NativeCursor values.  The NativeCursor type implements
IReduce and is compatible with the new transducer functions in
Clojurescript.  To perform tasks like sorting, a reducer chain should
return a fresh array.  The derive.native namespace contains various
helper methods for working with natives, cursors, native arrays, and
transducers.

The immutable abstraction is not rigorously enforced.  Some critical
things you should know:

- transaction! is not ACID.  It is Consistent and Isolated (by virtue
  of javascript's single-threadedness).  While side effects are
  computed atomically, they are not strictly Atomic as errors will
  leave the database in an inconsistent state.  There are no persistence
  or Durability guarantees either.
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
- Use NativeStore responsibly.  We emphasized maximal performance,
  reasonable flexiblity, with only a modicum of safety.  Safety
  requires adherence to the conventions mentioned above.

All these tradeoffs may be reconsidered in future revisions of the
NativeStore.  NativeStore should be considered at an Alpha level of
completeness.

# Using with Derive

Here is a native-store powered derive function with attention paid to copying.

```
(define-derivation note [db note-id]
  (let [note   (db note-id)
        sender (:sender note)]                   ;; via native refs
    (-> note 
		(assoc      :note-class (f-of-note note) ;; ensure a copy
		(update-in! :date       human-readable)  ;; mutation
		(assoc!     :content    escape-content)))))  ;; mutation
```

Writing the original note pullutes the DB so is an error (via the
read-only flag on natives) but the assoc copy is now mutable. 



