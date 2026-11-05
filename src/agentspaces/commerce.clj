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

(ns agentspaces.commerce
  "The commerce records (SPEC §6.5a, §7.6; the open `agentspaces-commerce`
  module): a mandate is an AP2 Checkout or Payment Mandate carried as an entry
  of a group's `commerce` space, an order the UCP order it led to. The builders
  emit the Java field order as array maps, so a record built here encodes byte
  for byte as the Java one; `digest` is the base64url SHA-256 the AP2 bindings
  use (`checkout_hash`, `transaction_id`)."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def ^:const capability-commerce "aspace:cap/commerce")
(def ^:const space-commerce "commerce")
(def roles #{"merchant" "shopper" "payment"})
(def kinds #{"checkout" "payment"})
(def states #{"issued" "settled" "refused" "expired"})
(def order-statuses #{"incomplete" "requires_escalation" "ready_for_complete" "complete_in_progress" "completed" "canceled"})

(defn item
  "One cart item: the UCP item id, the title, the quantity, the unit price in minor units."
  [sku title quantity unit-price]
  (when (< quantity 1) (throw (ex-info "an item's quantity is at least one" {:sku sku})))
  (array-map "sku" sku "title" (or title "") "quantity" (long quantity) "unitPrice" (long unit-price)))

(defn cart [items total currency]
  (when (> (count items) 64) (throw (ex-info "a cart carries at most 64 items" {:count (count items)})))
  (when (not= 3 (count currency)) (throw (ex-info (str "the currency is an ISO 4217 code: " currency) {:currency currency})))
  (array-map "items" (vec items) "total" (long total) "currency" currency))

(defn basis
  ([] (basis "" ""))
  ([approval decision] (array-map "approval" (or approval "") "decision" (or decision ""))))

(defn mandate [id kind role cart counterparty basis credential state at]
  (when-not (kinds kind) (throw (ex-info (str "kind must be one of " kinds) {:kind kind})))
  (when-not (roles role) (throw (ex-info (str "role must be one of " roles) {:role role})))
  (when-not (states state) (throw (ex-info (str "state must be one of " states) {:state state})))
  (array-map "schemaVersion" 1 "id" id "kind" kind "role" role "cart" cart "counterparty" (or counterparty "")
             "basis" (or basis (agentspaces.commerce/basis)) "credential" (into {} (or credential {})) "state" state "at" at))

(defn order [id mandate-id status fulfillment permalink at]
  (when-not (order-statuses status) (throw (ex-info (str "status must be one of " order-statuses) {:status status})))
  (array-map "schemaVersion" 1 "id" id "mandate" (or mandate-id "") "status" status "fulfillment" (into {} (or fulfillment {}))
             "permalink" (or permalink "") "at" at))

(defn items-total [cart]
  (reduce + 0 (map (fn [i] (* (long (get i "quantity")) (long (get i "unitPrice")))) (get cart "items"))))

(defn digest
  "The base64url SHA-256 of a string's UTF-8 bytes, no padding."
  ^String [^String s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))))
