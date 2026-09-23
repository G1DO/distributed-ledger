#!/usr/bin/env python3
"""Generate reproducible sequential KV requests, including a fixed edge-case prefix."""

import argparse
import json
from pathlib import Path


MASK = (1 << 64) - 1
KEYS = ("alpha", "beta", "γ", "🗝", "line\nkey", "zero\0key")
VALUES = ("", "one", "two", "mañana", "🧪", "line\nvalue", "zero\0value")


def encode(request):
    return json.dumps(request, ensure_ascii=False, separators=(",", ":"))


def edge_cases():
    """Fixed requests guarantee edge coverage independently of the random tail."""
    requests = [
        {"op": "GET", "key": "alpha"},
        {"op": "DELETE", "key": "alpha"},
        {"op": "CAS", "key": "alpha", "expected": "missing", "value": "unused"},
        {"op": "CAS", "key": "alpha", "expected": None, "value": ""},
        {"op": "GET", "key": "alpha"},
        {"op": "CAS", "key": "alpha", "expected": None, "value": "unused"},
        {"op": "CAS", "key": "alpha", "expected": "", "value": "one"},
        {"op": "PUT", "key": "alpha", "value": "two"},
        {"op": "DELETE", "key": "alpha"},
        {"op": "GET", "key": "alpha"},
        {"op": "PUT", "key": "🗝", "value": "🧪"},
        {"op": "GET", "key": "🗝"},
        {"op": "PUT", "key": "line\nkey", "value": "zero\0value"},
        {"op": "GET", "key": "line\nkey"},
        {"op": "GET", "key": ""},
        {"op": "GET", "key": 1},
        {"op": "PUT", "key": "alpha"},
        {"op": "GET", "key": "alpha", "value": "extra"},
        {"op": "PUT", "key": "🗝", "value": None},
        {"op": "CAS", "key": "🗝", "expected": False, "value": "unused"},
        {"op": "get", "key": "alpha"},
        {"op": [], "key": "alpha"},
        [], None, True, 42,
        {"op": "GET", "key": "🗝"},
    ]
    return [encode(request) for request in requests] + [
        "", "{", '{"op":"GET","key":"🗝","key":"alpha"}',
        '{"op":"PUT","key":"🗝","value":"\\ud800"}',
        '{"op":"GET","key":"\\udfff"}',
        '{"op":"CAS","key":"🗝","expected":"\\ud800","value":"unused"}',
        '{"op":"PUT","key":"🗝","value":NaN}',
        '{"op":"GET","key":"🗝"}',
    ]


def generate(seed, count):
    # The specified arithmetic and upper 32 bits make sequences independent of
    # Python's random implementation and the host platform.
    state = seed

    def choose(options):
        nonlocal state
        state = (6364136223846793005 * state + 1442695040888963407) & MASK
        return options[(state >> 32) % len(options)]

    prefix = edge_cases()
    for index in range(count):
        if index < len(prefix):
            yield prefix[index]
            continue
        op = choose(("GET", "PUT", "DELETE", "CAS", "INVALID"))
        key = choose(KEYS)
        if op == "INVALID":
            yield choose(prefix[14:26] + prefix[27:34])
            continue
        request = {"op": op, "key": key}
        if op == "CAS":
            request["expected"] = choose((None, *VALUES))
        if op in ("PUT", "CAS"):
            request["value"] = choose(VALUES)
        yield encode(request)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=21)
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 0 <= args.seed <= MASK or args.count < 0:
        parser.error("seed must be a 64-bit unsigned integer and count must be nonnegative")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for request in generate(args.seed, args.count):
            output.write(request + "\n")
    print(json.dumps({"seed": args.seed, "operations": args.count,
                      "generator": "lcg64-v1", "output": str(args.output)}))


if __name__ == "__main__":
    main()
