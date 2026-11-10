# agentspaces-clj documents

Written for: anyone who read the README and wants the rest of the story.

The README is short by design: what this is, the quick start, and how to add it to a build. Everything else lives here, one topic per document.

| Document | Written for | What it holds |
|---|---|---|
| [About agentspaces-clj](about.md) | Clojure developers who build with agentspaces-clj | What AgentSpaces is, and what this project adds to it. |
| [1. Setup](setup.md) | Clojure developers who build with agentspaces-clj | Add the bindings to your `deps.edn`. |
| [2. The shape of the API](api-shape.md) | Clojure developers who build with agentspaces-clj | Entry types stay Java record classes, because every peer, whatever its language, agrees on entry schemas by type. |
| [3. One peer, from a config map](config-map.md) | Clojure developers who build with agentspaces-clj | `fleet/start` turns one config map into a running peer and returns a system map. |
| [4. Security posture: profiles, grants, and identity providers](security.md) | Clojure developers who build with agentspaces-clj | SPEC §11 defines four security profiles. |
| [5. Transports: TCP, TLS, enterprise CA, attestation, multicast](transports.md) | Clojure developers who build with agentspaces-clj | The profile picks the transport; `:transport` and `:channel-auth` override it when a deployment must deviate deliberately. |
| [6. Groups: three ways to join, content keys, advertisements](groups.md) | Clojure developers who build with agentspaces-clj | A group is identified in exactly one of three ways; `fleet/start` fails fast before binding a port if a config mixes them (SPEC §4.4, §5.1). |
| [7. Spaces: strategies, admission, credentials, sharding, leases](spaces.md) | Clojure developers who build with agentspaces-clj | Each entry of `:spaces` builds one `ReplicatedSpace`. |
| [8. The space verbs](space-verbs.md) | Clojure developers who build with agentspaces-clj | The same verbs work on a `ReplicatedSpace` from `fleet/start` and on a `LocalSpace` from `space/local`, which has full lease semantics and no fabric. |
| [9. The `:where` DSL](where-dsl.md) | Clojure developers who build with agentspaces-clj | A `:where` map narrows any read, take, or subscription. |
| [10. The system map, agents, and capabilities](agents-and-capabilities.md) | Clojure developers who build with agentspaces-clj | `fleet/start` returns everything it built: |
| [11. Defaults and choices](defaults.md) | Clojure developers who build with agentspaces-clj | These are the places where the bindings made a choice you may want to reverse, and how. |
| [12. Config key to starter property](config-keys.md) | Clojure developers who build with agentspaces-clj | 12. Config key to starter property |
| [13. Tests](tests.md) | contributors who run and extend the test suite | `clj -M:test` runs seventy tests. |
| [Contributing](contributing.md) | contributors | How to contribute: the Developer Certificate of Origin, the code of conduct, and private vulnerability reports. |
