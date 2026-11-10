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

(ns agentspaces.app-card-host-test
  "SPEC §6.1a.4 (v0.1.28, ISSUE-AppHost; v0.1.32 sign-on values, AppCard
  origin and formats, 2026-11-09): the host group of a published AppCard,
  built from a map, appended last on the card, byte for byte the Java vector;
  `:sign-on` is idp (the default) or none and the record refuses tenant; a
  card without the host keeps its bytes; the record refuses what the spec
  refuses."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.agents :as agents])
  (:import (ai.badmonkey.agentspaces.api.ad AppCard AppCard$Ui)
           (ai.badmonkey.agentspaces.common.codec CborCodec)
           (ai.badmonkey.agentspaces.common.id GroupId PeerId)
           (java.time Duration Instant)
           (java.util HexFormat)))

(def ^:private at (Instant/parse "2026-11-06T09:00:00Z"))

(defn- plain-card []
  (AppCard. "aspace://zApps/app/zPeerA/support-chat" (PeerId/of "zPeerA") (GroupId/of "zApps") at
            (Duration/ofMinutes 15) "Support chat" "fed" "a2ui" "support" ["zApps/conversations"] ["ask"] "" "" "" "1"
            ["conversations"] (AppCard$Ui. "a2ui" "v0.9.1" ["example-widgets" "agentspaces-viz"] "summariser" "views" ["chat-1"])))

(deftest the-host-is-appended-last-and-matches-the-java-vector
  (let [codec (CborCodec/defaultCodec)
        host (agents/app-card-host {:url "https://chat.example.org" :port 7801 :host-agent "zHostPeer" :path "/"
                                    :sign-on "idp" :published-by "summariser" :published-at at :status "serving"})
        published (.withHost (plain-card) host)
        bytes (.toBytes codec published)
        decoded (.fromBytes codec bytes java.util.Map)]
    (testing "the map builder makes the Java record"
      (is (= {:url "https://chat.example.org" :port 7801 :host-agent "zHostPeer" :path "/" :sign-on "idp"
              :published-by "summariser" :published-at at :status "serving"} (agents/host->map host))))
    (testing "host is the last key, and the bytes are the api module's pinned vector"
      (is (= "host" (last (keys decoded))))
      (let [pinned (slurp "../agentspaces/agentspaces-api/src/test/resources/vectors/app_card_host_cbor.hex")]
        (is (= (clojure.string/trim pinned) (.formatHex (HexFormat/of) bytes)))))
    (testing "a card without the host keeps its bytes"
      (is (= (seq (.toBytes codec (plain-card))) (seq (.toBytes codec (.withHost published nil))))))
    (testing "the record refuses what the spec refuses"
      (is (thrown? IllegalArgumentException (agents/app-card-host {:url ""})))
      (is (thrown? IllegalArgumentException (agents/app-card-host {:url "http://x" :port 70000})))
      (is (thrown? IllegalArgumentException (agents/app-card-host {:url "http://x" :sign-on "password"})))
      (is (thrown? IllegalArgumentException (agents/app-card-host {:url "http://x" :sign-on "tenant"})) "the retired word")
      (is (= "idp" (:sign-on (agents/host->map (agents/app-card-host {:url "http://x"})))) "idp by default")
      (is (thrown? IllegalArgumentException (agents/app-card-host {:url "http://x" :status "gone"})))
      (is (= "/" (:path (agents/host->map (agents/app-card-host {:url "http://127.0.0.1:7801" :port 7801}))))))))
