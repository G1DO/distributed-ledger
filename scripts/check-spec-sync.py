#!/usr/bin/env python3
"""Require changes to the corresponding contract when production sources change."""

import argparse
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
KV = "docs/design/specifications/kv/"
GOLDEN = "scripts/kv/fixtures/positive."
VERIFICATION = "docs/development/kv-verification.md"


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def meaningful(path, data):
    text = data.decode("utf-8")
    if path.endswith(".tla"):
        # TLA+ block comments nest. Quotes inside comments have no lexical meaning.
        tokens, depth, index = [], 0, 0
        while index < len(text):
            if text.startswith("(*", index):
                depth += 1
                index += 2
            elif depth and text.startswith("*)", index):
                depth -= 1
                index += 2
            elif depth or text[index].isspace():
                index += 1
            elif text.startswith("\\*", index):
                end = text.find("\n", index)
                index = len(text) if end < 0 else end + 1
            elif text[index] == '"':
                string = re.match(r'"(?:\\.|[^"\\])*"', text[index:])
                if string is None:
                    raise ValueError(f"Unterminated TLA+ string in {path}")
                tokens.append(string[0])
                index += len(string[0])
            else:
                tokens.append(text[index])
                index += 1
        if depth:
            raise ValueError(f"Unterminated TLA+ comment in {path}")
        return tuple(tokens)
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    return " ".join(text.split())


def check(root, base, head="WORKTREE"):
    base = git(root, "rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}").decode().strip()
    if head != "WORKTREE":
        head = git(root, "rev-parse", "--verify", "--end-of-options", f"{head}^{{commit}}").decode().strip()
        base = git(root, "merge-base", base, head).decode().strip()
    args = ["diff", "--no-renames", "--name-only", "-z", base]
    if head != "WORKTREE":
        args.append(head)
    paths = set(git(root, *args, "--").decode().split("\0")) - {""}
    if head == "WORKTREE":
        paths.update(set(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")) - {""})

    def contents(revision, path):
        if revision == "WORKTREE":
            file = root / path
            return file.read_bytes() if file.is_file() else b""
        result = subprocess.run(["git", "-C", str(root), "show", f"{revision}:{path}"], capture_output=True)
        return result.stdout if result.returncode == 0 else b""

    def updated(path):
        if path not in paths:
            return False
        after = contents(head, path)
        return bool(after.strip()) and meaningful(path, contents(base, path)) != meaningful(path, after)

    errors = []
    for path in sorted(paths):
        file = Path(path)
        if (path.startswith(("service/src/test/", "reference/kv/tests/"))
                or (file.parent.as_posix() in {"scripts", "scripts/kv"}
                    and file.name.startswith("test_") and file.suffix == ".py")):
            continue
        if file.suffix not in {".java", ".rs", ".py", ".sql", ".sh"} and path not in {
            "reference/kv/Cargo.toml", "reference/kv/Cargo.lock"
        }:
            continue
        if path.startswith("reference/kv/"):
            if not updated(KV + "SequentialKV.tla"):
                errors.append(f"{path}: update executable {KV}SequentialKV.tla (comments/formatting do not count)")
            if not any(updated(GOLDEN + suffix) for suffix in ("input.jsonl", "output.jsonl")):
                errors.append(f"{path}: update the canonical positive KV fixture to witness the contract")
        elif path.startswith("service/") or path == "scripts/o2-compose-e2e.py":
            if not updated("docs/design/specifications/ledger-invariants.md"):
                errors.append(f"{path}: update docs/design/specifications/ledger-invariants.md")
        elif path in {"scripts/kv/check.py", "scripts/kv/generate.py"}:
            if not updated(KV + "README.md"):
                errors.append(f"{path}: update {KV}README.md")
        elif path.startswith("scripts/"):
            if not updated(VERIFICATION):
                errors.append(f"{path}: update {VERIFICATION}")
        else:
            errors.append(f"{path}: source has no contract mapping; add an explicit mapping and its tests")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", default="WORKTREE", help="commit or WORKTREE (default)")
    args = parser.parse_args()
    errors = check(ROOT, args.base, args.head)
    if errors:
        raise SystemExit("Specification synchronization failed:\n" + "\n".join(errors))
    print("Specification synchronization passed; executable checks and semantic review are also required.")


if __name__ == "__main__":
    main()
