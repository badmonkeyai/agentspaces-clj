;; Copyright 2026 Bad Monkey, Inc.
;; Licensed under the Apache License, Version 2.0.

(ns agentspaces.prompts-test
  "The prompt library's client side (spiral 4 §3.6): a reference renders the
  fallback until the set names it, fills placeholders, refuses a missing
  required variable, and reports the hint."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.prompts :as prompts]))

(deftest a-reference-renders-the-fallback-until-the-set-names-it
  (let [r (prompts/prompt-ref "correlation/classify" "Compare {left} and {right}." (constantly nil))]
    (is (= 0 ((:version r))))
    (is (= "Compare {left} and {right}." ((:text r))))
    (is (= "Compare A and B." ((:render r) {:left "A" :right "B"})))
    (is (= ["prompt.correlation/classify" "0"] ((:hint r)))))
  (let [live {:group "claims-fleet"
              :prompts [{:id "correlation/classify" :version 3 :hash "sha256:x" :kind "agent-system"
                         :text "You compare {left} with {right}. {hint}"
                         :variables [{:name "left" :required true} {:name "right" :required true} {:name "hint" :required false}]}]}
        r (prompts/prompt-ref "correlation/classify" "fallback" (constantly live))]
    (is (= 3 ((:version r))))
    (is (= "You compare A with B. " ((:render r) {"left" "A" "right" "B"})))
    (testing "a missing required variable is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"right" ((:render r) {:left "A"}))))
    (is (= ["prompt.correlation/classify" "3"] ((:hint r)))))
  (is (= "no placeholders" (prompts/render "no placeholders" [] nil))))
