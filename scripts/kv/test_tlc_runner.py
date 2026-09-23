#!/usr/bin/env python3
"""Exercise real TLC failures through the same command used by CI."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import run_tlc


class TlcRunnerTest(unittest.TestCase):
    def test_cli_rejects_incorrect_model(self):
        # Reuse the same pinned cache as the normal gate; retain the corrupted
        # input and TLC counterexample so rejection is independently repeatable.
        evidence = run_tlc.ROOT / "target/kv/tlc-runner-tests"
        model = evidence / "incorrect-model"
        model.mkdir(parents=True, exist_ok=True)
        for filename in ("SequentialKV.tla", "safety.cfg", "progress.cfg"):
            shutil.copyfile(run_tlc.MODEL / filename, model / filename)
        source_path = model / "SequentialKV.tla"
        source = source_path.read_text(encoding="utf-8")
        expression = 'value |-> store[request.key]'
        self.assertEqual(source.count(expression), 1)
        source_path.write_text(source.replace(expression, "value |-> Absent"), encoding="utf-8")
        command = [sys.executable, str(Path(run_tlc.__file__).resolve()),
                   "--model-dir", str(model), "--output", str(evidence / "rejected-checks")]
        result = subprocess.run(command, cwd=run_tlc.ROOT, capture_output=True,
                                text=True, timeout=180, check=False)
        (evidence / "driver.log").write_text(
            result.stdout + result.stderr, encoding="utf-8")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        summary = json.loads((evidence / "rejected-checks/summary.json").read_text())
        self.assertFalse(summary["passed"])
        self.assertEqual(len(summary["checks"]), 1)
        self.assertEqual(summary["checks"][0]["name"], "safety")
        self.assertFalse(summary["checks"][0]["accepted"])
        self.assertNotEqual(summary["checks"][0]["return_code"], 0)
        output = (evidence / "rejected-checks/safety.log").read_text()
        self.assertIn("Invariant ResponseCorrect is violated.", output)

    def test_corrupt_cached_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "tla2tools.jar"
            jar.write_bytes(b"not the pinned executable")
            with self.assertRaisesRegex(ValueError, "SHA-256 does not match the pin"):
                run_tlc.pinned_jar(jar)


if __name__ == "__main__":
    unittest.main()
