# 11. Defaults and choices

Written for: Clojure developers who build with agentspaces-clj.

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

**Capabilities come from the config map.** The `:capabilities` block (§10.2)
registers the vote, aggregate, ordered, semantic, and learn services on the
facade the way the starter's `agentspaces.capabilities.*` toggles do, and
one namespace per capability works each with maps. This is why the bindings
depend on `agentspaces-capabilities`; a peer with no `:capabilities` block
registers none of them and pays nothing for the dependency at run time.

**One group per system.** `:group` is a single map. A process that joins
several groups starts several systems, one per group, sharing an identity
through `:identity`. The starter supports a list of groups in one process;
the bindings favour one system map per group because it keeps `fleet/space`
and `fleet/writer` unambiguous.

**A TLS node also listens on plain TCP for dialing.** As under the starter, a
`:tls` node registers a `TcpTransport` too, so it can dial peers that
advertise only TCP endpoints. Under `:require-attestation` the plain TCP
binding is not registered at all (such a node registers attesting bindings
alone, SPEC §5.6), which is the intended posture.

**The writer agent is `"clj"`, the starter's is `"app"`.** Every space of a
system writes as `<peerId>/clj` by default, where a Spring Boot peer writes
as `<peerId>/app`. The name is part of the `AgentId`, so an allowlist, a
space credential, or a vote identity that names one does not name the other.
Set `:group {:agent "app"}` on the Clojure side when a fleet mixes the two
and its admission rules name the starter's agents, or name both.

**Tests can run on virtual time.** Since 0.7.0 the node runs each piece of due
work at its own deadline, and `fleet/start` reads one time source for the node
and everything it builds over it. Pass `:time (VirtualTime/create)` (from
`agentspaces-test-support`) and move protocol time with `(.advance vt
(Duration/ofSeconds 30))`: anti-entropy rounds, card refreshes, settle spans and
lease renewals run with no real wait. `:tick` no longer paces the node; it only
sets the cadence of the deprecated onTick adapters. The older bundled tests
still use bounded polling helpers over real time.

**The member TTL is the core's default.** With no `:membership-tuning :ttl` a
member is judged at the 45 s floor, and this node keeps its own keepalive
inside 30 s for older judges (SPEC §5.2). Before 0.7.0 the Clojure default was
30 s. A configured `:ttl` under 60 s warns at start, or refuses under `:ttl
{:strict true}`, unless `:ttl {:expert-overrides {"agentspaces.membership.member-ttl"
"reason"}}` names it.
