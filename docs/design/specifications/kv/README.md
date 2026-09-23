# Sequential key-value contract

This is the single specification lineage for the sequential GET/PUT/DELETE/CAS reference
introduced by [Issue #21](https://github.com/G1DO/distributed-ledger/issues/21).
[SequentialKV.tla](SequentialKV.tla) is its executable transition specification;
[the Rust program](../../../../reference/kv/src/main.rs) and
[independent Python checker](../../../../scripts/kv/check.py) implement and check this contract.
The existing PostgreSQL [ledger contract](../ledger-invariants.md) remains separate and unchanged.
This reference has no reservations, amounts, operation keys, audit, TTL, or implicit retries.

## State and wire format

Each process starts with an empty finite map from nonempty Unicode scalar strings to Unicode
scalar strings. Values may be empty; JSON `null` denotes absence and is never stored. Strings
are compared exactly without Unicode normalization, so `é` and `e\u0301` are distinct keys.
There is no contractual size limit beyond available memory; this is a local reference, not an
untrusted-input network service.

Input is UTF-8 JSON Lines on stdin. Each line is one invocation, with the exact object fields
below, in any order. The operation name is case-sensitive. CRLF and a final unterminated line
are accepted. Blank lines are invalid invocations. Multiple JSON objects on one line are invalid.
Each invocation produces and flushes one JSON response line before another line is processed.
Object member order is not semantically significant. For a fixed binary and input, the emitted
bytes are deterministic. On clean EOF the program emits exactly one additional
`{"state":{...}}` line, containing the entire map with keys in lexicographic Unicode scalar
order. The snapshot is verification output, not a fifth key-value operation.

Let `old` mean the value immediately before the operation, or `null` for an absent key:

| Request (exact fields) | Response | Effect |
| --- | --- | --- |
| `{"op":"GET","key":k}` | `{"status":"ok","value":old}` | No change. |
| `{"op":"PUT","key":k,"value":v}` | `{"status":"ok","previous":old}` | Store string `v`, replacing any old value. |
| `{"op":"DELETE","key":k}` | `{"status":"ok","previous":old}` | Remove `k`; an absent key succeeds with `previous:null`. |
| `{"op":"CAS","key":k,"expected":e,"value":v}` | `{"status":"ok","swapped":b,"previous":old}` | Atomically compare `e` to `old`; store `v` iff equal, and return that equality as boolean `b`. |

CAS requires an explicit `expected` string or `null`. An absent key matches `null` and can be
created by CAS. A present empty value matches `""`, not `null`. A mismatch returns the observed
old value and `swapped:false` with no state change. Replacing a value with itself succeeds.
CAS cannot delete: `value` must always be a string, and deletion uses DELETE.

Invalid requests return exactly `{"status":"invalid"}` and leave the entire map unchanged.
Invalid means malformed JSON (including non-JSON numbers), a nonobject root, unknown operation,
missing or extra fields, duplicate member names after escape decoding, an empty/nonstring key,
a nonstring value, a nonstring/non-null CAS expectation, or an unpaired Unicode surrogate.
No validation error partially applies an operation; processing continues with the next line.

## Histories and linearizability

A history records invocation and completion events with a unique operation identity, its request,
and its observed response. Event order, rather than synchronized wall clocks, defines real-time
precedence: if A completes before B is invoked, A must precede B in the sequential explanation.
Overlapping operations may be ordered either way. Each completed operation must appear exactly
once with its actual result, and replay of that ordering from the specified initial state must
obey every transition above. Invalid requests are completed no-op operations with an invalid
response. A well-formed history never has an unmatched completion or two completions for one
invocation. If a final state is supplied, it must also match the explanation.

For an incomplete history, one may append responses to any subset of pending invocations and
discard the remaining pending invocations, then apply that same test to the completed history.
A timeout, lost response, or other indeterminate client outcome is treated as pending, not as
`invalid`, a CAS mismatch, or proof that nothing happened. The explanation may include its
effect only once, at a point after invocation consistent with all known events. A later GET or
known final state can constrain whether it happened. Retrying creates a new invocation; no
at-most-once retry identity is promised. For example, an indeterminate PUT of `v` followed by a
GET returning `v` can be explained by including the PUT; a completed PUT of `v` followed by a
nonoverlapping GET returning absence cannot.

The current CLI admits one outstanding call and applies input lines in order; the Python
checker validates this complete sequential history directly. It does not search concurrent
histories or accept incomplete output as a passing conformance run. In TLA+, Invoke saves the
pre-state and Complete represents the atomic state/result transition. In Rust, the map operation
is the linearization point, before emitting the response. Process or output failure may prevent
observation of that response even though the in-memory effect occurred.

## Failure model and properties

The sequential reference assumes one process, no concurrent callers inside it, and sufficient
memory. It has no persistent storage, network, or restart recovery. A process crash loses its
map. A fresh process is a new empty-state history, not continuation of an acknowledged durable
history. Fatal stream I/O or invalid UTF-8 terminates nonzero without a successful EOF snapshot;
the last invocation may be indeterminate. Truncated/failed output must fail the conformance gate.
No Byzantine behavior, host-power-loss durability, or bounded response time is claimed.

Safety properties in both committed TLC configurations are:

- `TypeOK`: every key slot contains a value or the distinct absence sentinel; requests,
  phases, saved states and responses remain in their specified domains.
- `InvocationUnchanged`: invocation alone leaves the map unchanged and has no result yet.
- `ResponseCorrect`: each completion reports exactly the old value and, for CAS, the correct
  equality result; an invalid request reports invalid.
- `OperationEffect`: GET/invalid/mismatching CAS preserve state; PUT, DELETE and successful CAS
  have the specified effect on the addressed key.
- `OperationFrame`: other keys are unchanged.

The model also checks absence of deadlock. `RequestProgress` says every running request
eventually completes. It holds under `FairSpec`, which adds weak fairness of Complete: an
enabled pending request must eventually be scheduled. The caller need not submit more requests.
Concrete progress assumes a running process, finite input lines, available resources, and a
consumer that continues reading stdout. Waiting for input, blocked output, crash, or resource
exhaustion falls outside this progress claim. Safety does not require fairness.

Future replicated implementations must refine this same operation contract and satisfy the
history rule above, including across failover and ambiguous outcomes. Consensus, message loss,
partitions, duplicate delivery, durable acknowledgment, crash recovery, membership changes,
and their additional progress assumptions require explicit models and checks when introduced.
They are not properties established by this sequential model.

## Bounded model checking and conformance corpus

[safety.cfg](safety.cfg) checks safety without fairness;
[progress.cfg](progress.cfg) checks the same invariants plus fair request completion. Both
exhaust the finite abstraction with two keys, two representative values (empty and one nonempty string), a separate
absence sentinel, all 21 abstract request classes, and one outstanding invocation. They retain
only the latest request, pre-state, and response, so the state graph is recurrent rather than
limited to a fixed call count. This does not prove correctness for an unbounded key/value
domain. Invalid wire forms collapse to one abstract invalid request; parsing is checked by
Rust/Python tests, not by TLC.

The independent Python checker computes every expected response and the final map from recorded
raw input. It imports no Rust implementation or generated transition code. Hand-written
[positive input](../../../../scripts/kv/fixtures/positive.input.jsonl) and
[expected output](../../../../scripts/kv/fixtures/positive.output.jsonl), together with corrupted
fixtures, guard against an implementation and checker agreeing on the same mistaken result.
The generated corpus requires at least 10,000 operations and explicit coverage of all operations,
present/absent values, overwrite, CAS success/mismatch on present/absent keys, invalid requests,
empty values, and Unicode. Generation uses the documented fixed 64-bit LCG and a guaranteed
edge-case prefix; a seed and count reproduce the same bytes. The verifier generates twice and
replays the recorded input twice, requiring byte equality and two zero-violation checker reports.

See [reproduction, merge gates, and evidence retention](../../../development/kv-verification.md).
The PostgreSQL T-10-minute PITR, invariant reconciliation, gap report, and proven runbook remain
the issue's external sequencing prerequisite. This reference neither implements nor certifies it.
