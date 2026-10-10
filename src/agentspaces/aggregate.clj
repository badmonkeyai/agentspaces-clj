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


(ns agentspaces.aggregate
  "The push-sum aggregate (SPEC §8.2) over a `fleet/start` system with
  `:capabilities {:aggregate true}`: epochs started by value, estimates,
  settled estimates, and a settle listener.

      (aggregate/start! system \"load\" 12.5)
      (aggregate/await-settled system \"load\" {:ticks 3 :tolerance 0.01 :timeout \"20s\"})"
  (:require [agentspaces.core :as core])
  (:import (ai.badmonkey.agentspaces.capabilities.aggregate PushSumAggregate PushSumAggregate$Estimate
                                                            PushSumAggregate$Mode PushSumAggregate$Settle)
           (java.util.function Consumer Predicate)))

(set! *warn-on-reflection* true)

(defn- capability ^PushSumAggregate [system]
  (or (get-in system [:capabilities :aggregate])
      (throw (ex-info "no aggregate: start the peer with {:capabilities {:aggregate true}}"
                      {:capabilities (keys (:capabilities system))}))))

(defn- ->mode ^PushSumAggregate$Mode [kw]
  (PushSumAggregate$Mode/valueOf (.toUpperCase (name (or kw :avg)))))

(defn- settle ^PushSumAggregate$Settle [{:keys [ticks tolerance] :or {ticks 0 tolerance 0.0}}]
  (PushSumAggregate$Settle/after (int ticks) (double tolerance)))

(defn- estimate->map [^PushSumAggregate$Estimate e]
  {:epoch (.epochId e) :mode (keyword (.toLowerCase (.name (.mode e)))) :value (.value e) :ticks (.ticks e)})

(defn start!
  "Joins an epoch with this peer's value; `:mode` is :avg (default), :sum,
  :count, :min, or :max."
  ([system epoch value] (start! system epoch value {}))
  ([system epoch value {:keys [mode]}]
   (.start (capability system) ^String epoch (->mode mode) (double value))
   nil))

(defn estimate
  "The current estimate, or nil before any exchange."
  [system epoch]
  (let [e (.estimate (capability system) ^String epoch)]
    (when (.isPresent e) (.getAsDouble e))))

(defn tick!
  "One protocol round of the aggregate, for peers that drive it by hand
  (the node's tick drives it under `fleet/start`)."
  [system]
  (.tick (capability system)))

(defn await-settled
  "Blocks until the epoch's estimate holds for `:ticks` consecutive ticks
  within `:tolerance` (defaults 0 and 0: the first estimate), or `:timeout`
  (default \"30s\") passes; the value, or nil."
  [system epoch {:keys [timeout] :or {timeout "30s"} :as rule}]
  (let [done (promise)
        handle (.onEstimate (capability system)
                            (reify Predicate (test [_ id] (= id epoch)))
                            (settle rule)
                            (reify Consumer (accept [_ e] (deliver done (.value ^PushSumAggregate$Estimate e)))))]
    (try
      (deref done (.toMillis (core/duration timeout)) nil)
      (finally (.close ^java.lang.AutoCloseable handle)))))

(defn on-estimate!
  "Calls `(f estimate-map)` once per epoch passing `pred` when its estimate
  settles under the rule; returns a handle to close."
  [system pred rule f]
  (.onEstimate (capability system)
               (reify Predicate (test [_ id] (boolean (pred id))))
               (settle rule)
               (reify Consumer (accept [_ e] (f (estimate->map e))))))
