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

(ns agentspaces.space-test
  "The data layer and the space verbs over a LocalSpace: maps in, maps out,
  the :where DSL, take/complete with a result, and notify events."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.core :as core]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.common.id AgentId PeerId)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (clojure.lang ExceptionInfo)
           (java.time Duration)))

(deftest durations-parse-simple-and-iso-forms
  (is (= (Duration/ofMinutes 10) (core/duration "10m")))
  (is (= (Duration/ofMillis 500) (core/duration "500ms")))
  (is (= (Duration/ofDays 1) (core/duration "1d")))
  (is (= (Duration/ofMinutes 10) (core/duration "PT10M")))
  (is (= (Duration/ofMillis 250) (core/duration 250)))
  (is (= (Duration/ofSeconds 3) (core/duration (Duration/ofSeconds 3)))))

(deftest ids-coerce-from-strings-and-instances
  (let [identity (PeerIdentity/generate)
        peer (.peerId identity)
        agent (.agent identity "worker")]
    (is (identical? peer (core/peer-id peer)))
    (is (= peer (core/peer-id (.value peer))))
    (is (= peer (core/peer-id (str "aspace://" (.value peer)))))
    (is (= peer (core/peer-id agent)))
    (is (identical? agent (core/agent-id agent)))
    (is (= agent (core/agent-id (.encoded agent))))
    (is (instance? AgentId (core/agent-id (str (.value peer) "/steward"))))
    (is (instance? PeerId (core/peer-id (.encoded agent))))
    (is (thrown? ExceptionInfo (core/agent-id 42)))))

(deftest records-round-trip-through-maps
  (let [entry (core/to-entry Fixtures$TaskEntry {:topic "indexing" :priority 3})]
    (testing "a Clojure long fills the record's int component"
      (is (instance? Fixtures$TaskEntry entry))
      (is (= 3 (.priority ^Fixtures$TaskEntry entry))))
    (testing "and the record comes back as a map with the raw object in metadata"
      (let [m (core/from-entry entry)]
        (is (= {:topic "indexing" :priority 3} m))
        (is (identical? entry (core/raw m)))))))

(deftest the-verbs-speak-maps-over-a-local-space
  (with-open [s (space/local "clj-test")]
    (space/write! s Fixtures$TaskEntry {:topic "indexing" :priority 7})
    (space/write! s Fixtures$TaskEntry {:topic "billing" :priority 2})

    (testing "read with the :where DSL"
      (is (= {:topic "indexing" :priority 7}
             (space/read s Fixtures$TaskEntry {:where {:priority [:gte 5]}})))
      (is (nil? (space/read s Fixtures$TaskEntry {:where {:topic "nowhere"}}))))

    (testing "read-all"
      (is (= 2 (count (space/read-all s Fixtures$TaskEntry))))
      (is (= [{:topic "billing" :priority 2}]
             (space/read-all s Fixtures$TaskEntry {:where {:priority [:lt 5]}}))))

    (testing "take, then complete with a result entry"
      (let [task (space/take! s Fixtures$TaskEntry {:where {:topic "indexing"}
                                                    :lease "10m"})]
        (is (= "indexing" (:topic task)))
        (space/complete! s task Fixtures$FindingEntry
                         {:topic (:topic task) :summary "done"} {:lease "1h"})
        (is (= {:topic "indexing" :summary "done"}
               (space/read s Fixtures$FindingEntry)))
        (testing "and the taken entry is gone"
          (is (= 1 (count (space/read-all s Fixtures$TaskEntry)))))))

    (testing "take! returns nil rather than blocking when nothing matches"
      (is (nil? (space/take! s Fixtures$TaskEntry {:where {:topic "nowhere"}}))))))

(deftest notify-delivers-events-as-maps
  (with-open [s (space/local "clj-notify")]
    (let [events (atom [])
          subscription (space/notify! s Fixtures$FindingEntry
                                      (fn [event] (swap! events conj event)))]
      (space/write! s Fixtures$FindingEntry {:topic "t" :summary "one"})
      (is (= 1 (count @events)))
      (let [{:keys [kind entry]} (first @events)]
        (is (= :written kind))
        (is (= {:topic "t" :summary "one"} entry)))
      (space/unsubscribe! subscription)
      (space/write! s Fixtures$FindingEntry {:topic "t" :summary "two"})
      (is (= 1 (count @events))))))

(deftest tags-fresh-and-credentials-over-a-local-space
  (with-open [s (space/local "clj-tags")]
    (testing "tagged writes round-trip as entries (tags ride the record on the wire)"
      (space/write! s Fixtures$TaskEntry {:topic "tagged" :priority 1}
                    {:lease "10m" :tags {:region "eu" :tier :gold}})
      (is (= {:topic "tagged" :priority 1} (space/read s Fixtures$TaskEntry))))
    (testing ":fresh is ignored on a LocalSpace, which has nobody to pull from"
      (is (= {:topic "tagged" :priority 1}
             (space/read s Fixtures$TaskEntry {:fresh true})))
      (is (= 1 (count (space/read-all s Fixtures$TaskEntry {:fresh true})))))
    (testing "grant! and revoke! need a credential-admitted ReplicatedSpace"
      (is (thrown? ExceptionInfo (space/grant! s "zPeer/agent" #{:write} "1h")))
      (is (thrown? ExceptionInfo (space/revoke! s "zPeer/agent"))))))

(deftest handles-renew-and-cancel
  (with-open [s (space/local "clj-handles")]
    (let [handle (space/write! s Fixtures$TaskEntry {:topic "t" :priority 1}
                               {:lease "10m"})]
      (space/renew! handle "20m")
      (space/cancel! handle)
      (is (empty? (space/read-all s Fixtures$TaskEntry))))))
