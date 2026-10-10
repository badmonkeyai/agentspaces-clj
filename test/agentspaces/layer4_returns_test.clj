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

(ns agentspaces.layer4-returns-test
  "The three return conventions of a `defagent` verb body beyond a plain
  record: a `motion` that opens a vote, a `tagged` entry written with its
  tags, and a vector that forks into several entries; each over two TCP
  peers, plus the conversion rules of `convert-out` on their own."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space]
            [agentspaces.vote :as vote])
  (:import (ai.badmonkey.agentspaces.agent Entries Tagged)
           (ai.badmonkey.agentspaces.agent.capability Motion)
           (ai.badmonkey.agentspaces.capabilities.vote VoteCapability$Proposal)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (clojure.lang ExceptionInfo)
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

;; ------------------------------------------------------------ convert-out

(deftest convert-out-applies-the-return-conventions
  (let [finding Fixtures$FindingEntry]
    (testing "nil and plain values"
      (is (nil? (agents/convert-out finding [] nil)))
      (is (= "yes" (agents/convert-out String [] "yes"))))
    (testing "a record hint converts a map; a record passes through"
      (is (= (Fixtures$FindingEntry. "t" "s") (agents/convert-out finding [] {:topic "t" :summary "s"})))
      (is (= (Fixtures$FindingEntry. "t" "s") (agents/convert-out finding [] (Fixtures$FindingEntry. "t" "s")))))
    (testing "a Motion hint builds a Motion from a map, or passes one through"
      (let [^Motion m (agents/convert-out Motion [] {:id "m1" :question "q?" :options ["a" "b"] :quorum 2 :space "votes"})]
        (is (instance? Motion m))
        (is (= ["votes" "m1" "q?" ["a" "b"] 2] [(.space m) (.proposalId m) (.question m) (vec (.options m)) (.quorum m)]))
        (is (identical? m (agents/convert-out Motion [] m))))
      (is (= "" (.space ^Motion (agents/motion {:id "m2" :question "q?" :options ["a" "b"] :quorum 1}))) "no :space defers to the registered vote"))
    (testing "a tagged map becomes a Tagged with the entry converted through :produces"
      (let [^Tagged t (agents/convert-out Object [finding] (agents/tagged {:topic "t" :summary "s"} {:region "eu" :tier "gold"}))]
        (is (= (Fixtures$FindingEntry. "t" "s") (.entry t)))
        (is (= {"region" "eu" "tier" "gold"} (.tags t))))
      (let [^Tagged t (agents/convert-out nil [finding] {:entry (Fixtures$FindingEntry. "t" "s") :tags {"k" "v"}})]
        (is (= (Fixtures$FindingEntry. "t" "s") (.entry t)))
        (is (= {"k" "v"} (.tags t))))
      (is (thrown-with-msg? ExceptionInfo #"needs no return hint"
                            (agents/convert-out finding [] (agents/tagged {:topic "t" :summary "s"} {:k "v"})))))
    (testing "a vector becomes an Entries fork, each element converted"
      (let [^Entries e (agents/convert-out Object [finding] [{:topic "a" :summary "1"} (Fixtures$FindingEntry. "b" "2")])]
        (is (= [(Fixtures$FindingEntry. "a" "1") (Fixtures$FindingEntry. "b" "2")] (vec (.elements e)))))
      (is (thrown-with-msg? ExceptionInfo #"needs a record return hint or exactly one :produces class"
                            (agents/convert-out nil [] [{:topic "a" :summary "1"}])))
      (is (thrown-with-msg? ExceptionInfo #"needs no return hint"
                            (agents/convert-out finding [] [{:topic "a" :summary "1"}]))))))

;; ------------------------------------------------------------------ motion

(defagent Escalator
  {:description "Turns a task into a motion" :goals ["escalate"]}
  (take escalate [^Fixtures$TaskEntry t] {:space "work" :poll-timeout "200ms"} ^Motion
    (agents/motion {:id (str "m-" (:topic t)) :question (str "ship " (:topic t) "?")
                    :options ["yes" "no"] :quorum 2 :space "votes" :lease "1h"})))

(deftest a-motion-return-opens-the-vote-the-vote-namespace-sees
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "votes" :settle "150ms"}]
        caps {:capabilities {:vote {:space "votes"}}}
        a (fleet/start (config "clj-motion" port-a nil spaces caps))
        b (fleet/start (config "clj-motion" port-b port-a spaces caps))]
    (try
      (let [bound (agents/bind! a (->Escalator))]
        (is (= "escalator" (:agent (agents/card bound))))
        (is (some #(re-find #"Proposal" %) (:produces (agents/card bound))) "a declared Motion produces a Proposal")
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "release" :priority 1} {:lease "10m"})
        (is (await-truthy 30000 #(vote/proposal a "m-release")))
        (is (= {:id "m-release" :question "ship release?" :options ["yes" "no"] :quorum 2}
               (vote/proposal a "m-release")))
        (is (await-truthy 30000 #(vote/proposal b "m-release")) "the proposal replicates to the other peer")
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10}))) "the take completed")
        (is (= 1 (count (space/read-all (fleet/space a "votes") VoteCapability$Proposal {:limit 10}))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------------ tagged

(defagent Labeller
  {:description "Writes its finding with tags" :goals ["label"]}
  (take label [^Fixtures$TaskEntry t] {:space "work" :result-space "findings" :poll-timeout "200ms"
                                       :produces [Fixtures$FindingEntry]}
    (agents/tagged {:topic (:topic t) :summary (str "labelled " (:topic t))}
                   {:region "eu" :priority (:priority t)})))

(deftest a-tagged-return-writes-the-entry-with-its-tags
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-tagged" port-a nil spaces {}))
        b (fleet/start (config "clj-tagged" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->Labeller))]
        (is (some #(re-find #"FindingEntry" %) (:produces (agents/card bound))) ":produces feeds the card")
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "tags" :priority 7} {:lease "10m"})
        (is (await-truthy 30000 #(seq (space/read-entries (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        (let [[entry :as entries] (space/read-entries (fleet/space b "findings") Fixtures$FindingEntry {:limit 10})]
          (is (= 1 (count entries)))
          (is (= {:topic "tags" :summary "labelled tags"} (:value entry)))
          (is (= {"region" "eu" "priority" "7"} (:tags entry))))
        (is (= 1 (count (space/read-entries (fleet/space b "findings") Fixtures$FindingEntry {:tags {:region "eu"}}))))
        (is (empty? (space/read-entries (fleet/space b "findings") Fixtures$FindingEntry {:tags {:region "us"}})))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; -------------------------------------------------------------------- fork

(defagent Splitter
  {:description "Forks a task into two findings" :goals ["split"]}
  (take split [^Fixtures$TaskEntry t] {:space "work" :result-space "findings" :poll-timeout "200ms"
                                       :produces [Fixtures$FindingEntry]}
    [{:topic (:topic t) :summary "part 1"}
     (Fixtures$FindingEntry. (:topic t) "part 2")]))

(deftest a-vector-return-forks-into-several-entries
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-fork" port-a nil spaces {}))
        b (fleet/start (config "clj-fork" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->Splitter))]
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "fork" :priority 1} {:lease "10m"})
        (is (await-truthy 30000 #(= 2 (count (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10})))))
        (is (= #{"part 1" "part 2"} (set (map :summary (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10})))))
        (is (every? #(= "fork" (:topic %)) (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10})))
        (is (await-truthy 10000 #(empty? (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:limit 10}))) "the take completed once")
        (Thread/sleep 1000)
        (is (= 2 (count (space/read-all (fleet/space b "findings") Fixtures$FindingEntry {:limit 10}))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))
