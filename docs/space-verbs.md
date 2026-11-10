# 8. The space verbs

Written for: Clojure developers who build with agentspaces-clj.

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
(space/read-entries work TaskEntry {:tags {:region "eu"}})
;=> [{:value {..} :entry-id .. :issuer .. :tags {"region" "eu"} :expires-at #inst .. :issued ..}]
```

`read-entries` is `read-all` with each entry's metadata around it
(`Space.readAllEntries`): the value map under `:value`, the `EntryId`, the
issuing `AgentId`, the tags as a string map, the lease expiry as an
`Instant`, and the HLC issue stamp.

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
`:reappeared`, plus `:entry`, `:entry-id`, `:tags` (the entry's tags as a
string map), and `:details` (the same metadata map `read-entries` returns,
nil when the space supplies none). The callback runs on the space's
delivery thread, so return promptly and hand slow work elsewhere.

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
