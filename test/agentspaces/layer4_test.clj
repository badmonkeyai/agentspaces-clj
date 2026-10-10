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


(ns agentspaces.layer4-test
  "Layers 3 and 4 from Clojure (L3L4-COVERAGE.md §6.2): a `defagent` binds
  through the Java binder and takes, votes, and reacts over a two-peer
  loopback fleet; the vote, aggregate, ordered, semantic, and remote
  namespaces work the capabilities the `:capabilities` block registers; and
  `:tags` templates and `:bid-fn` do what the Java annotations do."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.aggregate :as aggregate]
            [agentspaces.core :as core]
            [agentspaces.fleet :as fleet]
            [agentspaces.ordered :as ordered]
            [agentspaces.remote :as remote]
            [agentspaces.semantic :as semantic]
            [agentspaces.space :as space]
            [agentspaces.vote :as vote])
  (:import (ai.badmonkey.agentspaces.capabilities.vote VoteCapability$Decision VoteCapability$Proposal)
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

;; ------------------------------------------------------------------ agents

(defagent Researcher
  {:description "Researches topics" :goals ["research"]}
  (take research [^Fixtures$TaskEntry task] {:space "work" :lease "10m" :poll-timeout "200ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic task) (str "clj researched " (:topic task)))))

(deftest a-defagent-takes-on-one-peer-what-another-wrote
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-l4" port-a nil spaces {}))
        b (fleet/start (config "clj-l4" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->Researcher))]
        (is (= "researcher" (:agent (agents/card bound))))
        (is (= ["research"] (:goals (agents/card bound))))
        (is (= 1 (count (:actions (agents/card bound)))))
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "tuple spaces" :priority 3} {:lease "10m"})
        ;; The result completes the take in the take space, where a remote caller awaits it.
        (is (await-truthy 30000 #(seq (space/read-all (fleet/space b "work") Fixtures$FindingEntry {:limit 10}))))
        (is (= "clj researched tuple spaces"
               (:summary (first (space/read-all (fleet/space b "work") Fixtures$FindingEntry {:limit 10})))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; -------------------------------------------------------------- vote

(defagent Panelist
  {:description "Votes on proposals" :goals ["decide"]}
  (ballot judge [^VoteCapability$Proposal p] {:space "votes"} ^String
    (if (.contains ^String (:question p) "ship") "yes" nil))
  (on-decision record [^VoteCapability$Decision d] {:space "votes" :result-space "findings"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:id d) (str "decided " (:winner d)))))

(deftest a-defagent-votes-and-the-vote-namespace-proposes-and-decides
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "votes" :settle "150ms"} {:name "findings" :settle "150ms"}]
        caps {:capabilities {:vote {:space "votes"}}}
        a (fleet/start (config "clj-vote" port-a nil spaces caps))
        b (fleet/start (config "clj-vote" port-b port-a spaces caps))]
    (try
      (let [bound-a (agents/bind! a (->Panelist))
            bound-b (agents/bind! b (->Panelist))]
        (vote/propose! a {:id "release" :question "ship 1.0?" :options ["yes" "no"] :quorum 2})
        (is (= {:id "release" :question "ship 1.0?" :options ["yes" "no"] :quorum 2} (vote/proposal a "release")))
        (is (await-truthy 30000 #(vote/decision b "release")))
        (is (= "yes" (:winner (vote/decision a "release"))))
        (is (= 2 (get (vote/tally a "release") "yes")))
        (is (await-truthy 20000 #(seq (space/read-all (fleet/space a "findings") Fixtures$FindingEntry {:limit 10}))))
        ;; A question without "ship" is abstained on by both and never decides.
        (vote/propose! b {:id "pause" :question "wait?" :options ["yes" "no"] :quorum 1})
        (Thread/sleep 1500)
        (is (nil? (vote/decision a "pause")))
        (agents/unbind! bound-a)
        (agents/unbind! bound-b))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------- aggregate

(deftest the-aggregate-namespace-settles-a-fleet-average
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"}]
        caps {:capabilities {:aggregate true}}
        a (fleet/start (config "clj-agg" port-a nil spaces caps))
        b (fleet/start (config "clj-agg" port-b port-a spaces caps))]
    (try
      (Thread/sleep 1500)
      (aggregate/start! a "load" 10.0)
      (aggregate/start! b "load" 30.0)
      (let [settled (aggregate/await-settled b "load" {:ticks 3 :tolerance 0.01 :timeout "30s"})]
        (is (some? settled))
        (is (< 19.0 settled 21.0)))
      (is (< 19.0 (aggregate/estimate a "load") 21.0))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; --------------------------------------------------------------- ordered

(deftest the-ordered-namespace-takes-each-entry-once-through-the-log
  (let [id-a (PeerIdentity/generate) id-b (PeerIdentity/generate)
        members [(.peerId id-a) (.peerId id-b)]
        port-a (free-port) port-b (free-port)
        spaces [{:name "orders" :settle "150ms"}]
        caps (fn [seed] {:capabilities {:ordered {:space "orders" :members members :seed seed}}})
        a (fleet/start (assoc (config "clj-ord" port-a nil spaces (caps 1)) :identity id-a))
        b (fleet/start (assoc (config "clj-ord" port-b port-a spaces (caps 2)) :identity id-b))]
    (try
      (Thread/sleep 2500)
      (doseq [i (range 4)]
        (space/write! (fleet/space a "orders") Fixtures$TaskEntry {:topic (str "order-" i) :priority i} {:lease "10m"}))
      (let [taken (atom [])
            drain (fn [system]
                    (loop []
                      (when-let [t (ordered/take! system Fixtures$TaskEntry {:timeout "2s"})]
                        (swap! taken conj (:topic t))
                        (ordered/complete! system t)
                        (recur))))
            ta (future (drain a))
            tb (future (drain b))]
        (is (await-truthy 60000 #(= 4 (count @taken))))
        @ta @tb
        (is (= #{"order-0" "order-1" "order-2" "order-3"} (set @taken)))
        (is (= 4 (count @taken)) "each entry exactly once across both takers"))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------- semantic and remote

(deftest semantic-discovery-ranks-a-bound-agents-card-and-remote-invokes-it
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        caps {:capabilities {:semantic true}}
        a (fleet/start (config "clj-sem" port-a nil spaces caps))
        b (fleet/start (config "clj-sem" port-b port-a spaces caps))]
    (try
      (let [bound (agents/bind! a (->Researcher))]
        (is (await-truthy 30000 #(seq (semantic/query b "research a topic" 3))))
        (is (= "researcher" (:name (first (semantic/query b "research a topic" 3)))))
        (is (await-truthy 30000 #(seq (remote/actions b))))
        (let [action (first (remote/actions b))]
          (is (= "researcher_TaskEntry" (:name action)))
          (is (= "clj researched remote"
                 (:summary (remote/invoke! b action {:topic "remote" :priority 1} {:timeout "30s"})))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------- tags and bid functions

(deftest tag-templates-and-bid-functions-work-from-config
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms" :strategy :auction
                 :bid-fn (fn [task] (if (= "cheap" (:topic task)) 1.0 100.0))}]
        a (fleet/start (config "clj-bid" port-a nil spaces {}))
        b (fleet/start (config "clj-bid" port-b port-a spaces {}))]
    (try
      (Thread/sleep 1500)
      (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic "eu-task" :priority 1}
                    {:lease "10m" :tags {:region "eu"}})
      (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic "us-task" :priority 1}
                    {:lease "10m" :tags {:region "us"}})
      (is (await-truthy 20000 #(= 2 (count (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10})))))
      (is (= ["eu-task"] (mapv :topic (space/read-all (fleet/space b "work") Fixtures$TaskEntry
                                                      {:limit 10 :tags {:region "eu"}}))))
      (is (= 2 (count (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10 :tags {:region :some?}}))))
      ;; Under AUCTION the configured bid prices the take; both peers price "cheap" at 1.
      (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic "cheap" :priority 9} {:lease "10m"})
      (is (await-truthy 20000 #(some #{"cheap"} (mapv :topic (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10})))))
      (let [t (space/take! (fleet/space b "work") Fixtures$TaskEntry {:where {:topic "cheap"} :timeout "5s"})]
        (is (= "cheap" (:topic t)))
        (space/complete! (fleet/space b "work") t))
      (finally (fleet/stop! b) (fleet/stop! a)))))
