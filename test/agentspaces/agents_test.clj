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

(ns agentspaces.agents-test
  "v0.1.13 from the config map (TODO-9-10-11 Phase 5): subordinate agent keys
  across two Clojure peers, stored AGENT_ATTESTED, through `space/as` or the
  verbs' `{:as agent}` option; an agent revoked by its own peer refused
  fleet-wide, and a late joiner never adopting a compromised agent's history;
  `:effective-from` and `:x509-leaf` revocations; agent keys persisted across a
  restart; and a content-key rotation every member follows, including one that
  sets only `:content-key`."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.api.ad CredentialRevocation$Target)
           (ai.badmonkey.agentspaces.api.space Space$Attestation Template)
           (ai.badmonkey.agentspaces.common.crypto Digests GroupKey GroupKeyRing)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.space.replicated ReplicatedSpace)
           (ai.badmonkey.agentspaces.peering.node GroupRuntime)
           (ai.badmonkey.agentspaces.test Fixtures$FindingEntry Fixtures$TaskEntry TestCa)
           (clojure.lang ExceptionInfo)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration Instant)
           (java.util Arrays Base64)
           (java.util.logging Handler Logger)))

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

(defn- config [group-name port seed-port extra group-extra]
  (merge {:bind (str "127.0.0.1:" port)
          :tick "200ms"
          :group (merge {:name group-name :founding (str group-name "-v1")
                         :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
                         :spaces [{:name "work" :settle "150ms"}]}
                        group-extra)}
         extra))

(defn- temp-dir []
  (str (Files/createTempDirectory "clj-agents" (make-array FileAttribute 0))))

(deftest subordinate-agents-write-take-and-complete-as-themselves
  (let [port-a (free-port)
        a (fleet/start (config "clj-agents" port-a nil {:agent-keys :subordinate} {}))
        b (fleet/start (config "clj-agents" (free-port) port-a {:agent-keys :subordinate} {}))]
    (try
      (let [planner (fleet/agent a "planner")
            worker (fleet/agent b "worker")]
        (is (.isSubordinate planner))
        (space/write! (space/as (fleet/space a "work") planner) Fixtures$TaskEntry
                      {:topic "attested" :priority 1} {:lease "10m"})
        (testing "the other peer stores the agent's write AGENT_ATTESTED"
          (is (await-truthy 20000
                            #(seq (.readAllIssued ^ReplicatedSpace (fleet/space b "work")
                                                  (Template/of Fixtures$TaskEntry) 10))))
          (let [issued (first (.readAllIssued ^ReplicatedSpace (fleet/space b "work")
                                              (Template/of Fixtures$TaskEntry) 10))]
            (is (= Space$Attestation/AGENT_ATTESTED (.attestation issued)))
            (is (= (.id planner) (.issuer issued)))))
        (testing "an agent of the other peer takes and completes it as itself"
          (let [as-worker (space/as (fleet/space b "work") worker)
                task (space/take! as-worker Fixtures$TaskEntry {:lease "5m" :timeout "20s"})]
            (is (= "attested" (:topic task)))
            (space/complete! as-worker task Fixtures$FindingEntry
                             {:topic "attested" :summary "by an agent"} {:lease "1h"})
            (is (= {:topic "attested" :summary "by an agent"}
                   (space/read (fleet/space a "work") Fixtures$FindingEntry {:timeout "20s"}))))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(defn- issued [system type]
  (.readAllIssued ^ReplicatedSpace (fleet/space system "work") (Template/of type) 10))

(deftest the-as-option-acts-as-the-agent
  (let [port-a (free-port)
        a (fleet/start (config "clj-as-opt" port-a nil {:agent-keys :subordinate} {}))
        b (fleet/start (config "clj-as-opt" (free-port) port-a {:agent-keys :subordinate} {}))]
    (try
      (let [planner (fleet/agent a "planner")
            worker (fleet/agent b "worker")]
        (space/write! (fleet/space a "work") Fixtures$TaskEntry {:topic "opt" :priority 1}
                      {:lease "10m" :as planner})
        (testing "write! {:as agent} is stored AGENT_ATTESTED with that agent as issuer"
          (is (await-truthy 20000 #(seq (issued b Fixtures$TaskEntry))))
          (let [entry (first (issued b Fixtures$TaskEntry))]
            (is (= Space$Attestation/AGENT_ATTESTED (.attestation entry)))
            (is (= (.id planner) (.issuer entry)))))
        (testing "take! and complete! {:as agent} act as that agent"
          (let [task (space/take! (fleet/space b "work") Fixtures$TaskEntry
                                  {:lease "5m" :timeout "20s" :as worker})]
            (is (= "opt" (:topic task)))
            (space/complete! (fleet/space b "work") task Fixtures$FindingEntry
                             {:topic "opt" :summary "as worker"} {:lease "1h" :as worker})
            (is (await-truthy 20000 #(seq (issued a Fixtures$FindingEntry))))
            (let [finding (first (issued a Fixtures$FindingEntry))]
              (is (= Space$Attestation/AGENT_ATTESTED (.attestation finding)))
              (is (= (.id worker) (.issuer finding)))))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest revoke-takes-an-effective-time-and-x509-leaves
  (let [a (fleet/start (config "clj-revoke-kinds" (free-port) nil {:agent-keys :subordinate}
                               {:found true}))]
    (try
      (let [retired (fleet/agent a "retired")
            since (.minusSeconds (Instant/now) 60)]
        (testing ":effective-from reaches the revocation, as an Instant or ISO string"
          (let [r (fleet/revoke! a {:kind :agent :agent (str (.encoded (.id retired)))
                                    :reason :retired :effective-from since})]
            (is (= since (.effectiveFrom r))))
          (let [keyed (fleet/agent a "keyed")
                r (fleet/revoke! a {:kind :agent-key :agent (str (.encoded (.id keyed)))
                                    :key (.publicKey keyed) :reason :superseded
                                    :effective-from (str since)})]
            (is (= since (.effectiveFrom r)))
            (is (Arrays/equals (Digests/sha256 (.publicKey keyed))
                               (.keyFingerprint (.target r))))))
        (testing ":x509-leaf names the leaf by issuer DN, hex serial, and DER fingerprint"
          (let [leaf (.certificate (.issue (TestCa/create) (.rawPublicKey (PeerIdentity/generate))))
                fingerprint (Digests/sha256 (.getEncoded leaf))
                r (fleet/revoke! a {:kind :x509-leaf :certificate leaf :reason :key-compromise})
                target (.target r)]
            (is (= CredentialRevocation$Target/X509_LEAF (.kind target)))
            (is (= (.getName (.getIssuerX500Principal leaf)) (.x509Issuer target)))
            (is (= (.toString (.getSerialNumber leaf) 16) (.x509Serial target)))
            (is (Arrays/equals fingerprint (.certificateFingerprint target)))
            (is (.leafRevoked (.credentialRevocations ^GroupRuntime (:runtime a)) fingerprint))))
        (is (thrown? ExceptionInfo (fleet/revoke! a {:kind :x509-leaf :certificate "nope"}))))
      (finally
        (fleet/stop! a)))))

(deftest a-late-joiner-never-adopts-a-compromised-agents-history
  (let [port-a (free-port)
        a (fleet/start (config "clj-late" port-a nil {:agent-keys :subordinate} {}))]
    (try
      (let [rogue (fleet/agent a "rogue")
            honest (fleet/agent a "honest")
            work (fleet/space a "work")]
        (space/write! work Fixtures$TaskEntry {:topic "rogue" :priority 1} {:as rogue})
        (space/write! work Fixtures$TaskEntry {:topic "honest" :priority 2} {:as honest})
        (is (some? (fleet/revoke! a {:kind :agent :agent (str (.encoded (.id rogue)))
                                     :reason :key-compromise})))
        (let [c (fleet/start (config "clj-late" (free-port) port-a {} {}))]
          (try
            (is (await-truthy 20000 #(some (fn [e] (= "honest" (.topic ^Fixtures$TaskEntry (.entry e))))
                                           (issued c Fixtures$TaskEntry)))
                "the late joiner syncs the honest agent's entry")
            (is (await-truthy 10000 #(fleet/revoked? c (.encoded (.id rogue)))))
            (Thread/sleep 1000)
            (is (some #(= (.id rogue) (.issuer %)) (issued a Fixtures$TaskEntry))
                "the writing peer still holds the entry")
            (is (not-any? #(= (.id rogue) (.issuer %)) (issued c Fixtures$TaskEntry))
                "the late joiner refuses the compromised agent's entry at merge")
            (finally (fleet/stop! c)))))
      (finally
        (fleet/stop! a)))))

(deftest an-agent-revoked-by-its-own-peer-is-refused-fleet-wide
  (let [port-a (free-port)
        a (fleet/start (config "clj-revoke" port-a nil {:agent-keys :subordinate} {}))
        b (fleet/start (config "clj-revoke" (free-port) port-a {:agent-keys :subordinate} {}))]
    (try
      (Thread/sleep 1500)
      (let [rogue (fleet/agent a "rogue")]
        (is (nil? (fleet/revoke! b {:kind :agent :agent (str (.id rogue)) :reason :retired}))
            "a stranger's peer has no authority over the agent")
        (is (some? (fleet/revoke! a {:kind :agent :agent (str (.encoded (.id rogue)))
                                     :reason :key-compromise})))
        (is (await-truthy 10000 #(fleet/revoked? b (.encoded (.id rogue)))))
        (is (not (fleet/revoked? b (.encoded (.id (fleet/agent a "honest"))))))
        (is (thrown? IllegalStateException
                     (space/write! (space/as (fleet/space a "work") rogue) Fixtures$TaskEntry
                                   {:topic "refused" :priority 1} {:lease "10m"})))
        (is (thrown? ExceptionInfo (fleet/revoke! a {:kind :agent :agent "x/y" :reason :because}))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))

(deftest agent-keys-persist-in-the-agent-keystore-across-a-restart
  (let [dir (temp-dir)
        cfg #(config "clj-keys" (free-port) nil
                     {:keystore (str dir "/peer") :agent-keys :subordinate
                      :agent-keystore (str dir "/agents")} {})
        first-sys (fleet/start (cfg))
        key-before (vec (.publicKey (fleet/agent first-sys "planner")))]
    (fleet/stop! first-sys)
    (let [second-sys (fleet/start (cfg))]
      (try
        (is (= (fleet/peer-id first-sys) (fleet/peer-id second-sys)))
        (is (= key-before (vec (.publicKey (fleet/agent second-sys "planner")))))
        (is (.isPresent (.encryptionPublicKey (fleet/agent second-sys "planner")))
            "the agent's X25519 key is certified too")
        (finally (fleet/stop! second-sys)))))
  (testing "a keystore under peer-signed agents fails fast"
    (is (thrown? ExceptionInfo
                 (fleet/start (config "clj-keys-bad" (free-port) nil
                                      {:agent-keystore (temp-dir)} {}))))))

(deftest every-member-follows-a-content-key-rotation
  (let [port-a (free-port)
        id-a (PeerIdentity/generate)
        key (.encodeToString (Base64/getEncoder) (.rawBytes (GroupKey/generate)))
        grants {:security {:profile :dev-local :grants {:key-rotator [(.peerId id-a)]}}}
        a (fleet/start (config "clj-rotate" port-a nil (assoc grants :identity id-a)
                               {:content-key key :content-key-rotation {:writer-grace "1h"}}))
        ;; b sets only :content-key: it follows rotations all the same.
        b (fleet/start (config "clj-rotate" (free-port) port-a grants {:content-key key}))]
    (try
      (Thread/sleep 1500)
      (is (identical? (:key-ring a) (.orElseThrow (.keyRing ^ReplicatedSpace (fleet/space a "work")))))
      (is (some? (:key-wrap b)) "key-wrap runs on a :content-key alone")
      (is (= 1 (fleet/rotate-content-key! a "0s")))
      (is (await-truthy 20000 #(.contains (.epochs ^GroupKeyRing (:key-ring b)) 1)))
      (space/write! (fleet/space b "work") Fixtures$TaskEntry {:topic "epoch one" :priority 1}
                    {:lease "10m"})
      (is (= "epoch one" (:topic (space/read (fleet/space a "work") Fixtures$TaskEntry
                                             {:timeout "20s"}))))
      (is (thrown? Exception (fleet/rotate-content-key! b "0s")) "b holds no :key-rotator grant")
      (testing "the default cutover is two gossip periods plus 30s, and a keystore-less
               rotation warns"
        (let [period (.period (.gossip (.advertisement ^GroupRuntime (:runtime a))))
              floor (.plus (Instant/now) (.plusSeconds (.multipliedBy ^Duration period 2) 30))
              warnings (atom [])
              logger (Logger/getLogger "agentspaces.fleet")
              handler (proxy [Handler] []
                        (publish [r] (swap! warnings conj (.getMessage ^java.util.logging.LogRecord r)))
                        (flush [])
                        (close []))]
          (.addHandler logger handler)
          (try
            (is (= 2 (fleet/rotate-content-key! a)))
            (finally (.removeHandler logger handler)))
          (is (not (.isBefore (.orElseThrow (.cutover ^GroupKeyRing (:key-ring a) 2)) floor)))
          (is (some #(re-find #"no :keystore" %) @warnings))))
      (testing "rotation without a content key fails at start"
        (is (thrown? ExceptionInfo
                     (fleet/start (config "clj-rotate-bad" (free-port) nil {}
                                          {:content-key-rotation {:rotate-every "1h"}})))))
      (finally
        (fleet/stop! b)
        (fleet/stop! a)))))
