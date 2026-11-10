;; Copyright 2026 Bad Monkey, Inc.
;; Licensed under the Apache License, Version 2.0.

(ns agentspaces.pdt-test
  "Persistent data trees over two TCP peers with `:pdt` (PD-C-01): a swap on
  one peer derefs on the other through the Clojure collection interfaces, a
  transient over the Java editor, `add-watch` on the other peer, a 100-key map
  with equal roots across peers, the set and vector kinds, and splicing a
  vector through the RRB path (PDT 2 phase 6, P2-R-05), values too large to
  ride inline on their head, which cross by the tree fetch (PD-X-01), and a
  `:text` ref whose texts both peers edit and merge (PDT 2 phase 7, P2-C-04)."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.fleet :as fleet]
            [agentspaces.pdt :as pdt])
  (:import (ai.badmonkey.agentspaces.agent.pdt PRef)
           (ai.badmonkey.agentspaces.api.space Space Template)
           (ai.badmonkey.agentspaces.pdt.values PText)
           (clojure.lang ExceptionInfo)
           (java.net ServerSocket)
           (java.nio.file Files)))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- await-truthy [timeout-ms f]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (or (f)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 100)
            (recur))))))

(defn- temp-dir [label]
  (str (Files/createTempDirectory label (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- config [group port seed-port]
  {:bind (str "127.0.0.1:" port)
   :tick "100ms"
   :agents true
   :content {:escalate-above "256KiB" :piece-size "64KiB"
             :store {:backends [:file] :path (temp-dir "clj-pdt-content")}}
   :pdt {:space "pdt" :store {:kind :memory} :load-timeout "20s" :collect-every "0ms"}
   :group {:name group
           :founding (str group "-v1")
           :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
           :membership-tuning {:ttl "30s" :probe "2s" :indirect 2}
           :spaces [{:name "work" :settle "150ms"}]}})

(deftest a-swap-on-one-peer-derefs-on-the-other
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt" port-a nil))
        b (fleet/start (config "clj-pdt" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (is (some? (:pdt a)) "the facade is on the system")
      (is (= "pdt" (:pdt-space-name a)))
      (let [facts-a (pdt/ref a "facts" :map)
            facts-b (pdt/ref b "pdt" "facts" :map)
            claim {:text "water boils at 100 C" :score 3 :tags [:physics :water]}]
        (is (identical? facts-a (pdt/ref a "facts" :map)) "one handle per name")
        (is (thrown? clojure.lang.ExceptionInfo (pdt/ref a "other" "facts" :map)))
        (testing "a swap with assoc-in, read back with get-in on the writer"
          (let [written (pdt/swap! facts-a assoc-in [:claims "c1"] claim)]
            (is (= claim (get-in written [:claims "c1"])))
            (is (= claim (get-in @facts-a [:claims "c1"])))
            (is (= 1 (count @facts-a)))
            (is (contains? @facts-a :claims))))
        (testing "the other peer derefs the same value through get-in"
          (is (await-truthy 20000 #(= claim (get-in @facts-b [:claims "c1"]))))
          (is (= (pdt/root @facts-a) (pdt/root @facts-b)))
          (is (= @facts-a @facts-b))
          (is (= :physics (get-in @facts-b [:claims "c1" :tags 0])) "keywords round trip inside values"))
        (testing "update-in and dissoc on the other peer are seen back on the writer"
          (pdt/swap! facts-b update-in [:claims "c1" :score] inc)
          (pdt/swap! facts-b assoc :note "from b")
          (is (await-truthy 20000 #(= 4 (get-in @facts-a [:claims "c1" :score]))))
          (is (await-truthy 20000 #(= "from b" (:note @facts-a))))
          (pdt/swap! facts-a assoc :note "from a")
          (is (await-truthy 20000 #(= "from a" (:note @facts-b))))
          ;; a swap whose result equals an ancestor version's content shares its root (content
          ;; addressing); the revert stays the head by its seq and both peers deref it
          (let [before (pdt/root @facts-a)]
            (pdt/swap! facts-a assoc :by "a")
            (is (await-truthy 20000 #(= "a" (:by @facts-b))))
            (is (= before (pdt/root (pdt/swap! facts-a dissoc :by))) "the revert has the ancestor's root")
            (is (= before (pdt/root @facts-a)))
            (is (await-truthy 20000 #(and (not (contains? @facts-b :by)) (= before (pdt/root @facts-b)))))
            (is (= [before] (mapv :root (:heads (pdt/heads facts-b)))))))
        (testing "strings that start with a colon or a backslash round trip as strings"
          (pdt/swap! facts-a assoc :colon ":not-a-keyword" :slash "\\x" :lone ":")
          (is (= ":not-a-keyword" (:colon @facts-a)))
          (is (await-truthy 20000 #(= ":not-a-keyword" (:colon @facts-b))))
          (is (= "\\x" (:slash @facts-b)))
          (is (= ":" (:lone @facts-b)))
          (is (= :physics (get-in @facts-b [:claims "c1" :tags 0])) "a keyword still reads back as a keyword"))
        (testing "heads, history (the earlier versions, newest first), and at"
          (let [{:keys [name heads]} (pdt/heads facts-a)
                earlier (pdt/history facts-a 10)]
            (is (= "facts" name))
            (is (= [(pdt/root @facts-a)] (mapv :root heads)) "one head, the version just written")
            (is (<= 2 (count earlier)))
            (is (not-any? #{(pdt/root @facts-a)} (map :root earlier)))
            (is (= [:map] (distinct (map :kind earlier))))
            (is (contains? (pdt/at facts-a (:root (first earlier))) :claims) "an earlier version reads back by root")))
        (testing "the facade's counters"
          (let [s (pdt/stats a)]
            (is (= 1 (:refs s)))
            (is (pos? (:nodes-held s))))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest transients-watches-and-a-hundred-keys
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt-big" port-a nil))
        b (fleet/start (config "clj-pdt-big" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (let [facts-a (pdt/ref a "facts" :map)
            facts-b (pdt/ref b "facts" :map)
            fired (atom [])]
        (add-watch facts-b :test (fn [k r old new] (swap! fired conj {:key k :ref r :old old :new new})))
        (testing "a transient over the editor: assoc!, conj!, persistent!"
          (let [m @facts-a
                t (transient m)
                t (assoc! t :k 1)
                t (conj! t ["z" [1 2]])
                p (persistent! t)]
            (is (= 2 (count p)))
            (is (= 1 (:k p)))
            (is (= [1 2] (get p "z")))
            (is (= 0 (count m)) "the persistent value is unchanged")
            (is (= (pdt/root p) (pdt/root (-> m (assoc :k 1) (assoc "z" [1 2])))) "the same root as the path copies")))
        (testing "a 100-key map written through into (a transient) round trips with equal roots"
          (let [written (pdt/swap! facts-a (fn [m] (into m (map (fn [i] [(str "k" i) {:n i :name (str "key " i)}])) (range 100))))]
            (is (= 100 (count written)))
            (is (await-truthy 30000 #(= 100 (count @facts-b))))
            (is (= (pdt/root written) (pdt/root @facts-b)))
            (is (= {:n 42 :name "key 42"} (get @facts-b "k42")))
            (is (= (into {} written) (into {} @facts-b)))
            (is (= (hash @facts-a) (hash @facts-b)))
            ;; the equality and hash contract against plain collections: one key in a set or a map
            (is (= (hash @facts-b) (hash (into {} @facts-b))))
            (is (= (.hashCode @facts-b) (.hashCode (into {} @facts-b))))
            (is (contains? #{(into {} @facts-b)} @facts-b))
            (is (= 1 (count (hash-set @facts-b (into {} @facts-b)))))
            (is (= 1 (count (hash-map @facts-b 1 (into {} @facts-b) 2))))))
        (testing "add-watch fires on the other peer with the new value"
          (is (await-truthy 20000 #(seq @fired)))
          (let [{:keys [key ref new]} (last @fired)]
            (is (= :test key))
            (is (identical? facts-b ref))
            (is (= 100 (count new))))
          (is (= {:test true} (update-vals (.getWatches facts-b) fn?)))
          (remove-watch facts-b :test)
          (is (= {} (.getWatches facts-b))))
        (testing "a swap that returns a plain map is copied into a version"
          (pdt/swap! facts-a (constantly {:only "this"}))
          (is (await-truthy 20000 #(= {:only "this"} (into {} @facts-b))))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest sets-and-vectors-cross-the-wire
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt-kinds" port-a nil))
        b (fleet/start (config "clj-pdt-kinds" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (let [tags-a (pdt/ref a "tags" :set) tags-b (pdt/ref b "tags" :set)
            log-a (pdt/ref a "log" :vector) log-b (pdt/ref b "log" :vector)]
        (testing "a set"
          (pdt/swap! tags-a conj :physics)
          (pdt/swap! tags-a into ["water" 7])
          (is (contains? @tags-a :physics))
          (is (= #{:physics "water" 7} @tags-a))
          (is (await-truthy 20000 #(= 3 (count @tags-b))))
          (is (= @tags-a @tags-b))
          (is ((set @tags-b) "water"))
          (is (= (hash @tags-b) (hash #{:physics "water" 7})))
          (is (= (.hashCode @tags-b) (.hashCode #{:physics "water" 7})))
          (is (contains? #{#{:physics "water" 7}} @tags-b)))
        (testing "a vector"
          (pdt/swap! log-a conj {:step 1})
          (pdt/swap! log-a conj {:step 2})
          (pdt/swap! log-a (fn [v] (persistent! (conj! (transient v) {:step 3}))))
          (is (= [{:step 1} {:step 2} {:step 3}] @log-a))
          (is (= (hash @log-a) (hash [{:step 1} {:step 2} {:step 3}])))
          (is (= (.hashCode @log-a) (.hashCode [{:step 1} {:step 2} {:step 3}])))
          (is (contains? #{[{:step 1} {:step 2} {:step 3}]} @log-a))
          (is (await-truthy 20000 #(= 3 (count @log-b))))
          (is (= {:step 2} (nth @log-b 1)))
          (is (= {:step 3} (peek @log-b)))
          (is (= 2 (count (pop @log-b))))
          (is (= (pdt/root @log-a) (pdt/root @log-b)))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest splicing-a-vector-copies-its-path-and-crosses-the-wire
  ;; PDT 2 phase 6, P2-R-05: subvec, catvec, splice, and insert-at over the RRB path. The vector stays
  ;; small enough that every version rides inline on its head.
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt-splice" port-a nil))
        b (fleet/start (config "clj-pdt-splice" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (let [lines-a (pdt/ref a "lines" :vector) lines-b (pdt/ref b "lines" :vector)
            plain (mapv #(str "line " %) (range 300))]
        (pdt/swap! lines-a into plain)
        (is (not (pdt/relaxed? @lines-a)))
        (testing "an insertion in the middle is relaxed and every element reads"
          (pdt/swap! lines-a pdt/insert-at 150 :inserted)
          (is (pdt/relaxed? @lines-a))
          (is (= (into (conj (subvec plain 0 150) :inserted) (subvec plain 150)) @lines-a))
          (is (await-truthy 20000 #(= 301 (count @lines-b))))
          (is (= :inserted (nth @lines-b 150)))
          (is (= @lines-a @lines-b))
          (is (= @lines-b (pdt/packed @lines-b)) "= compares elements whatever the shape")
          (is (not (pdt/relaxed? (pdt/packed @lines-b)))))
        (testing "subvec, catvec, and splice return PDT vectors"
          (let [v @lines-a
                cut (pdt/catvec (pdt/subvec v 0 100) (pdt/subvec v 200))
                spliced (pdt/splice v 5 2 [:p :q :r])]
            (is (instance? agentspaces.pdt.PersistentPVector cut))
            (is (= (into (subvec (vec v) 0 100) (subvec (vec v) 200)) cut))
            (is (= (into (into (subvec (vec v) 0 5) [:p :q :r]) (subvec (vec v) 7)) spliced))
            (is (= [:a :b] (pdt/splice [:a :x :b] 1 1 [])) "a plain vector splices as clojure.core would")))
        (testing "a swap that returns a clojure.core/subvec takes the slice"
          (pdt/swap! lines-a (fn [v] (into (subvec v 0 10) [:x :y])))
          (is (= (into (subvec plain 0 10) [:x :y]) @lines-a))
          (is (await-truthy 20000 #(= 12 (count @lines-b))))
          (is (= :y (peek @lines-b)))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest values-too-large-to-ride-inline-cross-by-the-tree-fetch
  ;; A value past :inline-delta (32 KiB) travels as a tree the other peer fetches. The fetch goes only to a
  ;; member whose content-swarm advertisement says trees = 1 (PD-X-01), read from the group's discovery, so
  ;; the swarm must be built with it; without it every fetch waited out :load-timeout (2026-11-09).
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt-large" port-a nil))
        b (fleet/start (config "clj-pdt-large" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (testing "a 3,000-element vector, then a relaxed insertion in its middle"
        (let [lines-a (pdt/ref a "lines" :vector) lines-b (pdt/ref b "lines" :vector)
              plain (mapv #(str "line " %) (range 3000))]
          (pdt/swap! lines-a into plain)
          (is (await-truthy 30000 #(= 3000 (count @lines-b))))
          (is (= plain @lines-b))
          (pdt/swap! lines-a pdt/insert-at 1500 :inserted)
          (is (await-truthy 30000 #(= 3001 (count @lines-b))))
          (is (= :inserted (nth @lines-b 1500)))
          (is (= @lines-a @lines-b))))
      (testing "a 3,000-key map"
        (let [table-a (pdt/ref a "table" :map) table-b (pdt/ref b "table" :map)
              entries (into {} (map (fn [i] [(str "key-" i) (str "value " i)])) (range 3000))]
          (pdt/swap! table-a into entries)
          (is (await-truthy 30000 #(= 3000 (count @table-b))))
          (is (= entries (into {} @table-b)))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest texts-on-a-text-ref-merge-the-edits-of-both-peers
  ;; PDT 2 phase 7, P2-C-04 (P2-T-02, P2-T-03): a :text ref is a map whose values are texts. Each peer
  ;; edits them under its own replica with pdt/insert and pdt/delete, and two edits that meet merge by
  ;; the union of their characters, whichever order the heads arrive in.
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt-text" port-a nil))
        b (fleet/start (config "clj-pdt-text" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (let [notes-a (pdt/ref a "notes" :text) notes-b (pdt/ref b "notes" :text)
            text-of (fn [r] (some-> (:doc @r) str))]
        (testing "each peer writes under the replica of its agent"
          (is (= (PText/replicaOf (.agent ^ai.badmonkey.agentspaces.identity.PeerIdentity (:identity a) "clj"))
                 (.replica ^PText (pdt/text a))))
          (is (not= (.replica ^PText (pdt/text a)) (.replica ^PText (pdt/text b))))
          (is (= "#pdt/text \"hi\"" (pr-str (pdt/text a "hi")))))
        (testing "a text written on one peer reads on the other"
          (pdt/swap! notes-a assoc :doc (pdt/text a "ac"))
          (is (= "ac" (text-of notes-a)))
          (is (await-truthy 20000 #(= "ac" (text-of notes-b))))
          (is (= #{2} (set (map #(.v ^PRef %) (.readAll ^Space (:pdt-space b) (Template/of PRef) 64))))
              "a value that holds a text writes version 2 heads (rule 3 of 6.10.3)"))
        (testing "each peer edits the text the other wrote"
          (pdt/swap! notes-b update :doc pdt/insert 1 "b")
          (is (await-truthy 20000 #(= "abc" (text-of notes-a))))
          (pdt/swap! notes-a update :doc pdt/delete 0 1)
          (is (await-truthy 20000 #(= "bc" (text-of notes-b)))))
        (testing "edits made at once on both peers meet in their union"
          (let [on-a (future (pdt/swap! notes-a update :doc pdt/insert 0 "X"))]
            (pdt/swap! notes-b update :doc pdt/insert 2 "Y")
            @on-a)
          (is (await-truthy 20000 #(and (= "XbcY" (text-of notes-a)) (= "XbcY" (text-of notes-b))))
              (str (text-of notes-a) " and " (text-of notes-b))))
        (testing "a :text ref holds texts only"
          (is (thrown? ExceptionInfo (pdt/swap! notes-a assoc :plain "not a text")))))
      (finally (fleet/stop! b) (fleet/stop! a)))))
