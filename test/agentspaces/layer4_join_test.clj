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

(ns agentspaces.layer4-join-test
  "`defagent` with a join and a reduce (L3L4-COVERAGE.md §6.2, the phase B
  leftover): a LOCAL join over two part types keyed by a shared field, and a
  LEASED reduce folding elements into an accumulator per key, both over two
  TCP peers; then the explicit `:mode` option, read back from the generated
  interface and exercised by a LEASED join and a `:mode :leased` reduce."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.core :as core]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.agent.annotation SpaceJoin SpaceJoin$Mode SpaceReduce SpaceReduce$Mode)
           (ai.badmonkey.agentspaces.agent.join Joined JoinTicket)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (java.net ServerSocket)))

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

(defn- config [group port seed-port spaces]
  {:bind (str "127.0.0.1:" port)
   :tick "200ms"
   :agents true
   :group {:name group
           :founding (str group "-v1")
           :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
           :membership {:ttl "30s" :probe "2s" :indirect 2}
           :spaces spaces}})

;; ------------------------------------------------------------------ join

(defagent Assembler
  {:description "Joins a task with its finding by topic" :goals ["join"]}
  (join assemble [^Joined j] {:space "work" :key "topic" :result-space "findings"
                              :parts [{:value Fixtures$TaskEntry} {:value Fixtures$FindingEntry}]}
    ^Fixtures$FindingEntry
    (let [task (core/from-entry (.get j Fixtures$TaskEntry))
          finding (core/from-entry (.get j Fixtures$FindingEntry))]
      (Fixtures$FindingEntry. (.key j) (str "joined " (:priority task) " " (:summary finding))))))

(deftest a-defagent-join-fires-once-per-key-when-every-part-is-present
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-join" port-a nil spaces))
        b (fleet/start (config "clj-join" port-b port-a spaces))]
    (try
      (let [bound (agents/bind! a (->Assembler))]
        (is (= "assembler" (:agent (agents/card bound))))
        (is (= 1 (count (:actions (agents/card bound)))))
        ;; One part alone fires nothing.
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "tuple spaces" :priority 3} {:lease "10m"})
        (Thread/sleep 1500)
        (is (empty? (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10})))
        ;; The second part completes the key: the join fires once, into the result space.
        (space/write! (fleet/space b "work") Fixtures$FindingEntry {:topic "tuple spaces" :summary "found"} {:lease "10m"})
        (is (await-truthy 30000 #(seq (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        (is (= ["joined 3 found"]
               (mapv :summary (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        ;; A repeated part for a fired key does not fire it again.
        (space/write! (fleet/space b "work") Fixtures$FindingEntry {:topic "tuple spaces" :summary "again"} {:lease "10m"})
        (Thread/sleep 1500)
        (is (= 1 (count (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ---------------------------------------------------------------- reduce

(defagent Tally
  {:description "Sums priorities per topic" :goals ["reduce"]}
  (reduce fold [^Fixtures$FindingEntry acc ^Fixtures$TaskEntry task]
          {:space "ledger" :key "topic" :lease "2s" :poll-timeout "100ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic task)
                            (str (+ (if acc (parse-long (:summary acc)) 0) (:priority task))))))

(deftest a-defagent-reduce-folds-every-element-into-the-keys-accumulator
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "ledger" :settle "150ms"}]
        a (fleet/start (config "clj-reduce" port-a nil spaces))
        b (fleet/start (config "clj-reduce" port-b port-a spaces))]
    (try
      (let [bound (agents/bind! a (->Tally))]
        (is (= "tally" (:agent (agents/card bound))))
        (doseq [p [1 2 3]]
          (space/write! (fleet/space b "ledger") Fixtures$TaskEntry {:topic "x" :priority p} {:lease "10m"}))
        (space/write! (fleet/space b "ledger") Fixtures$TaskEntry {:topic "y" :priority 10} {:lease "10m"})
        ;; The accumulator per key is the folded sum; every element was consumed.
        (is (await-truthy 30000 #(some (fn [f] (and (= "x" (:topic f)) (= "6" (:summary f))))
                                       (space/read-all (fleet/space b "ledger") Fixtures$FindingEntry {:limit 10}))))
        (is (await-truthy 30000 #(some (fn [f] (and (= "y" (:topic f)) (= "10" (:summary f))))
                                       (space/read-all (fleet/space b "ledger") Fixtures$FindingEntry {:limit 10}))))
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space b "ledger") Fixtures$TaskEntry {:limit 10}))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------- explicit :mode

(defagent LeasedAssembler
  {:description "Joins under a lease" :goals ["join"]}
  (join assemble [^Joined j] {:space "work" :key "topic" :result-space "findings" :mode :leased
                              :parts [{:value Fixtures$TaskEntry} {:value Fixtures$FindingEntry}]}
    ^Fixtures$FindingEntry
    (let [task (core/from-entry (.get j Fixtures$TaskEntry))
          finding (core/from-entry (.get j Fixtures$FindingEntry))]
      (Fixtures$FindingEntry. (.key j) (str "leased " (:priority task) " " (:summary finding))))))

(defagent LeasedTally
  {:description "Sums priorities per topic under a lease" :goals ["reduce"]}
  (reduce fold [^Fixtures$FindingEntry acc ^Fixtures$TaskEntry task]
          {:space "ledger" :key "topic" :lease "2s" :poll-timeout "100ms" :mode :leased}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic task)
                            (str (+ (if acc (parse-long (:summary acc)) 0) (:priority task))))))

(deftest the-mode-option-writes-the-enum-into-the-annotation
  (let [join-mode (fn [^Class spec] (.mode ^SpaceJoin (.getAnnotation (.getMethod spec "assemble" (into-array Class [Joined])) SpaceJoin)))
        reduce-mode (fn [^Class spec] (.mode ^SpaceReduce (.getAnnotation (.getMethod spec "fold" (into-array Class [Fixtures$FindingEntry Fixtures$TaskEntry])) SpaceReduce)))]
    (is (= SpaceJoin$Mode/LOCAL (join-mode AssemblerSpec)) "no :mode leaves the Java default")
    (is (= SpaceJoin$Mode/LEASED (join-mode LeasedAssemblerSpec)))
    (is (= SpaceReduce$Mode/LEASED (reduce-mode TallySpec)) "no :mode leaves the Java default")
    (is (= SpaceReduce$Mode/LEASED (reduce-mode LeasedTallySpec)))
    (testing "a mode the verb does not take is refused at expansion"
      (let [message (try (eval '(agentspaces.agents/defagent BadReduce {}
                                  (reduce fold [^ai.badmonkey.agentspaces.test.Fixtures$FindingEntry a
                                                ^ai.badmonkey.agentspaces.test.Fixtures$TaskEntry t]
                                          {:space "ledger" :key "topic" :mode :local} nil)))
                         "expanded"
                         (catch Throwable e (.getMessage (or (.getCause e) e))))]
        (is (re-find #":mode :local is not valid on reduce; one of \(:leased :ordered\)" message))))))

(deftest a-leased-join-fires-once-per-key-over-two-peers
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-ljoin" port-a nil spaces))
        b (fleet/start (config "clj-ljoin" port-b port-a spaces))]
    (try
      (let [bound (agents/bind! a (->LeasedAssembler))]
        (is (= "leasedassembler" (:agent (agents/card bound))))
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "leases" :priority 5} {:lease "10m"})
        (Thread/sleep 1500)
        (is (empty? (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10})))
        (space/write! (fleet/space b "work") Fixtures$FindingEntry {:topic "leases" :summary "found"} {:lease "10m"})
        (is (await-truthy 30000 #(seq (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        (is (= ["leased 5 found"]
               (mapv :summary (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        ;; A join reads its parts, so they stay; what the lease covered was the
        ;; key's ticket, completed once the key fired.
        (is (= 1 (count (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10}))))
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space a "work") JoinTicket {:limit 10}))))
        (space/write! (fleet/space b "work") Fixtures$FindingEntry {:topic "leases" :summary "again"} {:lease "10m"})
        (Thread/sleep 1500)
        (is (= 1 (count (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest an-explicitly-leased-reduce-folds-like-the-default
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "ledger" :settle "150ms"}]
        a (fleet/start (config "clj-lreduce" port-a nil spaces))
        b (fleet/start (config "clj-lreduce" port-b port-a spaces))]
    (try
      (let [bound (agents/bind! a (->LeasedTally))]
        (is (= "leasedtally" (:agent (agents/card bound))))
        (doseq [p [4 5]]
          (space/write! (fleet/space b "ledger") Fixtures$TaskEntry {:topic "z" :priority p} {:lease "10m"}))
        (is (await-truthy 30000 #(some (fn [f] (and (= "z" (:topic f)) (= "9" (:summary f))))
                                       (space/read-all (fleet/space b "ledger") Fixtures$FindingEntry {:limit 10}))))
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space b "ledger") Fixtures$TaskEntry {:limit 10}))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))
