# 9. The `:where` DSL

Written for: Clojure developers who build with agentspaces-clj.

A `:where` map narrows any read, take, or subscription. Plain values match by
equality, vectors name an operator, and functions become predicates. Numeric
bounds compare numerically, so Clojure longs match `int` components.

| Form | Meaning |
|---|---|
| `{:topic "indexing"}` | equality |
| `{:priority [:gt 5]}` | `:gt` `:gte` `:lt` `:lte` `:eq` `:ne` comparisons |
| `{:region [:in "us" "eu"]}` | membership |
| `{:summary [:contains "risk"]}` | substring |
| `{:assignee [:nil?]}` / `[:some?]` | null checks |
| `{:priority odd?}` | any Clojure predicate |
| `{:topic "x" :priority [:gte 3]}` | several fields, all must match |

`core/template` compiles a `:where` map into the fabric's `Template` when you
need the raw object.
