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

(ns agentspaces.capabilities-test
  "The two capability namespaces the layer-4 suite leaves alone, each over
  two TCP peers: `semantic/remote-query` asking the other peer to rank a
  card it published, and the `learn` namespace with `:capabilities {:learn
  ..}`, where two models average to the fleet mean and `end-epoch!` writes
  an evaluation per model into the configured metrics space."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.fleet :as fleet]
            [agentspaces.learn :as learn]
            [agentspaces.semantic :as semantic]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.api.ad AgentCard)
           (ai.badmonkey.agentspaces.capabilities.learn GossipLearner$Evaluation)
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

;; ------------------------------------------------------------ remote-query

(defagent Librarian
  {:description "Catalogues archived research papers by subject" :goals ["catalogue"]}
  (take file [^Fixtures$TaskEntry t] {:space "work" :poll-timeout "200ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "filed")))

(deftest remote-query-asks-the-other-peer-to-rank-the-card-it-published
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"}]
        caps {:capabilities {:semantic true}}
        a (fleet/start (config "clj-remoteq" port-a nil spaces caps))
        b (fleet/start (config "clj-remoteq" port-b port-a spaces caps))]
    (try
      (Thread/sleep 1500)                                  ; membership converges both ways
      (let [bound (agents/bind! a (->Librarian))
            matches (await-truthy 30000
                                  #(seq (semantic/remote-query b "catalogue archived research papers"
                                                               {:limit 3 :timeout "3s"})))]
        (is (seq matches))
        (let [{:keys [kind name description score advertisement]} (first matches)]
          (is (= :agent kind))
          (is (= "librarian" name))
          (is (= "Catalogues archived research papers by subject" description))
          (is (pos? score))
          (is (instance? AgentCard advertisement))
          (is (= (fleet/peer-id a) (.issuer ^AgentCard advertisement)) "the card the other peer issued"))
        (testing "a verified remote answer is cached, so the local query now answers too"
          (is (seq (.find (:discovery b) AgentCard (reify java.util.function.Predicate (test [_ _] true)))))
          (is (= "librarian" (:name (first (semantic/query b "catalogue archived research papers" 3))))))
        (testing "the default limit and timeout apply"
          (is (= "librarian" (:name (first (semantic/remote-query b "catalogue research papers"))))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------------- learn

(deftest the-learn-namespace-averages-models-across-the-fleet-and-ends-an-epoch
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "metrics" :settle "150ms"}]
        caps {:capabilities {:learn {:metrics "metrics" :metrics-lease "1h"}}}
        a (fleet/start (config "clj-learn" port-a nil spaces caps))
        b (fleet/start (config "clj-learn" port-b port-a spaces caps))
        close-to (fn [expected actual]
                   (and (= (count expected) (count actual))
                        (every? (fn [[e x]] (< (Math/abs (double (- e x))) 1e-3)) (map vector expected actual))))]
    (try
      (Thread/sleep 1500)
      (is (nil? (learn/model a "m")) "no model before start!")
      (is (zero? (learn/round a "m")))
      (learn/start! a "m" [0.0 100.0])
      (learn/start! b "m" [40.0 60.0])
      (is (= [0.0 100.0] (learn/model a "m")) "the model as started, before any exchange")
      ;; Pairwise averaging on the node's tick drives both to the fleet mean.
      (is (await-truthy 30000 #(and (close-to [20.0 80.0] (learn/model a "m"))
                                    (close-to [20.0 80.0] (learn/model b "m")))))
      (is (pos? (learn/round a "m")))
      (is (pos? (learn/round b "m")))
      (testing "end-epoch! evaluates every model and writes the evaluation into the metrics space"
        (let [[evaluation :as evaluations] (learn/end-epoch! a 1 (fn [parameters] (reduce + parameters)))]
          (is (= 1 (count evaluations)))
          (is (= "m" (:model-id evaluation)))
          (is (= 1 (:epoch evaluation)))
          (is (< (Math/abs (- 100.0 (:loss evaluation))) 1e-3))
          (is (string? (:content-id evaluation)))
          (is (await-truthy 20000 #(seq (space/read-all (fleet/space b "metrics") GossipLearner$Evaluation {:limit 10})))
              "the evaluation replicates")
          (let [^GossipLearner$Evaluation written (first (space/read-all (fleet/space b "metrics") GossipLearner$Evaluation {:limit 10}))]
            (is (= ["m" 1 (:content-id evaluation)]
                   [(:modelId written) (:epoch written) (:contentId written)])))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest learn-true-registers-the-capability-without-a-metrics-space
  (let [port (free-port)
        a (fleet/start (config "clj-learn-bare" port nil [{:name "work"}] {:capabilities {:learn true}}))]
    (try
      (learn/start! a "m" [1.0 2.0])
      (is (= [1.0 2.0] (learn/model a "m")))
      (is (thrown-with-msg? IllegalStateException #"no metrics space"
                            (learn/end-epoch! a 1 (fn [_] 0.0)))
          "ending an epoch needs :learn {:metrics ..}")
      (finally (fleet/stop! a)))))
