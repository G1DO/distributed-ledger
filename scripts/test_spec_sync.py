"""Exercise the merge gate using real commits, including deletions and renames."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("spec_sync", Path(__file__).with_name("check-spec-sync.py"))
SYNC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC)


class SpecSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Gate test")
        self.git("config", "user.email", "gate@example.invalid")
        self.write("reference/kv/src/main.rs", "fn main() {}\n")
        self.write(SYNC.KV + "SequentialKV.tla", "---- MODULE SequentialKV ----\nNext == TRUE\n====\n")
        self.write(SYNC.GOLDEN + "input.jsonl", '{"op":"GET","key":"a"}\n')
        self.write(SYNC.GOLDEN + "output.jsonl", '{"status":"ok","value":null}\n{"state":{}}\n')
        self.commit()
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.DEVNULL).decode()

    def write(self, path, value):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(value)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def errors(self):
        return SYNC.check(self.root, self.base, "HEAD")

    def change_source(self):
        self.write("reference/kv/src/main.rs", 'fn main() { println!("changed"); }\n')

    def test_code_only_and_unrelated_documentation_fail(self):
        self.change_source()
        self.write("README.md", "Unrelated documentation\n")
        self.commit()
        self.assertEqual(len(self.errors()), 2)

    def test_comment_or_formatting_only_model_update_fails(self):
        self.change_source()
        self.write(SYNC.KV + "SequentialKV.tla", "---- MODULE SequentialKV ----\n(* update *)\nNext  ==  TRUE \\* comment\n====\n")
        self.commit()
        self.assertTrue(any("executable" in error for error in self.errors()))

    def test_valid_model_and_witness_update_passes(self):
        self.change_source()
        self.write(SYNC.KV + "SequentialKV.tla", "---- MODULE SequentialKV ----\nNext == FALSE\n====\n")
        self.write(SYNC.GOLDEN + "input.jsonl", '{"op":"DELETE","key":"a"}\n')
        self.commit()
        self.assertEqual(self.errors(), [])

    def test_nested_comment_update_fails(self):
        self.change_source()
        self.write(SYNC.KV + "SequentialKV.tla", '---- MODULE SequentialKV ----\n(* outer " (* inner *) still a comment *)\nNext == TRUE\n====\n')
        self.commit()
        self.assertTrue(any("executable" in error for error in self.errors()))

    def test_quoted_comment_markers_remain_executable(self):
        self.assertNotEqual(SYNC.meaningful("spec.tla", b'X == "(* one *)"'),
                            SYNC.meaningful("spec.tla", b'X == "(* two *)"'))

    def test_deleting_model_does_not_count_as_update(self):
        self.change_source()
        (self.root / (SYNC.KV + "SequentialKV.tla")).unlink()
        self.commit()
        self.assertTrue(any("executable" in error for error in self.errors()))

    def test_source_deletion_and_rename_require_spec(self):
        original = self.root / "reference/kv/src/main.rs"
        original.rename(original.with_name("lib.rs"))
        self.commit()
        self.assertTrue(self.errors())

    def test_test_only_change_passes(self):
        self.write("reference/kv/tests/protocol.rs", "// extra test\n")
        self.commit()
        self.assertEqual(self.errors(), [])

    def test_production_test_names_do_not_bypass_gate(self):
        self.write("reference/kv/src/test_support.rs", "fn behavior() {}\n")
        self.write("service/src/main/java/example/test/Production.java", "class Production {}\n")
        self.commit()
        errors = self.errors()
        self.assertTrue(any("test_support.rs" in error for error in errors))
        self.assertTrue(any("Production.java" in error for error in errors))

    def test_ledger_source_requires_ledger_contract(self):
        self.write("service/src/main/java/Ledger.java", "class Ledger {}\n")
        self.write(SYNC.KV + "README.md", "KV changes cannot cover ledger changes\n")
        self.commit()
        self.assertTrue(any("ledger-invariants.md" in error for error in self.errors()))
        self.write("docs/design/specifications/ledger-invariants.md", "Capacity is nonnegative.\n")
        self.commit()
        self.assertEqual(self.errors(), [])

    def test_verifier_source_requires_verification_contract(self):
        self.write("scripts/verify-kv.py", "print('verify')\n")
        self.commit()
        self.assertTrue(self.errors())
        self.write(SYNC.VERIFICATION, "Run TLC and independently check all responses.\n")
        self.commit()
        self.assertEqual(self.errors(), [])

    def test_worktree_includes_new_untracked_source(self):
        self.write("reference/kv/src/new.rs", "// new runtime source\n")
        self.assertTrue(SYNC.check(self.root, self.base))


if __name__ == "__main__":
    unittest.main()
