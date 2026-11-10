# 7. Spaces: strategies, admission, credentials, sharding, leases

Written for: Clojure developers who build with agentspaces-clj.

Each entry of `:spaces` builds one `ReplicatedSpace`. Every space rides one
`BlockExchange` per group, so a payload over 64 KiB travels content-addressed
without further wiring, exactly as under the starter.

## Conflict strategies

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

## Admission: who may write and take

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

## Choosing an admission rule

Use `:group` for trusted fleets where group membership is the perimeter.
Use `:allowlist` when a fixed set of agents may write and you can configure
every replica with the list. Use `:credential` when admission changes at run
time, when the issuer is one specific peer, or when a partner organization
joins your group and you want to admit its agents one by one and revoke them
without redeploying. Use `:authorizer` when your identity provider, or a
policy engine of your own, already knows who may act.

## Tag sharding

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

## Leases on the space itself

```clojure
{:name "mission" :max-lease "24h"       ; the tombstone horizon: collected 2x this after expiry
                 :founder-lease "8h"}   ; the space turns read-only when the founders' lease lapses
```
