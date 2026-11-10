# 5. Transports: TCP, TLS, enterprise CA, attestation, multicast

Written for: Clojure developers who build with agentspaces-clj.

The profile picks the transport; `:transport` and `:channel-auth` override it
when a deployment must deviate deliberately (the starter's
`agentspaces.transport.*` do the same). A TLS node also keeps plain TCP
available for dialing peers that advertise only TCP.

```clojure
;; Plain TCP, every frame signed.
{:transport :tcp :channel-auth :signed}

;; Plain TCP, unsigned frames over channels a session handshake authenticated
;; (what :dev-local selects).
{:transport :tcp :channel-auth :session}

;; TLS with identity-endorsed certificates (SPEC §5.6): the handshake proves the
;; peer, so frames on that link may travel without an envelope signature.
{:transport :tls :channel-auth :attested}

;; Your own Transport implementation (a WebSocket binding, a test double).
{:transport (my.transport/make)}
```

## Enterprise CA mode

With a trust store, TLS switches to the enterprise mode of SPEC v0.1.9: a
peer attests only when its certificate chain validates to your CA anchors and
is not revoked. Revocation is checked against cached CRL files, which keeps
the fleet working through an identity-provider partition, or live under
`:revocation-strict`. `:key-store` supplies this node's own CA-issued
credential.

```clojure
{:transport :tls
 :tls {:key-store "./node.p12"   :key-store-password   "changeit"
       :trust-store "./ca.jks"   :trust-store-password "changeit"
       :crls ["./ca.crl"]
       :revocation-strict false}}
```

## Required attestation

`:require-attestation true` makes channel attestation a condition of
membership. Every inbound frame must arrive on a connection whose handshake
proved its sender, and a current member whose next handshake attests nothing
(its certificate was revoked) is evicted and refused until it attests again.
This is how a CA's revocation becomes the fleet's eject. Pair it with
`:transport :tls`.

```clojure
{:transport :tls :require-attestation true
 :tls {:trust-store "./ca.jks" :crls ["./ca.crl"]}}
```

## Multicast bootstrap

On a LAN, peers can find each other with no seeds at all. The beacon sends
this peer's signed advertisement as a UDP datagram once per interval, and a
receiver verifies it exactly as it verifies a gossiped advertisement before
introducing itself. Nothing unauthenticated is ever acted on.

```clojure
{:multicast true}                                          ; 239.255.42.99:7787 every 5 s
{:multicast {:group "239.255.42.99:7787" :interval "2s"}}
```

With `:multicast` on, the beacon is also a locator in the group's bootstrap
chain (§6), so a `:join` by id with no seeds finds its founding through the
datagrams the beacon has heard.

## Seeds

Seeds are `"host:port"` strings whose scheme follows the effective transport,
or an explicit `"tls://host:port"` or `"tcp://host:port"`. A peer with no
`:bind` is dial-only: it reaches seeds but nobody dials it, which is the
NAT-restricted posture that relay-role peers exist to serve.
