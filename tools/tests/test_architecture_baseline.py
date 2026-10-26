import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ratchet", Path(__file__).parents[1] / "check-architecture-baseline.py")
ratchet = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ratchet)


class BaselineRatchetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "sample/src/main/Old.kt"
        self.source.parent.mkdir(parents=True)
        self.policy = {"global": {"maxFileLines": 4, "baselineFile": ".architecture-baseline.json"},
                       "modules": [{"path": "sample"}]}
        self.baseline = {"files": {"sample/src/main/Old.kt": 8}}
        self.write_json()
        self.source.write_text("line\n" * 6, encoding="utf-8")
        self.git("init", "-q")
        self.git("add", "architecture-policy.json", ".architecture-baseline.json", "sample/src/main/Old.kt")
        self.git("-c", "user.name=Ratchet Test", "-c", "user.email=ratchet@example.test", "commit", "-qm", "fixture")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def write_json(self):
        (self.root / "architecture-policy.json").write_text(json.dumps(self.policy), encoding="utf-8")
        (self.root / ".architecture-baseline.json").write_text(json.dumps(self.baseline), encoding="utf-8")

    def test_existing_stale_ceiling_reports_hint_without_writing(self):
        before = (self.root / ".architecture-baseline.json").read_bytes()
        errors, hints = ratchet.check(self.root, "HEAD")
        self.assertEqual([], errors)
        self.assertEqual(1, len(hints))
        self.assertEqual(before, (self.root / ".architecture-baseline.json").read_bytes())

    def test_removed_debt_cannot_return_even_under_stale_baseline(self):
        self.source.write_text("line\n" * 7, encoding="utf-8")
        self.assertTrue(any("ceiling 6" in error for error in ratchet.check(self.root, "HEAD")[0]))

    def test_baseline_or_global_ceiling_cannot_be_increased(self):
        self.baseline["files"]["sample/src/main/Old.kt"] = 9
        self.policy["global"]["maxFileLines"] = 5
        self.write_json()
        errors, _ = ratchet.check(self.root, "HEAD")
        self.assertTrue(any("baseline increased" in error for error in errors))
        self.assertTrue(any("maxFileLines increased" in error for error in errors))

    def test_new_oversized_file_cannot_be_registered_as_legacy(self):
        path = "sample/src/main/New.kt"
        (self.root / path).write_text("line\n" * 8, encoding="utf-8")
        self.baseline["files"][path] = 8
        self.write_json()
        errors, _ = ratchet.check(self.root, "HEAD")
        self.assertTrue(any("new baseline exemption" in error for error in errors))
        self.assertTrue(any("ceiling 4" in error for error in errors))

    def test_lowering_and_removing_exemptions_succeed(self):
        self.source.write_text("line\n" * 5, encoding="utf-8")
        self.baseline["files"]["sample/src/main/Old.kt"] = 5
        self.write_json()
        self.assertEqual(([], []), ratchet.check(self.root, "HEAD"))
        self.source.write_text("line\n" * 4, encoding="utf-8")
        self.baseline["files"] = {}
        self.write_json()
        self.assertEqual(([], []), ratchet.check(self.root, "HEAD"))

    def test_missing_base_fails_closed(self):
        with self.assertRaises(ValueError):
            ratchet.check(self.root, "not-a-real-ref")

    def test_previously_compliant_source_cannot_reuse_a_stale_exemption(self):
        self.source.write_text("line\n" * 4, encoding="utf-8")
        self.git("add", "sample/src/main/Old.kt")
        self.git("-c", "user.name=Ratchet Test", "-c", "user.email=ratchet@example.test", "commit", "-qm", "shrink")
        self.source.write_text("line\n" * 5, encoding="utf-8")
        self.assertTrue(any("ceiling 4" in error for error in ratchet.check(self.root, "HEAD")[0]))

    def test_escape_path_is_rejected_before_reading_any_source(self):
        self.baseline["files"]["../Outside.kt"] = 99
        self.write_json()
        with self.assertRaises(ValueError):
            ratchet.check(self.root, "HEAD")

    def test_line_count_matches_jvm_line_reader(self):
        for value, expected in [("", 0), ("a", 1), ("a\n", 1), ("a\r\nb\r", 2), ("\n\n", 2)]:
            self.assertEqual(expected, ratchet.line_count(value))


if __name__ == "__main__":
    unittest.main()
