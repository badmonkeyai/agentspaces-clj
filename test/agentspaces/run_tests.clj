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

(ns agentspaces.run-tests
  "Runs the whole suite and exits nonzero on failure, so `clj -M:test` (and
  the plain-classpath invocation in the README) both work as build gates
  with nothing beyond clojure.test."
  (:require [clojure.test :as t]
            [agentspaces.space-test]
            [agentspaces.fleet-test]
            [agentspaces.agents-test]))

(defn -main [& _]
  (let [result (t/run-tests 'agentspaces.space-test 'agentspaces.fleet-test 'agentspaces.agents-test)]
    (System/exit (if (and (zero? (:fail result)) (zero? (:error result))) 0 1))))
