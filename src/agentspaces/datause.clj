(ns agentspaces.datause
  "The data-use declaration of the console's data catalog (SRAD
  bma-spiral1-console §3.4, S1-G-09): what a bound agent reads from a source
  that is not a space. `declare!` writes one leased `DataUse` entry per
  declaration into the group's `catalog` space and renews it at half-lease
  until the returned handle is closed; `unbind!` of the agent should close it.

  The entry's record class is the console's `DataUse` (on the classpath when
  the fleet carries the console records), named by IRI through the system's
  `:schema-registry`; without that class the declaration is a map entry the
  console cannot read, so `declare!` throws instead of writing it."
  (:require [agentspaces.core :as core]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.api.space EntryHandle Space)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def ^:const data-use-class "ai.badmonkey.bma.console.records.DataUse")
(def ^:const catalog-space "catalog")

(defn- record-class ^Class []
  (try (Class/forName data-use-class)
       (catch ClassNotFoundException _
         (throw (ex-info (str data-use-class " is not on the classpath; add bma-console-common to declare data use")
                         {:class data-use-class})))))

(defn declare!
  "Writes the declarations `[{:reads [..] :writes [..] :source \"..\" :sensitivity \"..\" :freshness \"..\"} ..]`
  for `agent-name` into the `catalog` space of a `fleet/start` system and
  renews them every half `lease` (default \"5m\"). Returns a map with
  `:entries` (the handles) and `:close` (a fn that stops renewing and cancels)."
  ([system agent-name decls] (declare! system agent-name decls {}))
  ([{:keys [spaces identity] :as system} agent-name decls {:keys [lease] :or {lease "5m"}}]
   (let [^Space catalog (or (get spaces catalog-space)
                            (throw (ex-info "the system has no catalog space; add {:name \"catalog\"} to :group :spaces"
                                            {:spaces (keys spaces)})))
         cls (record-class)
         peer (str (.peerId ^ai.badmonkey.agentspaces.identity.PeerIdentity identity))
         ^Duration d (core/duration lease)
         handles (mapv (fn [{:keys [reads writes source sensitivity freshness]}]
                         (space/write! catalog cls {:schemaVersion 1 :agent agent-name :peer peer
                                                    :reads (vec (or reads [])) :writes (vec (or writes []))
                                                    :source (or source "") :sensitivity (or sensitivity "")
                                                    :freshness (or freshness "") :declaredBy "decorator"}
                                       {:lease lease}))
                       decls)
         running (atom true)
         thread (doto (Thread. (fn []
                                 (while @running
                                   (Thread/sleep (max 250 (quot (.toMillis d) 2)))
                                   (when @running
                                     (doseq [^EntryHandle h handles]
                                       (try (.renew h d) (catch Exception _ nil))))))
                              (str agent-name ".data-use"))
                  (.setDaemon true)
                  (.start))]
     {:entries handles
      :close (fn []
               (reset! running false)
               (.interrupt thread)
               (doseq [^EntryHandle h handles] (try (.cancel h) (catch Exception _ nil))))})))
