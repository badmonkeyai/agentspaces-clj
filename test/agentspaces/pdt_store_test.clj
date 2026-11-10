;; Copyright 2026 Bad Monkey, Inc.
;; Licensed under the Apache License, Version 2.0.

(ns agentspaces.pdt-store-test
  "Content stores through the Clojure face (PDT 2 phase 7, P2-C-04): a peer
  with a `:store` block becomes a content store at feature level 2; another
  peer pins a ref's root on it with `store-pin!`, reads the copy with
  `store-status`, and proves the store holds it with `challenge!`, all over
  real TCP. A challenge of a member at level 1 fails at once, and a store
  without `:pdt` or `:capacity` refuses to start naming the key."
  (:require [clojure.test :refer [deftest is testing]]
            [agentspaces.fleet :as fleet]
            [agentspaces.pdt :as pdt])
  (:import (ai.badmonkey.agentspaces.store ContentStore)
           (clojure.lang ExceptionInfo)
           (java.net ServerSocket)))

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

(defn- config [group port seed-port extra]
  (merge {:bind (str "127.0.0.1:" port)
          :tick "100ms"
          :pdt {:store {:kind :memory} :collect-every "0ms"}
          :group {:name group
                  :founding (str group "-v1")
                  :seeds (if seed-port [(str "127.0.0.1:" seed-port)] [])
                  :membership-tuning {:ttl "30s" :probe "2s" :indirect 2}
                  :spaces [{:name "work" :settle "150ms"}]}}
         extra))

(defn- pin-when-a-card-arrives
  "The store's card reaches the requester through discovery, so the first pins
  may find no store yet; the test retries until one fits."
  [system root opts]
  (await-truthy 60000 #(try (pdt/store-pin! system root opts)
                            (catch IllegalStateException _ nil))))

(deftest a-member-pins-a-root-on-a-store-and-challenges-it
  (let [port-a (free-port) port-b (free-port)
        a (fleet/start (config "clj-pdt-store" port-a nil
                               {:store {:name "site-a" :capacity "64MiB" :accept-requesters :open}}))
        b (fleet/start (config "clj-pdt-store" port-b port-a {}))
        store-peer (str (fleet/peer-id a))]
    (try
      (is (await-truthy 10000 #(= 1 (count (.allMembers (.membership (:runtime b)))))))
      (is (instance? ContentStore (:store a)) "the store is on the system")
      (is (nil? (:store b)))
      (let [facts (pdt/ref b "facts" :map)
            value (pdt/swap! facts assoc "invoice-1" "paid" "invoice-2" "open")
            root (pdt/root value)]
        (testing "store-pin! writes a signed pin that names the store"
          (let [pinned (pin-when-a-card-arrives b root {:copies 1 :lease "1h"})]
            (is (= root (:root pinned)))
            (is (= :tree (:kind pinned)))
            (is (= 3600 (:lease pinned)))
            (is (= 1 (:copies pinned)))
            (is (= [store-peer] (:stores pinned)))))
        (testing "store-status shows the store's receipt once it holds the root"
          (is (await-truthy 60000 #(= 1 (:copies (pdt/store-status b root)))))
          (let [{:keys [holders unplaced]} (pdt/store-status b root)
                [holder] holders]
            (is (= store-peer (:store holder)))
            (is (pos? (:bytes holder)))
            (is (> (:until holder) (System/currentTimeMillis)))
            (is (false? (:blind holder)))
            (is (false? unplaced)))
          (is (= {:root "bafyreinone" :copies 0 :holders [] :unplaced false}
                 (pdt/store-status b "bafyreinone"))))
        (testing "challenge! proves the store holds the root"
          (let [result @(pdt/challenge! b root store-peer)]
            (is (true? (:passed result)) (pr-str result))
            (is (= store-peer (:store result)))
            (is (= root (:root result)))
            (is (pos? (:picks result)))
            (is (= (:picks result) (:proven result)))))
        (testing "a challenge of a member at level 1 fails at once"
          (let [result (deref (pdt/challenge! a root (str (fleet/peer-id b))) 5000 :timed-out)]
            (is (false? (:passed result)))
            (is (re-find #"pdt level 1" (:reason result))))))
      (finally (fleet/stop! b) (fleet/stop! a)))))

(deftest a-store-without-its-keys-refuses-to-start
  (testing "a store needs the persistent trees"
    (let [e (try (fleet/start (-> (config "clj-pdt-store-bad" (free-port) nil {:store {:capacity "1GiB"}})
                                  (dissoc :pdt)))
                 nil
                 (catch ExceptionInfo e e))]
      (is (some? e))
      (is (re-find #":pdt" (ex-message e)))))
  (testing "a store needs a capacity"
    (let [e (try (fleet/start (config "clj-pdt-store-bad" (free-port) nil {:store {:name "x"}}))
                 nil
                 (catch ExceptionInfo e e))]
      (is (some? e))
      (is (re-find #":capacity" (ex-message e))))))
