;; A Clojure agent that declares its data use (S1-G-09): joins a fleet by seed and founding, binds a defagent,
;; declares the source it reads, and stays up so the console's bridge mirrors the declaration.
;;   clojure -M:datause-worker examples/datause_worker.clj PORT SEED FOUNDING [MAX-SECONDS]
(ns datause-worker
  (:require [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.datause :as datause]
            [agentspaces.fleet :as fleet])
  (:import (ai.badmonkey.agentspaces.space.local NamespaceSchemaRegistry)
           (ai.badmonkey.bma.console.records Finding)))

(defagent CljCoverage
  {:name "clj-coverage" :description "Checks coverage from Clojure"}
  (notify onFinding [^Finding finding] {:space "catalog"}
    nil))

(let [[port seed founding max-seconds] *command-line-args*
      registry (NamespaceSchemaRegistry/of {"ai.badmonkey.bma.console.records" "https://badmonkey.ai/console#"})
      system (fleet/start {:bind (str "127.0.0.1:" port)
                           :tick "250ms"
                           :schema-registry registry
                           :agents true
                           :group {:name "xlang-fleet"
                                   :founding founding
                                   :seeds [seed]
                                   :spaces [{:name "catalog" :settle "150ms"}]}})
      bound (agents/bind! system (->CljCoverage))
      declared (datause/declare! system "clj-coverage"
                                 [{:reads ["claims.policies"] :source "jdbc:postgresql://claims-clj/policies"
                                   :sensitivity "internal" :freshness "PT5M"}] {:lease "PT2M"})]
  (println (str "{\"ok\":true,\"peerId\":\"" (.peerId (:identity system)) "\",\"entries\":" (count (:entries declared)) "}"))
  (flush)
  (Thread/sleep (* 1000 (Long/parseLong (or max-seconds "60"))))
  ((:close declared))
  (agents/unbind! bound)
  (fleet/stop! system)
  (System/exit 0))
