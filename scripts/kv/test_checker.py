#!/usr/bin/env python3
"""Exercise the checker as a separate process using hand-written oracle fixtures."""

from collections import Counter
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

import check
import generate


HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "fixtures"


class CheckerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.requests = check.lines(FIXTURES / "positive.input.jsonl")
        self.observed = check.lines(FIXTURES / "positive.output.jsonl")

    def run_checker(self, requests=None, observed=None, coverage=False):
        requests = self.requests if requests is None else requests
        observed = self.observed if observed is None else observed
        for name, records in (("input", requests), ("output", observed)):
            (self.directory / (name + ".jsonl")).write_text(
                "".join(record + "\n" for record in records), encoding="utf-8")
        command = [sys.executable, str(HERE / "check.py"),
                   "--input", str(self.directory / "input.jsonl"),
                   "--output", str(self.directory / "output.jsonl"),
                   "--report", str(self.directory / "report.json"),
                   "--failure-dir", str(self.directory / "failure")]
        if coverage:
            command.append("--require-coverage")
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(result.stderr, "", result.stderr)
        report = json.loads((self.directory / "report.json").read_text())
        return result, report

    def test_hand_written_positive(self):
        result, report = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(report["status"], "pass")
        self.assertEqual(report["operations"], 18)

    def test_corrupted_responses_and_states_fail_cli(self):
        cases = json.loads((FIXTURES / "negative-cases.json").read_text())
        for case in cases:
            with self.subTest(case=case["name"]):
                observed = self.observed.copy()
                observed[case["line"] - 1] = json.dumps(case["output"])
                result, report = self.run_checker(observed=observed)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(report["failure"]["line"], case["line"])
                self.assertEqual(report["failure"]["actual"], case["output"])
                reproduction = shlex.split((self.directory / "failure/reproduce.txt").read_text())
                replay = subprocess.run(reproduction, capture_output=True, text=True, check=False)
                self.assertEqual(replay.returncode, 1, replay.stdout + replay.stderr)
                failure = json.loads((self.directory / "failure/recheck.json").read_text())
                self.assertEqual(failure["failure"], report["failure"])

    def test_output_shape_and_count_fail_cli(self):
        for wrong in ("{", "[]", "null", '{"status":"ok"}',
                      '{"status":"ok","value":null,"extra":false}',
                      '{"status":"ok","value":null,"value":null}',
                      '{"status":"ok","value":NaN}'):
            with self.subTest(output=wrong):
                result, report = self.run_checker(observed=[wrong, *self.observed[1:]])
                self.assertEqual(result.returncode, 1)
                self.assertEqual(report["failure"]["line"], 1)
        for observed in ([], self.observed[:-1], self.observed[1:],
                         [*self.observed, "{}"], [*self.observed, ""]):
            with self.subTest(records=observed):
                result, _ = self.run_checker(observed=observed)
                self.assertEqual(result.returncode, 1)

    def test_invalid_requests_leave_state_unchanged(self):
        invalid = ["", "{", "null", "[]", "true", "1", '{}',
                   '{"op":[],"key":"a"}', '{"op":"GET","key":""}',
                   '{"op":"GET","key":true}', '{"op":"PUT","key":"a"}',
                   '{"op":"PUT","key":"a","value":null}',
                   '{"op":"DELETE","key":"a","extra":true}',
                   '{"op":"CAS","key":"a","expected":0,"value":"bad"}',
                   '{"op":"PUT","key":"a","value":"\\ud800"}',
                   '{"op":"GET","key":"\\udfff"}',
                   '{"op":"GET","key":"a","key":"a"}',
                   '{"op":"PUT","key":"a","value":Infinity}']
        requests = ['{"op":"PUT","key":"a","value":"original"}', *invalid,
                    '{"op":"GET","key":"a"}']
        output = ['{"status":"ok","previous":null}',
                  *['{"status":"invalid"}'] * len(invalid),
                  '{"status":"ok","value":"original"}', '{"state":{"a":"original"}}']
        result, report = self.run_checker(requests=requests, observed=output)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(report["operations"], len(requests))

    def test_empty_history_and_unicode_line_separators(self):
        result, _ = self.run_checker(requests=[], observed=['{"state":{}}'])
        self.assertEqual(result.returncode, 0)
        requests = ['{"op":"PUT","key":"line\u0085key","value":"line\u2028value"}']
        output = ['{"status":"ok","previous":null}',
                  '{"state":{"line\u0085key":"line\u2028value"}}']
        result, _ = self.run_checker(requests=requests, observed=output)
        self.assertEqual(result.returncode, 0)

    def test_malformed_utf8_output_is_retained(self):
        self.run_checker()
        (self.directory / "output.jsonl").write_bytes(b"\xff\n")
        command = [sys.executable, str(HERE / "check.py"),
                   "--input", str(self.directory / "input.jsonl"),
                   "--output", str(self.directory / "output.jsonl"),
                   "--report", str(self.directory / "report.json")]
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual((self.directory / "counterexample/observed.jsonl").read_bytes(), b"\xff\n")
        reproduction = shlex.split((self.directory / "counterexample/reproduce.txt").read_text())
        replay = subprocess.run(reproduction, capture_output=True, text=True, check=False)
        self.assertEqual(replay.returncode, 1, replay.stdout + replay.stderr)

    def test_small_or_unrepresentative_corpus_cannot_claim_coverage(self):
        result, report = self.run_checker(coverage=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("coverage", report["failure"]["reason"])
        result, report = self.run_checker(requests=['{"op":"GET","key":"a"}'] * 10000,
                                         observed=['{"status":"ok","value":null}'] * 10000
                                         + ['{"state":{}}'], coverage=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("put_absent", report["failure"]["actual"]["missing_coverage"])


class GeneratorTest(unittest.TestCase):
    def test_seed_is_stable_and_changes_tail(self):
        first = "\n".join(generate.generate(21, 10000)) + "\n"
        second = "\n".join(generate.generate(21, 10000)) + "\n"
        self.assertEqual(first, second)
        self.assertNotEqual(first, "\n".join(generate.generate(22, 10000)) + "\n")
        # Detect accidental changes to the retained seed's exact request sequence.
        self.assertEqual(hashlib.sha256(first.encode()).hexdigest(),
                         "66dfd84dafa244199299f5ffa88ffbb765386be6371b72b572c767d055fc40b2")

    def test_fixed_prefix_covers_required_edges(self):
        state, coverage = {}, Counter()
        for request in generate.edge_cases():
            check.transition(request, state, coverage)
        self.assertEqual([name for name in check.REQUIRED_COVERAGE if not coverage[name]], [])


if __name__ == "__main__":
    unittest.main()
