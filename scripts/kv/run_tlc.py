#!/usr/bin/env python3
"""Check the finite sequential KV model and retain reproducible TLC evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "docs/design/specifications/kv"
# Official release asset digest, verified against GitHub's release asset metadata:
# https://github.com/tlaplus/tlaplus/releases/tag/v1.8.0 (a prerelease).
RELEASE = "1.8.0"
JAR_URL = f"https://github.com/tlaplus/tlaplus/releases/download/v{RELEASE}/tla2tools.jar"
JAR_SHA256 = "9732eea90bdc7432e618184e4bee78700460e83e988238a80151dfd6507cfa0c"
SUCCESS = "Model checking completed. No error has been found."
BAD_GET = 'response\' = [status |-> "ok", value |-> store[request.key]]'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pinned_jar(path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
            downloaded = Path(temporary.name)
            try:
                with urlopen(JAR_URL, timeout=60) as remote:
                    shutil.copyfileobj(remote, temporary)
                temporary.flush()
                if digest(downloaded) != JAR_SHA256:
                    raise ValueError("Downloaded TLC jar SHA-256 does not match the pin")
                downloaded.replace(path)
            finally:
                downloaded.unlink(missing_ok=True)
    if digest(path) != JAR_SHA256:
        raise ValueError(f"TLC jar SHA-256 does not match the pin: {path}")
    return path.resolve()


def check(args, jar, name, config, negative=False):
    directory = args.output / name
    directory.mkdir(parents=True, exist_ok=True)
    source = (args.model_dir / "SequentialKV.tla").read_text(encoding="utf-8")
    if negative:
        # Corrupt a copy of the canonical transition, never a second spec lineage.
        # GET of a present key must then violate ResponseCorrect.
        if source.count(BAD_GET) != 1:
            raise ValueError("Negative control must locate exactly one GET response transition")
        source = source.replace(BAD_GET, 'response\' = [status |-> "ok", value |-> Absent]')
    (directory / "SequentialKV.tla").write_text(source, encoding="utf-8")
    shutil.copyfile(args.model_dir / config, directory / config)
    command = [
        args.java, "-Xmx512m", "-XX:+UseParallelGC", "-cp", str(jar), "tlc2.TLC",
        "-workers", "1", "-fp", "0", "-seed", "21", "-noGenerateSpecTE",
        "-metadir", str(directory / "states"), "-config", config, "SequentialKV.tla",
    ]
    log = args.output / f"{name}.log"
    timed_out = False
    with log.open("w", encoding="utf-8") as stream:
        try:
            result = subprocess.run(command, cwd=directory, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=args.timeout, check=False)
            return_code = result.returncode
        except subprocess.TimeoutExpired:
            return_code = None
            timed_out = True
    output = log.read_text(encoding="utf-8")
    if negative:
        accepted = (return_code is not None and return_code != 0
                    and "Invariant ResponseCorrect is violated." in output)
    else:
        accepted = return_code == 0 and SUCCESS in output
    counts = re.search(r"([\d,]+) states generated, ([\d,]+) distinct states found", output)
    depth = re.search(r"The depth of the complete state graph search is (\d+)\.", output)
    version = re.search(r"^TLC2 Version .+$", output, re.MULTILINE)
    return {
        "name": name, "config": config, "cwd": str(directory), "command": command,
        "expected": "ResponseCorrect violation" if negative else "all checks pass",
        "accepted": accepted, "return_code": return_code, "timed_out": timed_out,
        "log": str(log), "version": version.group(0) if version else None,
        "generated_states": int(counts[1].replace(",", "")) if counts else None,
        "distinct_states": int(counts[2].replace(",", "")) if counts else None,
        "depth": int(depth[1]) if depth else None,
        "model_sha256": digest(directory / "SequentialKV.tla"),
        "config_sha256": digest(directory / config),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "target/kv/tlc")
    parser.add_argument("--jar", type=Path, default=ROOT / f"target/kv/tools/tla2tools-{RELEASE}.jar",
                        help="Existing or downloadable jar; SHA-256 is always verified")
    parser.add_argument("--model-dir", type=Path, default=MODEL,
                        help="Directory containing SequentialKV.tla, safety.cfg, and progress.cfg")
    parser.add_argument("--java", default="java")
    parser.add_argument("--timeout", type=int, default=180, help="Seconds per TLC check")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    args.output = args.output.resolve()
    args.model_dir = args.model_dir.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "release": RELEASE, "jar_url": JAR_URL, "jar_sha256": JAR_SHA256,
        "runner_sha256": digest(Path(__file__)),
        "bounds": {"keys": 2, "values": 2, "absent_sentinels": 1,
                   "outstanding_requests": 1, "request_classes": 21},
        "assumptions": "Finite string abstraction; progress requires weak fairness of Complete",
        "checks": [], "passed": False,
    }
    try:
        evidence["base_commit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        evidence["java_version"] = subprocess.check_output(
            [args.java, "-version"], stderr=subprocess.STDOUT, text=True).strip()
        jar = pinned_jar(args.jar)
        for name, config, negative in (("safety", "safety.cfg", False),
                                       ("progress", "progress.cfg", False),
                                       ("negative-control", "safety.cfg", True)):
            result = check(args, jar, name, config, negative)
            evidence["checks"].append(result)
            print(f"{name}: {'PASS' if result['accepted'] else 'FAIL'}; "
                  f"{result['distinct_states']} distinct states; log: {result['log']}")
            if not result["accepted"]:
                raise ValueError(f"{name} did not produce its required outcome")
        evidence["passed"] = True
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        evidence["error"] = str(error)
        print(f"TLC verification failed: {error}", file=sys.stderr)
    finally:
        (args.output / "summary.json").write_text(
            json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
