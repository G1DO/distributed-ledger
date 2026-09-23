#!/usr/bin/env python3
"""Run the bounded KV conformance gate and retain reproducible evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile


ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_jsonl(path):
    # File iteration splits physical lines, never Unicode separators in strings.
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=21)
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 0 <= args.seed < 1 << 64:
        parser.error("seed must be a 64-bit unsigned integer")
    if args.count < 10000:
        parser.error("the conformance gate requires at least 10000 operations")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = (args.output or ROOT / "target" / "kv" / stamp).resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", CARGO_TARGET_DIR=str(ROOT / "reference/kv/target"))
    summary = {"seed": args.seed, "operations": args.count, "status": "failed", "checks": []}

    def run(name, command, *, cwd=ROOT, stdin=None, stdout=None, expected=0):
        print(f"{name}: {' '.join(map(str, command))}", flush=True)
        with (output / f"{name}.log").open("wb") as log:
            result = subprocess.run(list(map(str, command)), cwd=cwd, env=env,
                                    stdin=stdin, stdout=stdout or log, stderr=log, timeout=1200)
        summary["checks"].append({"name": name, "command": list(map(str, command)),
                                  "exit_code": result.returncode, "expected_exit_code": expected})
        if result.returncode != expected:
            raise RuntimeError(f"{name}: exit {result.returncode}, expected {expected}; see {output / (name + '.log')}")

    try:
        summary["commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        (output / "worktree.txt").write_bytes(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
        (output / "worktree.patch").write_bytes(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT))
        names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
        sources = sorted({name for name in names if name and (ROOT / name).is_file()})
        manifest = {name: digest(ROOT / name) for name in sources}
        (output / "source-sha256.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        with tarfile.open(output / "source.tar.gz", "w:gz") as archive:
            for name in sources:
                archive.add(ROOT / name, arcname=name, recursive=False)
        (output / "reproduce.txt").write_text(
            "# Extract source.tar.gz into an empty directory; use the documented toolchain.\n"
            "# Reconstruct a local checkout; original provenance is in summary.json/source-sha256.json.\n"
            "git init -q\n"
            "git add .\n"
            "git -c user.name='KV reproduction' -c user.email='kv@example.invalid' commit -qm 'Recorded source snapshot'\n"
            f"python3 scripts/verify-kv.py --seed {args.seed} --count {args.count}\n"
            "# For a checker failure, see failure-1/, failure-2/, or golden-failure/ for the saved prefix and reproduce.txt.\n"
        )
        crate = ROOT / "reference/kv"
        for name, command, cwd in [
            ("java-version", ["java", "-version"], ROOT),
            ("python-version", [sys.executable, "--version"], ROOT),
            ("rust-version", ["rustc", "--version"], crate),
            ("cargo-version", ["cargo", "--version"], crate),
            ("rust-format", ["cargo", "fmt", "--all", "--", "--check"], crate),
            ("rust-tests", ["cargo", "test", "--locked"], crate),
            ("rust-lint", ["cargo", "clippy", "--locked", "--all-targets", "--", "-D", "warnings"], crate),
            ("rust-build", ["cargo", "build", "--locked"], crate),
            ("checker-tests", [sys.executable, "-m", "unittest", "discover", "-s", "scripts/kv", "-p", "test_*.py", "-v"], ROOT),
            ("spec-sync-tests", [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_spec_sync.py", "-v"], ROOT),
        ]:
            run(name, command, cwd=cwd)
        run("tlc", [sys.executable, "scripts/kv/run_tlc.py", "--output", output / "tlc"])
        for replay in (1, 2):
            run(f"generate-{replay}", [sys.executable, "scripts/kv/generate.py", "--seed", args.seed,
                                      "--count", args.count, "--output", output / f"input-{replay}.jsonl"])
        if (output / "input-1.jsonl").read_bytes() != (output / "input-2.jsonl").read_bytes():
            raise RuntimeError("same seed produced different inputs")
        binary = crate / "target/debug/kv-reference"
        for replay in (1, 2):
            with (output / "input-1.jsonl").open("rb") as source, (output / f"output-{replay}.jsonl").open("wb") as observed:
                run(f"reference-{replay}", [binary], stdin=source, stdout=observed)
            run(f"check-{replay}", [sys.executable, "scripts/kv/check.py", "--input", output / "input-1.jsonl",
                                   "--output", output / f"output-{replay}.jsonl", "--report", output / f"checker-{replay}.json",
                                   "--failure-dir", output / f"failure-{replay}", "--require-coverage"])
        if (output / "output-1.jsonl").read_bytes() != (output / "output-2.jsonl").read_bytes():
            raise RuntimeError("replaying the same input produced different observable bytes")
        fixture = ROOT / "scripts/kv/fixtures"
        with (fixture / "positive.input.jsonl").open("rb") as source, (output / "positive.output.jsonl").open("wb") as observed:
            run("golden-reference", [binary], stdin=source, stdout=observed)
        run("golden-check", [sys.executable, "scripts/kv/check.py", "--input", fixture / "positive.input.jsonl",
                             "--output", output / "positive.output.jsonl", "--report", output / "golden-checker.json",
                             "--failure-dir", output / "golden-failure"])
        expected = read_jsonl(fixture / "positive.output.jsonl")
        actual = read_jsonl(output / "positive.output.jsonl")
        if json.dumps(actual, sort_keys=True) != json.dumps(expected, sort_keys=True):
            raise RuntimeError("Rust output disagrees with the hand-written golden fixture")
        summary.update(status="passed", input_sha256=digest(output / "input-1.jsonl"),
                       output_sha256=digest(output / "output-1.jsonl"), replay_identical=True)
    except (OSError, subprocess.SubprocessError, RuntimeError) as error:
        summary["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
        print(f"KV conformance {summary['status']}; evidence: {output}", flush=True)
    return 0 if summary["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
