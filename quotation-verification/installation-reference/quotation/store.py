import contextlib
import json
import sqlite3
import threading
import uuid
from datetime import datetime
from pathlib import Path

SCHEMA_VERSION = 1
APPLICATION_ID = 1364545364

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
 password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('leader','quoter')),
 active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
 token TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 csrf TEXT NOT NULL, expires REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS resources (
 id TEXT PRIMARY KEY, family TEXT NOT NULL, kind TEXT NOT NULL,
 version INTEGER NOT NULL, status TEXT NOT NULL, body TEXT NOT NULL,
 created_by TEXT NOT NULL, created_at TEXT NOT NULL, published_at TEXT,
 UNIQUE(family,version)
);
CREATE TABLE IF NOT EXISTS quotes (
 id TEXT PRIMARY KEY, family TEXT NOT NULL, version INTEGER NOT NULL,
 owner_id TEXT NOT NULL, body TEXT NOT NULL, result TEXT,
 state TEXT NOT NULL DEFAULT 'draft', revision INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 UNIQUE(family,version)
);
CREATE TABLE IF NOT EXISTS imports (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, filename TEXT NOT NULL,
 content BLOB NOT NULL, parsed TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS exports (
 id TEXT PRIMARY KEY, quote_id TEXT NOT NULL, kind TEXT NOT NULL,
 filename TEXT NOT NULL, content BLOB NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL,
 action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL, created_at TEXT NOT NULL
);
"""

DEFAULT_POLICY = {
    "version": 0,
    "unit_round": None, "line_round": None, "total_step": None,
    "fees": {k: {"mode": "pending", "expression": "", "parameters": {}}
             for k in ("installation", "freight", "tax")},
    "formal_template_confirmed": False,
}


def now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def uid():
    return uuid.uuid4().hex


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


class Store:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / "quotation.sqlite3"
        self.lock = threading.RLock()
        with self.db() as db:
            db.executescript(SCHEMA)
            db.execute(f"PRAGMA application_id={APPLICATION_ID}")
            db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            for key, value in {"policy": DEFAULT_POLICY, "export_directory": ""}.items():
                db.execute("INSERT OR IGNORE INTO settings VALUES (?,?)", (key, dumps(value)))

    @contextlib.contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=15)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys=ON")
            try:
                yield db
                db.commit()
            except Exception:
                db.rollback()
                raise
            finally:
                db.close()

    def audit(self, db, actor, action, target, detail=None):
        db.execute("INSERT INTO audit(actor,action,target,detail,created_at) VALUES (?,?,?,?,?)",
                   (actor, action, target, dumps(detail or {}), now()))

    def settings(self, db):
        return {r["key"]: json.loads(r["value"]) for r in db.execute("SELECT * FROM settings")}

    def backup(self, destination):
        with self.lock:
            with contextlib.closing(sqlite3.connect(self.path)) as source, contextlib.closing(sqlite3.connect(destination)) as target:
                source.backup(target)

    def validate_backup(self, path):
        with contextlib.closing(sqlite3.connect(f"{Path(path).resolve().as_uri()}?mode=ro", uri=True)) as db:
            if db.execute("PRAGMA application_id").fetchone()[0] != APPLICATION_ID:
                raise ValueError("这不是本系统的备份文件")
            if db.execute("PRAGMA user_version").fetchone()[0] != SCHEMA_VERSION:
                raise ValueError("备份版本不兼容")
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise ValueError("备份完整性检查失败")
            with self.db() as current:
                expected = set(tuple(r) for r in current.execute(
                    "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"))
            actual = set(tuple(r) for r in db.execute(
                "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"))
            if actual != expected:
                raise ValueError("备份的数据结构不匹配")
            if not db.execute("SELECT 1 FROM users WHERE role='leader' AND active=1").fetchone():
                raise ValueError("备份缺少有效的领导账号")
