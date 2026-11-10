# 3. One peer, from a config map

Written for: Clojure developers who build with agentspaces-clj.

`fleet/start` turns one config map into a running peer and returns a system
map. `fleet/stop!` closes it. The config shape mirrors the Spring starter's
properties (SPEC §10.1), so a Clojure peer and a Spring Boot peer read from
the same design and moving between them is renaming keys.

The smallest fleet member: plain TCP, a literal group name, one space.

```clojure
(def system
  (fleet/start
    {:bind  "127.0.0.1:7591"
     :group {:name   "research"
             :seeds  ["127.0.0.1:7590"]
             :spaces [{:name "tasks"}]}}))

(def tasks (fleet/space system "tasks"))
(space/write! tasks TaskEntry {:topic "indexing" :priority 3} {:lease "10m"})
(fleet/stop! system)
```

A fuller member, showing every top-level key:

```clojure
(fleet/start
  {:keystore "./peer-keys"                ; persistent identity; omit for ephemeral
   ;; :identity a-peer-identity           ; or reuse a PeerIdentity you hold
   :bind     "0.0.0.0:7591"               ; omit to run dial-only (a NAT-restricted peer)
   :roles    #{:rendezvous}               ; or #{:relay}, or both
   :tick     "250ms"                      ; protocol round period
   :lazy-push true                        ; the self-pruning gossip tree (false: random fan-out only)
   :peer-ad-interval "30s"                ; how often the self-advertisement is re-flooded
   :card-refresh "5m"                     ; how often bound cards and capability ads re-publish, §10
   :security {:profile :mtls}             ; §4 below
   :transport :tls                        ; §5 below; the profile already implies it
   :channel-auth :attested
   :tls      {:trust-store "./ca.p12"}    ; enterprise CA mode, §5
   :require-attestation true
   :multicast {:group "239.255.42.99:7787" :interval "5s"}
   :revocation-validator nil              ; a RevocationRegistry.RevocationValidator, §5
   :agent-keys :subordinate               ; each agent its own peer-certified key, §6
   :agent-keystore "./agent-keys"
   :agent-certificate-ttl "24h"
   :agents true                           ; discovery on for bound agents, §10.1
   :capabilities {:vote {:space "votes"}} ; the layer-4 services this peer provides, §10.2
   :content  {:escalate-above "4MiB"}     ; the content swarm, §10.2
   :group    {:name "research"            ; §6 below
              :founding "research-fleet-v1"
              :policy :invite                       ; or :open (default)
              :founder "z6MkFounder..."             ; whose credentials admit, under :invite
              :join-credential "<from JoinCredentials.issue>"
              :seeds ["tls://10.0.0.5:7590"]
              :locators [my-dns-locator]            ; BeaconLocators asked after the seeds
              :bootstrap {:require false :refresh-min "2s" :refresh-max "30s" :trace false}
              :membership-tuning {:ttl "30s" :probe "2s" :indirect 2}
              :content-key "<base64 of 32 bytes>"
              :advertise true
              :agent "worker"
              :spaces [{:name "tasks" :settle "150ms"}                 ; §7 below
                       {:name "bids"  :strategy :auction :bid-fn estimate}
                       {:name "control" :admission :allowlist
                        :allowed-agents ["z6Mk.../steward"]}
                       {:name "partner" :admission :credential}
                       {:name "audited" :admission :authorizer}]}})
```

Only `:group` is required. Every other key has a default that matches the
starter, with one deliberate exception described in §11.
