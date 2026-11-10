;; Copyright 2026 Bad Monkey, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      https://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns agentspaces.pdt
  "Persistent data trees (srad/agentspaces-pdt.md, PD-C-01) over a `fleet/start`
  system with `:pdt true` (or a map of the appendix C keys): a named ref holds
  a map, set, vector, blob, or file tree whose versions are content-addressed
  trees shared through the content swarm, and whose heads travel as `PRef`
  entries in the ref space.

      (def facts (pdt/ref system \"facts\" :map))
      (pdt/swap! facts assoc-in [:claims \"c1\"] {:text \"water boils at 100 C\"})
      (get-in @facts [:claims \"c1\"])            ; on any member
      (add-watch facts :log (fn [_ _ _ new] (println (count new))))

  The values a ref hands back are Clojure collections: `PersistentPMap`,
  `PersistentPSet`, and `PersistentPVector` wrap the Java `PMap`, `PSet`, and
  `PVector` and implement the collection interfaces, so `get`, `get-in`,
  `assoc`, `assoc-in`, `update`, `dissoc`, `conj`, `into`, `count`, `seq`,
  `contains?`, `nth`, `peek`, `pop`, `transient`, `assoc!`, `conj!`, and
  `persistent!` work as on the built-ins. A transient is the Java `Editor` of
  PD-V-05 and `persistent!` is its `done()`. Equality (`=`) between two wrapped
  values compares roots; against a plain collection it compares contents; the
  hash is the hash Clojure gives a plain collection of the same contents, so a
  wrapped value and its plain copy are one key in a hash set or hash map.

  Encoding. Keys and values pass through the Java `Codec.of(Object.class)`
  codec, the canonical CBOR every language shares, so Clojure keywords are
  not native to it: a keyword is written as the string of its print form
  (`:claims` as \":claims\", `:a/b` as \":a/b\") on the way in, and every
  string that starts with a colon reads back as a keyword on the way out. A
  string that itself starts with a colon or a backslash is written with one
  backslash in front (\":x\" as \"\\\\:x\"), and a string that starts with a
  backslash reads back without it, so such strings round trip as strings.
  Symbols are written as strings and read back as strings. Nested values come
  back as Clojure values (maps, vectors, strings, numbers, byte arrays), so
  `get-in`, `assoc-in`, and `update` work on them; keys of nested maps are
  written as strings by CBOR, nested sets come back as vectors, and ratios as
  doubles. A map value or set element cannot be nil.

  Splicing (PDT 2 phase 6, P2-R-05). `subvec`, `catvec`, `splice`, and
  `insert-at` run on a PDT vector in O(log n) node copies through the Java RRB
  operations and return a PDT vector that shares every node off the edited
  path; on any other vector they do what `clojure.core` does. A swap function
  may also return a `clojure.core/subvec` of the current vector, so
  `(into (subvec v 0 i) xs)` costs O(log n) per element of `xs`: the swap takes
  the slice of the edited vector instead of copying it. A spliced vector may be
  relaxed (an `rr` root, read by version 2 readers only); `packed` gives the
  dense vector of the same elements, and `=` compares elements whatever the
  shape.

  Texts (PDT 2 phase 7, P2-C-04). A `:text` ref is a map whose values are
  texts that merge (`PText`, srad/agentspaces-pdt2.md §3.9). `text` makes
  one under this peer's replica, `insert` and `delete` edit it, and `str`
  reads it; two peers' edits of one text meet in the union of their
  characters, with no conflict. Its heads are version 2, so readers at
  feature level 1 skip the ref.

      (def notes (pdt/ref system \"notes\" :text))
      (pdt/swap! notes assoc :doc (pdt/text system \"hello\"))
      (pdt/swap! notes update :doc pdt/insert 5 \" world\")

  Content stores (PDT 2 phase 7, P2-C-04). `store-pin!` asks the group's
  content stores to hold a root, `store-status` shows the copies they hold,
  and `challenge!` makes a store prove it holds a root. A `fleet/start`
  system with `:store` is a content store itself (`agentspaces.store`)."
  (:refer-clojure :exclude [ref swap! subvec])
  (:require [agentspaces.core :as core]
            [clojure.java.io :as io])
  (:import (ai.badmonkey.agentspaces.agent.content StorePin StoreReceipt)
           (ai.badmonkey.agentspaces.api.ad AssetCard)
           (ai.badmonkey.agentspaces.api.space Space Subscription)
           (ai.badmonkey.agentspaces.content Content Content$ChallengeResult)
           (ai.badmonkey.agentspaces.discovery DiscoveryService)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.peering.blocks BlockStore MemoryBlockStore)
           (ai.badmonkey.agentspaces.peering.node GroupRuntime)
           (ai.badmonkey.agentspaces.planner StoreChooser$Need)
           (ai.badmonkey.agentspaces.pdt NodeStore Pdt Pdt$Builder Pdt$Stats)
           (ai.badmonkey.agentspaces.pdt.blob FileEntry PBlob TreePublisher TreePublisher$Published)
           (ai.badmonkey.agentspaces.pdt.refs Heads Heads$Head PConflict Ref)
           (ai.badmonkey.agentspaces.pdt.store PackNodeStore)
           (ai.badmonkey.agentspaces.pdt.stores Stores Stores$Pinning)
           (ai.badmonkey.agentspaces.pdt.values Codec Editor PMap PSet PText PVector SetEditor VectorEditor)
           (clojure.lang APersistentVector$SubVector MapEntry)
           (java.nio.file Path)
           (java.time InstantSource)
           (java.util Collections Iterator Map$Entry Optional)
           (java.util.concurrent CompletableFuture)
           (java.util.function Consumer Function UnaryOperator)))

(set! *warn-on-reflection* true)

;; ------------------------------------------------------------------ encoding

(def ^:private ^Codec object-codec (Codec/of Object))

(defn- ->java
  "A Clojure value as the plain Java value the Object codec encodes: keywords
  and symbols as strings, maps as sorted Java maps when every key is a string
  (so equal maps encode to equal bytes on every peer), sets and sequences as
  lists, ratios as doubles; everything else passes through."
  [v]
  (cond
    (keyword? v) (str v)
    (string? v) (if (and (pos? (.length ^String v))
                         (let [c (.charAt ^String v 0)] (or (= \: c) (= \\ c))))
                  (str "\\" v)
                  v)
    (symbol? v) (str v)
    (map? v) (let [entries (mapv (fn [[k x]] [(->java k) (->java x)]) v)
                   ^java.util.Map out (if (every? #(string? (first %)) entries)
                                        (java.util.TreeMap.)
                                        (java.util.LinkedHashMap.))]
               (doseq [[k x] entries] (.put out k x))
               out)
    (or (set? v) (sequential? v)) (let [out (java.util.ArrayList.)]
                                    (doseq [x v] (.add out (->java x)))
                                    out)
    (ratio? v) (double v)
    :else v))

(defn- ->clj
  "A decoded Java value as a Clojure value: Java maps as maps, lists as
  vectors, strings that start with a colon as keywords, strings that start
  with a backslash as the string after it, small integers as longs."
  [v]
  (cond
    (instance? java.util.Map v)
    (persistent! (reduce (fn [m ^Map$Entry e] (assoc! m (->clj (.getKey e)) (->clj (.getValue e))))
                         (transient {})
                         (.entrySet ^java.util.Map v)))
    (instance? java.util.List v) (into [] (map ->clj) ^java.util.List v)
    (string? v) (cond
                  (and (pos? (.length ^String v)) (= \\ (.charAt ^String v 0))) (subs v 1)
                  (and (> (.length ^String v) 1) (= \: (.charAt ^String v 0))) (keyword (subs v 1))
                  :else v)
    (instance? Integer v) (long v)
    :else v))

(defn- required [v what]
  (if (nil? v)
    (throw (ex-info (str "a PDT " what " cannot be nil") {}))
    v))

(defn- file-entry->map [^FileEntry e]
  {:root (.root e) :size (.size e) :mode (.mode e) :media-type (.mediaType e)})

(defn- ->file-entry ^FileEntry [v]
  (if (instance? FileEntry v)
    v
    (FileEntry. (required (:root v) "file entry root") (long (or (:size v) 0))
                (or (:mode v) FileEntry/REGULAR) (or (:media-type v) FileEntry/OCTET_STREAM))))

;; A conversion: how the wrapper's keys and values cross into the Java value and
;; back, plus the codecs an empty value needs.
(def ^:private plain-conv
  {:k-in ->java :k-out ->clj :v-in ->java :v-out ->clj :keys object-codec :values object-codec})

(def ^:private tree-conv
  {:k-in str :k-out identity :v-in ->file-entry :v-out file-entry->map
   :keys (Codec/of String) :values (FileEntry/codec)})

(defn- ->text [v]
  (if (instance? PText v)
    v
    (throw (ex-info "a :text ref holds texts: make one with (pdt/text system s)" {:value (type v)}))))

(defn- text-conv
  "The conversion of a `:text` ref (PDT 2 phase 7, P2-C-04): keys as in a map
  ref, values texts read and written by `PText.codec` under this peer's
  replica, so the heads are version 2 and two texts merge by their union."
  [replica]
  {:k-in ->java :k-out ->clj :v-in ->text :v-out identity :keys object-codec :values (PText/codec replica)})

(declare ->PersistentPMap ->TransientPMap ->PersistentPSet ->TransientPSet ->PersistentPVector ->TransientPVector)

(defn- iter ^Iterator [s]
  (if-let [s (seq s)] (.iterator ^Iterable s) (.iterator (Collections/emptyList))))

(defn- map-conj
  "Clojure's map `conj`: an entry, a two-element vector, or a collection of entries."
  [m assoc-fn o]
  (cond
    (instance? Map$Entry o) (assoc-fn m (key o) (val o))
    (vector? o) (do (when (not= 2 (count o))
                      (throw (IllegalArgumentException. "Vector arg to map conj must be a pair")))
                    (assoc-fn m (nth o 0) (nth o 1)))
    :else (reduce (fn [m e] (assoc-fn m (key e) (val e))) m (seq o))))

;; ------------------------------------------------------------------ map

(deftype PersistentPMap [^PMap pm conv _meta]
  clojure.lang.IPersistentMap
  (assoc [_ k v]
    (PersistentPMap. (.assoc pm ((:k-in conv) (required k "map key")) ((:v-in conv) (required v "map value"))) conv _meta))
  (assocEx [this k v]
    (if (.containsKey this k)
      (throw (RuntimeException. "Key already present"))
      (.assoc this k v)))
  (without [_ k] (PersistentPMap. (.dissoc pm ((:k-in conv) (required k "map key"))) conv _meta))
  (containsKey [_ k] (and (some? k) (.containsKey pm ((:k-in conv) k))))
  (entryAt [_ k]
    (when (some? k)
      (let [^Optional o (.get pm ((:k-in conv) k))]
        (when (.isPresent o) (MapEntry. k ((:v-out conv) (.get o)))))))
  (count [_] (int (.count pm)))
  (cons [this o] (map-conj this (fn [^PersistentPMap m k v] (.assoc m k v)) o))
  (empty [_] (PersistentPMap. (PMap/empty (.store pm) (.loader pm) ^Codec (:keys conv) ^Codec (:values conv)) conv _meta))
  (equiv [this o]
    (cond
      (instance? PersistentPMap o) (= (.root pm) (.root ^PMap (.-pm ^PersistentPMap o)))
      (map? o) (and (= (count this) (count o))
                    (every? (fn [[k v]] (and (contains? o k) (= v (get o k)))) (seq this)))
      :else false))
  (seq [_]
    (seq (map (fn [^Map$Entry e] (MapEntry. ((:k-out conv) (.getKey e)) ((:v-out conv) (.getValue e))))
              (iterator-seq (.iterator pm)))))
  (iterator [this] (iter this))
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k nf]
    (if (nil? k)
      nf
      (let [^Optional o (.get pm ((:k-in conv) k))]
        (if (.isPresent o) ((:v-out conv) (.get o)) nf))))
  clojure.lang.IFn
  (invoke [this k] (.valAt this k nil))
  (invoke [this k nf] (.valAt this k nf))
  (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
  clojure.lang.IHashEq
  (hasheq [this] (clojure.lang.APersistentMap/mapHasheq this))
  clojure.lang.IEditableCollection
  (asTransient [_] (->TransientPMap (.editor pm) conv _meta))
  clojure.lang.IObj
  (withMeta [_ m] (PersistentPMap. pm conv m))
  clojure.lang.IMeta
  (meta [_] _meta)
  clojure.lang.MapEquivalence
  java.util.Map
  (size [_] (int (.count pm)))
  (isEmpty [_] (.isEmpty pm))
  (get [this k] (.valAt this k nil))
  (containsValue [this v] (boolean (some #(= v (val %)) (seq this))))
  (keySet [this] (set (map key (seq this))))
  (values [this] (mapv val (seq this)))
  (entrySet [this] (set (seq this)))
  (put [_ _ _] (throw (UnsupportedOperationException.)))
  (remove [_ _] (throw (UnsupportedOperationException.)))
  (putAll [_ _] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  Object
  (hashCode [this] (clojure.lang.APersistentMap/mapHash this))
  (equals [this o] (.equiv this o))
  (toString [_] (str "#pdt/map[" (.count pm) " entries " (.root pm) "]")))

(deftype TransientPMap [^Editor editor conv _meta]
  clojure.lang.ITransientMap
  (assoc [this k v]
    (.assoc editor ((:k-in conv) (required k "map key")) ((:v-in conv) (required v "map value")))
    this)
  (without [this k] (.dissoc editor ((:k-in conv) (required k "map key"))) this)
  (persistent [_] (->PersistentPMap (.value (.done editor)) conv _meta))
  (conj [this o] (map-conj this (fn [^TransientPMap m k v] (.assoc m k v)) o))
  (count [_] (int (.count editor)))
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k nf]
    (if (nil? k)
      nf
      (let [^Optional o (.get editor ((:k-in conv) k))]
        (if (.isPresent o) ((:v-out conv) (.get o)) nf))))
  clojure.lang.ITransientAssociative2
  (containsKey [_ k] (and (some? k) (.isPresent (.get editor ((:k-in conv) k)))))
  (entryAt [this k] (when (.containsKey this k) (MapEntry. k (.valAt this k nil)))))

;; ------------------------------------------------------------------ set

(deftype PersistentPSet [^PSet ps conv _meta]
  clojure.lang.IPersistentSet
  (disjoin [_ o] (PersistentPSet. (.disj ps ((:v-in conv) (required o "set element"))) conv _meta))
  (contains [_ o] (and (some? o) (.contains ps ((:v-in conv) o))))
  (get [this o] (when (.contains this o) o))
  (count [_] (int (.count ps)))
  (cons [_ o] (PersistentPSet. (.conj ps ((:v-in conv) (required o "set element"))) conv _meta))
  (empty [this]
    (let [^SetEditor e (.editor ps)]
      (doseq [x (iterator-seq (.iterator ps))] (.disj e x))
      (PersistentPSet. (.value (.done e)) conv _meta)))
  (equiv [this o]
    (cond
      (instance? PersistentPSet o) (= (.root ps) (.root ^PSet (.-ps ^PersistentPSet o)))
      (set? o) (and (= (count this) (count o)) (every? #(contains? o %) (seq this)))
      :else false))
  (seq [_] (seq (map (:v-out conv) (iterator-seq (.iterator ps)))))
  (iterator [this] (iter this))
  clojure.lang.IFn
  (invoke [this o] (.get this o))
  (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
  clojure.lang.IHashEq
  (hasheq [this] (clojure.lang.Murmur3/hashUnordered this))
  clojure.lang.IEditableCollection
  (asTransient [_] (->TransientPSet (.editor ps) conv _meta))
  clojure.lang.IObj
  (withMeta [_ m] (PersistentPSet. ps conv m))
  clojure.lang.IMeta
  (meta [_] _meta)
  java.util.Set
  (size [_] (int (.count ps)))
  (isEmpty [_] (.isEmpty ps))
  (containsAll [this c] (every? #(.contains this %) c))
  (toArray [this] (to-array (seq this)))
  (add [_ _] (throw (UnsupportedOperationException.)))
  (remove [_ _] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  Object
  (hashCode [this] (int (reduce (fn [h x] (unchecked-add-int (int h) (clojure.lang.Util/hash x))) (int 0) (seq this))))
  (equals [this o] (.equiv this o))
  (toString [_] (str "#pdt/set[" (.count ps) " elements " (.root ps) "]")))

(deftype TransientPSet [^SetEditor editor conv _meta]
  clojure.lang.ITransientSet
  (disjoin [this o] (.disj editor ((:v-in conv) (required o "set element"))) this)
  (contains [_ o] (and (some? o) (.contains editor ((:v-in conv) o))))
  (get [this o] (when (.contains this o) o))
  (count [_] (int (.count editor)))
  (conj [this o] (.conj editor ((:v-in conv) (required o "set element"))) this)
  (persistent [_] (->PersistentPSet (.value (.done editor)) conv _meta)))

;; ------------------------------------------------------------------ vector

(defn- index-of? [^long n k]
  (and (integer? k) (<= 0 (long k)) (< (long k) n)))

(deftype PersistentPVector [^PVector pv conv _meta]
  clojure.lang.IPersistentVector
  (length [_] (int (.count pv)))
  (assocN [this i v]
    (let [n (.count pv)
          i (long i)]
      (cond
        (= i n) (.cons this v)
        (index-of? n i) (PersistentPVector. (.assoc pv i ((:v-in conv) (required v "vector element"))) conv _meta)
        :else (throw (IndexOutOfBoundsException. (str "index " i " of " n))))))
  (cons [_ o] (PersistentPVector. (.conj pv ((:v-in conv) (required o "vector element"))) conv _meta))
  (assoc [this k v]
    (if (integer? k)
      (.assocN this (int k) v)
      (throw (IllegalArgumentException. "Key must be integer"))))
  (containsKey [_ k] (index-of? (.count pv) k))
  (entryAt [this k] (when (.containsKey this k) (MapEntry. k (.nth this (int k)))))
  (count [_] (int (.count pv)))
  (empty [_] (PersistentPVector. (PVector/empty (.store pv) (.loader pv) ^Codec (:values conv)) conv _meta))
  (equiv [this o]
    (cond
      (instance? PersistentPVector o) (.equals pv (.-pv ^PersistentPVector o))
      (sequential? o) (and (= (count this) (count o)) (every? true? (map = (seq this) (seq o))))
      :else false))
  (peek [_] (let [^Optional o (.peek pv)] (when (.isPresent o) ((:v-out conv) (.get o)))))
  (pop [_]
    (when (.isEmpty pv) (throw (IllegalStateException. "Can't pop empty vector")))
    (PersistentPVector. (.pop pv) conv _meta))
  (nth [_ i] ((:v-out conv) (.nth pv (long i))))
  (nth [this i nf] (if (index-of? (.count pv) i) (.nth this i) nf))
  (seq [_] (seq (map (:v-out conv) (iterator-seq (.iterator pv)))))
  (rseq [this] (seq (reverse (seq this))))
  java.lang.Iterable
  (iterator [this] (iter this))
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k nf] (if (.containsKey this k) (.nth this (int k)) nf))
  clojure.lang.IFn
  (invoke [this i] (.nth this (int i)))
  (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
  clojure.lang.IHashEq
  (hasheq [this] (clojure.lang.Murmur3/hashOrdered this))
  clojure.lang.IEditableCollection
  (asTransient [_] (->TransientPVector (.editor pv) conv _meta))
  clojure.lang.IObj
  (withMeta [_ m] (PersistentPVector. pv conv m))
  clojure.lang.IMeta
  (meta [_] _meta)
  Object
  (hashCode [this] (int (reduce (fn [h x] (unchecked-add-int (unchecked-multiply-int 31 (int h)) (clojure.lang.Util/hash x))) (int 1) (seq this))))
  (equals [this o] (.equiv this o))
  (toString [_] (str "#pdt/vector[" (.count pv) " elements " (.root pv) "]")))

(deftype TransientPVector [^VectorEditor editor conv _meta]
  clojure.lang.ITransientVector
  (assocN [this i v]
    (let [n (.count editor) i (long i)]
      (cond
        (= i n) (.conj this v)
        (index-of? n i) (do (.assoc editor i ((:v-in conv) (required v "vector element"))) this)
        :else (throw (IndexOutOfBoundsException. (str "index " i " of " n))))))
  (pop [this] (.pop editor) this)
  (assoc [this k v] (.assocN this (int k) v))
  (conj [this o] (.conj editor ((:v-in conv) (required o "vector element"))) this)
  (persistent [_] (->PersistentPVector (.value (.done editor)) conv _meta))
  (count [_] (int (.count editor)))
  (nth [_ i] ((:v-out conv) (.nth editor (long i))))
  (nth [this i nf] (if (index-of? (.count editor) i) (.nth this i) nf))
  (valAt [this k] (.valAt this k nil))
  (valAt [this k nf] (if (index-of? (.count editor) k) (.nth this (int k)) nf)))

;; ------------------------------------------------------------------ splicing (PDT 2 phase 6, P2-R-05)

(defn- pdt-vector? [v] (instance? PersistentPVector v))

(defn- with-pv [^PersistentPVector w ^PVector pv]
  (PersistentPVector. pv (.-conv w) (meta w)))

(defn subvec
  "The elements `[start, end)` of a vector: on a PDT vector an O(log n) slice
  that shares every node off its two cut paths (P2-R-05), on any other vector
  `clojure.core/subvec`."
  ([v start] (subvec v start (count v)))
  ([v start end]
   (if (pdt-vector? v)
     (with-pv v (.slice ^PVector (.-pv ^PersistentPVector v) (long start) (long end)))
     (clojure.core/subvec v start end))))

(defn catvec
  "Vectors joined in order: two PDT vectors in O(log n) node copies
  (P2-R-05), anything else by `into`."
  ([] [])
  ([a] a)
  ([a b]
   (if (and (pdt-vector? a) (pdt-vector? b))
     (with-pv a (.concat ^PVector (.-pv ^PersistentPVector a) (.-pv ^PersistentPVector b)))
     (into a b)))
  ([a b & more] (reduce catvec (catvec a b) more)))

(defn splice
  "The vector with `n` elements from `index` replaced by the elements of
  `coll`: on a PDT vector in O(log n) node copies plus `coll` (P2-R-05)."
  [v index n coll]
  (if (pdt-vector? v)
    (let [^PersistentPVector w v
          in (:v-in (.-conv w))
          items (java.util.ArrayList. ^java.util.Collection (mapv #(in (required % "vector element")) coll))]
      (with-pv w (.splice ^PVector (.-pv w) (long index) (long n) items)))
    (-> (clojure.core/subvec v 0 index) (into coll) (into (clojure.core/subvec v (+ index n))))))

(defn insert-at
  "The vector with `x` inserted before `index`, at most its count (P2-R-05)."
  [v index x]
  (splice v index 0 [x]))

(defn packed
  "The dense form of a PDT vector (P2-R-03): the `vr` vector of the same
  elements, whose root those elements have whatever edits made them, and
  which every reader reads. A dense vector is its own packed form."
  [v]
  (with-pv v (.packed ^PVector (.-pv ^PersistentPVector v))))

(defn relaxed?
  "Whether a PDT vector's tree holds relaxed nodes, so its head is version 2 (P2-R-01)."
  [v]
  (.relaxed ^PVector (.-pv ^PersistentPVector v)))

;; ------------------------------------------------------------------ texts (PDT 2 phase 7, P2-C-04)

(defn- replica
  "This peer's replica of texts (P2-T-02): `PText.replicaOf` its agent's id,
  so each peer's edits carry ids no other peer uses."
  ^String [system]
  (PText/replicaOf (.agent ^PeerIdentity (:identity system)
                           ^String (or (:agent-name system)
                                       (throw (ex-info "texts need a fleet/start system" {:keys (keys system)}))))))

(defn text
  "A new text under this peer's replica (P2-T-02), empty or holding `s`: the
  value a `:text` ref holds. Make a text once and edit it with `insert` and
  `delete`: two peers' edits of one text merge by the union of their
  characters, while a new text put under a key replaces the one there."
  (^PText [system] (PText/empty (replica system)))
  (^PText [system s] (.insert (PText/empty (replica system)) 0 (str s))))

(defn insert
  "The text with `s` inserted before position `pos`, counted in code points (P2-T-02)."
  ^PText [^PText t pos s]
  (.insert t (long pos) (str s)))

(defn delete
  "The text without the `n` code points from position `pos` (P2-T-02)."
  ^PText [^PText t pos n]
  (.delete t (long pos) (long n)))

(defmethod print-method PText [^PText t ^java.io.Writer w]
  (.write w "#pdt/text ") (print-method (str t) w))

(defmethod print-method PersistentPMap [^PersistentPMap m ^java.io.Writer w]
  (.write w "#pdt/map ") (print-method (into {} (seq m)) w))

(defmethod print-method PersistentPSet [^PersistentPSet s ^java.io.Writer w]
  (.write w "#pdt/set ") (print-method (into #{} (seq s)) w))

(defmethod print-method PersistentPVector [^PersistentPVector v ^java.io.Writer w]
  (.write w "#pdt/vector ") (print-method (into [] (seq v)) w))

;; ------------------------------------------------------------------ wrapping

(defn- wrap-for
  "The wrapper of a ref's kind: a function from the Java value to the Clojure
  value (a blob stays a `PBlob`)."
  [kind]
  (case kind
    :map (fn [v] (->PersistentPMap v plain-conv nil))
    :tree (fn [v] (->PersistentPMap v tree-conv nil))
    :set (fn [v] (->PersistentPSet v plain-conv nil))
    :vector (fn [v] (->PersistentPVector v plain-conv nil))
    :blob identity))

(defn- same-store?
  "Whether the result of a swap function was built over the store the current
  value came from (the recording store of PD-X-07), so its nodes are in the delta."
  [result current]
  (cond
    (instance? PMap result) (identical? (.store ^PMap result) (.store ^PMap current))
    (instance? PVector result) (identical? (.store ^PVector result) (.store ^PVector current))
    :else true))

(defn- unwrap
  "The Java value a swap function returned, rebuilt over the current value when
  it is a plain Clojure collection or was built over another store."
  [kind result wrapped-current current]
  (case kind
    (:map :tree :text)
    (cond
      (and (instance? PersistentPMap result) (same-store? (.-pm ^PersistentPMap result) current)) (.-pm ^PersistentPMap result)
      (map? result) (.-pm ^PersistentPMap (into (empty wrapped-current) result))
      :else (throw (ex-info "a map ref's swap function must return a map" {:returned (type result)})))
    :set
    (cond
      (instance? PersistentPSet result) (.-ps ^PersistentPSet result)
      (set? result) (.-ps ^PersistentPSet (into (empty wrapped-current) result))
      :else (throw (ex-info "a set ref's swap function must return a set" {:returned (type result)})))
    :vector
    (cond
      (and (instance? PersistentPVector result) (same-store? (.-pv ^PersistentPVector result) current)) (.-pv ^PersistentPVector result)
      ;; PDT 2 phase 6 (P2-R-05): a clojure.core/subvec of a PDT vector is a slice of it, O(log n)
      (and (instance? APersistentVector$SubVector result)
           (instance? PersistentPVector (.-v ^APersistentVector$SubVector result))
           (same-store? (.-pv ^PersistentPVector (.-v ^APersistentVector$SubVector result)) current))
      (let [^APersistentVector$SubVector sub result]
        (.slice ^PVector (.-pv ^PersistentPVector (.-v sub)) (long (.-start sub)) (long (.-end sub))))
      (sequential? result) (.-pv ^PersistentPVector (into (empty wrapped-current) result))
      :else (throw (ex-info "a vector ref's swap function must return a vector" {:returned (type result)})))
    :blob
    (cond
      (instance? PBlob result) result
      (bytes? result) (PBlob/of ^bytes result (.store ^PBlob current))
      :else (throw (ex-info "a blob ref's swap function must return a PBlob or bytes" {:returned (type result)})))))

;; ------------------------------------------------------------------ the ref

(deftype PdtRef [^Ref ref kind wrap watches last]
  clojure.lang.IDeref
  (deref [_] (let [v (wrap (.deref ref))] (reset! last v) v))
  clojure.lang.IRef
  (setValidator [_ _] nil)
  (getValidator [_] nil)
  (getWatches [_] (into {} (map (fn [[k [f _]]] [k f])) @watches))
  (addWatch [this k f]
    (.removeWatch this k)
    (let [sub (.watch ref (reify Consumer
                            (accept [_ v]
                              (let [nv (wrap v)
                                    ov @last]
                                (reset! last nv)
                                (f k this ov nv)))))]
      (clojure.core/swap! watches assoc k [f sub])
      this))
  (removeWatch [this k]
    (when-let [[_ ^Subscription sub] (get @watches k)]
      (clojure.core/swap! watches dissoc k)
      (try (.close sub) (catch Exception _)))
    this)
  Object
  (toString [_] (str "#pdt/ref[" (.name ref) " " (name kind) " " (.mode ref) "]")))

(defmethod print-method PdtRef [^PdtRef r ^java.io.Writer w]
  (.write w (str r)))

(defn- facade ^Pdt [system]
  (or (:pdt system)
      (throw (ex-info "no persistent data trees: start the peer with {:pdt true}" {:keys (keys system)}))))

(defn- ->kind
  "A ref kind as a keyword: one of the value kinds, or, where `text?`, `:text` too."
  ([kind] (->kind kind false))
  ([kind text?]
   (let [k (keyword kind)]
     (cond
       (#{:map :set :vector :blob :tree} k) k
       (and text? (= :text k)) k
       :else (throw (ex-info (if text?
                               "a ref kind is :map, :set, :vector, :blob, :tree, or :text"
                               "a ref kind is :map, :set, :vector, :blob, or :tree")
                             {:kind kind}))))))

(defn- java-ref ^Ref [^Pdt pdt ^String name kind]
  (case kind
    :map (.ref pdt name PMap)
    :set (.ref pdt name PSet)
    :vector (.ref pdt name PVector)
    :blob (.ref pdt name PBlob)
    :tree (.treeRef pdt name)))

(defn ref
  "The ref of a name and kind (`:map`, `:set`, `:vector`, `:blob`, `:tree`,
  or `:text`, a map of texts) on the system's ref space: an `IDeref` and
  `IRef`, so `@r` is the merged value of its heads and `add-watch` hears each
  new head (PD-R-07). One handle per name is kept on the system; the
  four-argument form names the ref space, which must be the one
  `:pdt {:space ..}` configured (\"pdt\" by default)."
  ([system name kind]
   (let [kind (->kind kind true)
         handles (:pdt-refs system)
         make (fn []
                (if (= :text kind)
                  ;; PDT 2 phase 7, P2-C-04: the values pass through PText.codec, so the heads are version 2
                  (let [conv (text-conv (replica system))]
                    (->PdtRef (.mapRef (facade system) (str name) object-codec ^Codec (:values conv)) kind
                              (fn [v] (->PersistentPMap v conv nil)) (atom {}) (atom nil)))
                  (->PdtRef (java-ref (facade system) (str name) kind) kind (wrap-for kind) (atom {}) (atom nil))))]
     (if handles
       (or (get @handles [(str name) kind])
           (get (clojure.core/swap! handles (fn [m] (if (get m [(str name) kind]) m (assoc m [(str name) kind] (make)))))
                [(str name) kind]))
       (make))))
  ([system space-name name kind]
   (let [configured (:pdt-space-name system)]
     (when (and configured (not= (str space-name) configured))
       (throw (ex-info (str "the ref space of this system is '" configured "'") {:space space-name})))
     (ref system name kind))))

(defn swap!
  "Applies `f` to the ref's current value (the merged heads, as a Clojure
  collection) with any `args`, writes the new version's nodes, and then a head
  whose base lists the heads read (PD-R-03), carrying the delta inline when it
  fits (PD-X-07); in linear mode the head is taken first (PD-R-04). `f` may
  return the wrapped value it was given, updated, or a plain Clojure
  collection, which is copied into a new version. Returns the value written."
  [^PdtRef r f & args]
  (let [^Ref ref (.-ref r)
        kind (.-kind r)
        wrap (.-wrap r)
        op (reify UnaryOperator
             (apply [_ current]
               (let [wrapped (wrap current)]
                 (unwrap kind (apply f wrapped args) wrapped current))))
        v (wrap (.swap ref op))]
    (reset! ^clojure.lang.Atom (.-last r) v)
    v))

(defn- head->map [^Heads$Head h]
  {:root (.root h) :base (vec (.base h)) :seq (.seq h) :size (.size h) :kind (keyword (.kind h))
   :mode (keyword (.mode h)) :issuer (str (.issuer h)) :issued (.encoded (.issued h))
   :entry-id (some-> (.entryId h) str) :expires-at (.expiresAtMillis h)})

(defn history
  "Up to `n` earlier versions of the ref, newest first, walking base links over
  the versions this peer holds (PD-R-09): maps of :root :base :seq :size :kind
  :mode :issuer :issued :entry-id :expires-at."
  [^PdtRef r n]
  (mapv head->map (.history ^Ref (.-ref r) (int n))))

(defn at
  "Any held version of the ref as a value, by root (PD-R-09)."
  [^PdtRef r root]
  ((.-wrap r) (.at ^Ref (.-ref r) (str root))))

(defn heads
  "The heads a reader sees now, without waiting for a fetch: {:name :heads [..]}."
  [^PdtRef r]
  (let [^Heads h (.heads ^Ref (.-ref r))]
    {:name (.name h) :heads (mapv head->map (.heads h))}))

(defn root
  "The root CID of a wrapped value (or of a `PBlob`)."
  [v]
  (cond
    (instance? PersistentPMap v) (.root ^PMap (.-pm ^PersistentPMap v))
    (instance? PersistentPSet v) (.root ^PSet (.-ps ^PersistentPSet v))
    (instance? PersistentPVector v) (.root ^PVector (.-pv ^PersistentPVector v))
    (instance? PBlob v) (.root ^PBlob v)
    :else (throw (ex-info "not a PDT value" {:value (type v)}))))

(defn- conflict->map [^PConflict c]
  {:name (.name c) :key (some-> (.key c) ->clj) :winner-root (.winnerRoot c) :loser-root (.loserRoot c)
   :reason (.reason c)})

(defn on-conflict
  "Calls `f` with a map of :name :key :winner-root :loser-root :reason for each
  merge decision of §3.6.1 this reader makes on the ref (PD-R-05). Returns the
  subscription; close it to stop."
  ^Subscription [^PdtRef r f]
  (.onConflict ^Ref (.-ref r) (reify Consumer (accept [_ c] (f (conflict->map c))))))

(defn merge-for!
  "Registers the merge function every reader of a name applies when heads of a
  vector or blob ref meet (PD-R-05): `(f base earlier later)` over wrapped
  values, base nil when none, returning the merged value."
  [system name kind f]
  (let [kind (->kind kind)
        wrap (wrap-for kind)]
    (.mergeFor (facade system) (str name)
               (reify ai.badmonkey.agentspaces.pdt.refs.MergeFunction
                 (merge [_ base earlier later]
                   (let [wrapped (wrap earlier)]
                     (unwrap kind (f (some-> base wrap) wrapped (wrap later)) wrapped earlier)))))
    nil))

;; ------------------------------------------------------------------ blobs and trees

(defn blob
  "Writes a file (a `java.io.File`, a `Path`, or a path string) or a byte array
  into the node store as a content-defined-chunked blob (PD-B-01) and returns
  {:root :size :blob PBlob}; put the `PBlob` in a blob ref with `swap!`."
  [system artifact]
  (let [store (.store (facade system))
        ^PBlob b (if (bytes? artifact)
                   (PBlob/of ^bytes artifact store)
                   (let [^Path path (if (instance? Path artifact) artifact (.toPath (io/file artifact)))]
                     (PBlob/of path store)))]
    {:root (.root b) :size (.size b) :blob b}))

(defn- published->map [^TreePublisher$Published p]
  {:root (.root p)
   :entries (into {} (map (fn [[k v]] [k (file-entry->map v)])) (.entries p))
   :written-bytes (.writtenBytes p)
   :changed-files (.changedFiles p)})

(defn publish-tree
  "Walks a directory into the node store as a file tree (PD-B-05): every
  regular file a blob, the tree a map from relative path to file entry.
  Returns {:root :entries {path {:root :size :mode :media-type}} :written-bytes
  :changed-files}; `(swap! tree-ref (constantly (:entries published)))` on a
  `:tree` ref publishes it."
  [system dir]
  (let [publisher (TreePublisher. (.store (facade system)))
        ^Path path (if (instance? Path dir) dir (.toPath (io/file dir)))]
    (published->map (.publish publisher path))))

(defn stats
  "The facade's counters (PD-C-08)."
  [system]
  (let [^Pdt$Stats s (.stats (facade system))]
    {:refs (.refs s) :heads (.heads s) :deltas-inline (.deltasInline s) :deltas-fetched (.deltasFetched s)
     :conflicts (.conflicts s) :bytes-received (.bytesReceived s) :heads-ignored (.headsIgnored s)
     :nodes-dropped (.nodesDropped s) :heads-rewritten (.headsRewritten s) :bases-fetched (.basesFetched s)
     :nodes-held (.nodesHeld s) :bytes-held (.bytesHeld s)}))

;; ------------------------------------------------------------------ content stores (PDT 2 phase 7, P2-C-04)

(defn- need
  "The planner's need of a pin: the copies, and the tier, the price, and the
  stores to avoid when given. The facade reads the size and the sealing from
  the root."
  ^StoreChooser$Need [{:keys [copies tier max-price avoid] :or {copies 1}}]
  (let [^StoreChooser$Need n (StoreChooser$Need/of 0 (int copies))
        ^StoreChooser$Need n (if tier (.tier n (name tier)) n)
        ^StoreChooser$Need n (if max-price (.maxPrice n (long max-price)) n)]
    (if (seq avoid) (.avoid n (set (map core/peer-id avoid))) n)))

(defn store-pin!
  "Asks the group's content stores to hold a root (P2-S-11): a tree root
  this peer holds complete, such as `(root @r)`, or the root of an artifact
  this peer published or fetched. The planner chooses stores from their
  cards, and the facade writes one signed pin that names them; a store
  charges only the member that signed (§3.2.2). Pinning the same root again
  renews the lease (P2-S-04). Options: `:copies` (1), `:lease` (\"30d\"),
  `:tier` (:hot or :cold), `:max-price` (per GiB-month), and
  `:avoid` [peer-id ..]. Returns {:root :kind :lease :copies :stores}, the
  lease in seconds and the stores' peer ids. Throws `IllegalStateException`
  when this peer does not hold the root or no store's card fits; cards
  arrive through discovery, so a caller that pins as its fleet starts may
  find none yet and pins again later."
  ([system root] (store-pin! system root {}))
  ([system root {:keys [lease] :or {lease "30d"} :as opts}]
   (let [^Stores$Pinning p (.storeFor (facade system) (str root) (need opts) (core/duration lease))
         ^StorePin pin (.pin p)]
     {:root (.root pin) :kind (keyword (.kind pin)) :lease (.lease pin) :copies (.copies pin)
      :stores (mapv (fn [^AssetCard c] (.value (.issuer c))) (.chosen p))})))

(defn store-status
  "What the content stores hold of a root, as this peer sees it (P2-S-07):
  {:root :copies :holders [{:store :bytes :until :blind}] :unplaced}.
  `:copies` counts the live receipts of stores this peer has not struck, and
  `:until` is the epoch millisecond a receipt lapses. `:unplaced` is true
  when this peer struck a store that held the root and found no other store
  to place it on, so it wrote no pin (§10.11)."
  [system root]
  (let [^Stores stores (.stores (facade system))
        receipts (.receipts stores (str root))]
    {:root (str root)
     :copies (count receipts)
     :holders (mapv (fn [^StoreReceipt r]
                      {:store (.value (.holder r)) :bytes (.bytes r) :until (.until r) :blind (.blind r)})
                    receipts)
     :unplaced (contains? (.unplaced stores) (str root))}))

(defn challenge!
  "Makes a store prove it holds a root (P2-S-07): random picks of the root go
  to the store (a peer id), which must answer each within the facade's
  challenge deadline (`:pdt {:challenge-deadline \"2s\"}`). Returns a future
  of {:store :root :picks :proven :passed :reason :elapsed-ms}, which
  settles by that deadline whatever the store does; deref it, with a timeout
  of your own if you wish. A member at feature level 1 fails at once. Two
  failures in a row on one root strike the store: its receipts stop counting
  in `store-status`, and this peer pins what it pinned there on another
  store."
  ^CompletableFuture [system root store]
  (.thenApply (.challenge (facade system) (str root) (core/peer-id store))
              (reify Function
                (apply [_ r]
                  (let [^Content$ChallengeResult r r]
                    {:store (.value (.holder r)) :root (.root r) :picks (.picks r) :proven (.proven r)
                     :passed (.passed r) :reason (.reason r) :elapsed-ms (.elapsed r)})))))

;; ------------------------------------------------------------------ building (fleet/start)

(defn- ->path ^Path [p]
  (if (instance? Path p) p (.toPath (io/file (str p)))))

(defn node-store
  "The node store of `:pdt {:store {:kind :pack|:memory :path ..}}`
  (appendix C `store.kind`, `store.path`): a pack store under the path, by
  default `~/.agentspaces/pdt/<group>`, or a memory store. A quota is not
  applied here: the quota store wraps the node store and cannot yet sit under
  the swarm (SRAD §10.3)."
  ^NodeStore [{:keys [kind path] :or {kind :pack}} group-name]
  (let [clock (InstantSource/system)
        ^BlockStore backend (case (keyword kind)
                              :memory (MemoryBlockStore.)
                              :pack (PackNodeStore/open
                                     (->path (or path (str (System/getProperty "user.home") "/.agentspaces/pdt/"
                                                           (.replaceAll ^String (str group-name) "[^A-Za-z0-9._-]" "_")))))
                              (throw (ex-info ":pdt :store :kind is :pack or :memory" {:kind kind})))]
    (NodeStore. backend clock)))

(defn build
  "The `Pdt` facade of a `:pdt` block over the ref space (SRAD §7.2, appendix
  C), built before the content swarm so the swarm takes `(.treeStore pdt)`;
  `attach!` joins the swarm once it exists. Keys: `:store {:kind :path}`,
  `:inline-delta` (bytes, \"32KiB\"), `:load-timeout` (\"10s\"), `:follow`
  (:full or :lazy), `:sealing`, `:retain {:versions 64 :age \"1h\"}`,
  `:head-lease` (\"10m\"), `:refs {name {:mode :multi|:linear}}`,
  `:take {:lease :timeout :retries}`, `:cancel-grace`, `:collect-every`
  (\"1m\", 0 disables), `:collector-visits`, `:challenge-deadline` (\"2s\").
  The identity signs the pins `store-pin!` writes, and the discovery holds
  the content stores' cards (P2-C-04)."
  ^Pdt [{:keys [store inline-delta load-timeout follow sealing retain head-lease refs take cancel-grace
                collect-every collector-visits challenge-deadline]}
        ^Space ref-space group-name ^PeerIdentity identity ^DiscoveryService discovery]
  (let [^Pdt$Builder builder (-> (Pdt/builder ref-space)
                    (.store (if (instance? NodeStore store) store (node-store (or store {}) group-name)))
                    (.identity identity))]
    (when discovery (.discovery builder discovery))
    (when challenge-deadline (.challengeDeadline builder (core/duration challenge-deadline)))
    (when inline-delta (.inlineDelta builder (int (if (number? inline-delta) inline-delta
                                                      (let [[_ n unit] (re-matches #"(?i)\s*(\d+)\s*(B|KiB|MiB|KB|MB)?\s*" (str inline-delta))]
                                                        (when-not n (throw (ex-info "not a byte size" {:value inline-delta})))
                                                        (* (Long/parseLong n) (case (some-> ^String unit .toLowerCase)
                                                                                ("kib" "kb") 1024 ("mib" "mb") 1048576 1)))))))
    (when load-timeout (.loadTimeout builder (core/duration load-timeout)))
    (when follow (.follow builder (name follow)))
    (when sealing (.sealing builder (name sealing)))
    (when retain (.retain builder (int (or (:versions retain) 64)) (core/duration (or (:age retain) "1h"))))
    (when head-lease (.headLease builder (core/duration head-lease)))
    (doseq [[ref-name {:keys [mode]}] refs :when mode]
      (.mode builder (str (name ref-name)) (name mode)))
    (when take (.take builder (core/duration (or (:lease take) "30s")) (core/duration (or (:timeout take) "5s"))
                      (int (or (:retries take) 3))))
    (when cancel-grace (.cancelGrace builder (core/duration cancel-grace)))
    (when collect-every (.collectEvery builder (core/duration collect-every)))
    (when collector-visits (.collectorVisits builder (int collector-visits)))
    (.build ^Pdt$Builder builder)))

(defn attach!
  "Joins the facade to the content swarm built with its tree store, starts it,
  and runs its tick on the group tick. Returns the tick registration, an
  `AutoCloseable`."
  ^java.lang.AutoCloseable [^Pdt pdt ^Content content ^GroupRuntime runtime]
  (.attach pdt content)
  (.start pdt)
  (.onTick runtime (fn [] (.tick pdt))))

(defn close!
  "Closes the facade and its tick registration, and the store's backend when it
  is a pack store."
  [^Pdt pdt ^java.lang.AutoCloseable tick]
  (when tick (try (.close tick) (catch Exception _)))
  (when pdt
    (try (.close pdt) (catch Exception _))
    (let [backend (.backend (.store pdt))]
      (when (instance? java.lang.AutoCloseable backend)
        (try (.close ^java.lang.AutoCloseable backend) (catch Exception _)))))
  nil)
