# agentspaces-clj

**Idiomatic Clojure for AgentSpaces**, from Bad Monkey AI. `fleet/start` takes a config map and starts a peer of a peer-to-peer fleet, `defagent` writes one clause per verb, and entries arrive and leave as maps, over the same Java binder the other JVM hosts use.

## Quick start: build your first agent with Clojure

An agent is a form. Its parameter is the work it takes, and its return value is the next entry in the space. In Clojure, `defagent` writes one clause per verb, entries arrive and leave as maps, and `fleet/start` builds a peer from a config map whose keys mirror the Spring starter's properties.

**1. Define the entries.** The fleet names an entry type by its Java class, so the two entries are Java records. Compile them once with `javac`.

```java
// src/shop/Order.java
package shop;

public record Order(String orderId, String item) {
}
```

```java
// src/shop/Shipment.java
package shop;

public record Shipment(String orderId, String item, String by) {
}
```

```
mkdir classes && javac -d classes src/shop/*.java
```

**2. Add the library.** The bindings pull the AgentSpaces Java libraries from Maven Central. The 0.6.0 bindings are not yet on Clojars (`RELEASES.md` at the workspace root lists what is published); until they are, point `:local/root` at a clone of this repository in place of the `:mvn/version`.

```clojure
{:paths ["classes"]
 :deps {ai.badmonkey.agentspaces/agentspaces-clj {:mvn/version "0.6.0"}}}
```

**3. Write the agent.** The hints on the parameter and the result name the entry types, and the body returns a plain map that the binder converts into a `Shipment`. `fleet/start` builds the peer under the default `:dev-local` security profile, which suits one host; add `:security {:profile :mtls}` before a fleet leaves it (§4, §11).

```clojure
(ns shop.fulfiller
  (:require [agentspaces.agents :as agents :refer [defagent]]
            [agentspaces.fleet :as fleet]
            [agentspaces.space :as space])
  (:import (shop Order Shipment)))

(defagent Fulfiller
  {:description "Ships orders"}
  (take ship [^Order order] {:space "orders" :lease "30s"} ^Shipment
    {:orderId (:orderId order) :item (:item order) :by "fulfiller"}))

(let [[port seed item] *command-line-args*          ; clojure -M fulfiller.clj 7702 127.0.0.1:7701 kite
      system (fleet/start {:bind (str "127.0.0.1:" (or port 7701))
                           :group {:name "shop"
                                   :founding "shop-v1"
                                   :seeds (if seed [seed] [])
                                   :spaces [{:name "orders"}]}})]
  (agents/bind! system (->Fulfiller))
  (if item
    (let [orders (fleet/space system "orders")]
      (space/write! orders Order {:orderId "o-1" :item item} {:lease "10m"})
      (println (space/read orders Shipment {:timeout "30s"}))
      (fleet/stop! system))
    @(promise)))
```

**4. Run two instances.** The first founds the group on port 7701. The second takes its own port, the first instance as its seed, and an item to order.

```
clojure -M fulfiller.clj
clojure -M fulfiller.clj 7702 127.0.0.1:7701 kite
```

The second instance prints the shipment as a map. Either instance can take the order, because both run the same agent.

**5. Kill one.** Wrap the body of `ship` in `(do (println "shipping" (:orderId order)) (Thread/sleep 10000) {...})`. Start both instances, write an order from the second, and run `kill -9` on whichever instance prints `shipping`. If you killed the first instance, the second prints the shipment. If you killed the second, the first prints `shipping o-1` again when the 30 second lease lapses.

The [documents](docs/README.md) add the rest: the shape of the API, one peer from a config map, security profiles, transports, groups, and every verb `defagent` supports.

## What it is

- `fleet/start` with a config map whose keys follow the Spring starter's properties.
- `defagent` and `bind!`: take, notify, bid, ballot, join, ordered take, and reduce, with maps in and out.
- The space verbs and a keyword `:where` DSL; capabilities under `:capabilities`.
- Persistent values as Clojure collections in `agentspaces.pdt`; DataScript on a ref in `agentspaces-datascript`.
- Clojure 1.11 or newer on Java 21; listens and dials `tcp` and `tls`.

## Add it to your build

```clojure
{:deps {ai.badmonkey.agentspaces/agentspaces-clj {:mvn/version "0.6.0"}}}
```

The jar is on Clojars and holds source only. [Setup](docs/setup.md) covers a local clone and an unreleased core.

## Where to read next

1. **[The developer guide](https://www.badmonkey.ai/agentspaces/developer-guide.html#gw-clojure)**: its Clojure section maps this library onto the Java chapters.
2. **[The Clojure guide](https://www.badmonkey.ai/agentspaces/clojure-guide.html)**: a newcomer's walk from a first agent to the REPL workflow, capabilities, values that change, and a database on a ref.
3. **[The examples](examples/)**: runnable Clojure fleets, from one peer to a vote.
4. **[The documents](docs/README.md)**: everything this README leaves out, one topic per document, each with the reader it is written for.

## License

Apache License 2.0, Copyright 2026 Bad Monkey, Inc. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Contributions are welcome under the Developer Certificate of Origin; see [CONTRIBUTING.md](CONTRIBUTING.md). Report a vulnerability privately, as [SECURITY.md](SECURITY.md) describes.
