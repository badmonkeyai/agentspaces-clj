# agentspaces-clj

Idiomatic Clojure bindings for [AgentSpaces](https://github.com/badmonkeyai/AgentSpaces): the
shared, leased, replicated tuple space and its peer-to-peer fabric, driven
with maps, keywords, and functions.

The bindings consume the AgentSpaces Java libraries by Maven coordinates:
`deps.edn` names `ai.badmonkey.agentspaces/agentspaces-*` with `:mvn/version`,
and tools.deps resolves them from Maven Central like any other dependency, or
from your `~/.m2` after a `mvn install` of the core when you work against an
unreleased version.

The bindings track the AgentSpaces specification,
[SPEC v0.1.13](https://github.com/badmonkeyai/agentspaces-spec/blob/main/SPEC.md).
Each section below names the clause it exposes as "SPEC §", and the mapping
table at the end names the Spring starter property each config key mirrors.

## Contents

1. Setup
2. The shape of the API
3. One peer, from a config map
4. Security posture: profiles, grants, and identity providers
5. Transports: TCP, TLS, enterprise CA, attestation, multicast
6. Groups: three ways to join, content keys, advertisements
7. Spaces: strategies, admission, credentials, sharding, leases
8. The space verbs
9. The `:where` DSL
10. The system map and interop
11. Defaults and choices
12. Config key to starter property
13. Tests

## 1. Setup

Add the bindings to your `deps.edn`. They are published to Clojars, and pull
the AgentSpaces Java libraries from Maven Central:

```clojure
{:deps {ai.badmonkey.agentspaces/agentspaces-clj {:mvn/version "0.2.0"}}}
```

To work on the bindings themselves, see [CONTRIBUTING.md](CONTRIBUTING.md). In
short: install the AgentSpaces core into your `~/.m2` (until its artifacts are
on Maven Central), then run the suite:

```bash
git clone https://github.com/badmonkeyai/AgentSpaces.git agentspaces
mvn -f agentspaces/pom.xml install -DskipTests
clojure -M:test
```

The suite runs real fleets over loopback and finishes in about fifteen
seconds. Once the dependencies are in `~/.m2`, the same suite runs on a plain
`java` classpath, with no network and no Clojure CLI at run time:

```bash
CP=$(clojure -Spath -M:test)   # once, while the CLI can resolve dependencies
java -cp "$CP" clojure.main -e "(require 'agentspaces.run-tests) (agentspaces.run-tests/-main)"
```

## 2. The shape of the API

Entry types stay Java record classes, because every peer, whatever its
language, agrees on entry schemas by type, and the fabric partitions storage
and matching by class. The bindings make the classes invisible in daily use.
Maps go in, built through the record's canonical constructor with numeric
coercion so a Clojure long fills an `int` component. Maps come out, with the
raw record riding along in metadata. Everything else is keywords and duration
strings.

```clojure
(require '[agentspaces.core  :as core]
         '[agentspaces.space :as space]
         '[agentspaces.fleet :as fleet])
(import 'com.example.TaskEntry 'com.example.FindingEntry)

;; A record from a map, and back.
(def task (core/to-entry TaskEntry {:topic "indexing" :priority 3}))
(core/from-entry task)          ;=> {:topic "indexing" :priority 3}
(core/raw (core/from-entry task)) ;=> the TaskEntry record

;; Durations and leases accept simple strings, ISO-8601, millis, or Duration.
(core/duration "10m")   (core/duration "PT10M")   (core/duration 600000)
(core/lease "1h")

;; Identifiers coerce from their string forms.
(core/peer-id "z6Mk...")                 ;=> PeerId
(core/peer-id "aspace://z6Mk...")        ;=> the same PeerId (URI prefix dropped)
(core/agent-id "z6Mk.../worker")         ;=> AgentId
```

Three namespaces carry everything:

| Namespace | Carries |
|---|---|
| `agentspaces.core` | `duration`, `lease`, `peer-id`, `agent-id`, `to-entry`, `from-entry`, `raw`, `template` |
| `agentspaces.space` | `local`, `as`, `write!`, `read`, `read-all`, `take!`, `taken`, `complete!`, `renew!`, `cancel!`, `notify!`, `unsubscribe!`, `grant!`, `revoke!` |
| `agentspaces.fleet` | `start`, `stop!`, `space`, `found-group`, `peer-id`, `writer`, `agent`, `revoke!`, `revoked?`, `rotate-content-key!`, `group-uri` |

## 3. One peer, from a config map

`fleet/start` turns one config map into a running peer and returns a system
map. `fleet/stop!` closes it. The config shape mirrors the Spring starter's
properties (SPEC §10.1), so a Clojure peer and a Spring Boot peer read from
the same design and moving between them is renaming keys.

The smallest fleet member: plain TCP, a literal group name, one space.

```clojure
(def system
  (fleet/start
    {:bind  "127.0.0.1:7591"
     :group {:name   "research"
             :seeds  ["127.0.0.1:7590"]
             :spaces [{:name "tasks"}]}}))

(def tasks (fleet/space system "tasks"))
(space/write! tasks TaskEntry {:topic "indexing" :priority 3} {:lease "10m"})
(fleet/stop! system)
```

A fuller member, showing every top-level key:

```clojure
(fleet/start
  {:keystore "./peer-keys"                ; persistent identity; omit for ephemeral
   :bind     "0.0.0.0:7591"               ; omit to run dial-only (a NAT-restricted peer)
   :roles    #{:rendezvous}               ; or #{:relay}, or both
   :tick     "250ms"                      ; protocol round period
   :security {:profile :mtls}             ; §4 below
   :transport :tls                        ; §5 below; the profile already implies it
   :channel-auth :attested
   :tls      {:trust-store "./ca.p12"}    ; enterprise CA mode, §5
   :require-attestation true
   :multicast {:group "239.255.42.99:7787" :interval "5s"}
   :group    {:name "research"            ; §6 below
              :founding "research-fleet-v1"
              :seeds ["tls://10.0.0.5:7590"]
              :membership {:ttl "30s" :probe "2s" :indirect 2}
              :content-key "<base64 of 32 bytes>"
              :advertise true
              :agent "worker"
              :spaces [{:name "tasks" :settle "150ms"}                 ; §7 below
                       {:name "bids"  :strategy :auction :bid-fn estimate}
                       {:name "control" :admission :allowlist
                        :allowed-agents ["z6Mk.../steward"]}
                       {:name "partner" :admission :credential}
                       {:name "audited" :admission :authorizer}]}})
```

Only `:group` is required. Every other key has a default that matches the
starter, with one deliberate exception described in §11.

## 4. Security posture: profiles, grants, and identity providers

SPEC §11 defines four security profiles. A profile decides three things at
once: the transport, whether every frame keeps its envelope signature, and
who answers authorization questions. `:security {:profile ..}` selects one,
exactly as `agentspaces.security.profile` does in the starter.

| Profile | Transport | Channel auth | Authorizer |
|---|---|---|---|
| `:dev-local` | TCP | `:signed` | membership, plus grants |
| `:mtls` | TLS with identity-endorsed certificates | `:attested` | membership, plus grants |
| `:mtls-oidc` | TLS | `:attested` | your identity provider |
| `:zero-trust` | TLS | `:signed` on every hop | your identity provider |

### Membership-rooted authorization

Under `:dev-local` and `:mtls`, `fleet/start` builds a `MembershipAuthorizer`
over the joined group and exposes it as `:authorizer` in the system map. Every
admitted member may perform every privileged operation until you narrow one
with a grant. A grant lists the peers allowed to perform an operation; an
absent or empty list leaves that operation open to any member.

```clojure
(fleet/start
  {:bind "127.0.0.1:7591"
   :security {:profile :mtls
              :grants {:directive-issuer ["z6MkConsole..."]     ; only the console commands workers
                       :key-holder       ["z6MkFounder..."]     ; only the founder hands out the group key
                       :raft-voter       ["z6MkA..." "z6MkB..." "z6MkC..."]
                       :space-write      []                     ; empty: any member
                       :space-take       []
                       :connector-serve  ["z6MkWarehouse..."]}}
   :group {:name "research" :seeds ["127.0.0.1:7590"]
           :spaces [{:name "tasks"}]}})
```

The six operations are the ones the fabric guards: `:raft-voter` (the ordered
log), `:directive-issuer` (command and control), `:connector-serve` (data
query results a fleet trusts), `:key-holder` (receiving the group content
key), and `:space-write` and `:space-take` (space admission under the
`:authorizer` rule, §7).

The authorizer is an ordinary Java object you can question yourself:

```clojure
(import 'ai.badmonkey.agentspaces.api.spi.Authorizer$Operation)
(.permits (:authorizer system) some-peer-id Authorizer$Operation/DIRECTIVE_ISSUER
          (.value (.id (:runtime system))))
;=> true for a member the grant names, false for a stranger
```

### Identity-provider authorization

Under `:mtls-oidc` and `:zero-trust` the starter builds an `OidcAuthorizer`
from `agentspaces.security.oidc.{issuer, audience, jwks-url}`. That class
lives in `agentspaces-auth-oidc`, which brings Nimbus JOSE+JWT with it, and
a library binding should not force that dependency on every consumer. So the
bindings ask you to construct the authorizer and pass it in. `fleet/start`
refuses an OIDC profile without one and tells you why.

```clojure
;; deps.edn: add ai.badmonkey.agentspaces/agentspaces-auth-oidc to your own project.
(import 'ai.badmonkey.agentspaces.auth.oidc.OidcAuthorizer
        'java.net.URL 'java.time.InstantSource)

(def oidc (OidcAuthorizer/fromJwks "https://idp.example.com" "agentspaces-fleet"
                                   (URL. "https://idp.example.com/.well-known/jwks.json")
                                   (InstantSource/system)))

(fleet/start
  {:bind "127.0.0.1:7591"
   :security {:profile    :mtls-oidc
              :authorizer oidc
              ;; this node's own token, bound to its PeerID by the agentspaces_peer claim;
              ;; it travels to other members as the aspace:oidc resource hint
              :token      (slurp "/run/secrets/fleet-token")
              ;; members' tokens arrive here, on admission and whenever they change
              :on-credential-hints
              (fn [peer-id hints]
                (when-let [jwt (get hints "aspace:oidc")]
                  (.authorize oidc peer-id jwt)))}
   :group {:name "research" :seeds ["10.0.0.5:7590"] :spaces [{:name "tasks"}]}})
```

Three things to know about tokens. A token authorizes exactly the peer named
in its `agentspaces_peer` claim, so a replayed hint grants nothing to anyone
else. The token rides inside the peer's signed self-advertisement, which
gossips once per period, so keep tokens under a couple of kilobytes. Refresh
is your responsibility: call `(.credentialHint (:node system) "aspace:oidc"
new-jwt)` when your identity provider issues a new one, and the next
self-advertisement carries it.

An explicit `:authorizer` wins under any profile, so you can also plug a
custom policy engine into `:mtls` by implementing the one-method `Authorizer`
interface:

```clojure
(import 'ai.badmonkey.agentspaces.api.spi.Authorizer)
(def policy
  (reify Authorizer
    (permits [_ peer operation scope]
      (my-policy-engine/allows? (.value peer) (str operation) scope))))
(fleet/start {:security {:profile :mtls :authorizer policy} :group {...}})
```

## 5. Transports: TCP, TLS, enterprise CA, attestation, multicast

The profile picks the transport; `:transport` and `:channel-auth` override it
when a deployment must deviate deliberately (the starter's
`agentspaces.transport.*` do the same). A TLS node also keeps plain TCP
available for dialing peers that advertise only TCP.

```clojure
;; Plain TCP, every frame signed.
{:transport :tcp :channel-auth :signed}

;; TLS with identity-endorsed certificates (SPEC §5.6): the handshake proves the
;; peer, so frames on that link may travel without an envelope signature.
{:transport :tls :channel-auth :attested}

;; Your own Transport implementation (a WebSocket binding, a test double).
{:transport (my.transport/make)}
```

### Enterprise CA mode

With a trust store, TLS switches to the enterprise mode of SPEC v0.1.9: a
peer attests only when its certificate chain validates to your CA anchors and
is not revoked. Revocation is checked against cached CRL files, which keeps
the fleet working through an identity-provider partition, or live under
`:revocation-strict`. `:key-store` supplies this node's own CA-issued
credential.

```clojure
{:transport :tls
 :tls {:key-store "./node.p12"   :key-store-password   "changeit"
       :trust-store "./ca.jks"   :trust-store-password "changeit"
       :crls ["./ca.crl"]
       :revocation-strict false}}
```

### Required attestation

`:require-attestation true` makes channel attestation a condition of
membership. Every inbound frame must arrive on a connection whose handshake
proved its sender, and a current member whose next handshake attests nothing
(its certificate was revoked) is evicted and refused until it attests again.
This is how a CA's revocation becomes the fleet's eject. Pair it with
`:transport :tls`.

```clojure
{:transport :tls :require-attestation true
 :tls {:trust-store "./ca.jks" :crls ["./ca.crl"]}}
```

### Multicast bootstrap

On a LAN, peers can find each other with no seeds at all. The beacon sends
this peer's signed advertisement as a UDP datagram once per interval, and a
receiver verifies it exactly as it verifies a gossiped advertisement before
introducing itself. Nothing unauthenticated is ever acted on.

```clojure
{:multicast true}                                          ; 239.255.42.99:7787 every 5 s
{:multicast {:group "239.255.42.99:7787" :interval "2s"}}
```

### Seeds

Seeds are `"host:port"` strings whose scheme follows the effective transport,
or an explicit `"tls://host:port"` or `"tcp://host:port"`. A peer with no
`:bind` is dial-only: it reaches seeds but nobody dials it, which is the
NAT-restricted posture that relay-role peers exist to serve.

## 6. Groups: three ways to join, content keys, advertisements

A group is identified in exactly one of three ways; `fleet/start` fails fast
before binding a port if a config mixes them (SPEC §4.4, §5.1).

### Derive the id from a shared string

Every member that knows the same founding string computes the same GroupID.
This is the simplest fleet, the one the starter's `founding` property builds,
and it suits fleets whose members share configuration.

```clojure
{:group {:name "research" :founding "research-fleet-v1" :seeds [...]}}
;; :founding defaults to :name, so {:name "research"} alone also works
```

### Found a self-certifying group

A founder signs the group's founding fields with its identity. The GroupID is
the hash of that signed document, so the id proves the policy: nobody can
re-publish the same id with a different membership policy or strategy.

```clojure
(def founder
  (fleet/start {:bind "127.0.0.1:7590"
                :group {:name "research" :found true
                        :spaces [{:name "tasks"}]}}))

(fleet/group-uri founder)   ;=> "aspace://zB3YQ..."  hand this out

;; Or found first and configure later.
(def signed (fleet/found-group identity {:name "research" :policy :open
                                         :strategy :lease-race :ttl "365d"}))
(fleet/start {:group {:name "research" :found signed :spaces [...]}})
```

### Join by id alone

A peer that knows only the URI and a seed asks the seed for the founding
document, verifies the signature, checks that the document hashes to the id
it was given, and only then joins. A hostile seed can stall a join; it cannot
substitute a policy.

```clojure
(fleet/start {:bind "127.0.0.1:7591"
              :group {:join  (fleet/group-uri founder)
                      :seeds ["127.0.0.1:7590"]
                      :join-timeout "10s"          ; default
                      :spaces [{:name "tasks"}]}})
```

### Membership tuning

```clojure
{:group {:membership {:ttl "30s"      ; a silent member lapses after this
                      :probe "2s"     ; direct ping timeout before indirect probing
                      :indirect 2}}}  ; how many relays to ask
```

If you partition peers in a test for several seconds, lengthen these; a peer
that evicts its partner during the partition has nobody to re-introduce
itself to afterwards.

### Content keys

A group content key seals every entry payload with AES-256-GCM (SPEC §11a).
A member without the key still verifies, stores, and forwards every record
and can read none of them, which is what lets rendezvous and relay peers
serve a group they may not read. Pass the same key to every reader.

```clojure
(import 'ai.badmonkey.agentspaces.common.crypto.GroupKey)
(def key (GroupKey/generate))                       ; share out of band, or via key-wrap
(fleet/start {:group {:content-key key ...}})       ; a GroupKey
(fleet/start {:group {:content-key "<base64>" ...}}) ; or its 32 bytes in Base64
```

**Rotation (v0.1.13).** With a `:content-key`, the group's spaces share one
content-key ring, and the key-wrap capability serves and follows it, so every member
picks up a rotation (SPEC §11a.3), as under the starter. `:content-key-rotation` only
tunes it, and needs a `:content-key`.

- **Who may rotate.** The founder, or a peer named in `:key-rotator` grants. Every
  member must carry the same grants, because each member vouches for rotators under
  its own configuration.
- **Settings.** `:rotate-every` rotates on a schedule. `:cutover-delay` defaults to two
  gossip periods plus 30s. `:writer-grace` defaults to 1h.
- **On demand.** `fleet/rotate-content-key!` rotates now, cutting over after the same
  default (two gossip periods plus 30s) or an explicit delay. Rotating without a
  `:keystore` logs a warning: the ring then lives in memory only.
- **Persistence.** With `:keystore`, the ring persists across restarts.

```clojure
(fleet/start {:security {:grants {:key-rotator ["<founder peer id>"]}}
              :group {:content-key key :content-key-rotation {:rotate-every "30d"} ...}})
(fleet/rotate-content-key! system)                   ; => the new epoch
```

### Agents of their own (v0.1.13)

`:agent-keys :subordinate` gives each agent a key of its own (SPEC §4.2). The key is
certified by the peer, renewed at half-life, and judged at signing time, and it
certifies an X25519 key for per-agent key wrap. Records, claims, and states then
carry the agent's signature and are stored `AGENT_ATTESTED`.

```clojure
(def sys (fleet/start {:agent-keys :subordinate :agent-keystore "./agent-keys" ...}))
(def planner (fleet/agent sys "planner"))
(space/write! (space/as (fleet/space sys "tasks") planner) TaskEntry {:topic "t" :priority 1})
(fleet/revoke! sys {:kind :agent :agent (str (.id planner)) :reason :retired})
(fleet/revoked? other-sys (str (.id planner)))       ; => true once it gossips
```

- **Acting as an agent.** `space/as` gives a view acting as the agent; `write!`,
  `take!`, and `complete!` also accept `{:as agent}` in their options.
- **Revocation authority.** An agent and its keys are revocable by its own peer or
  the founder. Peers, X.509 leaves (`{:kind :x509-leaf :certificate cert}`), and join
  credentials are revocable by the founder. `:effective-from` (an `Instant` or ISO
  string) dates an agent, agent-key, or leaf revocation.
- **The freeze rule.** It decides what still verifies after a revocation (SPEC §5.6).

### Advertising spaces

With `:advertise true`, each configured space publishes its SpaceAdvertisement
(name, strategy, schema hints, admission rule, replication mode) into the
group's discovery cache on creation, as the starter does by default. Other
members can then find the space by name. The `DiscoveryService` is exposed as
`:discovery` in the system map.

```clojure
(def sys (fleet/start {:group {:advertise true :spaces [{:name "tasks"}] ...}}))
(import 'ai.badmonkey.agentspaces.api.ad.SpaceAdvertisement)
(.find (:discovery sys) SpaceAdvertisement
       (reify java.util.function.Predicate (test [_ ad] (= "tasks" (.spaceName ad)))))
```

## 7. Spaces: strategies, admission, credentials, sharding, leases

Each entry of `:spaces` builds one `ReplicatedSpace`. Every space rides one
`BlockExchange` per group, so a payload over 64 KiB travels content-addressed
without further wiring, exactly as under the starter.

### Conflict strategies

```clojure
{:name "tasks"}                                   ; LEASE_RACE (default): lowest claim wins
{:name "tasks" :strategy :lease-race :settle "150ms"}
{:name "bids"  :strategy :auction :settle "1s"    ; AUCTION: lowest bid wins
 :bid-fn (fn [task] (* (:size task) cost-per-unit))}
```

`:settle` is the settle window a taker waits for competing claims; the
default is 200 ms and the specification recommends twice the gossip period.
ORDERED is not a space strategy; it is driven by the ordered-log capability
(see §10).

### Admission: who may write and take

SPEC §7.5 defines four admission rules. Reads stay open to the whole group
under every rule; admission decides writes, takes, and completions. A local
call by an unadmitted agent throws `SpaceAdmissionException`; an inbound
record or claim from an unadmitted agent is dropped on every replica.

**`:group`**, the default. Every group member may write and take.

```clojure
{:name "tasks"}
```

**`:allowlist`**. Only the named agents may. An agent is a peer plus a local
name, `"<peerId>/<name>"`; `fleet/writer` gives you another system's agent
id. An empty allowlist fails fast, since a space nobody may write to is a
configuration error. Refusals under an allowlist strike the offender, because
the list is local configuration and cannot be late.

```clojure
{:name "control" :admission :allowlist
 :allowed-agents ["z6MkConsole.../steward" (fleet/writer other-system)]}
```

**`:credential`**. The space's credential issuer admits agents by writing
leased credential entries into the space itself. A credential is an ordinary
signed entry, so it replicates like everything else, its lease is its expiry,
and revocation is a cancellation that reaches every replica in one gossip
round. A write that arrives before its credential is dropped without a strike
and re-offered by anti-entropy once the credential lands.

```clojure
;; The issuer's node (default: this node) configures the space...
(def issuer (fleet/start {:group {:spaces [{:name "partner" :admission :credential}] ...}}))
;; ...and every other replica names the issuer.
(def member (fleet/start {:group {:spaces [{:name "partner" :admission :credential
                                             :credential-issuer (fleet/peer-id issuer)}] ...}}))

;; Grant a partner agent write and take for an hour; the handle renews or cancels it.
(def handle (space/grant! (fleet/space issuer "partner")
                          (fleet/writer member) #{:write :take} "1h"))
(space/renew! handle "1h")

;; The partner can now write...
(space/write! (fleet/space member "partner") TaskEntry {:topic "shared" :priority 1})

;; ...until the issuer revokes it, or the credential lapses.
(space/revoke! (fleet/space issuer "partner") (fleet/writer member))
```

Scopes are `:write`, `:take`, or both; a completion requires `:take`. Only
the issuer's node may `grant!` or `revoke!`; any other node gets a
`SpaceAdmissionException`.

**`:authorizer`**. The space asks the profile's authorizer (§4) whether the
peer may `SPACE_WRITE` or `SPACE_TAKE` in the scope of the space name. Under
`:mtls` that means membership plus your `:space-write` and `:space-take`
grants; under `:mtls-oidc` it means the peer's token carries
`aspace:space-write:audited` or the unscoped `aspace:space-write`. Refusals
do not strike, since a token can arrive late exactly as a credential can.

```clojure
{:security {:profile :mtls :grants {:space-write ["z6MkA..."] :space-take ["z6MkA..." "z6MkB..."]}}
 :group {:spaces [{:name "audited" :admission :authorizer}] ...}}
```

### Choosing an admission rule

Use `:group` for trusted fleets where group membership is the perimeter.
Use `:allowlist` when a fixed set of agents may write and you can configure
every replica with the list. Use `:credential` when admission changes at run
time, when the issuer is one specific peer, or when a partner organization
joins your group and you want to admit its agents one by one and revoke them
without redeploying. Use `:authorizer` when your identity provider, or a
policy engine of your own, already knows who may act.

### Tag sharding

A replica can hold only the slice of a space its tag predicate selects (SPEC
§7.3). Tags ride inside the signed record, so a shard predicate sees exactly
what the writer signed.

```clojure
;; Keep entries whose :region tag is "eu"; a map means these pairs must all be present.
{:name "orders" :tag-shard {:region "eu"}}
;; Or any predicate over the tag map.
{:name "orders" :tag-shard (fn [tags] (contains? #{"eu" "uk"} (:region tags)))}

(space/write! orders OrderEntry {:id "o-1"} {:lease "1h" :tags {:region "eu"}})
```

### Leases on the space itself

```clojure
{:name "mission" :max-lease "24h"       ; the tombstone horizon: collected 2x this after expiry
                 :founder-lease "8h"}   ; the space turns read-only when the founders' lease lapses
```

## 8. The space verbs

The same verbs work on a `ReplicatedSpace` from `fleet/start` and on a
`LocalSpace` from `space/local`, which has full lease semantics and no fabric,
the right start for tests and the REPL.

```clojure
(def work (space/local "work"))
(def work (space/local "work" {:issuer my-agent-id :sweep-every "1s"}))
```

**Write.** Returns an `EntryHandle` for renewal or cancellation. The write
lease is the entry's time to live; an unrenewed entry vanishes.

```clojure
(def h (space/write! work TaskEntry {:topic "indexing" :priority 3} {:lease "10m"}))
(space/write! work TaskEntry {:topic "eu-orders"} {:lease "1h" :tags {:region "eu"}})
(space/renew! h "10m")
(space/cancel! h)
```

**Read.** Non-destructive; returns a map, or nil when nothing matches within
`:timeout` (default: no waiting). Within one entry type, reads and takes
prefer the oldest matching entry (SPEC §7.2, first in first out). `:fresh
true` performs one anti-entropy pull toward a random member before matching,
which a late joiner wants; a `LocalSpace` has nobody to pull from and ignores
the flag.

```clojure
(space/read work TaskEntry)
(space/read work TaskEntry {:where {:topic "indexing"} :timeout "1s"})
(space/read work TaskEntry {:fresh true :timeout "5s"})
(space/read-all work TaskEntry {:where {:priority [:gte 3]} :limit 50})
(space/read-all work TaskEntry {:fresh true})
```

**Take.** Destructive and exclusive under the space's conflict strategy. The
result carries the fabric handle in metadata for `complete!` and `renew!`. If
the taker crashes instead of completing, the take lease lapses and the entry
reappears for another worker. That is the heart of the model.

```clojure
(when-let [task (space/take! work TaskEntry {:where {:priority [:gte 3]}
                                             :lease "10m" :timeout "1s"})]
  (try
    (space/complete! work task FindingEntry
                     {:topic (:topic task) :summary (research task)} {:lease "1h"})
    (catch Exception e
      ;; do nothing: the lease lapses and the task reappears
      )))

;; Completion without a result, and the raw handle when you need it.
(space/complete! work task)
(space/taken task) ;=> TakenEntry
```

**Notify.** Subscribes a function to matching events. Events arrive as maps
with `:kind` one of `:written`, `:taken`, `:completed`, `:expired`,
`:reappeared`, plus `:entry` and `:entry-id`. The callback runs on the
space's delivery thread, so return promptly and hand slow work elsewhere.

```clojure
(def sub (space/notify! work FindingEntry
                        (fn [{:keys [kind entry]}]
                          (case kind
                            :written    (println "finding:" (:summary entry))
                            :reappeared (println "a worker died; retrying" (:topic entry))
                            nil))
                        {:lease "365d" :where {:topic [:contains "risk"]}}))
(space/unsubscribe! sub)
```

**Credentials.** `grant!` and `revoke!` work on a `:admission :credential`
`ReplicatedSpace` from the issuer's node (§7); on any other space they throw.

## 9. The `:where` DSL

A `:where` map narrows any read, take, or subscription. Plain values match by
equality, vectors name an operator, and functions become predicates. Numeric
bounds compare numerically, so Clojure longs match `int` components.

| Form | Meaning |
|---|---|
| `{:topic "indexing"}` | equality |
| `{:priority [:gt 5]}` | `:gt` `:gte` `:lt` `:lte` `:eq` `:ne` comparisons |
| `{:region [:in "us" "eu"]}` | membership |
| `{:summary [:contains "risk"]}` | substring |
| `{:assignee [:nil?]}` / `[:some?]` | null checks |
| `{:priority odd?}` | any Clojure predicate |
| `{:topic "x" :priority [:gte 3]}` | several fields, all must match |

`core/template` compiles a `:where` map into the fabric's `Template` when you
need the raw object.

## 10. The system map and interop

`fleet/start` returns everything it built:

| Key | Holds |
|---|---|
| `:identity` | the `PeerIdentity`; `(fleet/peer-id system)` is its PeerId |
| `:node` | the `PeerNode`: `.credentialHint`, `.channelModes`, `.bareFramesSent`, and the rest |
| `:runtime` | the `GroupRuntime`: `.membership`, `.gossip`, `.id`, `.revoke` |
| `:spaces` | name to `ReplicatedSpace`; `(fleet/space system "tasks")` |
| `:authorizer` | the profile's `Authorizer` (§4) |
| `:blocks` | the group's `BlockExchange` |
| `:discovery` | the `DiscoveryService`, when `:advertise true` |
| `:founding` | the `SignedGroupAdvertisement`, when this node founded the group |
| `:profile` `:transport` `:channel-auth` | the effective posture |
| `:config` | the map you passed |

Anything the map interface does not cover is one interop call away. The
capability services (SPEC §8) are the main example: the bindings do not wrap
them, because that would add the capabilities module to every consumer's
dependency graph. Add it to your own `deps.edn` and use the raw runtime.

```clojure
;; deps.edn: ai.badmonkey.agentspaces/agentspaces-capabilities {:mvn/version "0.2.0"}
(import 'ai.badmonkey.agentspaces.capabilities.runtime.CapabilityPipes
        'ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate
        'ai.badmonkey.agentspaces.common.codec.CborCodec
        'java.time.InstantSource)

(let [runtime (:runtime system)
      pipes   (CapabilityPipes. runtime (CborCodec/defaultCodec))
      agg     (PushSumAggregate. pipes (.sampler runtime) (fleet/peer-id system)
                                 (CborCodec/defaultCodec) (InstantSource/system))]
  (.start agg "battery" 0.72)          ; this node's local value
  ;; tick alongside the node, then read the converging fleet average
  (.estimate agg "battery"))
```

The same pattern reaches `VoteCapability` (quorum votes), `RaftLog` and
`OrderedTakes` (the ordered log and exactly-once takes), `GossipLearning`,
`SemanticDiscovery`, and `GroupKeyDistributor` (sealed distribution of the
content key to admitted members). Registering them with a `CapabilityRuntime`
advertises them to the group and keeps the advertisements refreshed.

## 11. Defaults and choices

These are the places where the bindings made a choice you may want to
reverse, and how.

**The default profile is `:dev-local`, not the starter's `mtls`.** A config
with no `:security` key runs plain TCP with signed frames, which is what
earlier versions of these bindings did and what the loopback tests assume.
The starter defaults to `mtls`. To match the starter, add
`{:security {:profile :mtls}}` to every config, or wrap `fleet/start` in
your own function that merges it in. Never expose a `:dev-local` peer beyond
one host or one trusted network segment.

**OIDC authorizers are yours to build.** The bindings do not depend on
`agentspaces-auth-oidc`. Add it yourself and pass an `OidcAuthorizer` under
`[:security :authorizer]` (§4). If you would rather the bindings wire it from
issuer, audience, and JWKS keys the way the starter does, that is a small
addition behind an optional alias; it was left out to keep the core
dependency graph to the fabric itself.

**Capabilities are interop, not wrapped.** See §10. A `:capabilities`
config block that registers the six shipped capabilities the way the starter's
`agentspaces.capabilities.*` toggles do is the natural next step if a Clojure
consumer needs them; it would add one dependency.

**One group per system.** `:group` is a single map. A process that joins
several groups starts several systems, one per group, sharing an identity
through `:identity`. The starter supports a list of groups in one process;
the bindings favour one system map per group because it keeps `fleet/space`
and `fleet/writer` unambiguous.

**A TLS node also listens on plain TCP for dialing.** As under the starter, a
`:tls` node registers a `TcpTransport` too, so it can dial peers that
advertise only TCP endpoints. Under `:require-attestation` those peers never
attest and are refused, which is the intended posture.

**Tick loops in tests advance real time.** `fleet/start` ticks the node on a
real scheduler (`:tick`). Anti-entropy paces itself by the group's gossip
period, so a test that wants several anti-entropy rounds waits real seconds;
the bundled tests use bounded polling helpers for this.

## 12. Config key to starter property

| Config key | Starter property | Notes |
|---|---|---|
| `:identity` / `:keystore` | `keystore` | a `PeerIdentity`, or a directory; omit both for an ephemeral identity |
| `:bind` | `bind` | `"host:port"`; omit for dial-only |
| `:roles` | `roles` | `#{:relay :rendezvous}` |
| `:tick` | `tick-millis` | default `"250ms"` |
| `:security {:profile}` | `security.profile` | default `:dev-local` here, `mtls` in the starter |
| `:security {:grants}` | `security.grants.*` | nine operation keys (v0.1.13 adds `:key-rotator`), each a list of peer ids |
| `:security {:authorizer}` | `security.oidc.*` | an `Authorizer` instance; required under OIDC profiles |
| `:security {:token}` | `security.oidc.token` | this node's `aspace:oidc` hint |
| `:security {:on-credential-hints}` | the Spring `Authorizers` hint listener | `(fn [peer-id hints])` |
| `:transport` | `transport.tls.enabled` | `:tcp`, `:tls`, or a `Transport` |
| `:channel-auth` | `transport.channel-auth` | `:signed` or `:attested` |
| `:tls {..}` | `transport.tls.{key-store, key-store-password, trust-store, trust-store-password, crls, revocation-strict}` | a trust store selects CA mode |
| `:require-attestation` | `transport.tls.require-attestation` | pair with `:transport :tls` |
| `:multicast` | `multicast.{enabled, group, interval-millis}` | `true` or a map |
| `:revocation-validator` | `PeerNode.Builder.revocationValidator` | a `RevocationValidator` |
| `:agent-keys` | `identity.agent-keys` | `:peer` (default) or `:subordinate` (v0.1.13) |
| `:agent-keystore` | `identity.agent-keystore` | a directory; needs `:subordinate` |
| `:agent-certificate-ttl` | `identity.agent-certificate-ttl` | default `"24h"` |
| `:group {:name}` | `groups[].name` | also the default founding string |
| `:group {:founding}` | `groups[].founding` | literal-id derivation |
| `:group {:join}` `:join-timeout` | `groups[].join`, `join-timeout-millis` | join by id; needs seeds; excludes `:founding` and `:found` |
| `:group {:found}` | none | `true` to found here, or a `SignedGroupAdvertisement` |
| `:group {:seeds}` | `groups[].seeds` | `"host:port"` or `"tls://host:port"` |
| `:group {:membership}` | membership config | `:ttl`, `:probe`, `:indirect` |
| `:group {:content-key}` | `groups[].content-key` | 32 bytes, Base64, or a `GroupKey` |
| `:group {:content-key-rotation}` | `groups[].content-key-rotation.*` | `:rotate-every`, `:cutover-delay`, `:writer-grace` (v0.1.13) |
| `:group {:advertise}` | starter default | publish SpaceAdvertisements |
| `:group {:agent}` | the starter's `"app"` | local agent name, default `"clj"` |
| `:spaces [{:name :strategy :settle :bid-fn}]` | `spaces[].{name, strategy, settle-window-millis}` | `:bid-fn` for AUCTION |
| `:spaces [{:admission}]` | `spaces[].admission` | `:group`, `:allowlist`, `:credential`, `:authorizer` |
| `:spaces [{:allowed-agents}]` | `spaces[].allowed-agents` | `"<peerId>/<name>"` or `AgentId` |
| `:spaces [{:credential-issuer}]` | `spaces[].credential-issuer` | default this node |
| `:spaces [{:max-lease :founder-lease}]` | `ReplicatedSpace.Builder.maxLease`, `founderLease` | |
| `:spaces [{:tag-shard}]` | `ReplicatedSpace.Builder.tagShard` | a map of required tags or a predicate |

## 13. Tests

`clj -M:test` runs twenty-four tests. The data layer and every space verb run
over a `LocalSpace`; then real fleets over loopback exercise each config
interface: a TCP take and complete round trip between two peers; an
attested-TLS pair that negotiates bare frames; a founder plus a peer that
joins by GroupID alone and finds the advertised space; an allowlist space
that refuses an unlisted local writer and admits the listed one; a credential
space where the issuer grants, the grantee's write replicates, a non-issuer's
grant is refused, and a revocation withdraws the credential fleet-wide; a
`:fresh` read on a late joiner, verified through a pull counter on the seed;
the `:mtls` profile yielding TLS, attested channels, and a membership
authorizer that permits a member, honours a grant, and refuses a stranger; a
content-key fleet where a keyless third peer stores entries it cannot read;
required attestation over TLS; and tags travelling the wire into a
tag-sharded replica that keeps only its own. Seven v0.1.13 tests cover the rest:
- subordinate agents that write, take, and complete as themselves across two peers,
  stored `AGENT_ATTESTED`;
- the `{:as agent}` option on `write!`, `take!`, and `complete!`, stored
  `AGENT_ATTESTED` with that agent as issuer at the other peer;
- an agent revoked by its own peer, refused fleet-wide, while a stranger's revocation
  is refused;
- `:effective-from` reaching agent and agent-key revocations, and an `:x509-leaf`
  revocation naming the leaf by issuer DN, serial, and fingerprint;
- a late joiner that syncs an honest agent's entry but never stores one a
  compromised agent signed, while the writing peer still holds it;
- agent keys persisting in the agent keystore across a restart;
- a content-key rotation every member follows, including one that sets only
  `:content-key`, with the default cutover of two gossip periods plus 30s and a
  warning when no `:keystore` persists the ring.

---

AgentSpaces is an open source project from Bad Monkey, Inc., licensed under
the Apache License, Version 2.0.

## Contributing

Contributions are welcome under the Developer Certificate of Origin; see
[CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).
Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.

## License

Copyright 2026 Bad Monkey, Inc.

AgentSpaces Clojure is an open source project from Bad Monkey, Inc, licensed
under the Apache License 2.0; see `LICENSE`.

For questions, contact `oss@badmonkey.ai`
