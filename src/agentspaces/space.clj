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

(ns agentspaces.space
  "The space verbs, data-oriented: write!, read, read-all, take!, complete!,
  and notify!, over any Space implementation (a LocalSpace for one process,
  a ReplicatedSpace from `agentspaces.fleet/start` for a fleet).

  Entries travel as Clojure maps against a Java record class that names the
  entry type; leases and timeouts accept duration strings (\"10m\"),
  milliseconds, or java.time.Duration. Reads and takes return maps (nil on
  timeout); a take result carries the fabric handle under `:taken` for
  `complete!` and `renew!`. Reads accept `:fresh true` for one anti-entropy
  pull before matching (ReplicatedSpace only; a LocalSpace ignores it), and
  `grant!`/`revoke!` issue and withdraw space credentials on a
  credential-admitted ReplicatedSpace (spec 7.5)."
  (:refer-clojure :exclude [read])
  (:require [agentspaces.core :as core]
            [clojure.string :as str])
  (:import (ai.badmonkey.agentspaces.api.space EntryHandle Space SpaceEvent
                                               SpaceListener Subscription TakenEntry)
           (ai.badmonkey.agentspaces.common.id AgentId PeerId)
           (ai.badmonkey.agentspaces.space ConsistencyHint)
           (ai.badmonkey.agentspaces.space.local LocalSpace)
           (ai.badmonkey.agentspaces.space.replicated ReplicatedSpace SpaceAdmission$Scope)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def ^:private default-write-lease "10m")
(def ^:private default-take-lease "10m")
(def ^:private default-notify-lease "365d")

(defn local
  "A single-process space, full lease semantics, no fabric: the right start
  for tests and REPL exploration.

      (local \"work\")
      (local \"work\" {:issuer my-agent-id :sweep-every \"1s\"})"
  (^LocalSpace [name] (local name {}))
  (^LocalSpace [name {:keys [issuer sweep-every clock]}]
   (let [issuer (or issuer (AgentId. (PeerId/of "zCljLocalPeer1") "clj"))
         builder (LocalSpace/builder name issuer)]
     (when sweep-every (.sweepEvery builder (core/duration sweep-every)))
     (when clock (.clock builder clock))
     (.build builder))))

(defn as
  "A view of a replicated space acting as `agent`, an AgentIdentity (SPEC 4.2,
  v0.1.13; see `agentspaces.fleet/agent`): writes, takes, and completions
  through it are signed by that agent, AGENT_ATTESTED under a subordinate key."
  [space ^ai.badmonkey.agentspaces.api.spi.AgentIdentity agent]
  (if (instance? ReplicatedSpace space)
    (.as ^ReplicatedSpace space agent)
    (throw (ex-info "as needs a ReplicatedSpace" {:space space}))))

(defn- acting
  "The space a writing verb acts through: `(as space agent)` when its opts
  carry `{:as agent}`, an AgentIdentity, else the space itself."
  [space opts]
  (if-let [agent (when (map? opts) (get opts :as))]
    (as space agent)
    space))

(defn write!
  "Writes an entry and returns its EntryHandle. The entry is a Java record
  instance, or a record class plus a map. `{:as agent}` (an AgentIdentity,
  see `agentspaces.fleet/agent`) writes as that agent, through `as`:

      (write! space task-record {:lease \"10m\"})
      (write! space TaskEntry {:topic \"indexing\" :priority 3} {:lease \"10m\"})
      (write! space TaskEntry {:topic \"t\" :priority 1} {:as planner})"
  (^EntryHandle [space entry] (write! space entry {}))
  (^EntryHandle [space entry-or-type map-or-opts & [opts]]
   (if (class? entry-or-type)
     (write! space (core/to-entry entry-or-type map-or-opts) (or opts {}))
     (let [{:keys [lease tags] :or {lease default-write-lease}} map-or-opts
           ^Space space (acting space map-or-opts)]
       (if (seq tags)
         (.write space entry-or-type (core/lease lease)
                 (into {} (map (fn [[k v]] [(name k) (str v)])) tags))
         (.write space entry-or-type (core/lease lease)))))))

(defn- fresh?
  "Whether a read should pull first: `:fresh true` on a ReplicatedSpace. A
  LocalSpace has nobody to pull from, so the flag is ignored there."
  [space {:keys [fresh]}]
  (and fresh (instance? ReplicatedSpace space)))

(defn read
  "A non-destructive match, returned as a map (nil when nothing matches
  within `:timeout`, which defaults to no waiting). `:fresh true` performs
  one anti-entropy pull toward a random member before matching (spec 7.2,
  ConsistencyHint/FRESH); only a ReplicatedSpace honours it.

      (read space TaskEntry {:where {:topic \"indexing\"} :timeout \"1s\"})
      (read space TaskEntry {:fresh true :timeout \"5s\"})"
  ([space type] (read space type {}))
  ([space type {:keys [timeout] :as opts}]
   (let [t (core/template type opts)]
     (core/from-entry
      (cond
        (fresh? space opts)
        (.orElse (.read ^ReplicatedSpace space t ConsistencyHint/FRESH
                        (core/duration (or timeout 0)))
                 nil)
        timeout (.orElse (.read ^Space space t (core/duration timeout)) nil)
        :else (.orElse (.read ^Space space t) nil))))))

(defn read-all
  "Every current match as a vector of maps, capped by `:limit` (default 100).
  `:fresh true` pulls once before matching, as in `read`."
  ([space type] (read-all space type {}))
  ([space type {:keys [limit] :or {limit 100} :as opts}]
   (let [t (core/template type opts)]
     (mapv core/from-entry
           (if (fresh? space opts)
             (.readAll ^ReplicatedSpace space t ConsistencyHint/FRESH (int limit))
             (.readAll ^Space space t (int limit)))))))

(defn take!
  "A destructive, exclusive match under the space's conflict strategy.
  Returns the entry map with the fabric handle under `:taken`, or nil when
  nothing matched within `:timeout` (default no waiting). The taker holds
  the `:lease` (default 10m); an uncompleted lapse makes the entry reappear.
  `{:as agent}` takes as that agent, through `as`.

      (when-let [task (take! space TaskEntry {:lease \"10m\" :timeout \"1s\"})]
        (work-on task)
        (complete! space task))"
  ([space type] (take! space type {}))
  ([space type {:keys [lease timeout] :or {lease default-take-lease
                                           timeout Duration/ZERO} :as opts}]
   (when-let [^TakenEntry taken (.orElse (.take ^Space (acting space opts)
                                               (core/template type opts)
                                               (core/lease lease)
                                               (core/duration timeout))
                                         nil)]
     (some-> (core/from-entry (.entry taken))
             (vary-meta assoc :agentspaces/taken taken)))))

(defn taken
  "The TakenEntry handle behind a `take!` result."
  ^TakenEntry [entry]
  (or (:agentspaces/taken (meta entry))
      (throw (ex-info "not a take! result" {:entry entry}))))

(defn complete!
  "Completes a taken entry, optionally writing a result entry in the same
  act (the choreography move: the completion and its successor appear
  together). `{:as agent}` completes as that agent, through `as`.

      (complete! space task)
      (complete! space task {:as worker})
      (complete! space task FindingEntry {:topic t :summary s} {:lease \"1h\"})
      (complete! space task FindingEntry {:topic t :summary s} {:as worker})"
  ([space took] (.complete ^Space space (taken took)) nil)
  ([space took result-or-opts]
   (if (map? result-or-opts)
     (do (.complete ^Space (acting space result-or-opts) (taken took)) nil)
     (complete! space took result-or-opts {})))
  ([space took result-or-type map-or-opts & [opts]]
   (if (class? result-or-type)
     (complete! space took (core/to-entry result-or-type map-or-opts) (or opts {}))
     (let [{:keys [lease] :or {lease "1h"}} map-or-opts]
       (.complete ^Space (acting space map-or-opts) (taken took) result-or-type
                  (core/lease lease))))))

(defn renew!
  "Extends a write handle's or take result's lease."
  [handle-or-entry extension]
  (let [d (core/duration extension)]
    (if (instance? EntryHandle handle-or-entry)
      (.renew ^EntryHandle handle-or-entry d)
      (.renew (taken handle-or-entry) d))))

(defn cancel!
  "Withdraws a written entry through its handle."
  [^EntryHandle handle]
  (.cancel handle))

(defn notify!
  "Subscribes `callback` to matching space events and returns the
  Subscription (close it, or `unsubscribe!` it, to stop). The callback
  receives {:kind :written|:taken|:completed|:expired|:reappeared,
  :entry <map>, :entry-id <EntryId>}; its return value is ignored, and it
  runs on the space's delivery thread, so return promptly.

      (notify! space FindingEntry
               (fn [{:keys [kind entry]}]
                 (when (= :written kind) (println \"finding:\" entry))))"
  (^Subscription [space type callback] (notify! space type callback {}))
  (^Subscription [space type callback {:keys [lease] :or {lease default-notify-lease}
                                       :as opts}]
   (.notify ^Space space (core/template type opts)
            (reify SpaceListener
              (onEvent [_ event]
                (callback {:kind (keyword (.toLowerCase (.name (.kind ^SpaceEvent event))))
                           :entry (core/from-entry (.entry ^SpaceEvent event))
                           :entry-id (.entryId ^SpaceEvent event)})))
            (core/lease lease))))

(defn unsubscribe!
  "Closes a notify! subscription."
  [^Subscription subscription]
  (.close subscription))

;; ----------------------------------------------------------- credentials

(defn- replicated ^ReplicatedSpace [space verb]
  (if (instance? ReplicatedSpace space)
    space
    (throw (ex-info (str verb " needs a credential-admitted ReplicatedSpace")
                    {:space space}))))

(defn- ->scope ^SpaceAdmission$Scope [k]
  (SpaceAdmission$Scope/valueOf (str/upper-case (name k))))

(defn grant!
  "Issues a space credential on a `:admission :credential` space (spec 7.5):
  admits `agent` (an AgentId or \"<peerId>/<name>\") for `scopes`, a set of
  `:write` and/or `:take`, for `validity`. The credential is an ordinary
  entry that replicates to every member and lapses with its lease; the
  returned EntryHandle renews or cancels it. Only the issuer's node may
  grant; anyone else gets a SpaceAdmissionException.

      (grant! space \"z6Mk.../worker\" #{:write :take} \"1h\")"
  ^EntryHandle [space agent scopes validity]
  (.grant (replicated space "grant!") (core/agent-id agent)
          ^java.util.Set (into #{} (map ->scope) scopes)
          (core/duration validity)))

(defn revoke!
  "Withdraws every live credential this replica holds for `agent`; the
  cancellation replicates like any removal. Issuer's node only."
  [space agent]
  (.revoke (replicated space "revoke!") (core/agent-id agent))
  nil)
