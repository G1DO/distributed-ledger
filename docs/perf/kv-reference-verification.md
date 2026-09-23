# Sequential KV verification — 2026-09-23

This records the initial bounded local verification for Issue #21. That run used
`90e32317b9bc79c01fde4c84022b7774f95838f7` plus the working-tree implementation, not a claim that
the new code exists in that commit. `target/kv/issue21-final/` retains the exact source archive,
per-file SHA-256 manifest, patch, inputs, outputs, checker reports, commands, and tool versions.
The [verification workflow](../development/kv-verification.md) reproduces the checks and
describes retention. Java production source and applied migrations are unchanged.

## Results

| Check | Observed result |
| --- | --- |
| `python3 scripts/verify-kv.py --output target/kv/issue21-final` | Pass: all checks, two input generations and two fresh-process replays. |
| Rust 1.92.0 `cargo fmt`, `cargo test --locked`, Clippy with warnings denied | Pass; 5 unit and 3 process tests. |
| Independent Python checker/generator tests | 9 pass, including 21 deliberately corrupted response/state fixtures and replayed counterexamples. |
| TLC runner negative tests | 2 pass: incorrect model exits 1; wrong cached JAR checksum is rejected. |
| Specification synchronization tests | 12 pass: code-only/unrelated docs/comment-only changes rejected; model plus witness accepted; deletions, renames, and test-like production names covered. |
| `python3 scripts/check-spec-sync.py --base HEAD` | Pass for the implementation worktree. |
| `python3 scripts/check-docs.py` | Pass. |
| JDK 25 `./mvnw -B clean verify -Pstrict` | Pass; 12 unit and 106 integration tests, zero failures/errors/skips, including the full 1,000-history O2 corpus and process crashes. |
| `python3 scripts/o2-compose-e2e.py` | Pass in 44.976 seconds, including lifecycle, scheduled expiry, PostgreSQL crash/rollback, and acknowledged-response replay. |

Java used Temurin 25.0.4.1 and the Maven 3.9.11 wrapper; Python was 3.12.3. The first Compose
attempt hit a pre-existing broken Docker Desktop Buildx symlink under `/usr/local/lib/docker`.
The successful retry used a temporary `DOCKER_CONFIG` with
`cliPluginsExtraDirs: ["/usr/libexec/docker/cli-plugins"]`, selecting the already-installed
Buildx binary. No repository or persistent Docker configuration was changed for that retry.
Its evidence is in `service/target/o2-compose-e2e/20260923T043138Z-59ef6211/`.

## TLC results and assumptions

Pinned release: official TLA+ v1.8.0 prerelease; TLC2 `2026.09.22.222048`, revision `35d40c9`.
JAR SHA-256: `9732eea90bdc7432e618184e4bee78700460e83e988238a80151dfd6507cfa0c`.

| Configuration | Generated states | Distinct states | Graph depth | Result |
| --- | ---: | ---: | ---: | --- |
| `safety.cfg` | 4,180 | 379 | 7 | All five invariants and deadlock check pass. |
| `progress.cfg` | 4,180 | 379 | 7 | Same invariants plus `RequestProgress` pass under weak fairness of Complete. |
| Corrupted GET transition in temporary copy | 486 | 129 | 5 at failure | Expected `ResponseCorrect` violation, nonzero TLC exit. |

Both committed configurations exhaust two keys, two representative string values (empty and
one nonempty value), one absence sentinel, 21 request classes, and one outstanding invocation.
The finite graph recurs across arbitrary call counts; this is not an unbounded-domain proof.
No concurrent implementation, replication, persistence, crash recovery, or network fault is
modeled. Fairness is a progress assumption, not a safety requirement. See the
[contract](../design/specifications/kv/README.md) for incomplete/indeterminate history rules.

## Recorded deterministic corpus

Seed **21**, generator **lcg64-v1**, **10,000** operations, **zero violations** in each replay.
The inputs and observable outputs are byte-identical across the two runs, and the independent
checker validates every result and the entire final map.

- Input SHA-256: `66dfd84dafa244199299f5ffa88ffbb765386be6371b72b572c767d055fc40b2`.
- Output SHA-256: `14948b562cd70dd0b5587391d474095e6197def5808108a409950659e1ecd0de`.

| Operations / edges | Count |
| --- | ---: |
| GET / PUT / DELETE / CAS / invalid | 2,027 / 1,978 / 2,063 / 2,008 / 1,924 |
| GET absent / present | 957 / 1,070 |
| PUT absent / overwrite | 956 / 1,022 |
| DELETE absent / present | 1,006 / 1,057 |
| CAS absent success / mismatch | 104 / 855 |
| CAS present success / mismatch | 143 / 906 |
| Empty value / Unicode key / Unicode value observations | 591 / 2,734 / 1,110 |

All required invalid-request categories are nonzero; full counts are in both checker reports.
Committed positive fixtures and explicit corrupted outputs exercise every operation, absence,
CAS mismatch, strict boolean typing, invalid-request nonmutation, and incorrect/missing/extra
final state. CLI rejection controls preserve and replay the counterexample, not merely an
in-process assertion that an error should occur.

## Required CI configuration and remaining boundary

The read `main` protection requires the up-to-date GitHub Actions `build-jdk25` status
(app ID 15368), with administrator enforcement and resolved conversations. The updated workflow
places synchronization, TLC, reference/checker verification, and all existing ledger checks in
that required job. An incorrect spec/reference fails the same status; unrelated documentation
cannot satisfy the source synchronization gate.

The [baseline `build-jdk25` run](https://github.com/G1DO/distributed-ledger/actions/runs/35815767673/job/107037816188)
passed for `90e32317b9bc79c01fde4c84022b7774f95838f7`. The initial local run preceded the
implementation commits. For the submitted revision, use the required `build-jdk25` result and
`verification-jdk25` artifact on the PR closing Issue #21; its `summary.json` records the exact
tested commit. CI success must not be inferred from the baseline or local runs. Protection and
baseline check responses are retained with the original local evidence. No remote protection
was weakened or replaced.

The issue's PostgreSQL T-10-minute PITR, reconciliation, gap report, and proven runbook remain
an external sequencing prerequisite without completion evidence. This issue neither implements
nor certifies them. No subsequent issue has been started.
