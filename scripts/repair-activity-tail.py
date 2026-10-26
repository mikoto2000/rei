"""Explicit offline repair of uncommitted Activity tails; dry-run by default.

Never adopts extra records or changes SQLite metadata. Requires a stopped backend.
"""

import argparse
from contextlib import closing, contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import uuid


def checked_path(root, relative):
    path = root / relative
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError("Unsafe Activity path")
    current = path
    while current != root:
        if current.is_symlink() or current.is_junction():
            raise ValueError("Linked Activity path")
        if current.parent == current:
            raise ValueError("Activity path escaped data directory")
        current = current.parent
    return path


def validate(data, size, expected):
    if size < 0 or len(data) < size:
        raise ValueError("Activity file is shorter than its committed size")
    prefix = data[:size]
    if prefix and not prefix.endswith(b"\n"):
        raise ValueError("Committed prefix has an incomplete record")
    digest = hashlib.sha256(b"").digest()
    for line in prefix.split(b"\n")[:-1]:
        if len(line) > 1024 * 1024:
            raise ValueError("Activity line exceeds the reader limit")
        record = json.loads(line.decode("utf-8"))
        if not isinstance(record, dict):
            raise ValueError("Activity record must be an object")
        digest = hashlib.sha256(digest + line + b"\n").digest()
    if digest.hex() != expected:
        raise ValueError("Committed Activity prefix does not match its hash chain")
    return prefix


@contextmanager
def offline_lock(root):
    # The same byte range is covered by StorageMigrationCoordinator's Java lock.
    with (root / ".storage/instance.lock").open("r+b") as lock:
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.lockf(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.lockf(lock.fileno(), fcntl.LOCK_UN)


def durable_write(path, data):
    with path.open("xb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def repair(root, apply=False):
    root = Path(root).resolve(strict=True)
    with offline_lock(root):
        # immutable reads must never ignore a live WAL or a pending rollback journal.
        if any((root / name).exists() for name in ("storage.db-wal", "storage.db-journal")):
            raise ValueError("SQLite journal exists; stop the backend cleanly before repair")
        uri = (root / "storage.db").as_uri() + "?mode=ro&immutable=1"
        with closing(sqlite3.connect(uri, uri=True)) as db:
            rows = db.execute(
                "SELECT id,scope,path,bytes,sha256 FROM activity_segments WHERE state='OPEN'"
            ).fetchall()
        pending = []
        for ident, scope, relative, size, chain in rows:
            expected = Path("projects") / scope if scope else Path()
            expected = expected / "logs/activity-archives" / (str(uuid.UUID(ident)) + ".jsonl")
            if scope:
                uuid.UUID(scope)
            if Path(relative) != expected:
                raise ValueError("Unexpected Activity segment identity")
            path = checked_path(root, relative)
            data = path.read_bytes()
            validate(data, size, chain)
            if len(data) > size:
                pending.append((path, data, {
                    "id": ident, "scope": scope, "path": relative,
                    "committed_bytes": size, "original_bytes": len(data),
                    "committed_chain": chain,
                    "original_sha256": hashlib.sha256(data).hexdigest(),
                }))
        report = {"apply": apply, "segments": [item[2] for item in pending]}
        if not apply or not pending:
            return report
        backup = root / "recovery" / (
            "activity-tail-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            + "-" + str(uuid.uuid4())
        )
        backup.mkdir(parents=True, exist_ok=False)
        # Preserve every original and the DB-derived repair plan before modifying anything.
        for _, data, item in pending:
            target = backup / (item["id"] + ".jsonl")
            durable_write(target, data)
            if hashlib.sha256(target.read_bytes()).hexdigest() != item["original_sha256"]:
                raise ValueError("Backup verification failed")
        durable_write(backup / "manifest.json", json.dumps(report, indent=2).encode("utf-8"))
        for path, data, item in pending:
            if path.read_bytes() != data:
                raise ValueError("Activity changed after inspection; original retained in backup")
            with path.open("r+b") as output:
                output.truncate(item["committed_bytes"])
                output.flush()
                os.fsync(output.fileno())
            restored = path.read_bytes()
            if restored != data[:item["committed_bytes"]]:
                raise ValueError("Restored Activity differs from the verified prefix")
            validate(restored, item["committed_bytes"], item["committed_chain"])
        report["backup"] = str(backup)
        return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data_directory", type=Path)
    parser.add_argument("--apply", action="store_true", help="Back up originals and restore committed prefixes")
    args = parser.parse_args()
    print(json.dumps(repair(args.data_directory, args.apply), indent=2))
