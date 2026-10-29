;; Copyright 2026 Bad Monkey, Inc.
;; Licensed under the Apache License, Version 2.0.

(ns agentspaces.content-test
  "The content swarm over two TCP peers with `:content`: a publish on one and a
  fetch on the other, byte for byte; a space payload above `:escalate-above`
  travelling as an artifact and read back; `describe` and `list-manifests`."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.content :as content]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space])
  (:import (ai.badmonkey.agentspaces.test Fixtures$TaskEntry)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.util Random)))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- await-truthy [timeout-ms f]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (or (f)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 100)
            (recur))))))

(defn- temp-dir [label]
  (str (Files/createTempDirectory label (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- config [group port seed-port]
  {:bind (str "127.0.0.1:" port)
   :tick "100ms"
   :agents true
   :content {:escalate-above "256KiB" :piece-size "64KiB"
             :store {:backends [:file] :path (temp-dir "clj-content")}}
   :group {:name group
           :founding (str group "-v1")
           :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
           :membership {:ttl "30s" :probe "2s" :indirect 2}
           :spaces [{:name "work" :settle "150ms"}]}})

(defn- random-bytes ^bytes [n seed]
  (let [out (byte-array n)]
    (.nextBytes (Random. (long seed)) out)
    out))

(deftest a-publish-on-one-peer-is-fetched-on-the-other
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-content" port-a nil))
        b (fleet/start (config "clj-content" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (let [artifact (random-bytes (+ (* 5 65536) 17) 7)
            manifest (content/publish! a artifact {:name "weights.bin" :attrs {:run 42}})]
        (is (clojure.string/starts-with? (:root manifest) "bafkrei"))
        (is (= 6 (:pieces manifest)))
        (is (= "weights.bin" (:name manifest)))
        (is (await-truthy 10000 #(some? (content/describe b (:root manifest)))))
        (let [fetch (content/fetch! b (:root manifest))]
          (is (content/await fetch "60s") (pr-str (content/trace fetch)))
          (is (java.util.Arrays/equals artifact (content/read-bytes fetch)))
          (is (pos? (:bytes-received (content/progress fetch))))
          (testing "b seeds what it completed; a sees two complete holders"
            (is (await-truthy 15000 #(= 2 (:complete-holders (content/describe a (:root manifest)))))))
          (testing "the catalogue"
            (is (= ["weights.bin"] (mapv :name (content/list-manifests b)))))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest a-space-payload-above-the-threshold-escalates-and-reads-back
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-escalate" port-a nil))
        b (fleet/start (config "clj-escalate" port-b port-a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (let [big (String. (char-array (repeat (* 400 1024) \x)))
            work-a (fleet/space a "work")
            work-b (fleet/space b "work")]
        (space/write! work-a Fixtures$TaskEntry {:topic big :priority 3} {:lease "10m"})
        (let [seen (await-truthy 60000 #(space/read work-b Fixtures$TaskEntry))]
          (is (some? seen))
          (is (= big (:topic seen)))
          (is (= 1 (:roots-held (content/stats b))) "the payload arrived as one artifact")))
      (finally (fleet/stop! b) (fleet/stop! a)))))
