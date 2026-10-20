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

(ns agentspaces.fleet
  "One peer in a fleet, from a config map to a running system map: identity,
  node, joined group, replicated spaces, ticking, all in `start`, all closed
  by `stop!`. The config shape mirrors the Spring starter's properties
  (spec 10.1), so moving between the two is renaming keys.

      (def system
        (fleet/start
          {:bind \"127.0.0.1:7591\"
           :security {:profile :mtls}
           :group {:name \"research\"
                   :founding \"research-fleet-v1\"
                   :seeds [\"127.0.0.1:7590\"]
                   :spaces [{:name \"tasks\" :settle \"150ms\"}
                            {:name \"tasks-auction\" :strategy :auction
                             :bid-fn (fn [task] (estimate task))}]}}))
      (fleet/space system \"tasks\")
      (fleet/stop! system)"
  (:refer-clojure :exclude [agent])
  (:require [agentspaces.core :as core]
            [clojure.string :as str])
  (:import (ai.badmonkey.agentspaces.api.ad GroupAdvertisement
                                            GroupAdvertisement$GossipParameters
                                            GroupAdvertisement$MembershipPolicy
                                            PeerAdvertisement$Endpoint
                                            PeerAdvertisement$PeerRole
                                            SpaceAdvertisement$Admission)
           (ai.badmonkey.agentspaces.agent AgentIdentityFactory AgentSpaces AgentSpaces$GroupContext)
           (ai.badmonkey.agentspaces.capabilities.aggregate PushSumAggregate)
           (ai.badmonkey.agentspaces.capabilities.learn GossipLearner GossipLearner$Builder
                                                        GossipLearning WeightAveraging)
           (ai.badmonkey.agentspaces.capabilities.orderedlog OrderedTakes)
           (ai.badmonkey.agentspaces.capabilities.runtime CapabilityPipes CapabilityRuntime)
           (ai.badmonkey.agentspaces.capabilities.semantic HashingEmbedder SemanticDiscovery)
           (ai.badmonkey.agentspaces.capabilities.vote VoteCapability)
           (ai.badmonkey.agentspaces.api.security SecurityProfile)
           (ai.badmonkey.agentspaces.api.space ConflictStrategyType)
           (ai.badmonkey.agentspaces.api.ad CredentialRevocation CredentialRevocation$Target)
           (ai.badmonkey.agentspaces.api.spi AgentIdentity Authorizer Authorizer$Operation Transport)
           (ai.badmonkey.agentspaces.capabilities.keywrap GroupKeyDistributor RingStore)
           (ai.badmonkey.agentspaces.capabilities.runtime CapabilityPipes CapabilityRuntime)
           (ai.badmonkey.agentspaces.common.crypto Digests GroupKeyRing)
           (ai.badmonkey.agentspaces.common.codec CborCodec)
           (ai.badmonkey.agentspaces.common.crypto GroupKey)
           (ai.badmonkey.agentspaces.common.id AgentId GroupId PeerId)
           (ai.badmonkey.agentspaces.discovery DiscoveryService)
           (ai.badmonkey.agentspaces.identity AgentKeystore ChannelTrust FileKeystore
                                              PeerIdentity ServingCredential)
           (ai.badmonkey.agentspaces.peering.blocks BlockExchange)
           (ai.badmonkey.agentspaces.peering.membership GroupMembership$Config
                                                        GroupScopedAuthorizer
                                                        MembershipAuthorizer)
           (ai.badmonkey.agentspaces.peering.node GroupFounding GroupRuntime
                                                  JoinCredentials PeerNode
                                                  PeerNode$Builder PeerNode$ChannelAuth
                                                  SignedGroupAdvertisement)
           (ai.badmonkey.agentspaces.peering.transport TcpTransport TlsTcpTransport)
           (ai.badmonkey.agentspaces.space DiscoveryPublisher)
           (ai.badmonkey.agentspaces.space.replicated CredentialIndex ReplicatedSpace
                                                      ReplicatedSpace$Builder
                                                      SpaceAdmission)
           (java.io InputStream)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.security KeyStore)
           (java.security.cert CertificateFactory X509Certificate X509CRL)
           (java.time Duration Instant InstantSource)
           (java.util Base64)
           (java.util.concurrent TimeoutException)
           (java.util.function BiConsumer Predicate ToDoubleFunction)))

(set! *warn-on-reflection* true)

;; -------------------------------------------------------------- identity

(defn- ->path ^Path [s] (Path/of ^String s (make-array String 0)))

(defn- ->identity ^PeerIdentity [{:keys [identity keystore]}]
  (cond
    (instance? PeerIdentity identity) identity
    keystore (FileKeystore/loadOrCreate (->path keystore))
    :else (PeerIdentity/generate)))

(defn- ->strategy ^ConflictStrategyType [kw]
  (ConflictStrategyType/valueOf
   (-> (name (or kw :lease-race)) str/upper-case (str/replace "-" "_"))))

(defn- ->policy ^GroupAdvertisement$MembershipPolicy [kw]
  (GroupAdvertisement$MembershipPolicy/valueOf (str/upper-case (name (or kw :open)))))

(defn- ->roles ^java.util.Set [roles]
  (into #{} (map #(PeerAdvertisement$PeerRole/valueOf (str/upper-case (name %))))
        (or roles #{})))

;; ------------------------------------------------------- security posture

(defn- ->profile ^SecurityProfile [kw]
  (SecurityProfile/fromName (name (or kw :dev-local))))

(defn- posture
  "Resolves the effective transport and channel-auth keywords: an explicit
  `:transport` / `:channel-auth` wins, otherwise the profile decides, as the
  starter does with agentspaces.transport.* over agentspaces.security.profile.
  With no `:security` at all the profile is :dev-local (plain TCP, signed
  frames), which is what earlier versions of these bindings did."
  [{:keys [transport channel-auth security]}]
  (let [profile-kw (or (:profile security) :dev-local)
        ^SecurityProfile profile (->profile profile-kw)]
    {:profile profile-kw
     :oidc? (.oidcAuthorization profile)
     :transport (or transport (if (.tlsTransport profile) :tls :tcp))
     :channel-auth (or channel-auth
                       (cond (.sessionAuthentication profile) :session
                             (.envelopeSignatureEveryHop profile) :signed
                             :else :attested))}))

(def ^:private grant-operations
  {:raft-voter Authorizer$Operation/RAFT_VOTER
   :directive-issuer Authorizer$Operation/DIRECTIVE_ISSUER
   :connector-serve Authorizer$Operation/CONNECTOR_SERVE
   :key-holder Authorizer$Operation/KEY_HOLDER
   :space-write Authorizer$Operation/SPACE_WRITE
   :space-take Authorizer$Operation/SPACE_TAKE
   :vote Authorizer$Operation/VOTE
   :model-serve Authorizer$Operation/MODEL_SERVE
   :key-rotator Authorizer$Operation/KEY_ROTATE})

(defn- ->grants
  "agentspaces.security.grants.*: {:directive-issuer [peer ..] ..} to
  {Operation #{PeerId ..}}; an empty or absent list leaves the operation open
  to any admitted member."
  ^java.util.Map [grants]
  (reduce-kv (fn [m k peers]
               (let [op (or (get grant-operations k)
                            (throw (ex-info "unknown grant operation"
                                            {:operation k :known (keys grant-operations)})))]
                 (if (seq peers)
                   (assoc m op (into #{} (map core/peer-id) peers))
                   m)))
             {}
             (or grants {})))

(defn- ->authorizer
  "The profile's answerer (spec 11): a MembershipAuthorizer over the joined
  group under :dev-local and :mtls; under :mtls-oidc and :zero-trust an
  `:authorizer` instance the caller built (the OIDC authorizer lives in
  agentspaces-auth-oidc, which these bindings do not depend on). An explicit
  `:authorizer` wins under any profile."
  ^Authorizer [{:keys [security]} {:keys [oidc? profile]} ^GroupRuntime runtime ^PeerIdentity identity]
  (let [inner (cond
                (:authorizer security) (:authorizer security)
                oidc? (throw (ex-info (str "security profile " (name profile) " authorizes through"
                                           " an identity provider: pass an Authorizer under"
                                           " [:security :authorizer] (agentspaces-auth-oidc's"
                                           " OidcAuthorizer, or your own)")
                                      {:profile profile}))
                :else (MembershipAuthorizer. (.membership runtime) (.peerId identity)
                                             (->grants (:grants security))))]
    ;; v0.1.13 (review M-4): scoped to the group, so what the group revoked is
    ;; refused, and a shared identity-provider authorizer also needs membership.
    (GroupScopedAuthorizer. inner (.revocationView runtime)
                            (when (or oidc? (:authorizer security)) (.membership runtime))
                            (.peerId identity))))

(defn- agent-identities
  "The agent identity factory `:agent-keys` selects (SPEC 4.2, v0.1.13), as
  the starter's agentspaces.identity.*: `:peer` (default) signs agents with
  the peer key; `:subordinate` gives each agent a renewing, peer-certified key
  of its own (with a certified X25519 key, SPEC 11a.2a), persisted under
  `:agent-keystore` when set, each certificate lasting `:agent-certificate-ttl`
  (default \"24h\")."
  [{:keys [agent-keys agent-keystore agent-certificate-ttl] :or {agent-certificate-ttl "24h"}}
   ^PeerIdentity identity]
  (case (or agent-keys :peer)
    :peer (do (when agent-keystore
                (throw (ex-info (str ":agent-keystore is set but :agent-keys is :peer: peer-signed"
                                     " agents have no keys to persist; use :agent-keys :subordinate")
                                {:agent-keystore agent-keystore})))
              (fn [agent-name] (.agentIdentity identity ^String agent-name)))
    :subordinate
    (let [ttl (core/duration agent-certificate-ttl)
          clock (InstantSource/system)
          store (when agent-keystore (AgentKeystore/at (->path agent-keystore)))]
      (memoize
       (fn [agent-name]
         (if store
           (.renewingSubordinate identity agent-name
                                 (.signingKeys store (.peerId identity) agent-name)
                                 (.encryptionKeys store (.peerId identity) agent-name) ttl clock)
           (.renewingSubordinate identity agent-name
                                 (ai.badmonkey.agentspaces.common.crypto.Ed25519/generate)
                                 (ai.badmonkey.agentspaces.common.crypto.X25519/generate) ttl clock)))))
    (throw (ex-info ":agent-keys must be :peer or :subordinate" {:agent-keys agent-keys}))))

;; ------------------------------------------------------------- transport

(defn- certificates-in
  "The X.509 certificates of a PKCS#12 (or .jks) trust store, the starter's
  private loader replicated."
  ^java.util.List [store-path password]
  (let [path (->path store-path)
        store (KeyStore/getInstance (if (str/ends-with? (str/lower-case store-path) ".jks")
                                      "JKS" "PKCS12"))]
    (with-open [^InputStream in (Files/newInputStream path (make-array java.nio.file.OpenOption 0))]
      (.load store in (when password (.toCharArray ^String password))))
    (let [certs (into [] (comp (map #(.getCertificate store ^String %))
                               (filter #(instance? X509Certificate %)))
                      (enumeration-seq (.aliases store)))]
      (when (empty? certs)
        (throw (ex-info "no certificates in trust store" {:path store-path})))
      certs)))

(defn- read-crl ^X509CRL [crl-path]
  (with-open [^InputStream in (Files/newInputStream (->path crl-path)
                                                    (make-array java.nio.file.OpenOption 0))]
    (.generateCRL (CertificateFactory/getInstance "X.509") in)))

(defn- tls-transport
  "The TLS transport (spec 5.6): identity-endorsed self-signed mode without a
  `:trust-store`; the enterprise-CA mode with one, where peers attest only
  when their chain validates to the store's CAs, revocation is checked
  against `:crls` (or live under `:revocation-strict`), and `:key-store`
  supplies this node's CA-issued credential."
  ^TlsTcpTransport [^PeerIdentity identity
                    {:keys [key-store key-store-password trust-store trust-store-password
                            crls revocation-strict]}]
  (if-not trust-store
    (TlsTcpTransport. identity)
    (let [authorities (certificates-in trust-store trust-store-password)
          crl-list (mapv read-crl crls)
          ^ChannelTrust trust (if (seq crl-list)
                                (ChannelTrust/withCrls authorities crl-list)
                                (ChannelTrust/of authorities))
          trust (if revocation-strict (.strictOnline trust) trust)
          ^String key-password (or key-store-password "")
          serving (when key-store
                    (ServingCredential/fromKeyStore (->path key-store)
                                                    (.toCharArray key-password)))]
      (TlsTcpTransport. identity ^ServingCredential serving trust))))

(defn- ->transport ^Transport [transport ^PeerIdentity identity tls]
  (case transport
    :tcp (TcpTransport.)
    :tls (tls-transport identity tls)
    transport))

(defn- ->endpoint ^PeerAdvertisement$Endpoint [seed default-scheme priority]
  (let [seed (str/trim seed)
        [scheme address] (if-let [i (str/index-of seed "://")]
                           [(subs seed 0 i) (subs seed (+ i 3))]
                           [default-scheme seed])]
    (PeerAdvertisement$Endpoint. scheme address (int priority))))

(defn- ->socket-address ^InetSocketAddress [host-port]
  (let [s (str/trim host-port)
        i (str/last-index-of s ":")]
    (when (or (nil? i) (zero? i) (= i (dec (count s))))
      (throw (ex-info "expected host:port" {:value host-port})))
    (InetSocketAddress. (subs s 0 i) (int (parse-long (subs s (inc i)))))))

;; ------------------------------------------------------------------ node

(defn- build-node
  ^PeerNode [{:keys [roles require-attestation multicast revocation-validator security peer-ad-interval lazy-push]}
             {:keys [channel-auth]} ^PeerIdentity identity]
  (let [^PeerNode$Builder builder
        (-> (PeerNode/builder identity)
            (.channelAuth (case channel-auth
                            :attested PeerNode$ChannelAuth/ATTESTED
                            :session PeerNode$ChannelAuth/SESSION
                            :signed PeerNode$ChannelAuth/SIGNED))
            (.roles (->roles roles))
            (.requireAttestation (boolean require-attestation)))]
    (when peer-ad-interval
      (.peerAdInterval builder (core/duration peer-ad-interval)))
    (when (some? lazy-push)
      (.lazyPush builder (boolean lazy-push)))
    (when multicast
      (let [{:keys [group interval] :or {group "239.255.42.99:7787" interval "5s"}}
            (if (map? multicast) multicast {})]
        (.multicast builder (->socket-address group) (core/duration interval))))
    (when revocation-validator
      (.revocationValidator builder revocation-validator))
    (when-let [f (:on-credential-hints security)]
      (.onCredentialHints builder (reify BiConsumer
                                    (accept [_ peer hints]
                                      (f peer (into {} hints))))))
    (.build builder)))

;; ----------------------------------------------------------------- group

(defn found-group
  "Founds a self-certifying group (spec 4.4, 5.1): signs the founding fields
  with `identity` and returns the SignedGroupAdvertisement to join with. Its
  GroupId is the hash of the signed document, so other peers can join it by
  id alone (`:group {:join \"aspace://<id>\"}`) and verify what the seeds
  hand them. `start` does this for you with `:group {:found true}`.

      (found-group identity {:name \"research\" :strategy :lease-race
                             :policy :open :ttl \"365d\"})"
  ^SignedGroupAdvertisement [^PeerIdentity identity
                             {:keys [name strategy policy ttl issued] :or {ttl "365d"}}]
  (GroupFounding/found identity name (->policy policy) (->strategy strategy)
                       (GroupAdvertisement$GossipParameters/defaults)
                       (or issued (Instant/now)) (core/duration ttl)))

(defn- group-advertisement
  "The literal-id advertisement of a fleet whose members share configuration:
  every member derives the same GroupId from `:founding` (the group name when
  absent)."
  ^GroupAdvertisement [{:keys [name founding strategy]}]
  (let [group-id (GroupId/fromFounding (.getBytes (str (or founding name))
                                                  StandardCharsets/UTF_8))]
    (GroupAdvertisement. (str "aspace://" (.value group-id))
                         (.peerId (PeerIdentity/generate))
                         group-id Instant/EPOCH (Duration/ofDays 365)
                         name GroupAdvertisement$MembershipPolicy/OPEN
                         (->strategy strategy)
                         (GroupAdvertisement$GossipParameters/defaults))))

(defn- membership-config ^GroupMembership$Config
  [{:keys [ttl probe indirect] :or {ttl "30s" probe "2s" indirect 2}}]
  (GroupMembership$Config. (core/duration ttl) (core/duration probe) (int indirect)))

(defn- ->group-id ^GroupId [join]
  (if (instance? GroupId join) join (GroupId/of (.value (core/peer-id join)))))

(defn- validate-group
  "The starter's fail-fast checks, before any port is bound: `:group` is
  required, `:join` excludes `:founding` and `:found`, and `:join` needs
  seeds to fetch the founding advertisement from."
  [{:keys [name join found founding seeds] :as group}]
  (when-not (map? group)
    (throw (ex-info "fleet/start needs a :group map" {:group group})))
  (when (and join (or founding found))
    (throw (ex-info (str "group '" name "' sets :join together with :founding or"
                         " :found; a group is joined by id, founded, or derived,"
                         " not two of those")
                    {:group (dissoc group :spaces)})))
  (when (and join (empty? seeds))
    (throw (ex-info (str "group '" name "' joins by GroupId but configures no"
                         " seeds to fetch the founding advertisement from")
                    {:join join})))
  group)

(defn- join-group
  "Joins the configured group one of three ways, mutually exclusive as in
  the starter: `:join` fetches and verifies the founding document from the
  seeds; `:found` founds (true) or joins with (an instance) a self-certifying
  advertisement; otherwise `:founding`/`:name` derive a literal id locally.
  Returns [runtime signed-founding-or-nil]."
  [^PeerNode node ^PeerIdentity identity
   {:keys [name join found seeds join-timeout membership] :or {join-timeout "10s"} :as group}
   scheme]
  (let [^java.util.List endpoints (mapv #(->endpoint %2 scheme %1) (range) seeds)
        config (membership-config membership)]
    (cond
      join
      (let [group-id (->group-id join)]
        (try
          [(.joinGroup node group-id config endpoints (core/duration join-timeout)) nil]
          (catch TimeoutException e
            (throw (ex-info (str "group '" name "': no seed served a verified founding"
                                 " advertisement for " group-id " within " join-timeout)
                            {:join join :seeds seeds} e)))))

      found
      (let [^SignedGroupAdvertisement signed
            (if (instance? SignedGroupAdvertisement found)
              found
              (found-group identity group))]
        [(.joinGroup node signed config endpoints) signed])

      :else
      [(.joinGroup node (group-advertisement group) config endpoints) nil])))

;; ---------------------------------------------------------------- spaces

(defn- ->content-key ^GroupKey [k]
  (cond
    (nil? k) nil
    (instance? GroupKey k) k
    (bytes? k) (GroupKey/fromBytes k)
    (string? k) (GroupKey/fromBytes (.decode (Base64/getDecoder) (str/trim ^String k)))
    :else (throw (ex-info "content key must be 32 bytes, their Base64, or a GroupKey"
                          {:value k}))))

(defn- tag-shard-predicate
  "A `:tag-shard` as a Java predicate over the wire's String->String tag map:
  a function sees it as a keyword map; a literal map means these tag pairs
  must all be present."
  ^Predicate [shard]
  (let [f (if (map? shard)
            (let [wanted (into {} (map (fn [[k v]] [(keyword k) (str v)])) shard)]
              (fn [tags] (= wanted (select-keys tags (keys wanted)))))
            shard)]
    (reify Predicate
      (test [_ tags]
        (boolean (f (into {} (map (fn [[k v]] [(keyword k) v])) tags)))))))

(defn- apply-admission
  ^ReplicatedSpace$Builder [^ReplicatedSpace$Builder builder ^Authorizer authorizer
                            {:keys [name admission allowed-agents credential-issuer]}]
  (case (or admission :group)
    :group builder
    :allowlist
    (let [^java.util.Set allowed (into #{} (map core/agent-id) allowed-agents)]
      (when (empty? allowed)
        (throw (ex-info (str "space '" name "' uses allowlist admission but lists no"
                             " :allowed-agents")
                        {:space name})))
      (.admission builder SpaceAdvertisement$Admission/ALLOWLIST allowed))
    :credential
    (if credential-issuer
      (.admission builder (SpaceAdmission/credentials (core/peer-id credential-issuer)
                                                      (CredentialIndex.)))
      (.admission builder SpaceAdvertisement$Admission/CREDENTIAL ^java.util.Set #{}))
    :authorizer
    (.admission builder (SpaceAdmission/authorizer authorizer name))))

(defn- build-space
  ^ReplicatedSpace
  [{:keys [runtime identity agent-name blocks discovery key-ring authorizer]}
   {:keys [name strategy settle bid-fn max-lease founder-lease tag-shard]
    :or {settle "200ms"} :as space-config}]
  (let [builder (-> (ReplicatedSpace/builder runtime name identity agent-name)
                    (.settleWindow (core/duration settle))
                    (.strategy (->strategy strategy))
                    (.blocks blocks)
                    (apply-admission authorizer space-config))]
    (when discovery
      (.advertise builder (reify DiscoveryPublisher
                            (publish [_ signed]
                              (.publish ^DiscoveryService discovery signed)))))
    (when key-ring (.keyRing builder key-ring))
    (when max-lease (.maxLease builder (core/duration max-lease)))
    (when founder-lease (.founderLease builder (core/duration founder-lease)))
    (when tag-shard (.tagShard builder (tag-shard-predicate tag-shard)))
    (when bid-fn
      (.bidFunction builder
                    (reify ToDoubleFunction
                      (applyAsDouble [_ task]
                        (double (bid-fn (core/from-entry task)))))))
    (.build builder)))

;; --------------------------------------------------- content-key rotation

(defn- content-key-ring
  "The group's one content-key ring, shared by its spaces (SPEC 11a.3,
  v0.1.13), epoch 0 being `:content-key`; persisted under `:keystore` when
  set. Nil for an unencrypted group."
  [config group ^GroupRuntime runtime codec]
  (let [rotation (:content-key-rotation group)]
    (when-let [key (->content-key (:content-key group))]
      (let [ring (GroupKeyRing/of key)]
        (when-let [grace (:writer-grace rotation)]
          (.writerGrace ring (core/duration grace)))
        (when-let [keystore (:keystore config)]
          (.attach (RingStore. (->path keystore) (.id runtime)
                               (FileKeystore/encryptionKeys (->path keystore)) codec)
                   ring))
        ring))))

(defn- start-key-wrap
  "Runs the key-wrap capability over the group's ring whenever the group has
  a `:content-key` (SPEC 11a.2, 11a.3), as the starter does: serves and
  follows the ring, so a member follows rotations whether or not it sets
  `:content-key-rotation`, and rotates on `:rotate-every` when this peer may.
  Returns the distributor, or nil for an unencrypted group."
  [config group ^GroupRuntime runtime ^PeerIdentity identity ^GroupKeyRing ring
   ^Authorizer authorizer ^DiscoveryService discovery codec]
  (let [{:keys [rotate-every cutover-delay] :as rotation} (:content-key-rotation group)]
    (when (and rotation (nil? ring))
      (throw (ex-info (str "group '" (:name group) "' sets :content-key-rotation but no"
                           " :content-key to rotate")
                      {:group (:name group)})))
    (when ring
      (let [pipes (CapabilityPipes. runtime codec)
            keys (if-let [keystore (:keystore config)]
                   (GroupKeyDistributor. pipes (.peerId identity) codec (InstantSource/system)
                                         (FileKeystore/encryptionKeys (->path keystore)))
                   (GroupKeyDistributor. pipes (.peerId identity) codec (InstantSource/system)))]
        (.serve keys ring authorizer (.id runtime))
        (.follow keys ring discovery)
        (when rotate-every
          (when-not (.rotator keys (.peerId identity))
            (throw (ex-info (str "group '" (:name group) "' sets :rotate-every but this peer may"
                                 " not rotate: it is not the founder and holds no :key-rotator grant")
                            {:group (:name group)})))
          (.autoRotate keys identity (core/duration rotate-every)
                       (if cutover-delay
                         (core/duration cutover-delay)
                         (.plusSeconds (.multipliedBy (.period (.gossip (.advertisement runtime))) 2) 30))))
        (.register (CapabilityRuntime. runtime discovery identity) keys)
        keys))))

;; ----------------------------------------------------------------- start

;; ------------------------------------------------- facade and capabilities

(defn- build-facade
  "The AgentSpaces facade over this peer's one group (the starter's shape):
  agents bind through it, typed clients resolve from it, and the
  capabilities the annotations need are registered on it. Present always;
  cheap when unused."
  [config ^PeerIdentity identity agents ^GroupRuntime runtime discovery spaces agent-name codec]
  (let [facade (AgentSpaces. identity (InstantSource/system)
                             (reify AgentIdentityFactory
                               (identityFor [_ agent-name] (agents agent-name))))
        group-name (get-in config [:group :name])
        ^AgentSpaces$GroupContext group (.register facade group-name (.id runtime) runtime discovery)]
    (when discovery
      (.capabilities group (CapabilityRuntime. runtime discovery identity)))
    (doseq [[space-name ^ReplicatedSpace space] spaces]
      (.space group ^String space-name space (.agent identity ^String agent-name)))
    {:facade facade :group group}))

(defn- ->peer-ids ^java.util.List [members]
  (mapv #(core/peer-id %) members))

(defn- build-capabilities
  "The `:capabilities` block, each key one layer-4 service registered on the
  facade's group so `agents/bind!` finds it and the typed clients resolve:

    :vote      {:space \"votes\"}                 VoteCapability over that space
    :aggregate true                              PushSumAggregate
    :ordered   {:space \"orders\" :members [peer-ids] :seed 1}
                                                 the Raft log over the members and
                                                 the ORDERED take coordinator on that space
    :semantic  true                              SemanticDiscovery (hashing embedder)
    :learn     true                              GossipLearning (weight averaging)
    :learn     {:metrics \"metrics\" :metrics-lease \"1h\"}
                                                 the same, writing each `end-epoch!`
                                                 evaluation into that space (default
                                                 lease 1h); without it `end-epoch!` throws

  Returns a map of the provider instances by key. Needs a discovery service,
  which `:capabilities` switches on."
  [{:keys [capabilities] :as config} {:keys [^AgentSpaces$GroupContext group]}
   ^GroupRuntime runtime ^PeerIdentity identity discovery ^CborCodec codec spaces]
  (when (seq capabilities)
    (when-not discovery
      (throw (ex-info ":capabilities needs a discovery service" {:capabilities capabilities})))
    (let [pipes (.pipes group)
          clock (InstantSource/system)
          agent-name (or (get-in config [:group :agent]) "clj")
          space-named (fn [k space-name]
                        (or (get spaces space-name)
                            (throw (ex-info (str ":capabilities " k " names a space this peer"
                                                 " does not register")
                                            {:space space-name :spaces (keys spaces)}))))
          space-of (fn [k] (space-named k (get-in capabilities [k :space])))
          vote (when (:vote capabilities)
                 (let [^ReplicatedSpace votes (space-of :vote)
                       v (VoteCapability. votes (.agent identity ^String agent-name) (.peerId identity) clock)]
                   (.vote group ^String (get-in capabilities [:vote :space]) v)
                   v))
          aggregate (when (:aggregate capabilities)
                      (let [a (PushSumAggregate. pipes (.sampler runtime) (.peerId identity) codec clock)]
                        (.provide group a)
                        a))
          ordered (when (:ordered capabilities)
                    (let [{:keys [space members seed] :or {seed 1}} (:ordered capabilities)
                          ^ReplicatedSpace target (space-of :ordered)
                          o (OrderedTakes/over pipes codec identity ^String agent-name
                                               (->peer-ids members) clock (long seed) target)]
                      (.ordered group ^String space o)
                      o))
          semantic (when (:semantic capabilities)
                     (let [s (SemanticDiscovery. pipes runtime discovery (.peerId identity) codec clock (HashingEmbedder.))]
                       (.provide group s)
                       s))
          learn (when-let [learn-config (:learn capabilities)]
                  (let [{:keys [metrics metrics-lease] :or {metrics-lease "1h"}}
                        (if (map? learn-config) learn-config {})
                        l (if metrics
                            (GossipLearning.
                             (-> ^GossipLearner$Builder
                                 (GossipLearner/builder pipes (.sampler runtime) (.peerId identity)
                                                        codec clock WeightAveraging/INSTANCE)
                                 (.metrics ^ReplicatedSpace (space-named :learn metrics)
                                           (core/lease metrics-lease))
                                 (.build)))
                            (GossipLearning. pipes (.sampler runtime) (.peerId identity) codec clock))]
                    (.provide group l)
                    l))]
      (cond-> {}
        vote (assoc :vote vote)
        aggregate (assoc :aggregate aggregate)
        ordered (assoc :ordered ordered)
        semantic (assoc :semantic semantic)
        learn (assoc :learn learn)))))

(defn start
  "Starts one peer and returns the system map {:identity :node :runtime
  :spaces :authorizer :blocks :discovery :founding :profile :transport
  :channel-auth :facade :group-context :capabilities :config}. Config
  keys, all optional except `:group`; each names the starter property it
  mirrors:

    :capabilities  {:vote {:space ..} :aggregate true
                    :ordered {:space .. :members [..] :seed 1}
                    :semantic true
                    :learn true | {:metrics .. :metrics-lease ..}}: the layer-4 services this
                   peer provides, registered on the facade so agentspaces.agents
                   binds against them and the typed clients resolve
                   (capabilities.*)
    :agents        true to switch discovery on for agents bound through
                   agentspaces.agents even with no capability

    :identity      a PeerIdentity to reuse
    :keystore      a directory path for a persistent identity (keystore)
    :bind          \"host:port\" to listen on; omit for a dial-only peer (bind)
    :roles         a set like #{:relay :rendezvous} (roles)
    :tick          protocol round period, default \"250ms\" (tick-millis)
    :lazy-push         false to gossip with random fan-out only, instead of the
                   self-pruning tree toward members that understand it (default true)
    :peer-ad-interval  how often the self-advertisement is re-flooded, default
                   \"30s\"; newcomers get it directly in between (peer-ad-interval-millis)
    :security      {:profile :dev-local|:mtls|:mtls-oidc|:zero-trust
                    :grants {:directive-issuer [peer ..] :raft-voter [..]
                             :connector-serve [..] :key-holder [..]
                             :space-write [..] :space-take [..]
                             :vote [..] :model-serve [..]}
                    :authorizer <Authorizer>      ; required under the OIDC profiles
                    :token \"<jwt>\"                ; this node's aspace:oidc hint
                    :on-credential-hints (fn [peer-id hints])}
                   (security.profile, security.grants.*, security.oidc.token).
                   The profile picks the transport and channel-auth defaults
                   like the starter: :dev-local is tcp + signed, :mtls is
                   tls + attested, :mtls-oidc tls + attested, :zero-trust
                   tls + signed. Absent :security means :dev-local.
    :transport     :tcp or :tls (identity-endorsed channel certificates,
                   spec 5.6), or a Transport instance; overrides the profile
                   (transport.tls.enabled)
    :channel-auth  :signed, :attested or :session (unsigned frames over authenticated
                   channels); overrides the profile (transport.channel-auth)
    :tls           {:key-store .. :key-store-password .. :trust-store ..
                    :trust-store-password .. :crls [..] :revocation-strict}
                   enterprise-CA mode when :trust-store is set
                   (transport.tls.*)
    :require-attestation  frames must arrive on a channel attested for
                   their sender; pair with :transport :tls (node builder)
    :multicast     true or {:group \"239.255.42.99:7787\" :interval \"5s\"},
                   the LAN bootstrap beacon (multicast.*)
    :revocation-validator  a RevocationRegistry.RevocationValidator
    :agent-keys    :peer (default) or :subordinate: each agent gets a renewing,
                   peer-certified key (identity.agent-keys, v0.1.13)
    :agent-keystore  a directory persisting subordinate agent keys
                   (identity.agent-keystore)
    :agent-certificate-ttl  each agent certificate's lifetime, default \"24h\"
                   (identity.agent-certificate-ttl)
    :group         {:name          the group name (groups[].name)
                    :founding      literal-id string every member derives the
                                   same GroupId from (founding); default :name
                    :join          \"aspace://<id>\": fetch and verify the
                                   founding document from the seeds (join)
                    :join-timeout  default \"10s\" (join-timeout-millis)
                    :found         true to found a self-certifying group with
                                   this identity, or a SignedGroupAdvertisement
                                   to join with; see `found-group`
                    :seeds         [\"host:port\" ..] (seeds)
                    :membership    {:ttl :probe :indirect}
                    :content-key   32 bytes, their Base64, or a GroupKey;
                                   encrypts every space (content-key)
                    :content-key-rotation  {:rotate-every :cutover-delay
                                   :writer-grace}: tunes the key-wrap that
                                   runs over the group's ring whenever
                                   :content-key is set (members follow
                                   rotations either way); :rotate-every rotates
                                   on a schedule (needs the founder or a
                                   :key-rotator grant); needs :content-key
                                   (groups[].content-key-rotation, v0.1.13)
                    :advertise     true publishes each space's advertisement
                                   through a DiscoveryService (starter default)
                    :agent         the local agent name spaces write as (\"clj\")
                    :spaces [{:name :strategy :settle :bid-fn
                              :admission :group|:allowlist|:credential|:authorizer
                              :allowed-agents [\"<peerId>/<name>\" ..]
                              :credential-issuer <peer id>   ; default this node
                              :max-lease :founder-lease :tag-shard} ..]}
                   (spaces[].strategy, settle-window-millis, admission,
                   allowed-agents, credential-issuer)

  Every space rides one BlockExchange per group, so bulk payloads travel
  content-addressed as under the starter. Seeds accept \"host:port\" (scheme
  follows the effective transport) or an explicit \"tls://\" / \"tcp://\"."
  [{:keys [bind tick tls group] :or {tick "250ms"} :as config}]
  (validate-group group)
  (let [identity (->identity config)
        {:keys [transport channel-auth profile] :as posture} (posture config)
        node (build-node config posture identity)
        scheme (if (keyword? transport) (name transport) (.scheme ^Transport transport))
        transport-instance (->transport transport identity tls)]
    (when-let [token (get-in config [:security :token])]
      (.credentialHint node JoinCredentials/OIDC_HINT_KEY token))
    (when (and (= :tls transport) (not (:require-attestation config)))
      ;; Plain TCP stays available for dialing peers that advertise it, except on a node that
      ;; requires attestation: such a node registers attesting bindings alone (spec 5.6, v0.1.19),
      ;; and would refuse the tcp binding at registration.
      (.transport node (TcpTransport.)))
    (if bind
      (.listen node transport-instance bind)
      (.transport node transport-instance))
    (let [[^GroupRuntime runtime founding] (join-group node identity group scheme)
          authorizer (->authorizer config posture runtime identity)
          codec (CborCodec/defaultCodec)
          blocks (BlockExchange. runtime codec)
          discovery (when (or (:advertise group) (:content-key group)
                              (:content-key-rotation group) (:capabilities config)
                              (:agents config))
                      (DiscoveryService/create runtime codec (.peerId identity)
                                               (InstantSource/system)
                                               (->roles (:roles config))))
          agents (agent-identities config identity)
          ring (content-key-ring config group runtime codec)
          key-wrap (start-key-wrap config group runtime identity ring authorizer discovery codec)
          context {:runtime runtime :identity identity
                   :agent-name (or (:agent group) "clj")
                   :blocks blocks :discovery discovery
                   :key-ring ring
                   :authorizer authorizer}
          spaces (into {}
                       (map (fn [space-config]
                              [(:name space-config) (build-space context space-config)]))
                       (:spaces group))]
      (let [facade (build-facade config identity agents runtime discovery spaces
                                 (or (:agent group) "clj") codec)
            capabilities (build-capabilities config facade runtime identity discovery codec spaces)]
        (.startTicking node (core/duration tick))
        {:identity identity :node node :runtime runtime :spaces spaces
         :authorizer authorizer :blocks blocks :discovery discovery :founding founding
         :profile profile :transport transport-instance :channel-auth channel-auth
         :agents agents :key-ring ring :key-wrap key-wrap
         :facade (:facade facade) :group-context (:group facade)
         :capabilities capabilities
         :config config}))))

;; ---------------------------------------------------------------- system

(defn space
  "A space of a started system, by name."
  [system space-name]
  (or (get-in system [:spaces space-name])
      (throw (ex-info "no such space" {:name space-name
                                       :known (keys (:spaces system))}))))

(defn peer-id
  "The PeerId of a started system."
  ^PeerId [system]
  (.peerId ^PeerIdentity (:identity system)))

(defn writer
  "The AgentId a started system's spaces write as (its identity plus the
  group's `:agent` name, \"clj\" by default): what an allowlist or a
  `grant!` on another peer names."
  ^AgentId [system]
  (.agent ^PeerIdentity (:identity system)
          (or (get-in system [:config :group :agent]) "clj")))

(defn agent
  "The AgentIdentity `:agent-keys` gives an agent name (SPEC 4.2, v0.1.13):
  peer-signed by default, or a renewing key of its own under :subordinate.
  Pass it to `agentspaces.space/as` to act as that agent.

      (space/write! (space/as (fleet/space sys \"tasks\") (fleet/agent sys \"planner\"))
                    TaskEntry {:topic \"t\" :priority 1})"
  ^AgentIdentity [system agent-name]
  ((:agents system) agent-name))

(def ^:private reasons
  {:key-compromise CredentialRevocation/KEY_COMPROMISE :retired CredentialRevocation/RETIRED
   :superseded CredentialRevocation/SUPERSEDED :privilege-withdrawn CredentialRevocation/PRIVILEGE_WITHDRAWN
   :unspecified CredentialRevocation/UNSPECIFIED})

(defn- ->instant ^Instant [t]
  (cond
    (nil? t) nil
    (instance? Instant t) t
    (string? t) (Instant/parse t)
    :else (throw (ex-info ":effective-from must be an Instant or an ISO-8601 string"
                          {:effective-from t}))))

(defn- x509-leaf-target ^CredentialRevocation$Target [certificate]
  (if (instance? X509Certificate certificate)
    (let [^X509Certificate cert certificate]
      (CredentialRevocation$Target/x509Leaf (.getName (.getIssuerX500Principal cert))
                                            (.toString (.getSerialNumber cert) 16)
                                            (Digests/sha256 (.getEncoded cert))))
    (throw (ex-info ":x509-leaf revocation needs :certificate, an X509Certificate"
                    {:certificate certificate}))))

(defn revoke!
  "Revokes something certified in the system's group (SPEC 5.6, v0.1.13).
  Returns the revocation, or nil when this peer lacks the authority:

      (fleet/revoke! sys {:kind :peer :peer \"z...\" :reason \"compromised\"})  ; founder
      (fleet/revoke! sys {:kind :agent :agent \"z.../planner\" :reason :retired
                          :effective-from \"2026-10-01T00:00:00Z\"})
      (fleet/revoke! sys {:kind :agent-key :agent \"z.../planner\" :key <32 bytes>
                          :reason :key-compromise})
      (fleet/revoke! sys {:kind :x509-leaf :certificate <X509Certificate>
                          :reason :key-compromise})                       ; founder
      (fleet/revoke! sys {:kind :join-credential :credential \"<base64>\"})

  An agent and its keys are revocable by its own peer or the founder; a peer,
  an X.509 leaf (named by issuer DN, hex serial, and the SHA-256 of its DER,
  all derived from `:certificate`), and a join credential by the founder.
  `:reason` is one of :key-compromise, :retired, :superseded,
  :privilege-withdrawn, :unspecified (default). `:effective-from` (an Instant
  or ISO-8601 string, no later than now; default now) applies to :agent,
  :agent-key, and :x509-leaf: under a non-compromise reason, signatures made
  before it keep verifying (the freeze rule)."
  [system {:keys [kind peer agent key credential certificate reason effective-from]
           :or {reason :unspecified}}]
  (let [^GroupRuntime runtime (:runtime system)
        why (if (keyword? reason)
              (or (get reasons reason) (throw (ex-info "unknown revocation reason" {:reason reason})))
              (str reason))
        from (->instant effective-from)]
    (-> (case kind
          :peer (.revoke runtime (core/peer-id peer) ^String why)
          :agent (.revoke runtime (CredentialRevocation$Target/agent (core/agent-id agent))
                          ^String why from)
          :agent-key (.revoke runtime (CredentialRevocation$Target/agentKey
                                       (core/agent-id agent) (Digests/sha256 ^bytes key))
                              ^String why from)
          :x509-leaf (.revoke runtime (x509-leaf-target certificate) ^String why from)
          :join-credential (.revokeJoinCredential runtime ^String credential why)
          (throw (ex-info "unknown revocation kind" {:kind kind})))
        (.orElse nil))))

(defn revoked?
  "Whether the system's group refuses an agent (or, given a PeerId-like
  value, a peer) at any time (SPEC 5.6, v0.1.13)."
  [system who]
  (let [view (.revocationView ^GroupRuntime (:runtime system))]
    (if (and (string? who) (not (str/includes? who "/")))
      (.revoked view (core/peer-id who))
      (.revoked view (core/agent-id who) nil))))

(def ^:private ^java.lang.System$Logger log (System/getLogger "agentspaces.fleet"))

(defn rotate-content-key!
  "Rotates the group's content key now (SPEC 11a.3, v0.1.13), cutting over
  after `cutover-delay` (default two gossip periods plus 30s, as the starter,
  so the new epoch reaches every member before writers switch); needs the
  group's `:content-key` and the founder or a :key-rotator grant. Without a
  `:keystore` the rotated ring lives only in memory, which is logged as a
  warning. Returns the new epoch."
  ([system]
   (rotate-content-key! system
                        (-> (.period (.gossip (.advertisement ^GroupRuntime (:runtime system))))
                            (.multipliedBy 2)
                            (.plusSeconds 30))))
  ([system cutover-delay]
   (let [^GroupKeyDistributor keys (or (:key-wrap system)
                                       (throw (ex-info (str "rotate-content-key! needs the group's"
                                                            " :content-key")
                                                       {})))
         epoch (.rotate keys ^PeerIdentity (:identity system)
                        (.plus (Instant/now) (core/duration cutover-delay)))]
     (when-not (get-in system [:config :keystore])
       (.log log java.lang.System$Logger$Level/WARNING
             ^String (str "rotated the content key of group '" (get-in system [:config :group :name])
                          "' to epoch " epoch " with no :keystore: the ring is held in memory"
                          " only and is lost on restart")))
     epoch)))

(defn group-uri
  "The joined group's \"aspace://<id>\" URI, which another peer can `:join`."
  [system]
  (.id (.advertisement ^GroupRuntime (:runtime system))))

(defn stop!
  "Closes the system's spaces and node. Idempotent enough for shutdown hooks."
  [{:keys [^PeerNode node spaces]}]
  (doseq [^ReplicatedSpace s (vals spaces)]
    (try (.close s) (catch Exception _)))
  (when node
    (try (.close node) (catch Exception _)))
  nil)
