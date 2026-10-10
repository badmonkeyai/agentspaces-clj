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

(ns agentspaces.agents-card-test
  "`agents/card` under the two agent-key modes (SPEC 4.2, v0.1.13): an agent
  bound under `:agent-keys :subordinate` carries the key it signs with and
  the peer-signed certificate that proves it, as a map; a peer-signed agent
  carries neither."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.fleet :as fleet])
  (:import (ai.badmonkey.agentspaces.api.ad AgentCard)
           (ai.badmonkey.agentspaces.api.security AgentCertificate)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (java.net ServerSocket)
           (java.time Duration Instant)
           (java.util Arrays)))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- config [group port seed-port extra]
  (merge {:bind (str "127.0.0.1:" port)
          :tick "200ms"
          :agents true
          :group {:name group
                  :founding (str group "-v1")
                  :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
                  :spaces [{:name "work" :settle "150ms"}]}}
         extra))

(defagent Clerk
  {:description "Files tasks" :goals ["file"]}
  (take file [^Fixtures$TaskEntry t] {:space "work" :poll-timeout "200ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "filed")))

(deftest the-card-carries-the-agent-certificate-under-subordinate-keys-and-nothing-under-peer-keys
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-card" port-a nil {:agent-keys :subordinate :agent-certificate-ttl "2h"}))
        b (fleet/start (config "clj-card" port-b port-a {}))]
    (try
      (let [bound-a (agents/bind! a (->Clerk))
            bound-b (agents/bind! b (->Clerk))
            card (agents/card bound-a)
            identity (fleet/agent a "clerk")
            {:keys [certificate agent-public-key]} card]
        (testing "the subordinate agent's card names its own key and the certificate proving it"
          (is (.isSubordinate identity))
          (is (bytes? agent-public-key))
          (is (Arrays/equals ^bytes agent-public-key ^bytes (.publicKey identity)) "the key the agent signs with")
          (is (= #{:agent :agent-public-key :issued :ttl :expires-at :peer-signature :encryption-public-key}
                 (set (keys certificate))))
          (is (= (.id identity) (:agent certificate)))
          (is (= "clerk" (.localName (:agent certificate))))
          (is (Arrays/equals ^bytes agent-public-key ^bytes (:agent-public-key certificate)))
          (is (= (Duration/ofHours 2) (:ttl certificate)) ":agent-certificate-ttl is the certificate's lifetime")
          (is (instance? Instant (:issued certificate)))
          (is (= (.plus ^Instant (:issued certificate) (Duration/ofHours 2)) (:expires-at certificate)))
          (is (.isAfter ^Instant (:expires-at certificate) (Instant/now)))
          (is (and (bytes? (:peer-signature certificate)) (pos? (alength ^bytes (:peer-signature certificate)))))
          (is (and (bytes? (:encryption-public-key certificate)) (pos? (alength ^bytes (:encryption-public-key certificate))))
              "a certified X25519 key for per-agent key wrap")
          (testing "and the map mirrors the raw AgentCard"
            (let [^AgentCard raw (.card bound-a)
                  ^AgentCertificate raw-cert (.agentCertificate raw)]
              (is (.attested raw))
              (is (= (.issued raw-cert) (:issued certificate)))
              (is (Arrays/equals ^bytes (.peerSignature raw-cert) ^bytes (:peer-signature certificate))))))
        (testing "a peer-signed agent's card carries neither"
          (let [card-b (agents/card bound-b)]
            (is (nil? (:certificate card-b)))
            (is (nil? (:agent-public-key card-b)))
            (is (contains? card-b :certificate) "the keys are present, nil")
            (is (not (.attested ^AgentCard (.card bound-b))))))
        (testing "the rest of the card reads as before"
          (is (= "clerk" (:agent card)))
          (is (= "Files tasks" (:description card)))
          (is (= [{:name "file" :kind "take" :description ""}] (:actions card))
              "an action with no :description reports an empty one; the card's own stands in"))
        (agents/unbind! bound-b)
        (agents/unbind! bound-a))
      (finally (fleet/stop! b) (fleet/stop! a)))))
