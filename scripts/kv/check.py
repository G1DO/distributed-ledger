#!/usr/bin/env python3
"""Independently check every sequential KV result and the final state."""

import argparse
from collections import Counter
import json
from pathlib import Path
import shlex
import sys


REQUIRED_COVERAGE = (
    "get_absent", "get_present", "put_absent", "put_overwrite",
    "delete_absent", "delete_present", "cas_absent_success", "cas_absent_mismatch",
    "cas_present_success", "cas_present_mismatch", "invalid_json", "invalid_shape",
    "invalid_op", "invalid_fields", "invalid_key", "invalid_value", "invalid_expected",
    "empty_value", "unicode_key", "unicode_value",
)
FIELDS = {"GET": {"op", "key"}, "PUT": {"op", "key", "value"},
          "DELETE": {"op", "key"}, "CAS": {"op", "key", "expected", "value"}}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON object key")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("non-JSON numeric constant: " + value)


def parse(line):
    return json.loads(line, object_pairs_hook=unique_object, parse_constant=reject_constant)


def scalar_string(value):
    return isinstance(value, str) and not any(0xD800 <= ord(char) <= 0xDFFF for char in value)


def request_error(request):
    if not isinstance(request, dict):
        return "invalid_shape"
    op = request.get("op")
    if not isinstance(op, str) or op not in FIELDS:
        return "invalid_op"
    if set(request) != FIELDS[op]:
        return "invalid_fields"
    if not scalar_string(request["key"]) or not request["key"]:
        return "invalid_key"
    if op in ("PUT", "CAS") and not scalar_string(request["value"]):
        return "invalid_value"
    if op == "CAS" and request["expected"] is not None and not scalar_string(request["expected"]):
        return "invalid_expected"
    return None


def transition(line, state, coverage):
    try:
        request = parse(line)
    except (ValueError, RecursionError):
        coverage["invalid_json"] += 1
        return {"status": "invalid"}
    error = request_error(request)
    if error:
        coverage[error] += 1
        return {"status": "invalid"}
    op, key = request["op"], request["key"]
    present, previous = key in state, state.get(key)
    coverage[op] += 1
    if any(ord(char) > 127 for char in key):
        coverage["unicode_key"] += 1
    if "value" in request:
        if request["value"] == "":
            coverage["empty_value"] += 1
        if any(ord(char) > 127 for char in request["value"]):
            coverage["unicode_value"] += 1
    if op == "GET":
        coverage["get_present" if present else "get_absent"] += 1
        return {"status": "ok", "value": previous}
    if op == "PUT":
        coverage["put_overwrite" if present else "put_absent"] += 1
        state[key] = request["value"]
    elif op == "DELETE":
        coverage["delete_present" if present else "delete_absent"] += 1
        state.pop(key, None)
    else:
        swapped = previous == request["expected"]
        coverage["cas_" + ("present" if present else "absent")
                 + ("_success" if swapped else "_mismatch")] += 1
        if swapped:
            state[key] = request["value"]
        return {"status": "ok", "swapped": swapped, "previous": previous}
    return {"status": "ok", "previous": previous}


def exact_equal(expected, actual):
    # Python considers True == 1; the wire contract requires a JSON boolean.
    if type(expected) is not type(actual):
        return False
    if isinstance(expected, dict):
        return expected.keys() == actual.keys() and all(
            exact_equal(value, actual[key]) for key, value in expected.items())
    return expected == actual


def lines(path):
    # Only LF separates records. Unicode line separators inside JSON strings
    # are ordinary key/value characters; a single terminal LF adds no request.
    data = path.read_bytes().decode("utf-8")
    if not data:
        return []
    records = data.split("\n")
    return records[:-1] if records[-1] == "" else records


def check(requests, observed, require_coverage=False):
    state, coverage = {}, Counter()
    report = {"status": "pass", "operations": len(requests), "coverage": coverage}
    for index in range(len(requests) + 1):
        expected = (transition(requests[index], state, coverage) if index < len(requests)
                    else {"state": dict(sorted(state.items()))})
        failure = {"line": index + 1, "expected": expected,
                   "request": requests[index] if index < len(requests) else "<EOF>"}
        if index >= len(observed):
            failure.update(reason="missing output record", actual=None)
        else:
            try:
                actual = parse(observed[index])
            except (ValueError, RecursionError) as error:
                failure.update(reason="invalid output JSON: " + str(error), actual=observed[index])
            else:
                if exact_equal(expected, actual):
                    continue
                failure.update(reason="result or final state differs", actual=actual)
        report.update(status="fail", failure=failure)
        return report
    if len(observed) != len(requests) + 1:
        report.update(status="fail", failure={"line": len(requests) + 2,
                      "reason": "extra output record", "expected": "<EOF>",
                      "actual": observed[len(requests) + 1]})
    elif require_coverage:
        missing = [name for name in REQUIRED_COVERAGE if not coverage[name]]
        if len(requests) < 10000 or missing:
            report.update(status="fail", failure={"line": len(requests) + 1,
                          "reason": "required 10000-operation edge coverage not met",
                          "expected": {"minimum_operations": 10000,
                                       "coverage": list(REQUIRED_COVERAGE)},
                          "actual": {"operations": len(requests), "missing_coverage": missing}})
    return report


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def preserve_failure(directory, requests, observed, report, require_coverage,
                     input_path, observed_path):
    directory.mkdir(parents=True, exist_ok=True)
    index = report["failure"].get("line")
    request_path, output_path = directory / "input.jsonl", directory / "observed.jsonl"
    for path, records, original in ((request_path, requests, input_path),
                                    (output_path, observed, observed_path)):
        if records is not None:
            path.write_text("".join(record + "\n" for record in records[:index]), encoding="utf-8")
        elif original.is_file():
            # Preserve malformed UTF-8 verbatim too: decoding failure must not
            # erase the evidence needed to reproduce a broken reference output.
            path.write_bytes(original.read_bytes())
    write_json(directory / "failure.json", report)
    command = [sys.executable, str(Path(__file__).resolve()), "--input", str(request_path.resolve()),
               "--output", str(output_path.resolve()), "--report", str((directory / "recheck.json").resolve()),
               "--failure-dir", str((directory / "recheck-failure").resolve())]
    if require_coverage:
        command.append("--require-coverage")
    (directory / "reproduce.txt").write_text(shlex.join(command) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="observed reference output")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--failure-dir", type=Path)
    parser.add_argument("--require-coverage", action="store_true")
    args = parser.parse_args()
    requests = observed = None
    try:
        requests = lines(args.input)
        observed = lines(args.output)
        report = check(requests, observed, args.require_coverage)
    except (OSError, UnicodeError) as error:
        report = {"status": "fail", "failure": {"reason": str(error)}}
    if report["status"] == "fail":
        directory = args.failure_dir or args.report.parent / "counterexample"
        preserve_failure(directory, requests, observed, report, args.require_coverage,
                         args.input, args.output)
        report["counterexample"] = str(directory)
    write_json(args.report, report)
    print(json.dumps(report, sort_keys=True))
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
