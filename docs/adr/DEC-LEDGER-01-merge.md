# DEC-LEDGER-01 — Single Evolving Repo (merge)

Status: accepted. Date: 2026-09-10.

## Context

The project needs one place for spec, service, engines, control-plane stub, and
simulation stub with a single CI signal. Alternatives were poly-repo
(spec/service/engines split) and mono-repo with independent versioning.

## Decision

Single evolving repo, canonical layout:

- `spec/` — invariants + API (spec-first, evolves with code)
- `service/` — Spring Boot 3.x app, package `com.g1do.ledger`
- `engines/postgres/` — durable store engine
- `engines/raft-lab/` — lab-only consensus experiments, behind lab flag
- `engines/replicated-store/` — replication façade over engines
- `control-plane/` — stub v1 (no API per REQ-V1-03)
- `simulation/` — stub v1 (deterministic replay harness later)
- `docs/adr/` — decisions; `README.md` — 5-min replay

## Consequences

- `main` stays releasable; rollback = revert commit.
- No per-directory versioning; one CI workflow gates all.
- `engines/raft-lab` MUST NOT affect prod paths; lab flag required.
- Future split (if repo grows) needs a new ADR; not assumed.
