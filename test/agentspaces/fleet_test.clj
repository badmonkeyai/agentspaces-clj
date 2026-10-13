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

(ns agentspaces.fleet-test
  "Clojure peers as real fleets over loopback: config maps in, replicated
  round-trips out, and every security and admission key of `fleet/start`
  reachable from the map: founding and joining by GroupId, allowlist and
  credential admission, FRESH reads, the mtls profile's authorizer, group
  content keys, required attestation, and tag shards."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.api.spi Authorizer Authorizer$Operation)
           (ai.badmonkey.agentspaces.common.crypto GroupKey)
           (ai.badmonkey.agentspaces.common.id GroupId)
           (ai.badmonkey.agentspaces.discovery DiscoveryService)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.peering.gossip ReconcilableState)
           (ai.badmonkey.agentspaces.peering.membership GroupScopedAuthorizer MembershipAuthorizer)
           (ai.badmonkey.agentspaces.peering.node GroupRuntime PeerNode
                                                  SignedGroupAdvertisement)
           (ai.badmonkey.agentspaces.peering.transport TlsTcpTransport)
           (ai.badmonkey.agentspaces.space SpaceAdmissionException)
           (ai.badmonkey.agentspaces.space.replicated ReplicatedSpace)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry)
           (clojure.lang ExceptionInfo)
           (java.net ServerSocket)
           (java.util Base64)))

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

(defn- group-config [group-name seed-port group-extra]
  (merge {:name group-name
          :founding (str group-name "-v1")
          :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
          :membership {:ttl "30s" :probe "2s" :indirect 2}
          :spaces [{:name "work" :settle "150ms"}]}
         group-extra))

(defn- fleet-config
  ([group-name port seed-port extra] (fleet-config group-name port seed-port extra {}))
  ([group-name port seed-port extra group-extra]
   (merge {:bind (str "127.0.0.1:" port)
           :tick "200ms"
           :group (group-config group-name seed-port group-extra)}
          extra)))

(defn- admitted?
  "Whether a local write is admitted right now (a late credential makes
  the answer change over time)."
  [s]
  (try (space/write! s Fixtures$TaskEntry {:topic "probe" :priority 0} {:lease "1m"})
       true
       (catch SpaceAdmissionException _ false)))

(deftest a-two-peer-clojure-fleet-round-trips-work-over-tcp
  (let [port-a (free-port)
        port-b (free-port)
        a (fleet/start (fleet-config "clj-fleet" port-a nil {}))
        b (fleet/start (fleet-config "clj-fleet" port-b port-a {}))]
    (try
      (Thread/sleep 1500)
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "replicate me" :priority 5} {:lease "10m"})

      (testing "the other peer takes the task off the shared space"
        (let [task (space/take! (fleet/space b "work") Fixtures$TaskEntry
                                {:lease "5m" :timeout "20s"})]
          (is (some? task))
          (is (= "replicate me" (:topic task)))
          (space/complete! (fleet/space b "work") task Fixtures$FindingEntry
                           {:topic (:topic task) :summary "done by b"}
                           {:lease "1h"})))

      (testing "and the completion's result replicates back"
        (let [finding (space/read (fleet/space a "work") Fixtures$FindingEntry
                                  {:timeout "20s"})]
          (is (= {:topic "replicate me" :summary "done by b"} finding))))

      (testing "the system map carries the starter's per-group plumbing"
        (is (some? (:blocks a)))
        (is (instance? GroupScopedAuthorizer (:authorizer a)))
        (is (instance? MembershipAuthorizer (.inner ^GroupScopedAuthorizer (:authorizer a))))
        (is (= :dev-local (:profile a)))
        (is (= :session (:channel-auth a))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest attested-tls-fleets-configure-from-the-map-and-elide-signatures
  (let [port-a (free-port)
        port-b (free-port)
        tls {:transport :tls :channel-auth :attested}
        a (fleet/start (fleet-config "clj-tls-fleet" port-a nil tls))
        b (fleet/start (fleet-config "clj-tls-fleet" port-b port-a tls))]
    (try
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "over tls" :priority 1} {:lease "10m"})
      (is (some? (space/read (fleet/space b "work") Fixtures$TaskEntry
                             {:timeout "20s"})))
      (testing "the channels negotiated bare frames"
        (is (await-truthy 10000
                          #(pos? (+ (.bareFramesSent ^PeerNode (:node a))
                                    (.bareFramesSent ^PeerNode (:node b)))))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest a-founder-serves-its-founding-document-to-a-peer-joining-by-group-id
  (let [port-a (free-port)
        port-b (free-port)
        a (fleet/start (fleet-config "founded" port-a nil {}
                                     {:found true :founding nil :advertise true}))
        b (fleet/start (fleet-config "founded" port-b port-a {}
                                     {:join (fleet/group-uri a) :founding nil
                                      :join-timeout "15s"}))]
    (try
      (testing "the founder joined with a self-certifying advertisement"
        (is (instance? SignedGroupAdvertisement (:founding a)))
        (is (str/starts-with? (fleet/group-uri a) "aspace://"))
        (is (= (fleet/group-uri a) (fleet/group-uri b)))
        (is (instance? DiscoveryService (:discovery a)))
        (is (.isPresent (.advertisement ^ReplicatedSpace (fleet/space a "work")))))

      (testing "the joiner, knowing only the id, replicates the founder's writes"
        (space/write! (fleet/space a "work") Fixtures$TaskEntry
                      {:topic "by id" :priority 1} {:lease "10m"})
        (is (= {:topic "by id" :priority 1}
               (space/read (fleet/space b "work") Fixtures$TaskEntry {:timeout "20s"}))))

      (testing ":join excludes :founding and :found, and needs seeds, before binding"
        (is (thrown? ExceptionInfo
                     (fleet/start {:group {:name "x" :join (fleet/group-uri a)
                                           :founding "x-v1" :seeds ["127.0.0.1:1"]}})))
        (is (thrown? ExceptionInfo
                     (fleet/start {:group {:name "x" :join (fleet/group-uri a)}}))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest an-allowlist-space-refuses-an-unlisted-writer-and-admits-a-listed-one
  (let [port-a (free-port)
        port-b (free-port)
        id-a (PeerIdentity/generate)
        spaces [{:name "control" :settle "150ms" :admission :allowlist
                 :allowed-agents [(.encoded (.agent id-a "clj"))]}]
        a (fleet/start (fleet-config "allow" port-a nil {:identity id-a} {:spaces spaces}))
        b (fleet/start (fleet-config "allow" port-b port-a {} {:spaces spaces}))]
    (try
      (is (= (.agent id-a "clj") (fleet/writer a)))
      (testing "the unlisted local writer is refused"
        (is (thrown? SpaceAdmissionException
                     (space/write! (fleet/space b "control") Fixtures$TaskEntry
                                   {:topic "unlisted" :priority 1}))))
      (testing "the listed writer's entry replicates, and reads stay open"
        (space/write! (fleet/space a "control") Fixtures$TaskEntry
                      {:topic "listed" :priority 1} {:lease "10m"})
        (is (= {:topic "listed" :priority 1}
               (space/read (fleet/space b "control") Fixtures$TaskEntry {:timeout "20s"}))))
      (testing "an empty allowlist fails fast"
        (is (thrown? ExceptionInfo
                     (fleet/start {:group {:name "x" :spaces [{:name "s" :admission :allowlist}]}}))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest a-credential-space-admits-whom-the-issuer-grants
  (let [port-a (free-port)
        port-b (free-port)
        id-a (PeerIdentity/generate)
        a (fleet/start (fleet-config "cred" port-a nil {:identity id-a}
                                     {:spaces [{:name "partner" :settle "150ms"
                                                :admission :credential}]}))
        b (fleet/start (fleet-config "cred" port-b port-a {}
                                     {:spaces [{:name "partner" :settle "150ms"
                                                :admission :credential
                                                :credential-issuer (.peerId id-a)}]}))
        partner-a (fleet/space a "partner")
        partner-b (fleet/space b "partner")]
    (try
      (Thread/sleep 1000)
      (testing "an ungranted agent is refused locally, and only the issuer grants"
        (is (thrown? SpaceAdmissionException
                     (space/write! partner-b Fixtures$TaskEntry {:topic "ungranted" :priority 1})))
        (is (thrown? SpaceAdmissionException
                     (space/grant! partner-b (fleet/writer b) #{:write} "1h"))))

      (testing "the grantee writes once the credential arrives, and its write replicates"
        (space/grant! partner-a (fleet/writer b) #{:write :take} "1h")
        (is (await-truthy 20000 #(admitted? partner-b)))
        (space/write! partner-b Fixtures$TaskEntry {:topic "granted" :priority 2} {:lease "10m"})
        (is (= {:topic "granted" :priority 2}
               (space/read partner-a Fixtures$TaskEntry {:where {:topic "granted"} :timeout "20s"}))))

      (testing "revoke! withdraws the credential fleet-wide"
        (space/revoke! partner-a (fleet/writer b))
        (is (await-truthy 20000 #(not (admitted? partner-b)))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(defn- pull-counter [counter]
  (reify ReconcilableState
    ;; Never equal between two members: since v0.1.17 a round opens with digest hashes and
    ;; asks for no delta on a stream whose hashes match.
    (digest [_] (.getBytes (str (java.util.UUID/randomUUID))))
    (deltaFor [_ _] (swap! counter inc) (byte-array 0))
    (applyDelta [_ _])))

(deftest a-fresh-read-on-a-late-joiner-pulls-the-seed-before-matching
  (let [port-a (free-port)
        port-b (free-port)
        a (fleet/start (fleet-config "fresh" port-a nil {}))]
    (try
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "before b" :priority 1} {:lease "10m"})
      (Thread/sleep 1000)
      ;; The joiner never ticks, so nothing but its own FRESH read can pull.
      (let [b (fleet/start (fleet-config "fresh" port-b port-a {:tick "1h"}))
            answered-by-a (atom 0)
            answered-by-b (atom 0)]
        (try
          (.reconcile (.gossip ^GroupRuntime (:runtime a)) "pull-counter"
                      (pull-counter answered-by-a))
          (.reconcile (.gossip ^GroupRuntime (:runtime b)) "pull-counter"
                      (pull-counter answered-by-b))
          (Thread/sleep 1500) ; the seed's ticks introduce it to the joiner
          (let [before @answered-by-a]
            (is (= {:topic "before b" :priority 1}
                   (space/read (fleet/space b "work") Fixtures$TaskEntry
                               {:fresh true :timeout "20s"})))
            (testing "the seed answered the joiner's pull"
              (is (await-truthy 5000 #(> @answered-by-a before))))
            (testing "read-all takes the hint too"
              (is (= [{:topic "before b" :priority 1}]
                     (space/read-all (fleet/space b "work") Fixtures$TaskEntry {:fresh true})))))
          (finally
            (fleet/stop! b))))
      (finally
        (fleet/stop! a)))))

(deftest the-mtls-profile-selects-tls-attestation-and-a-membership-authorizer
  (let [port-a (free-port)
        port-b (free-port)
        id-a (PeerIdentity/generate)
        id-b (PeerIdentity/generate)
        security {:profile :mtls :grants {:directive-issuer [(.peerId id-a)]}}
        a (fleet/start (fleet-config "mtls" port-a nil {:identity id-a :security security}))
        b (fleet/start (fleet-config "mtls" port-b port-a {:identity id-b :security security}))]
    (try
      (testing "the profile picked the posture"
        (is (= :mtls (:profile a)))
        (is (instance? TlsTcpTransport (:transport a)))
        (is (= :attested (:channel-auth a)))
        (is (instance? GroupScopedAuthorizer (:authorizer a)))
        (is (instance? MembershipAuthorizer (.inner ^GroupScopedAuthorizer (:authorizer a)))))

      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "over mtls" :priority 1} {:lease "10m"})
      (is (some? (space/read (fleet/space b "work") Fixtures$TaskEntry {:timeout "20s"})))

      (testing "the authorizer permits members, honours grants, and refuses a stranger"
        (let [^Authorizer authorizer (:authorizer a)
              group-id (.value ^GroupId (.id ^GroupRuntime (:runtime a)))]
          (is (await-truthy 10000
                            #(.permits authorizer (.peerId id-b)
                                       Authorizer$Operation/SPACE_WRITE "work")))
          (is (.permits authorizer (.peerId id-a) Authorizer$Operation/DIRECTIVE_ISSUER group-id))
          (is (not (.permits authorizer (.peerId id-b)
                             Authorizer$Operation/DIRECTIVE_ISSUER group-id)))
          (is (not (.permits authorizer (.peerId (PeerIdentity/generate))
                             Authorizer$Operation/SPACE_WRITE "work")))))

      (testing "the OIDC profiles need an Authorizer instance"
        (is (thrown? ExceptionInfo
                     (fleet/start {:security {:profile :mtls-oidc}
                                   :group {:name "x"}}))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest a-content-key-seals-payloads-from-a-keyless-member
  (let [port-a (free-port)
        port-b (free-port)
        port-c (free-port)
        raw (.rawBytes (GroupKey/generate))
        b64 (.encodeToString (Base64/getEncoder) raw)
        a (fleet/start (fleet-config "sealed" port-a nil {} {:content-key raw}))
        b (fleet/start (fleet-config "sealed" port-b port-a {} {:content-key b64}))
        c (fleet/start (fleet-config "sealed" port-c port-a {}))]
    (try
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "secret" :priority 9} {:lease "10m"})
      (testing "a keyed member reads the entry"
        (is (= {:topic "secret" :priority 9}
               (space/read (fleet/space b "work") Fixtures$TaskEntry {:timeout "20s"}))))
      (testing "a keyless member stores the record but cannot read it"
        (is (await-truthy 20000 #(pos? (.knownEntries ^ReplicatedSpace (fleet/space c "work")))))
        (is (empty? (space/read-all (fleet/space c "work") Fixtures$TaskEntry))))
      (finally
        (fleet/stop! c)
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest required-attestation-over-tls-still-converges
  (let [port-a (free-port)
        port-b (free-port)
        strict {:transport :tls :channel-auth :attested :require-attestation true}
        a (fleet/start (fleet-config "strict" port-a nil strict))
        b (fleet/start (fleet-config "strict" port-b port-a strict))]
    (try
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "attested" :priority 1} {:lease "10m"})
      (is (= {:topic "attested" :priority 1}
             (space/read (fleet/space b "work") Fixtures$TaskEntry {:timeout "20s"})))
      (is (await-truthy 10000
                        #(pos? (+ (.bareFramesSent ^PeerNode (:node a))
                                  (.bareFramesSent ^PeerNode (:node b))))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest tags-travel-the-wire-and-a-tag-shard-keeps-only-its-own
  (let [port-a (free-port)
        port-b (free-port)
        a (fleet/start (fleet-config "tags" port-a nil {}))
        b (fleet/start (fleet-config "tags" port-b port-a {}
                                     {:spaces [{:name "work" :settle "150ms"
                                                :tag-shard {:region "eu"}}]}))]
    (try
      (Thread/sleep 1000)
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "eu task" :priority 1} {:lease "10m" :tags {:region "eu"}})
      (space/write! (fleet/space a "work") Fixtures$TaskEntry
                    {:topic "us task" :priority 2} {:lease "10m" :tags {:region "us"}})
      (is (= {:topic "eu task" :priority 1}
             (space/read (fleet/space b "work") Fixtures$TaskEntry {:timeout "20s"})))
      (Thread/sleep 1000)
      (is (= [{:topic "eu task" :priority 1}]
             (space/read-all (fleet/space b "work") Fixtures$TaskEntry)))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))
