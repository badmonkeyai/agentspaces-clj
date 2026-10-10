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

(ns agentspaces.core
  "Data plumbing for the AgentSpaces bindings: maps in, maps out.

  Entry types stay Java record classes, because the fabric partitions
  storage and matching by class and every peer, whatever its language,
  agrees on the schema that way. This namespace makes the classes
  invisible in daily use: `to-entry` builds a record from a Clojure map
  through the canonical constructor, `from-entry` turns a record back
  into a map (the raw object rides along in metadata), and `template`
  compiles a `:where` map into the fabric's matcher DSL."
  (:require [clojure.string :as str])
  (:import (ai.badmonkey.agentspaces.api.space Lease Matchers Template)
           (ai.badmonkey.agentspaces.common.id AgentId PeerId)
           (java.lang.reflect RecordComponent)
           (java.time Duration)
           (java.util.function Predicate)))

(set! *warn-on-reflection* true)

;; ------------------------------------------------------------------- ids

(defn peer-id
  "Coerces to a PeerId: an instance passes through, a string is parsed
  (\"aspace://\" and anything after a slash are dropped, so a group URI or an
  encoded AgentId also yields its peer)."
  ^PeerId [x]
  (cond
    (instance? PeerId x) x
    (instance? AgentId x) (.peer ^AgentId x)
    (string? x)
    (let [s (str/trim x)
          s (if (str/starts-with? s "aspace://") (subs s 9) s)
          s (if-let [i (str/index-of s "/")] (subs s 0 i) s)]
      (PeerId/of s))
    :else (throw (ex-info "cannot coerce to a peer id" {:value x}))))

(defn agent-id
  "Coerces to an AgentId: an instance passes through, a string is the
  canonical \"<peerId>/<name>\" form."
  ^AgentId [x]
  (cond
    (instance? AgentId x) x
    (string? x) (AgentId/parse (str/trim x))
    :else (throw (ex-info "cannot coerce to an agent id" {:value x}))))

;; ---------------------------------------------------------------- durations

(defn duration
  "Coerces to java.time.Duration: a Duration passes through, an integer is
  milliseconds, and a string is either simple form (\"10m\", \"500ms\",
  \"30s\", \"2h\", \"1d\") or ISO-8601 (\"PT10M\")."
  ^Duration [x]
  (cond
    (instance? Duration x) x
    (integer? x) (Duration/ofMillis (long x))
    (string? x)
    (if-let [[_ n unit] (re-matches #"(?i)\+?(\d+)(ms|s|m|h|d)" (str/trim x))]
      (let [n (parse-long n)]
        (case (str/lower-case unit)
          "ms" (Duration/ofMillis n)
          "s"  (Duration/ofSeconds n)
          "m"  (Duration/ofMinutes n)
          "h"  (Duration/ofHours n)
          "d"  (Duration/ofDays n)))
      (Duration/parse x))
    :else (throw (ex-info "cannot coerce to a duration" {:value x}))))

(defn lease
  "Coerces to a Lease through `duration`."
  ^Lease [x]
  (if (instance? Lease x) x (Lease/of (duration x))))

;; ------------------------------------------------------- records <-> maps

(defn- coerce-component [^Class target v]
  (cond
    (nil? v) v
    (or (= target Integer/TYPE) (= target Integer)) (int v)
    (or (= target Long/TYPE) (= target Long)) (long v)
    (or (= target Double/TYPE) (= target Double)) (double v)
    (or (= target Float/TYPE) (= target Float)) (float v)
    (or (= target Short/TYPE) (= target Short)) (short v)
    (or (= target Boolean/TYPE) (= target Boolean)) (boolean v)
    (= target String) (some-> v str)
    :else v))

(defn to-entry
  "Builds an instance of the Java record `type` from a Clojure map, matching
  record components by keyword (or string) name through the canonical
  constructor, with numeric coercion (a Clojure long fills an int field).
  A value that is already an instance of `type` passes through."
  [^Class type m]
  (cond
    (instance? type m) m
    (map? m)
    (let [components (.getRecordComponents type)
          types (into-array Class (map #(.getType ^RecordComponent %) components))
          args (object-array
                (map (fn [^RecordComponent c]
                       (let [k (keyword (.getName c))
                             v (if (contains? m k) (get m k) (get m (.getName c)))]
                         (coerce-component (.getType c) v)))
                     components))]
      (.newInstance (.getDeclaredConstructor type types) args))
    :else (throw (ex-info "expected a map or an entry instance"
                          {:type type :value m}))))

(defn from-entry
  "Turns a Java record into a Clojure map keyed by component name, with the
  raw object under `:agentspaces/raw` metadata; anything that is no record
  passes through unchanged (nil included)."
  [obj]
  (if (and (some? obj) (.isRecord (class obj)))
    (with-meta
      (into {}
            (map (fn [^RecordComponent c]
                   [(keyword (.getName c))
                    (.invoke (.getAccessor c) obj (object-array 0))]))
            (.getRecordComponents (class obj)))
      {:agentspaces/raw obj})
    obj))

(defn raw
  "The raw Java entry behind a map `from-entry` produced, or the value
  itself when it never was a record."
  [entry]
  (or (:agentspaces/raw (meta entry)) entry))

;; ------------------------------------------------------------- templates

(defn- predicate-matcher [f]
  (Matchers/predicate (reify Predicate (test [_ v] (boolean (f v))))))

(defn- num-matcher
  "Numeric bounds compare numerically, so a Clojure long matches an int
  record component; Java equals/compareTo would separate the boxes."
  [f bound]
  (predicate-matcher (fn [v] (and (number? v) (f v bound)))))

(defn- comparison [java-op clj-op bound]
  (if (number? bound) (num-matcher clj-op bound) (java-op bound)))

(defn- matcher [spec]
  (cond
    (instance? ai.badmonkey.agentspaces.api.space.Matcher spec) spec
    (fn? spec) (predicate-matcher spec)
    (vector? spec)
    (let [[op & args] spec
          bound (first args)]
      (case op
        :eq (comparison #(Matchers/eq %) == bound)
        :ne (comparison #(Matchers/ne %) (fn [v b] (not (== v b))) bound)
        :gt (comparison #(Matchers/gt %) > bound)
        :gte (comparison #(Matchers/gte %) >= bound)
        :lt (comparison #(Matchers/lt %) < bound)
        :lte (comparison #(Matchers/lte %) <= bound)
        :in (if (every? number? args)
              (predicate-matcher (fn [v] (and (number? v) (some #(== v %) args))))
              (Matchers/in (object-array args)))
        :contains (Matchers/contains ^CharSequence bound)
        :nil? (Matchers/isNull)
        :some? (Matchers/notNull)))
    (number? spec) (num-matcher == spec)
    :else (Matchers/eq spec)))

(defn template
  "Compiles an entry class and optional `:where` and `:tags` maps into a
  Template. Each `:where` value is an equality by default; a vector names an
  operator: [:gte 5], [:ne \"x\"], [:in \"a\" \"b\"], [:contains \"frag\"],
  [:nil?], [:some?]; a function becomes a predicate matcher; a Matcher passes
  through. `:tags` applies the same specs to the entry's tags (whereTag);
  a tag spec of `:some?` means the tag must be present whatever its value.

      (template TaskEntry {:where {:topic \"indexing\" :priority [:gte 5]}
                           :tags {:region \"eu\"}})"
  (^Template [^Class type] (Template/of type))
  (^Template [^Class type {:keys [where tags]}]
   (as-> (Template/of type) t
     (reduce-kv (fn [^Template t k spec] (.where t (name k) (matcher spec))) t (or where {}))
     (reduce-kv (fn [^Template t k spec]
                  (.whereTag t (name k) (matcher (if (= spec :some?) [:some?] spec))))
                t (or tags {})))))
