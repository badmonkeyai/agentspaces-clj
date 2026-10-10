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

(ns agentspaces.layer4-options-test
  "The verb options the other suites leave at their defaults, each read back
  from the generated interface and exercised over two TCP peers:
  `:result-lease` and `:description` on a take; a join in `:mode :ordered`
  over the log, `@Part` options `:at-least`, `:key-tag`, and `:optional`,
  and `:within` with `:max-open`; a reduce in `:mode :ordered`, by
  `:key-tag`, and with an `:accumulator-lease`; and a propose keyed by
  `:key-tag`."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.core :as core]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space]
            [agentspaces.vote :as vote])
  (:import (ai.badmonkey.agentspaces.agent.annotation Part Propose SpaceJoin SpaceJoin$Mode
                                                      SpaceReduce SpaceReduce$Mode SpaceTake)
           (ai.badmonkey.agentspaces.agent.join Joined JoinTicket)
           (ai.badmonkey.agentspaces.capabilities.vote VoteCapability$Proposal)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (java.net ServerSocket)
           (java.time Duration Instant)))

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
  (space/read-all (fleet/space system "findings") Fixtures$FindingEntry {:limit 20}))

(defn- within-minutes?
  "Whether `instant` lies between `lo` and `hi` minutes from now."
  [^Instant instant lo hi]
  (let [now (Instant/now)]
    (and (.isAfter instant (.plus now (Duration/ofMinutes lo)))
         (.isBefore instant (.plus now (Duration/ofMinutes hi))))))

(defn- annotation [^Class spec method param-types ^Class annotation]
  (.getAnnotation (.getMethod spec method (into-array Class param-types)) annotation))

;; ------------------------------------------- take: :result-lease, :description

(defagent Desk
  {:description "A research desk" :goals ["research"]}
  (take research [^Fixtures$TaskEntry t] {:space "work" :result-space "findings" :poll-timeout "200ms"
                                          :result-lease "2m" :description "Researches a topic in depth"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "in depth")))

(deftest a-take-result-lease-bounds-the-result-and-its-description-reaches-the-card
  (let [^SpaceTake take (annotation DeskSpec "research" [Fixtures$TaskEntry] SpaceTake)]
    (is (= "2m" (.resultLease take)))
    (is (= "Researches a topic in depth" (.description take))))
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "work" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-rlease" port-a nil spaces {}))
        b (fleet/start (config "clj-rlease" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->Desk))]
        (is (= [{:name "research" :kind "take" :description "Researches a topic in depth"}]
               (:actions (agents/card bound))))
        (is (= "Researches a topic in depth"
               (.description ^ai.badmonkey.agentspaces.api.ad.CardAction (first (.actions (.card bound))))))
        (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "leases" :priority 1} {:lease "10m"})
        (is (await-truthy 30000 #(seq (space/read-entries (fleet/space b "findings") Fixtures$FindingEntry))))
        (let [[entry] (space/read-entries (fleet/space b "findings") Fixtures$FindingEntry)]
          (is (= {:topic "leases" :summary "in depth"} (:value entry)))
          (is (within-minutes? (:expires-at entry) 1 3) "the result carries the 2m lease, 1h being the default"))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ---------------------------------------------------------- join: :mode :ordered

(defagent OrderedAssembler
  {:description "Joins a task with its finding once fleet-wide" :goals ["join"]}
  ;; A short ticket take lease: a ticket a member wins before every part has
  ;; replicated to it is released by that lease and retried, so the lease
  ;; bounds how long one retry costs under load.
  (join assemble [^Joined j] {:space "intake" :key "topic" :mode :ordered :take-lease "10s"
                              :poll-timeout "200ms" :result-space "findings"
                              :parts [{:value Fixtures$TaskEntry} {:value Fixtures$FindingEntry}]}
    ^Fixtures$FindingEntry
    (let [task (core/from-entry (.get j Fixtures$TaskEntry))
          finding (core/from-entry (.get j Fixtures$FindingEntry))]
      (Fixtures$FindingEntry. (.key j) (str "ordered " (:priority task) " " (:summary finding))))))

(deftest an-ordered-join-fires-once-per-key-across-two-joiners-and-drains-its-tickets
  (is (= SpaceJoin$Mode/ORDERED (.mode ^SpaceJoin (annotation OrderedAssemblerSpec "assemble" [Joined] SpaceJoin))))
  (let [id-a (PeerIdentity/generate) id-b (PeerIdentity/generate)
        members [(.peerId id-a) (.peerId id-b)]
        port-a (free-port) port-b (free-port)
        spaces [{:name "intake" :settle "150ms"} {:name "findings" :settle "150ms"}]
        caps (fn [seed] {:capabilities {:ordered {:space "intake" :members members :seed seed}}})
        a (fleet/start (assoc (config "clj-ojoin" port-a nil spaces (caps 1)) :identity id-a))
        b (fleet/start (assoc (config "clj-ojoin" port-b port-a spaces (caps 2)) :identity id-b))]
    (try
      (let [bound-a (agents/bind! a (->OrderedAssembler))
            bound-b (agents/bind! b (->OrderedAssembler))]
        (is (= ["join"] (map :kind (:actions (agents/card bound-a)))))
        (Thread/sleep 2500)
        ;; Each key's halves land at different members, so completeness is first
        ;; seen at different replicas and either joiner may write the ticket.
        (doseq [i (range 4)]
          (let [[first-half second-half] (if (even? i) [a b] [b a])]
            (space/write! (fleet/space first-half "intake") Fixtures$TaskEntry {:topic (str "k" i) :priority i} {:lease "10m"})
            (space/write! (fleet/space second-half "intake") Fixtures$FindingEntry {:topic (str "k" i) :summary (str "n" i)} {:lease "10m"})))
        (is (await-truthy 90000 #(= 4 (count (findings b)))))
        (is (= {"k0" "ordered 0 n0" "k1" "ordered 1 n1" "k2" "ordered 2 n2" "k3" "ordered 3 n3"}
               (into {} (map (juxt :topic :summary)) (findings b))))
        (is (await-truthy 20000 #(and (empty? (space/read-all (fleet/space a "intake") JoinTicket {:limit 20}))
                                      (empty? (space/read-all (fleet/space b "intake") JoinTicket {:limit 20}))))
            "tickets drained at both members")
        (Thread/sleep 1500)
        (is (= 4 (count (findings b))) "every key fired exactly once across the fleet")
        (agents/unbind! bound-b)
        (agents/unbind! bound-a))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; --------------------------------------------- @Part: :at-least, :key-tag, :optional

(defagent Gatherer
  {:description "Gathers two tasks and a finding keyed by its tag" :goals ["gather"]}
  (join gather [^Joined j] {:space "intake" :key "topic" :result-space "findings"
                            :parts [{:value Fixtures$TaskEntry :at-least 2}
                                    {:value Fixtures$FindingEntry :key-tag "case"}]}
    ^Fixtures$FindingEntry
    (let [tasks (map core/from-entry (.all j Fixtures$TaskEntry))
          finding (core/from-entry (.get j Fixtures$FindingEntry))]
      (Fixtures$FindingEntry. (.key j) (str (count tasks) " tasks, " (reduce + (map :priority tasks))
                                            " priority, " (:summary finding))))))

(defagent Annotator
  {:description "Joins a task with an optional note" :goals ["join"]}
  (join annotate [^Joined j] {:space "intake" :key "topic" :result-space "findings"
                              :parts [{:value Fixtures$TaskEntry}
                                      {:value Fixtures$FindingEntry :optional true}]}
    ^Fixtures$FindingEntry
    (let [task (core/from-entry (.get j Fixtures$TaskEntry))
          note (.find j Fixtures$FindingEntry)]
      (Fixtures$FindingEntry. (.key j) (str "task " (:priority task) ", note: "
                                            (if (.isPresent note) (:summary (core/from-entry (.get note))) "none"))))))

(deftest part-options-reach-the-annotation
  (let [parts (fn [^Class spec method] (vec (.parts ^SpaceJoin (annotation spec method [Joined] SpaceJoin))))
        [^Part tasks ^Part finding] (parts GathererSpec "gather")
        [_ ^Part note] (parts AnnotatorSpec "annotate")]
    (is (= [Fixtures$TaskEntry 2 "" false] [(.value tasks) (.atLeast tasks) (.keyTag tasks) (.optional tasks)]))
    (is (= [Fixtures$FindingEntry 1 "case" false] [(.value finding) (.atLeast finding) (.keyTag finding) (.optional finding)]))
    (is (= [Fixtures$FindingEntry true] [(.value note) (.optional note)]))))

(deftest a-gathering-part-waits-for-its-count-and-a-tag-keyed-part-joins-by-its-tag
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "intake" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-gather" port-a nil spaces {}))
        b (fleet/start (config "clj-gather" port-b port-a spaces {}))
        intake (fn [system] (fleet/space system "intake"))]
    (try
      (let [bound (agents/bind! a (->Gatherer))]
        ;; One task and the tagged finding: the gather still waits for a second task.
        (space/write! (intake b) Fixtures$TaskEntry {:topic "c1" :priority 3} {:lease "10m"})
        (space/write! (intake b) Fixtures$FindingEntry {:topic "unrelated" :summary "damage"} {:lease "10m" :tags {:case "c1"}})
        (Thread/sleep 1500)
        (is (empty? (findings b)) "at-least 2 holds the key open on one task")
        (space/write! (intake b) Fixtures$TaskEntry {:topic "c1" :priority 4} {:lease "10m"})
        (is (await-truthy 30000 #(seq (findings b))))
        (is (= [{:topic "c1" :summary "2 tasks, 7 priority, damage"}] (findings b))
            "the finding joined by its case tag; its topic field never mattered")
        ;; A finding tagged with another case completes nothing for c1, and two
        ;; tasks with no tagged finding fire nothing either.
        (space/write! (intake b) Fixtures$TaskEntry {:topic "c2" :priority 1} {:lease "10m"})
        (space/write! (intake b) Fixtures$TaskEntry {:topic "c2" :priority 2} {:lease "10m"})
        (Thread/sleep 1500)
        (is (= 1 (count (findings b))))
        (space/write! (intake b) Fixtures$FindingEntry {:topic "c2" :summary "untagged"} {:lease "10m"})
        (Thread/sleep 1500)
        (is (= 1 (count (findings b))) "a tag-keyed part reads only its tag")
        (space/write! (intake b) Fixtures$FindingEntry {:topic "whatever" :summary "tagged"} {:lease "10m" :tags {:case "c2"}})
        (is (await-truthy 30000 #(= 2 (count (findings b)))))
        (is (some #(= {:topic "c2" :summary "2 tasks, 3 priority, tagged"} %) (findings b)))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest an-optional-part-rides-along-when-present-and-never-holds-the-key-open
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "intake" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-optional" port-a nil spaces {}))
        b (fleet/start (config "clj-optional" port-b port-a spaces {}))]
    (try
      (let [bound (agents/bind! a (->Annotator))]
        ;; The note arrives first, then the task: the key completes with the note in it.
        (space/write! (fleet/space b "intake") Fixtures$FindingEntry {:topic "noted" :summary "late shipment"} {:lease "10m"})
        (Thread/sleep 1500)
        (is (empty? (findings b)) "an optional part alone fires nothing")
        (space/write! (fleet/space b "intake") Fixtures$TaskEntry {:topic "noted" :priority 5} {:lease "10m"})
        (is (await-truthy 30000 #(seq (findings b))))
        (is (= [{:topic "noted" :summary "task 5, note: late shipment"}] (findings b)))
        ;; A task alone completes its key without waiting for a note.
        (space/write! (fleet/space b "intake") Fixtures$TaskEntry {:topic "bare" :priority 6} {:lease "10m"})
        (is (await-truthy 30000 #(= 2 (count (findings b)))))
        (is (some #(= {:topic "bare" :summary "task 6, note: none"} %) (findings b)))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------ join: :within, :max-open

(defagent Forgetful
  {:description "Forgets a key quickly and keeps few open" :goals ["join"]}
  (join assemble [^Joined j] {:space "intake" :key "topic" :within "200ms" :max-open 2 :result-space "findings"
                              :parts [{:value Fixtures$TaskEntry} {:value Fixtures$FindingEntry}]}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (.key j) "remembered")))

(deftest a-forgotten-or-evicted-key-is-found-again-in-the-space-when-its-last-part-arrives
  (let [^SpaceJoin join (annotation ForgetfulSpec "assemble" [Joined] SpaceJoin)]
    (is (= "200ms" (.within join)))
    (is (= 2 (.maxOpen join))))
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "intake" :settle "150ms"} {:name "findings" :settle "150ms"}]
        a (fleet/start (config "clj-forget" port-a nil spaces {}))
        b (fleet/start (config "clj-forget" port-b port-a spaces {}))
        intake (fleet/space a "intake")]
    (try
      (let [bound (agents/bind! a (->Forgetful))]
        (space/write! intake Fixtures$TaskEntry {:topic "slow" :priority 1} {:lease "10m"})
        (Thread/sleep 600)                                   ; past :within, the key is forgotten
        (space/write! intake Fixtures$TaskEntry {:topic "e1" :priority 1} {:lease "10m"})
        (space/write! intake Fixtures$TaskEntry {:topic "e2" :priority 1} {:lease "10m"})
        (space/write! intake Fixtures$TaskEntry {:topic "e3" :priority 1} {:lease "10m"}) ; beyond :max-open, the eldest is evicted
        (space/write! intake Fixtures$FindingEntry {:topic "slow" :summary "s"} {:lease "10m"}) ; the forgotten key's last part
        (space/write! intake Fixtures$FindingEntry {:topic "e1" :summary "s"} {:lease "10m"})   ; the evicted key's last part
        (is (await-truthy 30000 #(= 2 (count (findings b)))))
        (is (= #{"slow" "e1"} (set (map :topic (findings b)))))
        (Thread/sleep 1500)
        (is (= 2 (count (findings b))))
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------- reduce: :mode :ordered, :accumulator-lease

(defagent OrderedTally
  {:description "Sums priorities per topic once fleet-wide" :goals ["reduce"]}
  (reduce fold [^Fixtures$FindingEntry acc ^Fixtures$TaskEntry task]
          {:space "ledger" :key "topic" :mode :ordered :lease "30s" :poll-timeout "200ms" :accumulator-lease "2m"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic task)
                            (str (+ (if acc (parse-long (:summary acc)) 0) (:priority task))))))

(deftest an-ordered-reduce-folds-every-element-once-across-two-reducers-under-the-accumulator-lease
  (let [^SpaceReduce reduce (annotation OrderedTallySpec "fold" [Fixtures$FindingEntry Fixtures$TaskEntry] SpaceReduce)]
    (is (= SpaceReduce$Mode/ORDERED (.mode reduce)))
    (is (= "2m" (.accumulatorLease reduce))))
  (let [id-a (PeerIdentity/generate) id-b (PeerIdentity/generate)
        members [(.peerId id-a) (.peerId id-b)]
        port-a (free-port) port-b (free-port)
        spaces [{:name "ledger" :settle "150ms"}]
        caps (fn [seed] {:capabilities {:ordered {:space "ledger" :members members :seed seed}}})
        a (fleet/start (assoc (config "clj-oreduce" port-a nil spaces (caps 1)) :identity id-a))
        b (fleet/start (assoc (config "clj-oreduce" port-b port-a spaces (caps 2)) :identity id-b))
        ledger (fn [system] (fleet/space system "ledger"))
        sums (fn [system]
               (into {} (map (juxt :topic :summary)) (space/read-all (ledger system) Fixtures$FindingEntry {:limit 20})))]
    (try
      (let [bound-a (agents/bind! a (->OrderedTally))
            bound-b (agents/bind! b (->OrderedTally))]
        (is (= ["reduce"] (map :kind (:actions (agents/card bound-a)))))
        (Thread/sleep 2500)
        (doseq [[i p] (map-indexed vector [1 2 3 10 20])]
          (space/write! (ledger (if (even? i) a b)) Fixtures$TaskEntry
                        {:topic (if (< p 10) "x" "y") :priority p} {:lease "10m"}))
        (is (await-truthy 60000 #(and (= {"x" "6" "y" "30"} (sums a)) (= {"x" "6" "y" "30"} (sums b))))
            "every member holds one accumulator per key with the full sum")
        (is (await-truthy 20000 #(and (empty? (space/read-all (ledger a) Fixtures$TaskEntry {:limit 20}))
                                      (empty? (space/read-all (ledger a) JoinTicket {:limit 20}))
                                      (empty? (space/read-all (ledger b) JoinTicket {:limit 20}))))
            "elements folded and tickets drained everywhere")
        (let [x (first (filter #(= "x" (get-in % [:value :topic]))
                               (space/read-entries (ledger b) Fixtures$FindingEntry {:limit 20})))]
          (is (within-minutes? (:expires-at x) 1 3) "the accumulator carries the 2m lease, 24h being the default"))
        (Thread/sleep 1500)
        (is (= {"x" "6" "y" "30"} (sums b)) "each element folded exactly once")
        (agents/unbind! bound-b)
        (agents/unbind! bound-a))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ------------------------------------------------------------ reduce: :key-tag

(defagent TagTally
  {:description "Sums priorities per account tag" :goals ["reduce"]}
  (reduce fold [^Fixtures$FindingEntry acc ^Fixtures$TaskEntry task]
          {:space "ledger" :key-tag "account" :lease "2s" :poll-timeout "100ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. "balance"
                            (str (+ (if acc (parse-long (:summary acc)) 0) (:priority task))))))

(deftest a-tag-keyed-reduce-folds-by-the-elements-tag-and-leaves-untagged-elements-alone
  (is (= ["" "account"] (let [^SpaceReduce r (annotation TagTallySpec "fold" [Fixtures$FindingEntry Fixtures$TaskEntry] SpaceReduce)]
                          [(.key r) (.keyTag r)])))
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "ledger" :settle "150ms"}]
        a (fleet/start (config "clj-tagreduce" port-a nil spaces {}))
        b (fleet/start (config "clj-tagreduce" port-b port-a spaces {}))
        ledger (fleet/space b "ledger")
        balance (fn [account]
                  (some-> (space/read-entries ledger Fixtures$FindingEntry {:tags {:key account}}) first :value :summary))]
    (try
      (let [bound (agents/bind! a (->TagTally))]
        (doseq [p [1 2 3]]
          (space/write! ledger Fixtures$TaskEntry {:topic (str "t" p) :priority p} {:lease "10m" :tags {:account "acct-1"}}))
        (space/write! ledger Fixtures$TaskEntry {:topic "other" :priority 10} {:lease "10m" :tags {:account "acct-2"}})
        (space/write! ledger Fixtures$TaskEntry {:topic "untagged" :priority 100} {:lease "10m"})
        (is (await-truthy 30000 #(= "6" (balance "acct-1"))))
        (is (await-truthy 30000 #(= "10" (balance "acct-2"))))
        (is (await-truthy 10000 #(= ["untagged"] (map :topic (space/read-all ledger Fixtures$TaskEntry {:limit 20}))))
            "tagged elements are consumed; one with no account tag has no key and stays")
        (is (= 2 (count (space/read-all ledger Fixtures$FindingEntry {:limit 20}))) "one accumulator per tag value")
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))

;; ----------------------------------------------------------- propose: :key-tag

(defagent TagLead
  {:description "Keys its votes by a tag" :goals ["decide"]}
  (propose open [^Fixtures$TaskEntry s] {:space "cues" :vote "votes" :prefix "sig:" :key-tag "case"
                                         :tags ["kind=alert"] :options ["ack" "ignore"] :quorum 1}
    ^String
    (:topic s)))

(deftest a-tag-keyed-propose-filters-and-keys-its-vote-by-the-cues-tags
  (is (= ["case" []] (let [^Propose p (annotation TagLeadSpec "open" [Fixtures$TaskEntry] Propose)]
                       [(.keyTag p) (vec (.key p))])))
  (let [port-a (free-port) port-b (free-port)
        spaces [{:name "cues" :settle "150ms"} {:name "votes" :settle "150ms"}]
        caps {:capabilities {:vote {:space "votes"}}}
        a (fleet/start (config "clj-tagpropose" port-a nil spaces caps))
        b (fleet/start (config "clj-tagpropose" port-b port-a spaces caps))
        cues (fleet/space b "cues")]
    (try
      (let [bound (agents/bind! a (->TagLead))]
        (space/write! cues Fixtures$TaskEntry {:topic "disk full" :priority 1} {:lease "10m" :tags {:case "c-9" :kind "alert"}})
        (space/write! cues Fixtures$TaskEntry {:topic "just info" :priority 1} {:lease "10m" :tags {:case "c-10" :kind "info"}})
        (space/write! cues Fixtures$TaskEntry {:topic "no case" :priority 1} {:lease "10m"
                                                                             :tags {:kind "alert"}})
        (is (await-truthy 30000 #(vote/proposal a "sig:c-9")))
        (is (= {:id "sig:c-9" :question "disk full" :options ["ack" "ignore"] :quorum 1} (vote/proposal a "sig:c-9")))
        (is (await-truthy 30000 #(vote/proposal b "sig:c-9")) "the proposal replicates")
        (Thread/sleep 1500)
        (is (= 1 (count (space/read-all (fleet/space a "votes") VoteCapability$Proposal {:limit 10})))
            "an info cue is filtered out, and a cue with no case tag has no key")
        (agents/unbind! bound))
      (finally (fleet/stop! b) (fleet/stop! a)))))
