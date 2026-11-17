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
  the :where DSL, take/complete with a result, read-entries, and notify
  events with their tags and details."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.core :as core]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.api.entry EntryId)
           (ai.badmonkey.agentspaces.common.id AgentId PeerId)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (clojure.lang ExceptionInfo)
           (java.time Duration Instant)))

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

(deftest read-entries-returns-the-metadata-view-of-each-match
  (with-open [s (space/local "clj-entries")]
    (space/write! s Fixtures$TaskEntry {:topic "eu" :priority 1} {:lease "10m" :tags {:region "eu"}})
    (space/write! s Fixtures$TaskEntry {:topic "us" :priority 2} {:lease "10m" :tags {:region "us"}})
    (let [entries (space/read-entries s Fixtures$TaskEntry)
          eu (first (filter #(= "eu" (get-in % [:value :topic])) entries))]
      (is (= 2 (count entries)))
      (is (= #{:value :entry-id :issuer :tags :expires-at :issued} (set (keys eu))))
      (is (= {:topic "eu" :priority 1} (:value eu)))
      (is (= {"region" "eu"} (:tags eu)))
      (is (instance? EntryId (:entry-id eu)))
      (is (instance? AgentId (:issuer eu)))
      (is (instance? Instant (:expires-at eu)))
      (is (.isAfter ^Instant (:expires-at eu) (Instant/now)))
      (is (some? (:issued eu)))
      ;; read-all stays the plain value view; the two agree on the entry.
      (is (= (mapv :value entries) (space/read-all s Fixtures$TaskEntry))))
    (testing ":tags and :where narrow it as they narrow read-all"
      (is (= ["us"] (mapv #(get-in % [:value :topic]) (space/read-entries s Fixtures$TaskEntry {:tags {:region "us"}}))))
      (is (= ["eu"] (mapv #(get-in % [:value :topic]) (space/read-entries s Fixtures$TaskEntry {:where {:priority [:lt 2]}}))))
      (is (= 1 (count (space/read-entries s Fixtures$TaskEntry {:limit 1})))))))

(deftest notify-events-carry-the-entrys-tags-and-details
  (with-open [s (space/local "clj-notify-tags")]
    (let [events (atom [])
          subscription (space/notify! s Fixtures$FindingEntry (fn [event] (swap! events conj event)))]
      (space/write! s Fixtures$FindingEntry {:topic "t" :summary "tagged"} {:lease "10m" :tags {:region "eu" :tier "gold"}})
      (is (= 1 (count @events)))
      (let [{:keys [kind entry tags details entry-id]} (first @events)]
        (is (= :written kind))
        (is (= {"region" "eu" "tier" "gold"} tags))
        (is (= {:topic "t" :summary "tagged"} entry))
        (is (= entry (:value details)))
        (is (= tags (:tags details)))
        (is (= entry-id (:entry-id details)))
        (is (instance? Instant (:expires-at details))))
      (space/write! s Fixtures$FindingEntry {:topic "t" :summary "bare"})
      (is (= {} (:tags (second @events))) "an untagged entry reports no tags")
      (space/unsubscribe! subscription))))

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

(deftest renew-never-shortens-and-cancel-is-how-an-entry-ends-sooner
  ;; SPEC §7.2 (v0.1.35): the new expiry is the later of the current one and
  ;; now plus the extension, so renew! cannot shorten a lease (RE-L-04). The
  ;; test reads the lease read-entries reports as :expires-at, which moves with
  ;; a renewal since LocalSpace keeps its record's lease with the expiry
  ;; (RESUME-RBE core item a), and checks by a wait that the entries live by it.
  (with-open [s (space/local "clj-renew-contract" {:renew-shortening-log false :sweep-every "100ms"})]
    (let [present? (fn [topic] (some #(= topic (:topic %)) (space/read-all s Fixtures$TaskEntry)))
          expires-at (fn [topic] (some #(when (= topic (:topic (:value %))) (:expires-at %))
                                       (space/read-entries s Fixtures$TaskEntry)))
          long-lived (space/write! s Fixtures$TaskEntry {:topic "long" :priority 1} {:lease "1h"})
          short-lived (space/write! s Fixtures$TaskEntry {:topic "short" :priority 1} {:lease "500ms"})]
      (testing "a shorter renewal succeeds and leaves the lease as it was"
        (let [before (expires-at "long")]
          (is (some? before))
          (space/renew! long-lived "100ms")
          (is (= 1 (.renewShortenings s)) "the shortening renewal is counted")
          (is (= before (expires-at "long")) "the reported expiry did not move")))
      (testing "a longer renewal extends a lease"
        (let [before (expires-at "short")
              ;; whole milliseconds, as the space's clock reads them
              floor (.plus (Instant/ofEpochMilli (System/currentTimeMillis)) (Duration/ofHours 1))]
          (space/renew! short-lived "1h")
          (let [after (expires-at "short")
                ceiling (.plus (Instant/ofEpochMilli (System/currentTimeMillis)) (Duration/ofHours 1))]
            (is (.isAfter after before) "the reported expiry moved later")
            (is (not (.isBefore after floor)) "at least an hour from the renewal")
            (is (not (.isAfter after ceiling)) "and no more than an hour from now"))))
      (Thread/sleep 1200)
      (is (present? "long") "still there well past the 100 ms the shorter renewal asked for")
      (is (present? "short") "still there past its written 500 ms")
      (testing "a caller that wants a shorter lease cancels and writes again"
        (space/cancel! long-lived)
        (is (not (present? "long")))
        (space/write! s Fixtures$TaskEntry {:topic "long" :priority 1} {:lease "300ms"})
        (is (present? "long"))
        (Thread/sleep 1200)
        (is (not (present? "long")) "the rewrite lapsed with its shorter lease"))
      (testing "a cancelled entry is gone, so renewing it throws"
        (is (thrown? ai.badmonkey.agentspaces.api.error.LeaseExpiredException
                     (space/renew! long-lived "1h")))))))

(deftest a-take-renewal-never-shortens-its-take-lease
  (with-open [s (space/local "clj-renew-take" {:sweep-every "200ms"})]
    (space/write! s Fixtures$TaskEntry {:topic "held" :priority 1} {:lease "1h"})
    (let [task (space/take! s Fixtures$TaskEntry {:lease "1h"})]
      (is (some? task))
      ;; One second would let the take lapse almost at once; the hold keeps its hour, so
      ;; the entry stays taken past that second instead of reappearing.
      (space/renew! task "1s")
      (Thread/sleep 1500)
      (is (nil? (space/read s Fixtures$TaskEntry)) "still taken after the shorter renewal")
      (space/complete! s task)
      (is (nil? (space/read s Fixtures$TaskEntry))))))
