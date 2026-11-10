# 13. Tests

Written for: contributors who run and extend the test suite.

`clj -M:test` runs seventy tests. The data layer and every space verb run
over a `LocalSpace`, `read-entries` and the tags and details on notify events
among them; then real fleets over loopback exercise each config
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

The layer 3 and 4 suites bind `defagent` agents over two TCP peers: a take,
a ballot with an on-decision, the aggregate, ordered, semantic, and remote
namespaces, tag templates and bid functions; a LOCAL join, a LEASED join,
and a reduce with the default and the explicit `:mode :leased`, with the
`:mode` enum read back from the generated interface; the three returns (a
motion the vote namespace sees on both peers, a tagged entry read back with
`read-entries`, a fork landing two entries) and the `convert-out` rules on
their own; and the remaining verbs: `propose` opening one vote per cue key,
`on-estimate` writing a finding once the aggregate settles, `ordered-take`
handling each entry once across two takers, and a take narrowed by `:tags`
and `:where` that leaves the other entries alone.

The parity suites close what the audit of these bindings against the Java
annotations found untested, again over two TCP peers:
- the reacting verbs and returns: `notify` firing once per written entry
  with its result in `:result-space` and the cue left in place; `bid`
  pricing an AUCTION space where the cheaper bid takes every lot; a
  `contribution` return feeding the aggregate until the fleet mean settles;
  and `space/notify!` reporting `:expired` once a 1s write lease lapses on
  the other replica's sweep;
- the options: `:result-lease` bounding the result entry's `:expires-at`
  and `:description` reaching the card action; a join in `:mode :ordered`
  firing once per key across two joiners and draining its tickets; `@Part`
  `:at-least`, `:key-tag`, and `:optional`, and `:within` with `:max-open`
  finding a forgotten or evicted key again; a reduce in `:mode :ordered`
  under an `:accumulator-lease`, and by `:key-tag` leaving untagged elements
  alone; a propose keyed by `:key-tag`; each option read back from the
  generated interface;
- the capabilities: `semantic/remote-query` asking the other peer to rank
  the card it published, and `learn` averaging two models to the fleet mean
  and ending an epoch into the metrics space, plus `:learn true` refusing to
  end one;
- the card: `:certificate` and `:agent-public-key` under
  `:agent-keys :subordinate`, mirroring the raw `AgentCard`, and nil for a
  peer-signed agent.

The 0.5.0 parity suite covers what the review of the bindings against the
Spring starter found missing, over loopback peers: a peer the `:vote` grant
leaves out is refused when it casts, and `on-decision!` fires after its 1s
lease would have lapsed; a bound card's issue time advances on a 200 ms
`:card-refresh` and `stop!` shuts the refresher down; a join by the bare id
through a `:locators` locator that answers nothing the first time and a
member the second; the refusal naming `:seeds`, `:locators`, and
`:multicast`, the `FoundingTimeoutException` counts in the `ex-info`, and
the advice for a seed founded from a text; an `:invite` group admitting the
peer that presents a `:join-credential` and never the stranger, the
credential-without-founder refusal, and the `:membership` alias.

---

AgentSpaces is an open source project from Bad Monkey, Inc., licensed under
the Apache License, Version 2.0.
