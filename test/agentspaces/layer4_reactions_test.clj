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

(ns agentspaces.layer4-reactions-test
  "The reacting verbs and returns of `defagent`, each over two TCP peers:
  `notify` (once per written entry, the result landing in `:result-space`,
  the cue left in place), `bid` (an AUCTION space where the cheaper bid wins
  every take), a `contribution` return feeding the aggregate, and the
  `:expired` event `space/notify!` reports when a short write lease lapses
  under the fleet's clock."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.aggregate :as aggregate]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.agent.capability Contribution)
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

(defn- config [group port seed-port spaces extra]
  (merge {:bind (str "127.0.0.1:" port)
          :tick "200ms"
          :agents true
          :group {:name group
                  :founding (str group "-v1")
                  :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
                  :membership {:ttl "30s" :probe "2s" :indirect 2}
                  :spaces spaces}}
         extra))

(defn- findings [system]
  (space/read-all (fleet/space system "findings") Fixtures$FindingEntry {:limit 10}))

;; ------------------------------------------------------------------ notify

(defagent Auditor
  {:description "Reacts to every task written" :goals ["audit"]}
  (notify audit [^Fixtures$TaskEntry t] {:space "work" :result-space "findings"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) (str "seen " (:priority t)))))

(deftest a-notify-verb-reacts-once-per-written-entry-and-leaves-the-cue-in-place
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-notify" port-a nil spaces {}))
        b (fleet/start (config "clj-notify" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->Auditor))]
        (is (= "auditor" (:agent (agents/card bound))))
        (is (= ["notify"] (map :kind (:actions (agents/card bound)))))
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "first" :priority 1} {:lease "10m"})
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "second" :priority 2} {:lease "10m"})
        (is (await-truthy 30000 #(= 2 (count (findings b)))))
        (is (= {"first" "seen 1" "second" "seen 2"}
               (into {} (map (juxt :topic :summary)) (findings b))))
        (testing "a reaction reads its cue; the tasks stay for a taker"
          (is (= 2 (count (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10}))))
          (is (= 2 (count (space/read-all (fleet/space a "work") Fixtures$TaskEntry {:limit 10})))))
        (Thread/sleep 1500)
        (is (= 2 (count (findings b))) "once per written entry")
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; --------------------------------------------------------------------- bid

(defagent CheapBidder
  {:description "Bids low and takes what it wins" :goals ["bid"]}
  (bid price [^Fixtures$TaskEntry t] {:space "work"} ^double (identity 1.0))
  (take work [^Fixtures$TaskEntry t] {:space "work" :result-space "findings" :poll-timeout "200ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "cheap")))

(defagent DearBidder
  {:description "Bids high and takes what it wins" :goals ["bid"]}
  (bid price [^Fixtures$TaskEntry t] {:space "work"} ^double (identity 100.0))
  (take work [^Fixtures$TaskEntry t] {:space "work" :result-space "findings" :poll-timeout "200ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "dear")))

(deftest a-bid-verb-prices-an-auction-space-and-the-cheaper-bid-wins
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :strategy :auction :settle "500ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-bidverb" port-a nil spaces {}))
        b (fleet/start (config "clj-bidverb" port-b port-a spaces {}))]
    (try
      (let [cheap (agents/bind! a (->CheapBidder))
            dear (agents/bind! b (->DearBidder))]
        (is (= "double" (str (.getReturnType (.getMethod CheapBidderSpec "price" (into-array Class [Fixtures$TaskEntry])))))
            "a ^double hint declares the primitive return @BidFunction requires")
        (is (= ["take"] (map :kind (:actions (agents/card cheap)))) "a bid function is no card action")
        (Thread/sleep 1500)
        ;; One lot at a time: a bidder busy completing a lot bids on no other, and
        ;; an auction judges only the claims inside its settle window, so a lot
        ;; written while the cheap bidder works would go to the dear bidder alone.
        (doseq [i (range 4)]
          (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic (str "lot-" i) :priority i} {:lease "10m"})
          (is (await-truthy 30000 #(= (inc i) (count (findings b))))))
        (is (= #{"lot-0" "lot-1" "lot-2" "lot-3"} (set (map :topic (findings b)))))
        (is (every? #(= "cheap" (:summary %)) (findings b)) "the lowest bid takes every lot")
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10}))))
        (Thread/sleep 1500)
        (is (= 4 (count (findings b))) "each lot taken once")
        (agents/unbind! dear)
        (agents/unbind! cheap))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------ contribution

(defagent Sensor
  {:description "Feels each reading into the fleet aggregate" :goals ["sense"]}
  (notify feel [^Fixtures$TaskEntry r] {:space "readings"} ^Contribution
    (agents/contribution (:topic r) (:priority r))))

(deftest contribution-builds-and-converts
  (let [^Contribution c (agents/contribution :backlog 40)]
    (is (= "backlog" (.epochId c)))
    (is (= 40.0 (.value c)))
    (is (identical? c (agents/convert-out Contribution [] c)) "an instance passes through")
    (is (identical? c (agents/convert-out Object [] c)) "under any hint")
    (let [^Contribution m (agents/convert-out Contribution [] {:epoch "load" :value 2})]
      (is (= ["load" 2.0] [(.epochId m) (.value m)])) "a map under the hint converts")))

(deftest a-contribution-return-feeds-the-aggregate-instead-of-writing-an-entry
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "readings" :settle "150ms"}]
        caps {:capabilities {:aggregate true}}
        a (fleet/start (config "clj-contrib" port-a nil spaces caps))
        b (fleet/start (config "clj-contrib" port-b port-a spaces caps))]
    (try
      (let [bound (agents/bind! a (->Sensor))]
        (is (= ["notify"] (map :kind (:actions (agents/card bound)))))
        (Thread/sleep 1500)
        (space/write! (fleet/space b "readings") Fixtures$TaskEntry {:topic "backlog" :priority 40} {:lease "5m"})
        (aggregate/start! b "backlog" 20.0)
        (let [settled (aggregate/await-settled b "backlog" {:ticks 3 :tolerance 0.01 :timeout "30s"})]
          (is (some? settled))
          (is (< 29.0 settled 31.0) "the fleet mean of a's 40 and b's 20"))
        (is (< 29.0 (aggregate/estimate a "backlog") 31.0))
        (is (= 1 (count (space/read-all (fleet/space b "readings") Fixtures$TaskEntry {:limit 10})))
            "the reading stays; a contribution writes nothing")
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ----------------------------------------------------------- :expired events

(deftest notify-reports-an-entry-whose-write-lease-lapses-under-the-fleet
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"}]
        a (fleet/start (config "clj-expired" port-a nil spaces {}))
        b (fleet/start (config "clj-expired" port-b port-a spaces {}))]
    (try
      (Thread/sleep 1500)
      (let [events (atom [])
            subscription (space/notify! (fleet/space b "work") Fixtures$TaskEntry
                                        (fn [event] (swap! events conj event)))]
        (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic "brief" :priority 1} {:lease "1s"})
        (is (await-truthy 10000 (fn [] (some (comp #{:written} :kind) @events))) "the write replicates")
        (is (await-truthy 20000 (fn [] (some (comp #{:expired} :kind) @events))) "the lease lapses on the replica's sweep")
        (let [expired (first (filter (comp #{:expired} :kind) @events))]
          (is (= {:topic "brief" :priority 1} (:entry expired)))
          (is (= (:entry-id (first @events)) (:entry-id expired)) "the same entry that was written"))
        (is (= [:written :expired] (map :kind @events)) "one event per transition")
        (is (empty? (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10})))
        (space/unsubscribe! subscription))
      (finally (fleet/stop! b) (fleet/stop! a)))))
