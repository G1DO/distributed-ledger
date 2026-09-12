# DEC-LEDGER-01 — Single Evolving Repo (merge)

Status: accepted. Date: 2026-09-10.

## Context

The project needs one place for documentation, service, engines, control-plane stub, and
simulation stub with a single CI signal. Alternatives were poly-repo
(spec/service/engines split) and mono-repo with independent versioning.

## Decision

Single evolving repo, canonical layout:

- `docs/` — technical documentation, including API, specifications, and decisions
- `service/` — Spring Boot app, package `com.g1do.ledger`
- `engines/postgres/` — durable store engine
- `engines/raft-lab/` — reserved for consensus experiments; no implementation is present
- `engines/replicated-store/` — replication façade over engines
- `control-plane/` — placeholder directory; no implementation is present
- `simulation/` — placeholder directory; no implementation is present
- `docs/decisions/` — accepted decisions; `README.md` — 5-min replay

## Consequences

- `main` stays releasable; rollback = revert commit.
- No per-directory versioning; one CI workflow gates all.
- Placeholder directories do not participate in the current runtime path.
- Future split (if repo grows) needs a new ADR; not assumed.
