# 12. Config key to starter property

Written for: Clojure developers who build with agentspaces-clj.

| Config key | Starter property | Notes |
|---|---|---|
| `:identity` / `:keystore` | `keystore` | a `PeerIdentity`, or a directory; omit both for an ephemeral identity |
| `:bind` | `bind` | `"host:port"`; omit for dial-only |
| `:roles` | `roles` | `#{:relay :rendezvous}` |
| `:tick` | `tick-millis` | default `"250ms"` |
| `:security {:profile}` | `security.profile` | default `:dev-local` here, `mtls` in the starter |
| `:security {:grants}` | `security.grants.*` | twelve operation keys (`:raft-voter` `:raft-client` `:directive-issuer` `:connector-serve` `:connector-invoke` `:key-holder` `:key-rotator` `:space-write` `:space-take` `:vote` `:model-serve` `:content-fetch`), each a list of peer ids |
| `:security {:authorizer}` | `security.oidc.*` | an `Authorizer` instance; required under OIDC profiles |
| `:security {:token}` | `security.oidc.token` | this node's `aspace:oidc` hint |
| `:security {:on-credential-hints}` | the Spring `Authorizers` hint listener | `(fn [peer-id hints])` |
| `:transport` | `transport.tls.enabled` | `:tcp`, `:tls`, or a `Transport` |
| `:channel-auth` | `transport.channel-auth` | `:signed`, `:attested`, or `:session` |
| `:tls {..}` | `transport.tls.{key-store, key-store-password, trust-store, trust-store-password, crls, revocation-strict}` | a trust store selects CA mode |
| `:require-attestation` | `transport.tls.require-attestation` | pair with `:transport :tls` |
| `:multicast` | `multicast.{enabled, group, interval-millis}` | `true` or a map; also a join locator |
| `:lazy-push` | `lazy-push` | default `true` |
| `:peer-ad-interval` | `peer-ad-interval-millis` | default `"30s"` |
| `:card-refresh` | `card-refresh-millis` | default `"5m"` |
| `:agents` | none (the starter always creates discovery) | `true` switches discovery on for bound agents with no capability |
| `:capabilities {..}` | `capabilities.*` | §10.2 |
| `:content` | `content.*` | `true` or a map; §10.2 |
| `:revocation-validator` | `PeerNode.Builder.revocationValidator` | a `RevocationValidator` |
| `:agent-keys` | `identity.agent-keys` | `:peer` (default) or `:subordinate` (v0.1.13) |
| `:agent-keystore` | `identity.agent-keystore` | a directory; needs `:subordinate` |
| `:agent-certificate-ttl` | `identity.agent-certificate-ttl` | default `"24h"` |
| `:group {:name}` | `groups[].name` | also the default founding string |
| `:group {:founding}` | `groups[].founding` | literal-id derivation |
| `:group {:join}` `:join-timeout` | `groups[].join`, `join-timeout-millis` | join by id; needs a chain with something to ask (`:seeds`, `:locators`, or `:multicast`); excludes `:founding` and `:found` |
| `:group {:policy}` | `groups[].membership` | `:open` (default) or `:invite` |
| `:group {:founder}` | `groups[].founder` | the PeerId whose credentials admit under `:invite`; default this peer |
| `:group {:join-credential}` | `groups[].join-credential` | the `aspace:join` hint, from `JoinCredentials.issue` |
| `:group {:locators}` | `BeaconLocator` beans | a vector of `BeaconLocator` instances, asked after the seeds |
| `:group {:bootstrap}` | `bootstrap.{require, refresh-min, refresh-max, trace}` and the chain's `firstWins`, `timeout` | the `LocatePolicy` and the chain's shape |
| `:group {:found}` | none | `true` to found here, or a `SignedGroupAdvertisement` |
| `:group {:seeds}` | `groups[].seeds` | `"host:port"` or `"tls://host:port"` |
| `:group {:membership-tuning}` | `GroupMembership.Config` | `:ttl`, `:probe`, `:indirect`; `:membership` is the deprecated alias |
| `:group {:content-key}` | `groups[].content-key` | 32 bytes, Base64, or a `GroupKey` |
| `:group {:content-key-rotation}` | `groups[].content-key-rotation.*` | `:rotate-every`, `:cutover-delay`, `:writer-grace` (v0.1.13) |
| `:group {:advertise}` | starter default | publish SpaceAdvertisements |
| `:group {:agent}` | the starter's `"app"` | local agent name, default `"clj"` (§11: set `"app"` to share admission rules with a Spring peer) |
| `:spaces [{:name :strategy :settle :bid-fn}]` | `spaces[].{name, strategy, settle-window-millis}` | `:bid-fn` for AUCTION |
| `:spaces [{:admission}]` | `spaces[].admission` | `:group`, `:allowlist`, `:credential`, `:authorizer` |
| `:spaces [{:allowed-agents}]` | `spaces[].allowed-agents` | `"<peerId>/<name>"` or `AgentId` |
| `:spaces [{:credential-issuer}]` | `spaces[].credential-issuer` | default this node |
| `:spaces [{:max-lease :founder-lease}]` | `ReplicatedSpace.Builder.maxLease`, `founderLease` | |
| `:spaces [{:tag-shard}]` | `ReplicatedSpace.Builder.tagShard` | a map of required tags or a predicate |
