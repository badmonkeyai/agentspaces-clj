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


(ns agentspaces.semantic
  "Semantic discovery (SPEC §8.6) over a `fleet/start` system with
  `:capabilities {:semantic true}`: rank the group's cards by meaning,
  locally or across peers whose embedder matches."
  (:require [agentspaces.core :as core])
  (:import (ai.badmonkey.agentspaces.api.ad AgentCard AssetCard)
           (ai.badmonkey.agentspaces.capabilities.semantic SemanticDiscovery SemanticDiscovery$Match)))

(set! *warn-on-reflection* true)

(defn- capability ^SemanticDiscovery [system]
  (or (get-in system [:capabilities :semantic])
      (throw (ex-info "no semantic discovery: start the peer with {:capabilities {:semantic true}}"
                      {:capabilities (keys (:capabilities system))}))))

(defn- match->map [^SemanticDiscovery$Match m]
  (let [ad (.advertisement m)]
    (cond-> {:score (.score m) :advertisement ad}
      (instance? AgentCard ad) (assoc :kind :agent :name (.localName (.agent ^AgentCard ad))
                                      :description (.description ^AgentCard ad))
      (instance? AssetCard ad) (assoc :kind :asset :name (.asset ^AssetCard ad)
                                      :description (.description ^AssetCard ad)))))

(defn query
  "Ranks the cards this peer holds against a question; `limit` default 5."
  ([system text] (query system text 5))
  ([system text limit]
   (mapv match->map (.query (capability system) ^String text (int limit)))))

(defn remote-query
  "Asks the group's peers to rank too, waiting `:timeout` (default \"2s\")."
  ([system text] (remote-query system text {}))
  ([system text {:keys [limit timeout] :or {limit 5 timeout "2s"}}]
   (mapv match->map (.remoteQuery (capability system) ^String text (int limit) (core/duration timeout)))))
