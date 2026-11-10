# 2. The shape of the API

Written for: Clojure developers who build with agentspaces-clj.

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

Eleven namespaces carry everything. The first three are the data layer, the
space verbs, and the peer; `agents` is the programming model (§10.1); the
rest each work one capability the `:capabilities` block or the `:content`
block registers (§10.2).

| Namespace | Carries |
|---|---|
| `agentspaces.core` | `duration`, `lease`, `peer-id`, `agent-id`, `to-entry`, `from-entry`, `raw`, `template` |
| `agentspaces.space` | `local`, `as`, `write!`, `read`, `read-all`, `read-entries`, `take!`, `taken`, `complete!`, `renew!`, `cancel!`, `notify!`, `unsubscribe!`, `grant!`, `revoke!` |
| `agentspaces.fleet` | `start`, `stop!`, `space`, `found-group`, `peer-id`, `writer`, `agent`, `revoke!`, `revoked?`, `rotate-content-key!`, `group-uri` |
| `agentspaces.agents` | `defagent`, `bind!`, `unbind!`, `card`, and the returns `motion`, `contribution`, `tagged`, `convert-out` |
| `agentspaces.vote` | `propose!`, `cast!`, `proposal`, `tally`, `decision`, `on-decision!` (`{:lease :renew}`) over the vote capability |
| `agentspaces.aggregate` | `start!`, `estimate`, `tick!`, `await-settled`, `on-estimate!` over the push-sum aggregate |
| `agentspaces.ordered` | `take!`, `complete!`, `leader?`: the exactly-once take through the ordered log |
| `agentspaces.semantic` | `query`, `remote-query`: the group's cards ranked by meaning, locally or across peers |
| `agentspaces.learn` | `start!`, `model`, `round`, `end-epoch!` over gossip learning |
| `agentspaces.remote` | `actions`, `invoke!`: a foreign card's invocable actions, called with a map |
| `agentspaces.content` | `publish!`, `fetch!`, `await`, `progress`, `read-bytes`, `open`, `describe`, `list-manifests`, `share!`, `pin!`, `unpin!`, `stats` over the content swarm |
