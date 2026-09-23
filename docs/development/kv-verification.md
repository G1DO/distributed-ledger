# Sequential KV verification

The [canonical contract](../design/specifications/kv/README.md) defines the wire format,
transitions, history rules, failure model, and finite model-checking boundary. This workflow
checks that contract without changing the Java reservation service.

## Reproduce the complete gate

Requirements: JDK 25, Python 3.12+, Git, and rustup. The reference's `rust-toolchain.toml` pins
Rust 1.92.0 with rustfmt and Clippy; `Cargo.lock` pins dependencies. The first run needs network
access for dependencies and the pinned TLC release asset. The existing Java gate additionally
needs Docker and the repository Maven wrapper.

From the repository root:

```bash
python3 scripts/verify-kv.py
python3 scripts/check-spec-sync.py --base HEAD
python3 scripts/check-docs.py
cd service
./mvnw clean verify -Pstrict
cd ..
python3 scripts/o2-compose-e2e.py
```

`verify-kv.py` checks Rust formatting, Clippy, Rust unit/process tests, Python checker tests,
merge-gate tests, both TLC configurations, and deliberately failing specification/checker
controls. It generates seed 21 / 10,000 requests twice, compares their bytes, runs the actual
Rust binary twice from fresh state against the first retained input, independently checks both
results and final maps, compares output bytes, and checks the hand-written positive fixture.
A command failure, missing result, incorrect response/state, missing required coverage,
nondeterministic replay, or unexpected negative-control result makes the gate fail nonzero.

Each run prints a unique evidence directory under `target/kv/`. `--output <new-directory>`
selects a different directory and refuses to overwrite existing evidence. `--seed` accepts an
unsigned 64-bit integer; `--count` must be at least 10,000 for the complete gate. To use the
recorded input directly after building the reference:

```bash
reference/kv/target/debug/kv-reference < target/kv/RUN/input-1.jsonl > /tmp/replay.jsonl
python3 scripts/kv/check.py --input target/kv/RUN/input-1.jsonl \
  --output /tmp/replay.jsonl --report /tmp/checker.json --require-coverage
cmp target/kv/RUN/output-1.jsonl /tmp/replay.jsonl
```

Replace `RUN` with the directory printed by the complete gate. To generate just an input:

```bash
python3 scripts/kv/generate.py --seed 21 --count 10000 --output /tmp/input.jsonl
```

Generator `lcg64-v1` emits a 35-record fixed edge-case prefix, then uses
`state = (6364136223846793005 * state + 1442695040888963407) mod 2^64`; each choice indexes
its ordered choices with `(state >> 32) mod number_of_choices`. The initial state is the seed.
The source defines the ordered key/value pools and draw order, and a fixed seed digest test
guards accidental sequence changes. JSON is compact UTF-8 with LF separators. The generator
contains no expected-response implementation; the checker computes results independently.

## TLC pin and scope

```bash
python3 scripts/kv/run_tlc.py --output target/kv/tlc
```

The runner downloads the official [TLA+ v1.8.0 prerelease](https://github.com/tlaplus/tlaplus/releases/tag/v1.8.0)
asset and verifies SHA-256
`9732eea90bdc7432e618184e4bee78700460e83e988238a80151dfd6507cfa0c` on every invocation,
including cached jars. `--jar` permits a predownloaded copy but never bypasses that checksum.
The pinned artifact identifies itself as TLC2 `2026.09.22.222048`, revision `35d40c9`.
It uses one worker, seed 21, fingerprint polynomial 0, a 512 MiB heap, and a 180-second timeout
per configuration. Its summary records full commands, config/model hashes, version, state
counts, and exit codes. Configurations live beside the single canonical model.

Both configurations use two keys, two string values plus absence, 21 request classes, and one
outstanding operation. Safety checks five invariants and deadlock; progress adds weak fairness
of Complete and eventual completion of each invocation. The complete finite graph, not an
unbounded-domain proof, is the checked claim. The runner also corrupts a temporary copy of GET
to return absence for a present key and requires the `ResponseCorrect` invariant to fail.
The canonical model is not changed, and no alternate specification lineage is maintained.

## Required merge check and specification synchronization

The existing required GitHub Actions check remains **`build-jdk25`**. Its job now includes
specification synchronization on pull requests and the full KV verification command on every
run, in addition to the existing strict Java verification, documentation check, and Compose
recovery drill. None of these steps uses `continue-on-error`; a failing specification or
reference makes the required check fail. The allowed-failure Java 26 and OSV jobs remain
informational. No new optional status is substituted for the required ledger check.

The repository's `main` protection was read during implementation: `build-jdk25` is required
from GitHub Actions (app ID 15368), strict/up-to-date checking is enabled, pull requests and
resolved conversations are required, protections apply to administrators, and force pushes
and deletion are disabled. Required numeric approvals remain zero. The workflow reuses this
existing protection; no remote repository settings need to change.

On PRs, `check-spec-sync.py` compares the merge base with the submitted head using full Git
history, including source additions, removals, and renames. Local `--base HEAD` also checks
untracked new files. Source changes require the following corresponding artifacts:

| Source scope | Required specification update |
| --- | --- |
| `reference/kv/` Rust source or Cargo dependency manifests | Executable change to canonical `SequentialKV.tla` **and** an update to the canonical positive input/output witness. |
| `service/` Java/SQL/shell/Python source, or the ledger Compose driver | `docs/design/specifications/ledger-invariants.md`. |
| KV Python checker or generator | `docs/design/specifications/kv/README.md`. |
| Other verification/development scripts | This verification workflow contract. |

Known test roots (`service/src/test/`, `reference/kv/tests/`, and `test_*.py` directly in
`scripts/` or `scripts/kv/`) are excluded; test-like names inside production roots are not.
Unmapped source paths
in the repository's source languages fail closed and require an explicit mapping with tests.
Deleting a required specification does not satisfy the gate. Whitespace-only updates do not
count; TLA+ comments (including nested block comments) and Markdown HTML comments do not count.
An unrelated README edit cannot satisfy a runtime source change. For a behavior-preserving
Rust change, strengthen the model's checks and its witness to document the preserved obligation
instead of changing operation semantics merely to appease the gate.

The synchronization check establishes correspondence of required artifacts, not semantic
equivalence of arbitrary programs. TLC, golden fixtures, and the independent reference checker
must also pass. Review must still establish that the changed model, witness, and implementation
describe the same behavior; finite testing cannot certify every possible input or detect an
intentionally coordinated weakening of all contracts and checks.

The synchronization tests use real temporary Git histories to prove rejection of code-only,
unrelated documentation, comment-only specification, deleted specification, and renamed-source
changes, and acceptance with a changed executable model plus witness. Acceptance in this test
is only synchronization acceptance; the model/reference must separately pass executable checks.
The TLC runner test invokes the normal CLI on an incorrect model and requires exit 1.
The Python checker tests invoke its normal CLI on 21 hand-written corruptions of every operation,
absence, mismatch, and final state, require exit 1, then execute each saved counterexample.

## Evidence and failures

Each full run retains seed/count, inputs, both raw output streams, checker reports with coverage,
logs for every check, exact commands, TLC logs/configurations and counterexample, tool versions,
base commit, working-tree diff, source SHA-256 manifest, and a complete source archive including
new untracked source files. `summary.json` records success only after every step passes. For
an uncommitted worktree, the base SHA alone is not the tested source: use the archive and manifest.
The archive's reproduction instructions initialize a local Git checkout before rerunning; its
new local commit is not the original upstream commit.

A checker failure retains the failing input/output prefix and expected/actual result plus a
`reproduce.txt` command under `failure-1/`, `failure-2/`, or `golden-failure/`. Malformed UTF-8
output is retained as raw bytes. This is a reproducible first-failure prefix, not a claim of
globally minimal shrinking. CLI/process failures retain original input, partial output, and
stderr even when no complete result exists. TLC failure retains the explored counterexample
and exact copied model. Archive evidence before deleting `target/`.

CI uploads `target/kv/` with the existing `verification-jdk25` artifact even after failure,
retaining it for 30 days alongside ledger evidence. Copy release evidence to durable storage
before expiry. GitHub CI results must be reported for the actual submitted commit; local
success or a successful baseline commit must not be labeled as CI success for an unsubmitted
patch. See [recorded verification](../perf/kv-reference-verification.md).
