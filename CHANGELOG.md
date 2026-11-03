# agentspaces-clj changelog

What changed in the bindings, newest first. The Java core's history is in
the root `CHANGELOG.md` of the AgentSpaces workspace.

## 0.5.0 (2026-11-02, in preparation; not yet on Clojars)

Parity with the Spring starter at AgentSpaces 0.5.0, from the review
`reviews/review-110226-deadcode-parity.md` §4 (items C1 to C5, §4.3, §4.4).
Seventy tests, five of them new (`agentspaces.fleet-parity-test`).

- **The `:vote` grant is enforced (C1).** `fleet/start` builds the
  `VoteCapability` with `ANY_AUTHENTICATED_ISSUER` and the profile's
  authorizer, as the starter does, so a peer the `:vote` grant leaves out is
  refused when it casts and its ballot never counts.
- **Cards refresh (C2).** A daemon scheduler re-publishes every bound
  agent's card and every capability advertisement each `:card-refresh`
  (default `"5m"`, the starter's `card-refresh-millis`), inside the
  fifteen-minute TTL; `fleet/stop!` shuts it down. The system map carries
  it as `:card-refresher`.
- **The bootstrap chain (C3).** Every join goes through a `BootstrapChain`
  over the `:seeds`, the new `:locators` (`BeaconLocator` instances, in
  order), and the multicast beacon when `:multicast` is on, with the
  `LocatePolicy` from the new `:bootstrap {:require :refresh-min
  :refresh-max :trace :first-wins :timeout}`. A `:join` is refused only when
  the chain has nothing to ask, and the message names `:seeds`,
  `:locators`, and `:multicast`. A timeout's `ex-info` carries the
  `FoundingTimeoutException`'s `:locates`, `:dialed`, `:connected`, and
  `:message`, plus the advice that a group started from a `:founding` text
  cannot be joined by id when a connected seed served nothing. `:join`
  accepts the bare id as well as the `aspace://` URI.
- **Join credentials and policy (C4).** `:join-credential` becomes the
  `aspace:join` hint on every join; `:policy #{:open :invite}` and
  `:founder` on the group map build the advertisement as the starter's
  `membership` and `founder` do; a credential without a founder on a
  derived invite group is refused. The SWIM tuning map is
  `:membership-tuning`; `:membership` stays as a deprecated alias that logs
  one warning per process.
- **Votes and grants (C5).** `vote/on-decision!` takes `{:lease :renew}`
  and renews its subscription at half-life until closed. The grants map
  gains `:raft-client`, `:connector-invoke`, and `:content-fetch`, so all
  twelve operations of `Authorizer.Operation` are reachable. The writer
  agent stays `"clj"`; the README documents that the starter's is `"app"`
  and how to set it.
- **Dead code.** `build-facade` loses its unused codec parameter and empty
  spaces loop; the discovery checks that `start` had already made
  unreachable, the duplicate `CapabilityPipes`/`CapabilityRuntime` import,
  the orphaned section headers, and the twice-computed agent name are gone;
  `->group-id` parses the id itself instead of through `PeerId`; the
  `agents/bind!` refusal now describes the one case the facade leaves it
  (an object with no agent annotations).
- **Docs.** The README states once that 0.5.0 is not yet on Clojars, names
  SPEC v0.1.23 and the 0.5.0 coordinate, lists eleven namespaces with
  `agentspaces.content`, the twelve grant operations, `:session` as the
  `:dev-local` channel auth and as a `:channel-auth` value, every top-level
  and group key, and the test count; new sections cover `:locators`,
  `:bootstrap`, `:policy`, `:founder`, `:join-credential`,
  `:membership-tuning`, and `:card-refresh`. The `space` and `agents`
  docstrings name `:agentspaces/taken` and `reviews/L3L4-COVERAGE.md`.

## 0.4.0 and earlier

See the root `CHANGELOG.md` and `RELEASES.md` of the AgentSpaces workspace.
