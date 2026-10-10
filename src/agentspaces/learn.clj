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


(ns agentspaces.learn
  "Gossip learning (SPEC §8.5) over a `fleet/start` system with
  `:capabilities {:learn true}`: models as double vectors merged by weight
  averaging across the group, round by round on the node's tick. `end-epoch!`
  writes its evaluations into a metrics space, so it needs the fuller shape
  `{:learn {:metrics \"metrics\" :metrics-lease \"1h\"}}`; under `:learn true`
  it throws an IllegalStateException naming the missing space."
  (:import (ai.badmonkey.agentspaces.capabilities.learn GossipLearning GossipLearner$Evaluation)
           (java.util.function ToDoubleFunction)))

(set! *warn-on-reflection* true)

(defn- capability ^GossipLearning [system]
  (or (get-in system [:capabilities :learn])
      (throw (ex-info "no gossip learning: start the peer with {:capabilities {:learn true}}"
                      {:capabilities (keys (:capabilities system))}))))

(defn start!
  "Starts (or replaces) this peer's model under an id."
  [system model-id parameters]
  (.start (capability system) ^String model-id (double-array parameters))
  nil)

(defn model
  "The current merged model as a vector, or nil."
  [system model-id]
  (let [m (.model (capability system) ^String model-id)]
    (when (.isPresent m) (vec (.get m)))))

(defn round
  "How many merge rounds this peer has run for the model."
  [system model-id]
  (.round (capability system) ^String model-id))

(defn end-epoch!
  "Evaluates every model with `(f parameters-vector)`, writes one
  GossipLearner.Evaluation per model into the metrics space `:learn
  {:metrics ..}` names, and returns them as
  [{:model-id :epoch :loss :content-id} ...]."
  [system epoch f]
  (mapv (fn [^GossipLearner$Evaluation e]
          {:model-id (.modelId e) :epoch (.epoch e) :loss (.loss e) :content-id (.contentId e)})
        (.endEpoch (capability system) (int epoch)
                   (reify ToDoubleFunction (applyAsDouble [_ p] (double (f (vec p))))))))
