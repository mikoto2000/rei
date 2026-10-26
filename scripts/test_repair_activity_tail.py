import hashlib
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location("repair", Path(__file__).with_name("repair-activity-tail.py"))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class ActivityTailRepairTest(unittest.TestCase):
    def fixture(self, root, tail=b'{"id":"pending"}\n'):
        (root / ".storage").mkdir()
        (root / ".storage/instance.lock").touch()
        ident = str(uuid.uuid4())
        relative = f"logs/activity-archives/{ident}.jsonl"
        path = root / relative
        path.parent.mkdir(parents=True)
        prefix = '{"id":"確定済み"}\n'.encode()
        chain = hashlib.sha256(hashlib.sha256(b"").digest() + prefix).hexdigest()
        path.write_bytes(prefix + tail)
        with closing(sqlite3.connect(root / "storage.db")) as db:
            with db:
                db.execute("CREATE TABLE activity_segments(id,scope,path,bytes,sha256,state)")
                db.execute("INSERT INTO activity_segments VALUES(?,?,?,?,?,'OPEN')", (ident, "", relative, len(prefix), chain))
        return path, prefix

    def test_dry_run_and_explicit_repair_preserve_original_and_database(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path, prefix = self.fixture(root, b'{"partial":')
            original = path.read_bytes()
            database = (root / "storage.db").read_bytes()
            self.assertEqual(len(repair.repair(root)["segments"]), 1)
            self.assertEqual(path.read_bytes(), original)
            result = repair.repair(root, True)
            self.assertEqual(path.read_bytes(), prefix)
            self.assertEqual((Path(result["backup"]) / path.name).read_bytes(), original)
            self.assertEqual((root / "storage.db").read_bytes(), database)
            self.assertEqual(repair.repair(root, True)["segments"], [])

    def test_changed_committed_prefix_is_never_repaired(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path, _ = self.fixture(root)
            changed = path.read_bytes().replace(b'"id"', b'"ID"', 1)
            path.write_bytes(changed)
            with self.assertRaisesRegex(ValueError, "hash chain"):
                repair.repair(root, True)
            self.assertEqual(path.read_bytes(), changed)
            self.assertFalse((root / "recovery").exists())

    def test_journal_blocks_repair(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path, _ = self.fixture(root)
            original = path.read_bytes()
            (root / "storage.db-wal").touch()
            with self.assertRaisesRegex(ValueError, "journal"):
                repair.repair(root, True)
            self.assertEqual(path.read_bytes(), original)

    def test_shortened_or_partial_committed_data_is_rejected(self):
        with self.assertRaises(ValueError):
            repair.validate(b"", 1, "ignored")
        with self.assertRaises(ValueError):
            repair.validate(b"{", 1, "ignored")

    def test_backend_lock_blocks_repair(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path, _ = self.fixture(root)
            original = path.read_bytes()
            with repair.offline_lock(root):
                process = subprocess.run(
                    [sys.executable, "-B", str(Path(repair.__file__)), str(root), "--apply"],
                    capture_output=True, timeout=10,
                )
            self.assertNotEqual(process.returncode, 0)
            self.assertEqual(path.read_bytes(), original)
            self.assertFalse((root / "recovery").exists())

    def test_path_escape_blocks_repair(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path, _ = self.fixture(root)
            original = path.read_bytes()
            with closing(sqlite3.connect(root / "storage.db")) as db:
                with db:
                    db.execute("UPDATE activity_segments SET path='../outside.jsonl'")
            with self.assertRaisesRegex(ValueError, "identity"):
                repair.repair(root, True)
            self.assertEqual(path.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
