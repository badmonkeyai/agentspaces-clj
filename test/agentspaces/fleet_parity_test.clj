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

(ns agentspaces.fleet-parity-test
  "What the 0.5.0 parity review (reviews/review-110226-deadcode-parity.md
  §4.2, C1 to C5) found the bindings lacking against the Spring starter,
  each over real loopback peers: the `:vote` grant enforced on a cast and
  `on-decision!` outliving its lease; the card refresh re-publishing a bound
  card; a join by id through a `:locators` locator that answers late, the
  refusal naming its three remedies, and the FoundingTimeoutException data
  with the text-founded seed's advice; an `:invite` group admitting a
  `:join-credential` and refusing a stranger; and the `:membership` alias."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space]
            [agentspaces.vote :as vote])
  (:import (ai.badmonkey.agentspaces.api.ad AgentCard PeerAdvertisement$Endpoint)
           (ai.badmonkey.agentspaces.api.spi BeaconHint BeaconLocator BeaconRequest)
           (ai.badmonkey.agentspaces.common.id GroupId)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.peering.membership GroupMembership)
           (ai.badmonkey.agentspaces.peering.node GroupRuntime JoinCredentials)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (clojure.lang ExceptionInfo)
           (java.net ServerSocket)
           (java.time Instant)
           (java.util.concurrent ScheduledExecutorService)))

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

(defn- config
  ([group port seed-port extra] (config group port seed-port extra {}))
  ([group port seed-port extra group-extra]
   (merge {:bind (str "127.0.0.1:" port)
           :tick "200ms"
           :group (merge {:name group
                          :founding (str group "-v1")
                          :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
                          :membership-tuning {:ttl "30s" :probe "2s" :indirect 2}
                          :spaces [{:name "work" :settle "150ms"}]}
                         group-extra)}
          extra)))

(defn- member? [system peer-id]
  (.isPresent (.member ^GroupMembership (.membership ^GroupRuntime (:runtime system)) peer-id)))

;; ------------------------------------------------------------- C1 and C5: vote

(deftest a-peer-without-the-vote-grant-cannot-cast-and-on-decision-outlives-its-lease
  (let [port-a (free-port) port-b (free-port)
        id-a (PeerIdentity/generate) id-b (PeerIdentity/generate)
        ;; Every member carries the same grants, as the starter requires: only a may vote.
        grants {:security {:grants {:vote [(.peerId id-a)]}}
                :capabilities {:vote {:space "votes"}}}
        spaces {:spaces [{:name "votes" :settle "150ms"}]}
        a (fleet/start (config "clj-vote-grant" port-a nil (assoc grants :identity id-a) spaces))
        b (fleet/start (config "clj-vote-grant" port-b port-a (assoc grants :identity id-b) spaces))]
    (try
      (is (await-truthy 10000 #(member? a (.peerId id-b))))
      (vote/propose! a {:id "release" :question "ship?" :options ["yes" "no"] :quorum 1})
      (is (await-truthy 10000 #(vote/proposal b "release")))
      (testing "the ungranted peer's cast is refused before it writes a ballot every tally would drop"
        (is (thrown-with-msg? IllegalStateException #"not permitted VOTE"
                              (vote/cast! b "release" "yes"))))
      (testing "on-decision! renews its subscription: a 1s lease still fires after 3s"
        (let [decided (promise)
              sub (vote/on-decision! a #(= "release" %) #(deliver decided %) {:lease "1s"})]
          (Thread/sleep 3000)
          (vote/cast! a "release" "yes")
          (is (= "yes" (:winner (deref decided 20000 nil))))
          (is (await-truthy 20000 #(= 1 (get (vote/tally b "release") "yes")))
              "the granted ballot counts at both replicas")
          (.close sub)))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

;; ------------------------------------------------------------- C2: card refresh

(defagent Clerk
  {:description "Files tasks" :goals ["file"]}
  (take file [^Fixtures$TaskEntry t] {:space "work" :poll-timeout "200ms"}
    ^Fixtures$FindingEntry
    (Fixtures$FindingEntry. (:topic t) "filed")))

(defn- card-issued ^Instant [system]
  (some-> (first (.find (:discovery system) AgentCard
                        (reify java.util.function.Predicate (test [_ _] true))))
          (.issued)))

(deftest card-refresh-re-publishes-a-bound-agents-card
  (let [a (fleet/start (config "clj-card-refresh" (free-port) nil {:agents true :card-refresh "200ms"}))
        bound (agents/bind! a (->Clerk))]
    (try
      (is (instance? ScheduledExecutorService (:card-refresher a)))
      (let [first-issued (card-issued a)]
        (is (instance? Instant first-issued) "the bound card is in the discovery cache")
        (testing "the card's issue time advances on the :card-refresh cadence"
          (is (await-truthy 5000 #(let [now (card-issued a)]
                                    (and now (.isAfter now first-issued)))))))
      (agents/unbind! bound)
      (finally
        (fleet/stop! a)
        (is (.isShutdown ^ScheduledExecutorService (:card-refresher a)) "stop! shuts the refresher down")))))

;; ---------------------------------------------------------- C3: locators and chain

(defn- late-locator
  "A BeaconLocator that answers nothing the first time it is asked and the
  given endpoint afterwards, counting its calls."
  [calls port]
  (reify BeaconLocator
    (name [_] "late")
    (locate [_ request]
      (is (instance? BeaconRequest request))
      (if (= 1 (swap! calls inc))
        (BeaconHint/none)
        (BeaconHint/of [(PeerAdvertisement$Endpoint. "tcp" (str "127.0.0.1:" port) 0)] "late[1]")))))

(deftest a-join-by-id-asks-a-locators-entry-again-after-an-empty-first-answer
  (let [port-a (free-port)
        calls (atom 0)
        a (fleet/start (config "located" port-a nil {} {:found true :founding nil :advertise true}))
        bare-id (subs (fleet/group-uri a) (count "aspace://"))
        b (fleet/start (config "located" (free-port) nil {}
                               {:join bare-id :founding nil :seeds []
                                :locators [(late-locator calls port-a)]
                                :bootstrap {:refresh-min "500ms" :trace true}
                                :join-timeout "20s"}))]
    (try
      (testing "the locator was asked more than once and the bare id parsed"
        (is (<= 2 @calls))
        (is (= (fleet/group-uri a) (fleet/group-uri b))))
      (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic "located" :priority 1} {:lease "10m"})
      (is (= {:topic "located" :priority 1}
             (space/read (fleet/space b "work") Fixtures$TaskEntry {:timeout "20s"})))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest a-join-with-nothing-to-ask-is-refused-and-a-timeout-reports-its-counts
  (let [port-a (free-port)
        a (fleet/start (config "text-founded" port-a nil {}))
        uri (fleet/group-uri a)]
    (try
      (testing "the refusal names the three remedies"
        (let [e (is (thrown? ExceptionInfo (fleet/start {:group {:name "x" :join uri}})))
              message (ex-message e)]
          (is (str/includes? message ":seeds"))
          (is (str/includes? message ":locators"))
          (is (str/includes? message ":multicast"))))
      (testing "a seed founded from a text cannot serve a signed founding: the data and the advice"
        (let [e (is (thrown? ExceptionInfo
                             (fleet/start (config "text-founded" (free-port) port-a {}
                                                  {:join uri :founding nil :join-timeout "4s"}))))
              data (ex-data e)]
          (is (instance? ai.badmonkey.agentspaces.peering.node.FoundingTimeoutException (ex-cause e)))
          (is (every? #(contains? data %) [:locates :dialed :connected :message]))
          (is (pos? (:connected data)) "the seed was connected and stayed silent")
          (is (str/includes? (ex-message e) "has no signed founding"))))
      (testing ":locators must hold BeaconLocator instances, and :policy must be :open or :invite"
        (is (thrown? ExceptionInfo (fleet/start {:group {:name "x" :join uri :locators ["127.0.0.1:1"]}})))
        (is (thrown? ExceptionInfo (fleet/start {:group {:name "x" :policy :secret}}))))
      (finally
        (fleet/stop! a)))))

;; ------------------------------------------------- C4: join credentials and policy

(deftest an-invite-group-admits-a-credential-and-refuses-a-stranger
  (let [port-a (free-port)
        id-a (PeerIdentity/generate) id-b (PeerIdentity/generate) id-c (PeerIdentity/generate)
        a (fleet/start (config "invite" port-a nil {:identity id-a} {:policy :invite}))
        group-id (.id ^GroupRuntime (:runtime a))
        credential (JoinCredentials/issue id-a group-id (.peerId id-b))
        b (fleet/start (config "invite" (free-port) port-a {:identity id-b}
                               {:policy :invite :founder (.peerId id-a) :join-credential credential}))
        c (fleet/start (config "invite" (free-port) port-a {:identity id-c}
                               {:policy :invite :founder (.peerId id-a)}))]
    (try
      (is (instance? GroupId group-id))
      (testing "the credentialed peer is admitted"
        (is (await-truthy 15000 #(member? a (.peerId id-b)))))
      (testing "the stranger is not"
        (Thread/sleep 1500)
        (is (not (member? a (.peerId id-c)))))
      (testing "a credential with no founder, and the deprecated :membership alias"
        (is (thrown-with-msg? ExceptionInfo #":founder"
                              (fleet/start {:group {:name "x" :policy :invite :join-credential credential}})))
        (let [d (fleet/start (config "alias" (free-port) nil {}
                                     {:membership {:ttl "10s" :probe "1s" :indirect 1}
                                      :membership-tuning nil}))]
          (try
            (is (some? (:runtime d)))
            (finally (fleet/stop! d)))))
      (finally
        (fleet/stop! c)
        (fleet/stop! b)
        (fleet/stop! a)))))
