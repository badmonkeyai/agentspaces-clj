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

(ns agentspaces.prompts
  "The prompt library on the client side (enterprise platform spiral 4 §3.6,
  S4-P-03): a group's `PromptSet` arrives in the `policy` space as the console's
  `AgentConfiguration` of kind `bma-console/prompts`; `prompt-set` reads the
  newest, `prompt` hands out a reference that renders the version in force or
  the fallback the agent ships with, and `render` fills the `{placeholders}`.

  The entry's record class is the console's `AgentConfiguration` (on the
  classpath when the fleet carries the console records); `render` and a
  reference over a map need nothing of it."
  (:require [agentspaces.fleet :as fleet]
            [agentspaces.space :as space]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:const prompts-kind "bma-console/prompts")
(def ^:const prompts-instance "prompts")
(def ^:const hint-prefix "prompt.")
(def ^:const configuration-class "ai.badmonkey.bma.console.records.AgentConfiguration")
(def ^:const policy-space "policy")

(def ^:private placeholder #"\{([a-zA-Z_][a-zA-Z0-9_.-]*)\}")

(defn render
  "The text with its `{placeholders}` filled from `values` (string keys or
  keywords); a required variable that is missing throws, an optional one
  fills with an empty string (the Java `Prompt.render` rule)."
  [text variables values]
  (let [value (fn [k] (let [v (or (get values k) (get values (keyword k)))] (if (nil? v) "" (str v))))]
    (doseq [{:keys [name required]} variables]
      (when (and required (str/blank? (value name)))
        (throw (ex-info (str "the prompt needs {" name "}") {:variable name}))))
    (str/replace (or text "") placeholder (fn [[_ k]] (value k)))))

(defn- record-class ^Class []
  (try (Class/forName configuration-class)
       (catch ClassNotFoundException _
         (throw (ex-info (str configuration-class " is not on the classpath; add bma-console-common to read the prompt library")
                         {:class configuration-class})))))

(defn prompt-set
  "The group's set in force from a `fleet/start` system: the newest
  configuration of the prompts kind in `policy`, as the map the console
  published (`:group`, `:prompts` of `{:id :version :hash :kind :text :variables}`),
  or nil before any arrived."
  [system]
  (let [cls (record-class)
        entries (space/read-all (fleet/space system policy-space) cls {:limit 64})
        mine (filter (fn [c] (and (= prompts-kind (:agent c)) (= prompts-instance (:instance c)))) entries)]
    (when (seq mine)
      (:body (apply max-key :version mine)))))

(defn prompt-ref
  "A reference over a loader (a no-arg fn returning the set map or nil):
  `{:ref :fallback :entry :version :text :render :hint}` as functions of the
  set in force at the moment of the call."
  [ref fallback loader]
  (let [entry (fn [] (some (fn [e] (when (= ref (:id e)) e)) (:prompts (loader))))]
    {:ref ref
     :fallback (or fallback "")
     :entry entry
     :version (fn [] (or (:version (entry)) 0))
     :text (fn [] (or (:text (entry)) fallback ""))
     :render (fn [values] (if-let [e (entry)] (render (:text e) (:variables e) values) (render (or fallback "") [] values)))
     :hint (fn [] [(str hint-prefix ref) (str (or (:version (entry)) 0))])}))

(defn prompt
  "A reference reading the system's group for its set on every use."
  [system ref fallback]
  (prompt-ref ref fallback #(prompt-set system)))
