;; A Clojure worker on example 02's research fleet, for run-polyglot-test.sh.
;;
;;   clojure -M:research-worker examples/research_worker.clj PORT SEED-HOST:PORT
;;
;; Joins the group example 02 founds from the text "research-fleet-demo-v1"
;; (the same bytes, so the GroupId agrees), takes ResearchTask entries from
;; the "tasks" space, and writes a Finding stamped "clj-worker" for each.
;; Runs until killed.
(ns research-worker
  (:require [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.fleet :as fleet])
  (:import (ai.badmonkey.agentspaces.examples.fleet ResearchFleet$ResearchTask ResearchFleet$Finding)))

(defagent Researcher
  {:description "Researches a topic in Clojure" :goals ["research"]}
  (take research [^ResearchFleet$ResearchTask task] {:space "tasks" :lease "30s"}
    ^ResearchFleet$Finding
    (do (println (str "clojure worker: took " (:topic task) " (priority " (:priority task) ")"))
        (flush)
        {:topic (:topic task) :summary (str "researched in clojure: " (:topic task)) :worker "clj-worker"})))

(let [[port seed] *command-line-args*
      ;; A tls:// seed selects the mtls profile (TLS 1.3, channel certificates); otherwise plain TCP.
      tls? (boolean (and seed (.startsWith ^String seed "tls://")))
      system (fleet/start {:bind (str "127.0.0.1:" (or port 7480))
                           :tick "250ms"
                           :security {:profile (if tls? :mtls :dev-local)}
                           :group {:name "research-fleet"
                                   :founding "research-fleet-demo-v1"
                                   :seeds (if seed [seed] [])
                                   :spaces [{:name "tasks" :settle "150ms"}]}})]
  (agents/bind! system (->Researcher))
  (println (str "clojure worker on 127.0.0.1:" (or port 7480) ", seeded from " seed))
  (flush)
  @(promise))
