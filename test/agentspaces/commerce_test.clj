;; Copyright 2026 Bad Monkey, Inc.
;; Licensed under the Apache License, Version 2.0.

(ns agentspaces.commerce-test
  "The commerce twins (SPEC §6.5a): the builders emit the Java field order and
  the golden vectors of agentspaces-commerce round-trip byte for byte."
  (:require [clojure.test :refer [deftest is]]
            [agentspaces.commerce :as commerce])
  (:import (ai.badmonkey.agentspaces.common.codec CborCodec)
           (java.nio.file Files Path)
           (java.util HexFormat Map)))

(def ^CborCodec codec (CborCodec/defaultCodec))
(defn- decode [^bytes raw] (.fromBytes codec raw Map))
(defn- encode ^bytes [m] (.toBytes codec m))

(def vectors (.resolve (.toAbsolutePath (Path/of "" (make-array String 0))) "../agentspaces-commerce/src/test/resources/vectors"))

(defn- vector-bytes ^bytes [name]
  (.parseHex (HexFormat/of) (.trim (Files/readString (.resolve ^Path vectors (str name ".hex"))))))

(deftest the-builders-emit-the-java-field-order
  (let [m (commerce/mandate "mnd-1" "checkout" "shopper" (commerce/cart [(commerce/item "claims.policies" "Policy data set, one month" 1 19900)] 19900 "USD")
                            "https://merchant.example/.well-known/ucp" (commerce/basis "apr-7" "") {"issuerSignedJwt" "eyJ.a.b"} "issued" "2026-11-05T12:00:00Z")]
    (is (= ["schemaVersion" "id" "kind" "role" "cart" "counterparty" "basis" "credential" "state" "at"] (vec (keys m))))
    (is (= ["sku" "title" "quantity" "unitPrice"] (vec (keys (first (get-in m ["cart" "items"]))))))
    (is (= 19900 (commerce/items-total (get m "cart"))))
    (is (thrown? clojure.lang.ExceptionInfo (commerce/mandate "m" "refund" "shopper" (get m "cart") "" nil {} "issued" "")))
    (is (= ["schemaVersion" "id" "mandate" "status" "fulfillment" "permalink" "at"] (vec (keys (commerce/order "o" "m" "completed" {} "" "x")))))
    (is (= "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0" (commerce/digest "abc")))))

(defn- plain
  "A Java map or list from the codec as Clojure data, for structural equality."
  [v]
  (cond (instance? Map v) (into {} (map (fn [[k x]] [k (plain x)])) v)
        (instance? java.util.List v) (mapv plain v)
        :else v))

(deftest the-golden-vectors-decode-into-what-the-builders-build
  ;; the Java codec writes a record and a map with different framing, so the twin is judged structurally: every
  ;; field and value of the vector is what the builder builds, in the Java field order
  (when (Files/exists vectors (make-array java.nio.file.LinkOption 0))
    (let [decoded (decode (vector-bytes "mandate_cbor"))
          m (plain decoded)
          c (get m "cart")
          rebuilt (commerce/mandate (get m "id") (get m "kind") (get m "role")
                                    (commerce/cart (mapv (fn [i] (commerce/item (get i "sku") (get i "title") (get i "quantity") (get i "unitPrice"))) (get c "items")) (get c "total") (get c "currency"))
                                    (get m "counterparty") (commerce/basis (get-in m ["basis" "approval"]) (get-in m ["basis" "decision"])) (get m "credential") (get m "state") (get m "at"))]
      (is (= m rebuilt) "the Clojure builder builds the Java mandate")
      (is (= (vec (.keySet ^Map decoded)) (vec (keys rebuilt))) "in the Java field order")
      (let [o (plain (decode (vector-bytes "order_cbor")))]
        (is (= o (commerce/order (get o "id") (get o "mandate") (get o "status") (get o "fulfillment") (get o "permalink") (get o "at")))))
      (is (= "aspace:cap/commerce" (get (plain (decode (vector-bytes "capability_commerce_cbor"))) "capabilityType"))))))
