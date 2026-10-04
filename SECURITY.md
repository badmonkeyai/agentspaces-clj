# Security Policy

AgentSpaces Clojure is a thin binding over the AgentSpaces Java libraries:
peers authenticate each other with Ed25519 signatures over self-certifying
identities, and the threat model is the core's (the AgentSpaces specification,
[SPEC §11](https://github.com/badmonkeyai/agentspaces-spec/blob/main/SPEC.md)).

## Reporting a vulnerability

Please report vulnerabilities **privately** via GitHub's security advisories:

> https://github.com/badmonkeyai/agentspaces-clj/security/advisories/new

A vulnerability in the Java libraries themselves belongs in the core
repository's advisories instead:
https://github.com/badmonkeyai/AgentSpaces/security/advisories/new

Do not open public issues for suspected vulnerabilities, and do not include
exploit details in public discussions until a fix is released.

We will acknowledge your report within five business days, keep you informed of
progress, credit you in the advisory unless you prefer otherwise, and
coordinate the disclosure timeline with you. There is currently no bug bounty
program.

Questions can be sent to `oss [at] badmonkey.ai`

## Scope

In scope: anything in the bindings that weakens a guarantee the core provides.
For example:
- a config key that silently selects a weaker security posture than it names;
- a default that differs from the Spring starter's documented default;
- key material written with broader permissions than the Java keystore uses;
- an agent identity or grant lost in translation.

## Supported versions

During 0.x, only the latest minor release receives security fixes.
