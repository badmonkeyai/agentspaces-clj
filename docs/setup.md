# 1. Setup

Written for: Clojure developers who build with agentspaces-clj.

Add the bindings to your `deps.edn`. They pull the AgentSpaces Java
libraries from Maven Central. The 0.6.0 bindings are not yet on Clojars
(`RELEASES.md` at the workspace root lists what is published); until they are, replace the
`:mvn/version` with a `:local/root` pointing at a clone of this repository:

```clojure
{:deps {ai.badmonkey.agentspaces/agentspaces-clj {:mvn/version "0.6.0"}}}
```

To work on the bindings themselves, see [CONTRIBUTING.md](../CONTRIBUTING.md). In
short: install the AgentSpaces core into your `~/.m2` (until its artifacts are
on Maven Central), then run the suite:

```bash
git clone https://github.com/badmonkeyai/AgentSpaces.git agentspaces
mvn -f agentspaces/pom.xml install -DskipTests
clojure -M:test
```

The suite runs real fleets over loopback and finishes in about fifteen
seconds. Once the dependencies are in `~/.m2`, the same suite runs on a plain
`java` classpath, with no network and no Clojure CLI at run time:

```bash
CP=$(clojure -Spath -M:test)   # once, while the CLI can resolve dependencies
java -cp "$CP" clojure.main -e "(require 'agentspaces.run-tests) (agentspaces.run-tests/-main)"
```
