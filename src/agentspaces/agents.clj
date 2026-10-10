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


(ns agentspaces.agents
  "The programming model, Clojure-shaped (L3L4-COVERAGE.md §6.2): `defagent`
  writes a Java interface whose methods carry the fleet annotations as
  metadata and a constructor that reifies it, so an agent reads as a Clojure
  form and binds through the Java `AgentBinder` via the facade; nothing is
  reimplemented. Verbs: take, notify, bid, ballot, on-decision, ordered-take,
  on-estimate, propose, join, reduce.

      (defagent Pricer
        {:description \"Prices an order\" :goals [\"price orders\"]}
        (take price [^PriceRequest r] {:space \"tasks\" :lease \"10m\"} ^Quote
          (->Quote (:item r) (:quantity r) (* 4.25 (:quantity r))))
        (ballot judge [^Proposal p] {:space \"votes\"} ^String
          (if (> (:amount p) 100) \"approve\" \"deny\")))

      (bind! system (->Pricer))

  Parameters arrive as maps (`core/from-entry`); a return hinted with a
  record class is converted back (`core/to-entry`); nil abstains or writes
  nothing, as under Java. Four further returns (`convert-out`): a `motion`
  opens a vote, a `contribution` feeds the aggregate, a `tagged` value writes
  its entry with tags, and a vector forks into several entries."
  (:require [agentspaces.core :as core]
            [clojure.string :as str])
  (:import (ai.badmonkey.agentspaces.agent AgentBinder$Bound AgentSpaces Entries Tagged)
           (ai.badmonkey.agentspaces.agent.annotation AgentSpec Ballot BidFunction OnDecision
                                                      OnEstimate OrderedTake Part Propose
                                                      SpaceJoin SpaceNotify SpaceReduce SpaceTake)
           (ai.badmonkey.agentspaces.agent.capability Contribution Motion)
           (ai.badmonkey.agentspaces.api.ad AgentCard CardAction)
           (ai.badmonkey.agentspaces.api.security AgentCertificate)))

(set! *warn-on-reflection* true)

(def ^:private verbs
  {'take SpaceTake
   'notify SpaceNotify
   'bid BidFunction
   'ballot Ballot
   'on-decision OnDecision
   'ordered-take OrderedTake
   'on-estimate OnEstimate
   'propose Propose
   'join SpaceJoin
   'reduce SpaceReduce})

(def ^:private modes
  "The `:mode` values each verb accepts, and the enum each names."
  {'join {:enum "SpaceJoin$Mode" :values #{:local :leased :ordered}}
   'reduce {:enum "SpaceReduce$Mode" :values #{:leased :ordered}}})

(defn- camel [k]
  (let [[h & t] (str/split (name k) #"-")]
    (apply str h (map str/capitalize t))))

(defn- class-symbol
  "An annotation class as the symbol Clojure's interface generator resolves:
  it reads only symbol keys that name annotation types."
  [^Class c]
  (symbol (.getName c)))

(declare qualify)

(defn- attribute-type
  "The declared type of an annotation attribute, or an error naming the
  option and the attributes the annotation has."
  ^Class [^Class annotation k]
  (try
    (.getReturnType (.getMethod annotation (camel k) (make-array Class 0)))
    (catch NoSuchMethodException _
      (throw (ex-info (str "unknown option " k " for @" (.getSimpleName annotation) "; one of "
                           (sort (map (fn [^java.lang.reflect.Method m] (.getName m)) (.getDeclaredMethods annotation))))
                      {:option k :annotation annotation})))))

(defn- attribute-value
  "A Clojure option value as the annotation expects it: keywords become their
  names, numbers take the attribute's primitive width (a Clojure long fills
  an int attribute), class symbols resolve in the caller's namespace (inside
  vectors too, for `:produces`), an enum constant symbol (`Class/CONSTANT`,
  see `mode-symbol`) passes through for the interface generator to evaluate,
  and maps under :parts become nested @Part annotations."
  [^Class annotation k v]
  (let [type (attribute-type annotation k)]
    (cond
      ;; A nested annotation is the seq form Clojure's interface generator reads:
      ;; (AnnotationClass {attr value ...}), a map would be read as a plain value.
      (= k :parts) (mapv (fn [part] (list (class-symbol Part) (into {} (map (fn [[pk pv]] [(symbol (camel pk)) (attribute-value Part pk pv)])) part))) v)
      (keyword? v) (name v)
      (symbol? v) (qualify v)
      (vector? v) (mapv (fn [x] (if (symbol? x) (qualify x) x)) v)
      (and (number? v) (= type Integer/TYPE)) (int v)
      (and (number? v) (= type Long/TYPE)) (long v)
      (and (number? v) (= type Double/TYPE)) (double v)
      (and (number? v) (= type Float/TYPE)) (float v)
      :else v)))

(defn- annotation-meta
  "{annotation-class-symbol {attr value ...}} from a verb's option map."
  [annotation opts]
  {(class-symbol annotation) (into {} (map (fn [[k v]] [(symbol (camel k)) (attribute-value annotation k v)])) opts)})

(defn- mode-symbol
  "`:mode :local|:leased|:ordered` as the fully qualified enum constant symbol
  (`ai.badmonkey.agentspaces.agent.annotation.SpaceJoin$Mode/LEASED`): the
  interface generator evaluates it and writes the enum into the annotation."
  [verb mode]
  (let [{:keys [enum values]} (get modes verb)]
    (when-not (and enum (contains? values mode))
      (throw (ex-info (str ":mode " (pr-str mode) " is not valid on " verb
                           (if enum (str "; one of " (sort values)) "; only join and reduce take a mode"))
                      {:verb verb :mode mode})))
    (symbol (str "ai.badmonkey.agentspaces.agent.annotation." enum) (str/upper-case (name mode)))))

(defn- qualify
  "A type hint as read in the caller's namespace, resolved to its class's
  full name at expansion time, so the generated interface and reify see it
  wherever they are compiled. A namespaced symbol (an enum constant) passes
  through."
  [tag]
  (if (and (symbol? tag) (namespace tag))
    tag
    (if-let [c (and (symbol? tag) (resolve tag))]
      (if (class? c) (symbol (.getName ^Class c)) tag)
      tag)))

;; --------------------------------------------------------------- returns

(defn motion
  "A Motion for a verb body to return (declare the return `^Motion`): the
  binder opens the vote once per proposal id, as the bound agent, after the
  take completes. `:space` names the vote space (optional with one vote
  capability registered); `:lease` defaults to 1h.

      (agents/motion {:id \"release\" :question \"ship?\" :options [\"yes\" \"no\"]
                      :quorum 2 :space \"votes\" :lease \"1h\"})"
  ^Motion [{:keys [id question options quorum space lease] :or {lease "1h"}}]
  (let [options ^java.util.List (mapv str options)
        lease (core/lease lease)]
    (if space
      (Motion/in space id question options (int quorum) lease)
      (Motion/of id question options (int quorum) lease))))

(defn contribution
  "A Contribution for a verb body to return (declare the return
  `^Contribution`, so `bind!` fails fast without an aggregate): the binder
  completes the take, writes nothing, and starts (or joins) the push-sum
  epoch `epoch` with `value` as this peer's share, through the aggregate
  `:capabilities {:aggregate true}` registered.

      (notify feel [^Reading r] {:space \"readings\"} ^Contribution
        (agents/contribution (:topic r) (:value r)))"
  ^Contribution [epoch value]
  (Contribution/to (if (keyword? epoch) (name epoch) (str epoch)) (double value)))

(defn tagged
  "A tagged return: the entry (a map or a record) written with `tags` on it.
  The verb declares no return hint (the Java return is Object) and names the
  entry class under `:produces`, which converts a map entry and feeds the
  card. A plain `{:entry m :tags {..}}` map reads the same way."
  [entry tags]
  (with-meta {:entry entry :tags tags} {::tagged true}))

(defn- string-tags [tags]
  (into {} (map (fn [[k v]] [(name k) (str v)])) tags))

(defn- tagged-map? [v]
  (and (map? v)
       (or (::tagged (meta v))
           (and (contains? v :entry) (every? #{:entry :tags} (keys v))))))

(defn- record-hint?
  "Whether the hint is an entry record class (Motion, Contribution, and
  Tagged are Java records too, with their own conversions)."
  [hint]
  (and (class? hint) (.isRecord ^Class hint)
       (not= hint Motion) (not= hint Contribution) (not= hint Tagged)))

(defn- entry-type
  "The record class a map entry converts through: the return hint when it is
  an entry record, else the one `:produces` class."
  [hint produces]
  (cond
    (record-hint? hint) hint
    (= 1 (count produces)) (first produces)))

(defn- convert-entry [type value]
  (if (map? value)
    (if type
      (core/to-entry type value)
      (throw (ex-info "a map entry needs a record return hint or exactly one :produces class"
                      {:value value})))
    value))

(defn- require-object-return
  "The binder reads a Tagged's entry type from the Java generic and a fork's
  from `:produces`, so both need the method declared as returning Object:
  no return hint, plus `:produces [EntryClass]`."
  [what hint]
  (when (and (class? hint) (not= hint Object) (not= hint Entries) (not= hint Tagged))
    (throw (ex-info (str what " needs no return hint (the method returns Object) and "
                         ":produces [EntryClass]; the hint was " (.getName ^Class hint))
                    {:hint hint}))))

(defn convert-out
  "The method's return, converted for the binder by its hint and `:produces`:
  nil stays nil; a Motion, Contribution, Tagged, or Entries passes through; a
  map under a `^Motion` hint becomes a Motion (`motion`); a map under a
  `^Contribution` hint (`{:epoch .. :value ..}`) becomes a Contribution
  (`contribution`); a `tagged` map becomes a Tagged
  with its entry converted; a vector becomes an Entries fork with each
  element converted (maps through the hint or the one `:produces` class,
  records as they are); a map under a record hint becomes that record."
  [hint produces value]
  (cond
    (nil? value) nil
    (or (instance? Motion value) (instance? Contribution value)
        (instance? Tagged value) (instance? Entries value)) value
    (= hint Motion) (if (map? value) (motion value) value)
    (= hint Contribution) (if (map? value) (contribution (:epoch value) (:value value)) value)
    (tagged-map? value)
    (do (require-object-return "a tagged return" hint)
        (Tagged/of (convert-entry (entry-type hint produces) (:entry value))
                   ^java.util.Map (string-tags (:tags value))))
    (vector? value)
    (do (require-object-return "a fork (vector return)" hint)
        (Entries/of ^java.util.List (mapv #(convert-entry (entry-type hint produces) %) value)))
    (record-hint? hint) (core/to-entry hint value)
    :else value))

;; -------------------------------------------------------------- defagent

(defmacro defagent
  "Defines an agent: `(defagent Name spec-map & verbs)` where spec-map takes
  `:name` (default the lowercased Name), `:description`, `:goals`, and each
  verb is `(verb method-name [^Type params ...] opts ^ReturnType body...)`,
  the return hint on the body's first form (wrap a literal in `(identity ..)`).
  Emits the interface `NameSpec` (the annotations and the declared types live
  there, which is what the card and remote callers read) and `->Name`, a
  constructor returning a bound-ready object.

  Options are the annotation's attributes in kebab case; `:mode` takes
  `:local`, `:leased`, or `:ordered` on a join and `:leased` or `:ordered` on
  a reduce; `:produces [EntryClass ...]` declares what an Object return
  (a `tagged` entry or a fork) writes, for the card and for map conversion."
  [name spec & forms]
  (let [iface (symbol (str name "Spec"))
        agent-name (or (:name spec) (str/lower-case (str name)))
        spec-meta {(class-symbol AgentSpec) {'name agent-name
                              'description (or (:description spec) "")
                              'goals (vec (or (:goals spec) []))}}
        parsed (doall
                (for [[verb method params opts & body] forms]
                  (let [annotation (or (get verbs verb)
                                       (throw (ex-info (str "unknown verb " verb ": one of " (keys verbs))
                                                       {:verb verb})))
                        ret-tag (qualify (:tag (meta (first body))))
                        produces (mapv qualify (:produces opts))
                        opts (cond-> opts (contains? opts :mode) (assoc :mode (mode-symbol verb (:mode opts))))
                        param-syms (mapv (fn [p] (with-meta (gensym "arg") {:tag (qualify (:tag (meta p)))})) params)]
                    {:method method :params params :param-syms param-syms :ret-tag ret-tag
                     :produces produces :body body :annotation annotation :opts opts})))]
    `(do
       (definterface ~(with-meta iface spec-meta)
         ~@(for [{:keys [method param-syms ret-tag annotation opts]} parsed]
             (list (with-meta method (cond-> (annotation-meta annotation opts) ret-tag (assoc :tag ret-tag)))
                   param-syms)))
       (defn ~(symbol (str "->" name)) []
         (reify ~iface
           ~@(for [{:keys [method params param-syms ret-tag produces body]} parsed]
               `(~(if ret-tag (with-meta method {:tag ret-tag}) method) [~'_ ~@param-syms]
                 (let [~@(mapcat (fn [p s] [(with-meta p {}) `(core/from-entry ~s)]) params param-syms)]
                   (convert-out ~ret-tag ~produces (do ~@body))))))))))

(defn bind!
  "Binds an agent object (from a `defagent` constructor, or any object whose
  class or interfaces carry the Java annotations) through the facade of a
  `fleet/start` system: its loops start, its card publishes, and the
  returned handle closes them. Capabilities the verbs need come from the
  config's `:capabilities` block."
  ^AgentBinder$Bound [{:keys [^AgentSpaces facade] :as system} agent]
  (when-not facade
    (throw (ex-info "the system has no facade; start it with fleet/start" {:keys (keys system)})))
  (let [bound (.bind facade agent)]
    (when (empty? bound)
      (throw (ex-info (str "no group of this peer satisfies " (class agent)
                           ": a verb names a space it does not register, or a capability"
                           " :capabilities does not provide")
                      {:agent agent :spaces (keys (:spaces system))})))
    (first bound)))

(defn unbind!
  "Closes a bound agent: its loops stop and its card lapses."
  [^AgentBinder$Bound bound]
  (.close bound))

(defn- certificate->map
  "An AgentCertificate (SPEC 4.2, v0.1.13) as a map of its fields, with the
  expiry it implies; nil for nil."
  [^AgentCertificate c]
  (when c
    {:agent (.agent c)
     :agent-public-key (.agentPublicKey c)
     :issued (.issued c)
     :ttl (.ttl c)
     :expires-at (.expiresAt c)
     :peer-signature (.peerSignature c)
     :encryption-public-key (.encryptionPublicKey c)}))

(defn card
  "The bound agent's AgentCard as a map of its public fields: `:agent`,
  `:description`, `:goals`, `:consumes`, `:produces`, `:actions` (each
  `{:name :kind :description}`), and, for an agent bound under
  `:agent-keys :subordinate`, `:agent-public-key` (the key it signs with)
  and `:certificate` (the peer-signed AgentCertificate that proves it, as a
  map: `:agent`, `:agent-public-key`, `:issued`, `:ttl`, `:expires-at`,
  `:peer-signature`, `:encryption-public-key`); both nil for a peer-signed
  agent."
  [^AgentBinder$Bound bound]
  (let [^AgentCard c (.card bound)]
    {:agent (.localName (.agent c)) :description (.description c) :goals (vec (.goals c))
     :consumes (vec (.consumes c)) :produces (vec (.produces c))
     :actions (mapv (fn [^CardAction a] {:name (.name a) :kind (.kind a) :description (.description a)})
                    (.actions c))
     :agent-public-key (.agentPublicKey c)
     :certificate (certificate->map (.agentCertificate c))}))
