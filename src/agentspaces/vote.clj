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


(ns agentspaces.vote
  "The vote capability (SPEC §8.4) over a `fleet/start` system whose
  `:capabilities` block names a vote space: proposals, ballots, tallies,
  and decisions as maps, with the voter being the system's agent.

      (vote/propose! system {:id \"release\" :question \"Ship?\" :options [\"ship\" \"hold\"] :quorum 2})
      (vote/cast! system \"release\" \"ship\")
      (vote/decision system \"release\")   ; => {:id .. :winner .. :tally {..}} or nil"
  (:require [agentspaces.core :as core])
  (:import (ai.badmonkey.agentspaces.capabilities.vote VoteCapability VoteCapability$Decision
                                                       VoteCapability$Proposal)
           (java.util.function Consumer Predicate)))

(set! *warn-on-reflection* true)

(defn- capability ^VoteCapability [system]
  (or (get-in system [:capabilities :vote])
      (throw (ex-info "no vote capability: start the peer with {:capabilities {:vote {:space ..}}}"
                      {:capabilities (keys (:capabilities system))}))))

(defn- proposal->map [^VoteCapability$Proposal p]
  (when p {:id (.proposalId p) :question (.question p) :options (vec (.options p)) :quorum (.quorum p)}))

(defn- decision->map [^VoteCapability$Decision d]
  (when d {:id (.proposalId d) :winner (.winner d) :tally (into {} (.tally d))}))

(defn propose!
  "Opens a proposal: `{:id :question :options :quorum :lease}` (lease default 1h).
  Idempotent on the id."
  [system {:keys [id question options quorum lease] :or {lease "1h"}}]
  (let [v (capability system)]
    (when (.isEmpty (.proposal v ^String id))
      (.propose v ^String id ^String question ^java.util.List (vec options) (int quorum) (core/lease lease)))
    (proposal->map (.orElse (.proposal v ^String id) nil))))

(defn cast!
  "Casts this agent's ballot for an option; once per proposal counts."
  ([system id option] (cast! system id option {}))
  ([system id option {:keys [lease] :or {lease "1h"}}]
   (.castBallot (capability system) ^String id ^String option (core/lease lease))
   nil))

(defn proposal
  "The proposal as a map, or nil."
  [system id]
  (proposal->map (.orElse (.proposal (capability system) ^String id) nil)))

(defn tally
  "Votes per option so far."
  [system id]
  (into {} (.tally (capability system) ^String id)))

(defn decision
  "The closed decision as a map, or nil while the quorum is open."
  [system id]
  (decision->map (.orElse (.decision (capability system) ^String id) nil)))

(defn on-decision!
  "Calls `(f decision-map)` once per proposal whose id passes `pred` when
  it closes; returns the subscription (close it to stop)."
  ([system f] (on-decision! system (constantly true) f))
  ([system pred f]
   (.onDecision (capability system)
                (reify Predicate (test [_ id] (boolean (pred id))))
                (reify Consumer (accept [_ d] (f (decision->map d))))
                (core/lease "1h"))))
