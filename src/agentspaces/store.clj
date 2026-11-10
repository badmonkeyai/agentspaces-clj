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

(ns agentspaces.store
  "A content store peer (PDT 2 phase 7, P2-C-04; srad/agentspaces-pdt2.md
  §3.2): the `:store` block of `fleet/start`, as `agentspaces.store.*` is in
  the Spring starter. The peer publishes a `content-store` card, takes the
  signed pins its policy admits, holds their roots, writes receipts, and
  answers possession challenges.

      (fleet/start {... :pdt {:store {:kind :pack}}
                    :store {:name \"site-a\" :capacity \"2TiB\" :accept-requesters :grant}})

  `fleet/start` loads this namespace only for a `:store` block, so a
  classpath that the Clojure CLI computed before `agentspaces-clj` named
  `agentspaces-store` still loads `agentspaces.fleet` and `agentspaces.pdt`.
  Members pin and challenge through `agentspaces.pdt/store-pin!` and
  `challenge!`."
  (:require [agentspaces.core :as core])
  (:import (ai.badmonkey.agentspaces.api.spi Authorizer)
           (ai.badmonkey.agentspaces.discovery DiscoveryService)
           (ai.badmonkey.agentspaces.identity PeerIdentity)
           (ai.badmonkey.agentspaces.pdt Pdt)
           (ai.badmonkey.agentspaces.peering.node GroupRuntime)
           (ai.badmonkey.agentspaces.store ContentStore StorePolicy StorePolicy$Accept)))

(set! *warn-on-reflection* true)

(defn policy
  "The policy of a `:store` block (appendix C, the keys of Spring's
  `agentspaces.store.*`): `:capacity` (required, \"2TiB\"), `:reserve`
  (\"5%\" or a size), `:max-object` (\"64GiB\"), `:lease-default` (\"30d\"),
  `:lease-max` (\"365d\"), `:copies-max` (3), `:accept-kinds`
  ([:tree :artifact]), `:accept-requesters` (:grant, which asks the group's
  authorizer, or :open), `:blind` (false), `:tier` (:hot or :cold), and
  `:price-per-gib-month` (0). `:name` names the store and is no policy key.
  A missing capacity or a value out of range throws naming its key."
  ^StorePolicy [{:keys [capacity reserve max-object lease-default lease-max copies-max accept-kinds
                        accept-requesters blind tier price-per-gib-month]}]
  (when-not capacity
    (throw (ex-info ":store :capacity is required: the bytes the store holds, such as \"2TiB\"" {:key :capacity})))
  (let [step (fn [k f]
               (try (f)
                    (catch IllegalArgumentException e
                      (throw (ex-info (str ":store " k ": " (.getMessage e)) {:key k} e)))))
        cap (long (step :capacity #(StorePolicy/bytes (str capacity))))
        ^StorePolicy p (step :capacity #(StorePolicy/of cap))
        ^StorePolicy p (if reserve (step :reserve #(.reserve p (StorePolicy/reserveOf (str reserve) cap))) p)
        ^StorePolicy p (if max-object (step :max-object #(.maxObject p (StorePolicy/bytes (str max-object)))) p)
        ^StorePolicy p (if (or lease-default lease-max)
                         (step :lease-default #(.leases p (if lease-default (core/duration lease-default) (.leaseDefault p))
                                                          (if lease-max (core/duration lease-max) (.leaseMax p))))
                         p)
        ^StorePolicy p (if copies-max (step :copies-max #(.copiesMax p (int copies-max))) p)
        ^StorePolicy p (if accept-kinds (step :accept-kinds #(.acceptKinds p (set (map name accept-kinds)))) p)
        ^StorePolicy p (if tier (step :tier #(.tier p (name tier))) p)
        ^StorePolicy p (if price-per-gib-month (step :price-per-gib-month #(.price p (long price-per-gib-month))) p)
        ^StorePolicy p (if (some? blind) (.blind p (boolean blind)) p)]
    (case (keyword (name (or accept-requesters :grant)))
      :grant (.acceptRequesters p StorePolicy$Accept/GRANT)
      :open (.acceptRequesters p StorePolicy$Accept/OPEN)
      (throw (ex-info (str ":store :accept-requesters is :open or :grant, got " accept-requesters)
                      {:key :accept-requesters})))))

(defn start!
  "Makes this peer a content store: a `ContentStore` over the facade, which
  `agentspaces.pdt/attach!` joined to a swarm at feature level 2, publishing
  its card through the discovery, taking the pins its policy admits, and
  ticking on the group tick. `:name` names the store (the peer id when nil).
  A `:grant` store asks the authorizer; with none it takes no pins and its
  card says it waits for one. Returns {:store ContentStore :tick
  AutoCloseable}."
  [{:keys [^StorePolicy policy name ^Pdt pdt ^DiscoveryService discovery ^PeerIdentity identity
           ^Authorizer authorizer ^GroupRuntime runtime]}]
  (let [builder (-> (ContentStore/builder pdt discovery identity) (.policy policy))
        _ (when name (.name builder (str name)))
        _ (when authorizer (.authorizer builder authorizer))
        ^ContentStore store (.build builder)]
    (.start store)
    {:store store :tick (.onTick runtime (fn [] (.tick store)))}))

(defn close!
  "Closes a store `start!` started, and its tick registration."
  [{:keys [^ContentStore store ^java.lang.AutoCloseable tick]}]
  (when tick (try (.close tick) (catch Exception _)))
  (when store (try (.close store) (catch Exception _)))
  nil)
