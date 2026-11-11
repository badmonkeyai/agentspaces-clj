# About agentspaces-clj

Written for: Clojure developers who build with agentspaces-clj.

**A peer-to-peer coordination fabric for agents, services, and the people who work with them.**

AgentSpaces is a P2P framework to quickly discover and join P2P networks, where agents, software services, and people share assets, queries, results, and coordinate. The framework helps you build fleets of agents, tie in other software, and coordinate across them with a replicated tuple space, quorum building, and more. Agents post work, claim tasks, publish results, vote on decisions, and recover from each other's failures. There is no broker, no registry, no scheduler, and no server to keep alive. Coordination is peer-to-peer, secured end to end, and replicated across the fleet.

## About agentspaces-clj

Idiomatic Clojure bindings for [AgentSpaces](https://github.com/badmonkeyai/AgentSpaces): the
shared, leased, replicated tuple space and its peer-to-peer fabric, driven
with maps, keywords, and functions.

The bindings consume the AgentSpaces Java libraries by Maven coordinates:
`deps.edn` names `ai.badmonkey.agentspaces/agentspaces-*` with `:mvn/version`,
and tools.deps resolves them from Maven Central like any other dependency, or
from your `~/.m2` after a `mvn install` of the core when you work against an
unreleased version.

The bindings track the AgentSpaces specification,
[SPEC v0.1.33](https://github.com/badmonkeyai/agentspaces-spec/blob/main/SPEC.md).
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
