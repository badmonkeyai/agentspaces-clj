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

(ns agentspaces.layer4-verbs-test
  "The `defagent` verbs the other suites leave alone, each over two TCP
  peers: `propose` (a cue opens a vote once per key), `on-estimate` (a
  settled aggregate writes a finding), `ordered-take` (each entry once
  through the log across two takers), and a `take` narrowed by `:tags` and
  `:where`."
  (:require [clojure.test :refer [deftest is]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.aggregate :as aggregate]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space]
            [agentspaces.vote :as vote])
  (:import (ai.badmonkey.agentspaces.capabilities.aggregate PushSumAggregate$Estimate)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
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

;; ----------------------------------------------------------------- propose

(defagent Asker
  {:description "Opens a vote per cue topic" :goals ["ask"]}
  (propose ask [^Fixtures$TaskEntry cue] {:space "work" :vote "votes" :prefix "ask-" :key ["topic"]
                                          :options ["yes" "no"] :quorum 2 :lease "1h"}
    ^String
    (when (pos? (:priority cue)) (str "ship " (:topic cue) "?"))))

(deftest a-propose-verb-opens-one-vote-per-cue-key
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "votes" :settle "150ms"}]
        caps {:capabilities {:vote {:space "votes"}}}
        a (fleet/start (config "clj-propose" port-a nil spaces caps))
        b (fleet/start (config "clj-propose" port-b port-a spaces caps))]
    (try
      (let [bound (agents/bind! a (->Asker))]
        (is (= "asker" (:agent (agents/card bound))))
        (is (= ["propose"] (map :kind (:actions (agents/card bound)))))
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "release" :priority 1} {:lease "10m"})
        (is (await-truthy 30000 #(vote/proposal a "ask-release")))
        (is (= {:id "ask-release" :question "ship release?" :options ["yes" "no"] :quorum 2}
               (vote/proposal a "ask-release")))
        (is (await-truthy 30000 #(vote/proposal b "ask-release")))
        ;; A second cue with the same key asks nothing more; a new key opens a new vote.
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "release" :priority 2} {:lease "10m"})
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "pause" :priority 0} {:lease "10m"})
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "rollback" :priority 3} {:lease "10m"})
        (is (await-truthy 30000 #(vote/proposal a "ask-rollback")))
        (Thread/sleep 1500)
        (is (nil? (vote/proposal a "ask-pause")) "a nil question asks nothing")
        (is (= "ship release?" (:question (vote/proposal a "ask-release"))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------- on-estimate

(defagent Gauge
  {:description "Records a settled fleet estimate" :goals ["gauge"]}
  (on-estimate settled [^PushSumAggregate$Estimate e] {:prefix "load" :settle-ticks 3 :tolerance 0.01
                                                       :result-space "findings"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:epochId e) (str (Math/round ^double (:value e))))))

(deftest an-on-estimate-verb-fires-once-the-aggregate-settles
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "findings" :settle "150ms"}]
        caps {:capabilities {:aggregate true}}
        a (fleet/start (config "clj-estimate" port-a nil spaces caps))
        b (fleet/start (config "clj-estimate" port-b port-a spaces caps))]
    (try
      (let [bound (agents/bind! a (->Gauge))]
        (is (= ["on-estimate"] (map :kind (:actions (agents/card bound)))))
        (Thread/sleep 1500)
        (aggregate/start! a "load" 10.0)
        (aggregate/start! b "load" 30.0)
        (is (await-truthy 30000 #(seq (findings b))))
        (let [f (first (findings b))]
          (is (= "load" (:topic f)))
          (is (= "20" (:summary f))))
        (Thread/sleep 1000)
        (is (= 1 (count (findings b))) "once per epoch")
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------ ordered-take

(defagent Handler
  {:description "Handles orders through the log" :goals ["handle"]}
  (ordered-take handle [^Fixtures$TaskEntry t] {:space "orders" :poll-timeout "500ms" :result-space "findings"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "handled")))

(deftest an-ordered-take-verb-handles-each-entry-once-across-two-takers
  (let [id-a (PeerIdentity/generate) id-b (PeerIdentity/generate)
        members [(.peerId id-a) (.peerId id-b)]
        port-a (free-port) port-b (free-port)
        spaces [{:name "orders" :settle "150ms"} {:name "findings" :settle "150ms"}]
        caps (fn [seed] {:capabilities {:ordered {:space "orders" :members members :seed seed}}})
        a (fleet/start (assoc (config "clj-otake" port-a nil spaces (caps 1)) :identity id-a))
        b (fleet/start (assoc (config "clj-otake" port-b port-a spaces (caps 2)) :identity id-b))]
    (try
      (let [bound-a (agents/bind! a (->Handler))
            bound-b (agents/bind! b (->Handler))]
        (is (= ["ordered-take"] (map :kind (:actions (agents/card bound-a)))))
        (Thread/sleep 2500)
        (doseq [i (range 4)]
          (space/write! (fleet/space a "orders") Fixtures$TaskEntry {:topic (str "order-" i) :priority i} {:lease "10m"}))
        (is (await-truthy 60000 #(= 4 (count (findings b)))))
        (is (= #{"order-0" "order-1" "order-2" "order-3"} (set (map :topic (findings b)))))
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space b "orders") Fixtures$TaskEntry {:limit 10}))))
        (Thread/sleep 1500)
        (is (= 4 (count (findings b))) "each entry exactly once across both takers")
        (agents/unbind! bound-a)
        (agents/unbind! bound-b))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ---------------------------------------------------- take with tags, where

(defagent EuUrgent
  {:description "Takes urgent EU tasks only" :goals ["eu"]}
  (take research [^Fixtures$TaskEntry t] {:space "work" :result-space "findings" :poll-timeout "200ms"
                                          :tags ["region=eu"] :where ["priority=1"]}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "eu urgent")))

(deftest a-take-narrowed-by-tags-and-where-leaves-the-rest-alone
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-narrow" port-a nil spaces {}))
        b (fleet/start (config "clj-narrow" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->EuUrgent))
            work (fleet/space b "work")]
        (space/write! work Fixtures$TaskEntry {:topic "us-urgent" :priority 1} {:lease "10m" :tags {:region "us"}})
        (space/write! work Fixtures$TaskEntry {:topic "eu-later" :priority 2} {:lease "10m" :tags {:region "eu"}})
        (space/write! work Fixtures$TaskEntry {:topic "untagged" :priority 1} {:lease "10m"})
        (space/write! work Fixtures$TaskEntry {:topic "eu-urgent" :priority 1} {:lease "10m" :tags {:region "eu"}})
        (is (await-truthy 30000 #(seq (findings b))))
        (is (= ["eu-urgent"] (map :topic (findings b))))
        (Thread/sleep 1500)
        (is (= 1 (count (findings b))))
        (is (= #{"us-urgent" "eu-later" "untagged"} (set (map :topic (space/read-all work Fixtures$TaskEntry {:limit 10})))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))
