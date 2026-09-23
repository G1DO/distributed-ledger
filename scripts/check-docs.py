#!/usr/bin/env python3
"""Check the repository's documentation taxonomy and local Markdown links."""

from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
errors = []
categories = (
    "api", "architecture", "decisions", "design/rfcs", "design/specifications",
    "development", "operations/runbooks", "perf", "reference", "security",
)
for category in categories:
    if not any((DOCS / category).rglob("*.md")):
        errors.append(f"Empty documentation category: {category}")
for directory in DOCS.rglob("*"):
    if directory.is_dir() and not any(directory.rglob("*.md")):
        errors.append(f"Empty documentation category: {directory.relative_to(ROOT)}")

canonical = DOCS / "design/rfcs/o2-transfer-expiry.md"
copies = list(DOCS.rglob("o2-transfer-expiry.md"))
if copies != [canonical]:
    errors.append("O2 must have one canonical RFC: docs/design/rfcs/o2-transfer-expiry.md")
index = (DOCS / "README.md").read_text()
if index.count("(design/rfcs/o2-transfer-expiry.md)") != 1:
    errors.append("docs/README.md must link the canonical O2 RFC exactly once")

for document in [ROOT / "README.md", *sorted(DOCS.rglob("*.md"))]:
    contents = document.read_text()
    # Ignore fenced examples; this repository uses inline Markdown links outside code fences.
    contents = re.sub(r"```.*?```", "", contents, flags=re.S)
    for target in re.findall(r"\[[^\]]*\]\(([^\s)]+)(?:\s+\"[^\"]*\")?\)", contents):
        parsed = urlsplit(target.strip("<>"))
        if parsed.scheme or parsed.netloc or not parsed.path:
            continue
        resolved = (document.parent / unquote(parsed.path)).resolve()
        if not resolved.is_relative_to(ROOT) or not resolved.exists():
            errors.append(f"{document.relative_to(ROOT)}: broken local link {target}")

for legacy in (DOCS / "runbooks").glob("*.md"):
    destination = DOCS / "operations/runbooks" / legacy.name
    if not destination.is_file() or f"(../operations/runbooks/{legacy.name})" not in legacy.read_text():
        errors.append(f"{legacy.relative_to(ROOT)} must forward to its canonical operations runbook")

if errors:
    raise SystemExit("\n".join(errors))
print("Documentation taxonomy and local links passed; one canonical O2 RFC.")
