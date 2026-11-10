;; Copyright 2026 Bad Monkey, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     https://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns agentspaces.card-spill-test
  "SPEC §6.1a.2, §6.1a.5, and §6.3 (v0.1.32; AppCard origin and formats,
  2026-11-09) from Clojure: `app-card-origin` builds the open origin group;
  the node options set the AppCard level, both cache bounds, and the spill
  fetch bound; a peer with a content swarm spills a large card and
  `resolve-card` completes the stub; semantic matches show origin and formats."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents]
            [agentspaces.fleet :as fleet]
            [agentspaces.semantic :as semantic])
  (:import (ai.badmonkey.agentspaces.api.ad AppCard AppCard$Ui CardSpill)
           (ai.badmonkey.agentspaces.capabilities.semantic SemanticDiscovery$Match)
           (ai.badmonkey.agentspaces.content ContentSpillStore)
           (ai.badmonkey.agentspaces.discovery DiscoveryService)
           (ai.badmonkey.agentspaces.identity AdvertisementSigner)
           (ai.badmonkey.agentspaces.peering.node PeerNode)
           (java.net ServerSocket)
           (java.time Duration Instant)))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- board
  "An a2ui AppCard past the spill threshold through its 64 view names."
  ^AppCard [system]
  (let [peer (.peerId ^ai.badmonkey.agentspaces.identity.PeerIdentity (:identity system))
        group (.id ^ai.badmonkey.agentspaces.peering.node.GroupRuntime (:runtime system))]
    (-> (AppCard. (str "aspace://" (.value group) "/app/" (.value peer) "/board") peer group (Instant/now)
                  (Duration/ofMinutes 15) "board" "fed" "a2ui" "example" [] [] "" "" "" "1"
                  (mapv #(str "view-" % "-" (apply str (repeat 1100 "x"))) (range AppCard/MAX_TERMS))
                  (AppCard$Ui. "a2ui" "v0.9.1" ["example-widgets"] "board" "views" ["board-1"]))
        (.withOrigin (agents/app-card-origin {:system "console" :ref "example-console/board"}))
        (.withFormats ["application/geo+json"]))))

(deftest the-origin-builder-makes-the-java-record
  (let [o (agents/app-card-origin {:system "realm" :ref "example-realm/example-app"
                                   :attrs {"realm.reads" "Item,Order" "realm.format" "1"}})]
    (is (= "realm" (.system o)))
    (is (= "" (.version o)) "no version names none")
    (is (= ["realm.reads" "realm.format"] (vec (keys (.attrs o)))) "the P2 key order")
    (is (nil? (.attrs (agents/app-card-origin {:system "realm" :ref "r" :attrs {}}))) "an empty map is absent")
    (is (thrown? IllegalArgumentException (agents/app-card-origin {:system "Realm Two" :ref "r"})))
    (is (thrown? IllegalArgumentException (agents/app-card-origin {:system "realm" :ref "r" :attrs {"namespace" "x"}})))))

(deftest the-node-options-set-the-level-the-cache-bounds-and-the-fetch-bound
  (let [system (fleet/start {:bind (str "127.0.0.1:" (free-port)) :tick "200ms" :content true
                             :appcard-level 1 :ad-cache-max-entries 100 :ad-cache-max-bytes 1000000
                             :spill-max-fetch-bytes 2048
                             :group {:name "clj-spill-opts" :founding "clj-spill-opts-v1" :seeds []}})]
    (try
      (let [^DiscoveryService discovery (:discovery system)]
        (is (not (contains? (.wireHints ^PeerNode (:node system)) PeerNode/APPCARD_HINT)) "level 1 advertises no hint")
        (is (= 100 (.maxEntries (.cache discovery))))
        (is (= 1000000 (.maxBytes (.cache discovery))))
        (is (= 2048 (.maxSpillFetchBytes discovery)))
        (is (instance? ContentSpillStore (.store (.spiller discovery))) "the swarm takes the parts documents")
        (is (not (.appCardGateOpen discovery)) "a level-1 node writes no gated field"))
      (finally (fleet/stop! system)))))

(deftest a-large-card-spills-through-the-swarm-and-resolve-card-completes-it
  (let [system (fleet/start {:bind (str "127.0.0.1:" (free-port)) :tick "200ms" :content true
                             :group {:name "clj-spill" :founding "clj-spill-v1" :seeds []}})]
    (try
      (let [^DiscoveryService discovery (:discovery system)
            full (board system)
            _ (is (< CardSpill/SPILL_THRESHOLD_BYTES (alength (.toBytes (ai.badmonkey.agentspaces.common.codec.CborCodec/defaultCodec) full))))
            ^AppCard stub (.join (.prepare (.spiller discovery) full (.appCardGateOpen discovery)))]
        (is (some? (.spill stub)) "the gate is open on a peer alone")
        (.publish discovery (.sign (AdvertisementSigner.) stub (:identity system)))
        (let [cached (first (.find discovery AppCard (reify java.util.function.Predicate (test [_ a] (.fromOrigin ^AppCard a "console")))))]
          (is (some? (.spill ^AppCard cached)) "the cache holds the stub")
          (is (= full (agents/resolve-card system cached)))
          (is (= full (agents/resolve-card system full)) "a full card is itself")))
      (finally (fleet/stop! system)))))

(deftest a-semantic-match-shows-origin-and-formats
  (let [match (SemanticDiscovery$Match.
               (-> (AppCard. "aspace://zApps/app/zPeerA/fleet-map" (ai.badmonkey.agentspaces.common.id.PeerId/of "zPeerA")
                             (ai.badmonkey.agentspaces.common.id.GroupId/of "zApps") (Instant/now) (Duration/ofMinutes 15)
                             "fleet-map" "fed" "web" "dispatch" [] [] "" "https://maps.example.org/" "" "1" [] nil)
                   (.withOrigin (agents/app-card-origin {:system "com.example.catalog" :ref "geo/trucks"}))
                   (.withFormats ["application/geo+json"]))
               0.5)
        shown (#'semantic/match->map match)]
    (is (= :app (:kind shown)))
    (is (re-find #"com\.example\.catalog geo/trucks application/geo\+json" (:description shown)))))
