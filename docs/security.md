# 4. Security posture: profiles, grants, and identity providers

Written for: Clojure developers who build with agentspaces-clj.

SPEC §11 defines four security profiles. A profile decides three things at
once: the transport, whether every frame keeps its envelope signature, and
who answers authorization questions. `:security {:profile ..}` selects one,
exactly as `agentspaces.security.profile` does in the starter.

| Profile | Transport | Channel auth | Authorizer |
|---|---|---|---|
| `:dev-local` | TCP | `:session` | membership, plus grants |
| `:mtls` | TLS with identity-endorsed certificates | `:attested` | membership, plus grants |
| `:mtls-oidc` | TLS | `:attested` | your identity provider |
| `:zero-trust` | TLS | `:signed` on every hop | your identity provider |

## Membership-rooted authorization

Under `:dev-local` and `:mtls`, `fleet/start` builds a `MembershipAuthorizer`
over the joined group and exposes it as `:authorizer` in the system map. Every
admitted member may perform every privileged operation until you narrow one
with a grant. A grant lists the peers allowed to perform an operation; an
absent or empty list leaves that operation open to any member.

```clojure
(fleet/start
  {:bind "127.0.0.1:7591"
   :security {:profile :mtls
              :grants {:directive-issuer ["z6MkConsole..."]     ; only the console commands workers
                       :key-holder       ["z6MkFounder..."]     ; only the founder hands out the group key
                       :raft-voter       ["z6MkA..." "z6MkB..." "z6MkC..."]
                       :space-write      []                     ; empty: any member
                       :space-take       []
                       :connector-serve  ["z6MkWarehouse..."]}}
   :group {:name "research" :seeds ["127.0.0.1:7590"]
           :spaces [{:name "tasks"}]}})
```

The twelve operations are the ones of `Authorizer.Operation`, the ones the
fabric guards: `:raft-voter` and `:raft-client` (the ordered log's quorum
and its clients), `:directive-issuer` (command and control),
`:connector-serve` and `:connector-invoke` (data query results a fleet
trusts, and who may ask), `:key-holder` and `:key-rotator` (receiving and
rotating the group content key), `:space-write` and `:space-take` (space
admission under the `:authorizer` rule, §7), `:vote` (casting a ballot the
vote capability counts, §10.2), `:model-serve` (gossip learning), and
`:content-fetch` (pulling from the content swarm). Every member must carry
the same grants, because each member answers from its own configuration.

The authorizer is an ordinary Java object you can question yourself:

```clojure
(import 'ai.badmonkey.agentspaces.api.spi.Authorizer$Operation)
(.permits (:authorizer system) some-peer-id Authorizer$Operation/DIRECTIVE_ISSUER
          (.value (.id (:runtime system))))
;=> true for a member the grant names, false for a stranger
```

## Identity-provider authorization

Under `:mtls-oidc` and `:zero-trust` the starter builds an `OidcAuthorizer`
from `agentspaces.security.oidc.{issuer, audience, jwks-url}`. That class
lives in `agentspaces-auth-oidc`, which brings Nimbus JOSE+JWT with it, and
a library binding should not force that dependency on every consumer. So the
bindings ask you to construct the authorizer and pass it in. `fleet/start`
refuses an OIDC profile without one and tells you why.

```clojure
;; deps.edn: add ai.badmonkey.agentspaces/agentspaces-auth-oidc to your own project.
(import 'ai.badmonkey.agentspaces.auth.oidc.OidcAuthorizer
        'java.net.URL 'java.time.InstantSource)

(def oidc (OidcAuthorizer/fromJwks "https://idp.example.com" "agentspaces-fleet"
                                   (URL. "https://idp.example.com/.well-known/jwks.json")
                                   (InstantSource/system)))

(fleet/start
  {:bind "127.0.0.1:7591"
   :security {:profile    :mtls-oidc
              :authorizer oidc
              ;; this node's own token, bound to its PeerID by the agentspaces_peer claim;
              ;; it travels to other members as the aspace:oidc resource hint
              :token      (slurp "/run/secrets/fleet-token")
              ;; members' tokens arrive here, on admission and whenever they change
              :on-credential-hints
              (fn [peer-id hints]
                (when-let [jwt (get hints "aspace:oidc")]
                  (.authorize oidc peer-id jwt)))}
   :group {:name "research" :seeds ["10.0.0.5:7590"] :spaces [{:name "tasks"}]}})
```

Three things to know about tokens. A token authorizes exactly the peer named
in its `agentspaces_peer` claim, so a replayed hint grants nothing to anyone
else. The token rides inside the peer's signed self-advertisement, which
gossips once per period, so keep tokens under a couple of kilobytes. Refresh
is your responsibility: call `(.credentialHint (:node system) "aspace:oidc"
new-jwt)` when your identity provider issues a new one, and the next
self-advertisement carries it.

An explicit `:authorizer` wins under any profile, so you can also plug a
custom policy engine into `:mtls` by implementing the one-method `Authorizer`
interface:

```clojure
(import 'ai.badmonkey.agentspaces.api.spi.Authorizer)
(def policy
  (reify Authorizer
    (permits [_ peer operation scope]
      (my-policy-engine/allows? (.value peer) (str operation) scope))))
(fleet/start {:security {:profile :mtls :authorizer policy} :group {...}})
```
