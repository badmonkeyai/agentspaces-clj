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


(ns agentspaces.ordered
  "ORDERED takes (SPEC §7.7, §8.3) over a `fleet/start` system with
  `:capabilities {:ordered {:space .. :members [..]}}`: the exactly-once
  take through the group's log, with the same map shape as `space/take!`."
  (:require [agentspaces.core :as core]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.api.space TakenEntry)
           (ai.badmonkey.agentspaces.capabilities.orderedlog OrderedTakes)))

(set! *warn-on-reflection* true)

(defn- coordinator ^OrderedTakes [system]
  (or (get-in system [:capabilities :ordered])
      (throw (ex-info "no ordered coordinator: start the peer with {:capabilities {:ordered {:space .. :members [..]}}}"
                      {:capabilities (keys (:capabilities system))}))))

(defn take!
  "An exactly-once take through the log: the entry map with the handle under
  `:agentspaces/taken`, or nil when nothing was committed to this peer within
  `:timeout` (default \"2s\"); complete it with `space/complete!`."
  ([system type] (take! system type {}))
  ([system type {:keys [lease timeout] :or {lease "30s" timeout "2s"} :as opts}]
   (when-let [^TakenEntry taken (.orElse (.take (coordinator system) (core/template type opts)
                                                (core/lease lease) (core/duration timeout))
                                         nil)]
     (some-> (core/from-entry (.entry taken))
             (vary-meta assoc :agentspaces/taken taken)))))

(defn leader?
  "Whether this peer currently leads the log."
  [system]
  (.isLeader (.raft (coordinator system))))

(defn complete!
  "Completes an ordered take, optionally with a result entry, as `space/complete!`."
  ([system entry] (space/complete! (.space (coordinator system)) entry))
  ([system entry type result] (space/complete! (.space (coordinator system)) entry type result)))
