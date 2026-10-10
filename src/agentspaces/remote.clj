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


(ns agentspaces.remote
  "Remote actions (SPEC §10.6): every foreign AgentCard's invocable actions,
  as maps, and `invoke!`, which writes the input into the agent's take space
  and awaits the correlated result."
  (:require [agentspaces.core :as core])
  (:import (ai.badmonkey.agentspaces.agent AgentSpaces$GroupContext)
           (ai.badmonkey.agentspaces.agent.remote RemoteAction RemoteActions)))

(set! *warn-on-reflection* true)

(defn- remote ^RemoteActions [{:keys [^AgentSpaces$GroupContext group-context identity] :as system}]
  (when-not group-context
    (throw (ex-info "the system has no facade; start it with fleet/start" {:keys (keys system)})))
  (RemoteActions/over group-context (.peerId ^ai.badmonkey.agentspaces.identity.PeerIdentity identity)))

(defn- action->map [^RemoteAction a]
  {:name (.name a) :description (.description a) :goals (vec (.goals a))
   :agent (.localName (.agent (.card a))) :input (.inputType a) :output (.outputType a)
   :agentspaces/action a})

(defn actions
  "The foreign actions this peer can invoke right now."
  [system]
  (mapv action->map (.available (remote system))))

(defn invoke!
  "Invokes an action (a map from `actions`, or its name) with an input map,
  waiting `:timeout` (default \"30s\") for the result map, or nil."
  ([system action input] (invoke! system action input {}))
  ([system action input {:keys [timeout] :or {timeout "30s"}}]
   (let [^RemoteAction a (if (map? action)
                           (:agentspaces/action action)
                           (or (some (fn [^RemoteAction a] (when (= (.name a) action) a)) (.available (remote system)))
                               (throw (ex-info (str "no remote action named " action)
                                               {:available (mapv #(.name ^RemoteAction %) (.available (remote system)))}))))
         result (.invoke a (core/to-entry (.inputType a) input) (core/duration timeout))]
     (when (.isPresent result)
       (core/from-entry (.get result))))))
