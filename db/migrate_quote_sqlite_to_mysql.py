"""Migrate supported quotation rules and quote records from SQLite to MySQL.

Passwords are read only from QUOTATION_DB_PASSWORD. The script deliberately
does not migrate accounts, sessions, imports, exported files, or audit logs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any

import pymysql


QUOTE_TABLES = (
    "quote_catalog",
    "quote_order",
    "quote_import",
    "quote_export",
    "quote_audit",
    "quote_setting",
)


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=3306, type=int)
    parser.add_argument("--user", default="root")
    parser.add_argument("--database", default="QuotationSystem")
    parser.add_argument("--owner-id", default=1, type=int)
    parser.add_argument("--schema-sql", type=Path, default=Path(__file__).with_name("quotation-system.sql"))
    parser.add_argument("--backup-dir", required=True, type=Path)
    return parser.parse_args()


def mysql_password() -> str:
    password = os.environ.get("QUOTATION_DB_PASSWORD")
    if password is None:
        raise SystemExit("缺少环境变量 QUOTATION_DB_PASSWORD")
    return password


def mysql_connection(options: argparse.Namespace, database: str | None = None):
    return pymysql.connect(
        host=options.host,
        port=options.port,
        user=options.user,
        password=mysql_password(),
        database=database,
        charset="utf8mb4",
        autocommit=False,
    )


def local_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    parsed = datetime.fromisoformat(value)
    return parsed.replace(tzinfo=None)


def canonical_json(value: str | None) -> str | None:
    if value is None:
        return None
    return json.dumps(json.loads(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value: str | None) -> str | None:
    canonical = canonical_json(value)
    return None if canonical is None else hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def split_sql(script: str) -> list[str]:
    lines = [line for line in script.splitlines() if not line.lstrip().startswith("--")]
    return [statement.strip() for statement in "\n".join(lines).split(";") if statement.strip()]


def target_backup(connection, backup_dir: Path, database: str) -> Path:
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = backup_dir / f"mysql-{database}-quote-tables-before-{stamp}.json"
    snapshot: dict[str, Any] = {"database": database, "created_at": datetime.now().isoformat(), "tables": {}}
    with connection.cursor() as cursor:
        for table in QUOTE_TABLES:
            cursor.execute(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s AND table_name=%s",
                (database, table),
            )
            if not cursor.fetchone()[0]:
                snapshot["tables"][table] = None
                continue
            cursor.execute(f"SELECT * FROM `{table}`")
            columns = [item[0] for item in cursor.description]
            rows = []
            for values in cursor.fetchall():
                row = {}
                for key, value in zip(columns, values):
                    if isinstance(value, bytes):
                        row[key] = {"bytes_sha256": hashlib.sha256(value).hexdigest(), "size": len(value)}
                    elif isinstance(value, datetime):
                        row[key] = value.isoformat(sep=" ")
                    else:
                        row[key] = value
                rows.append(row)
            snapshot["tables"][table] = rows
    path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return path


def main() -> None:
    options = args()
    if not options.source.is_file():
        raise SystemExit(f"SQLite 文件不存在：{options.source}")
    if not re.fullmatch(r"[A-Za-z0-9_]+", options.database):
        raise SystemExit("数据库名只能包含字母、数字和下划线")

    source = sqlite3.connect(str(options.source))
    source.row_factory = sqlite3.Row
    catalogs = source.execute(
        "SELECT id,family,kind,version,status,body,created_at,published_at "
        "FROM resources WHERE kind='duct_catalog' ORDER BY family,version"
    ).fetchall()
    quotes = source.execute(
        "SELECT id,family,version,body,result,state,revision,created_at,updated_at FROM quotes "
        "ORDER BY family,version"
    ).fetchall()
    if not catalogs:
        raise SystemExit("源库没有可迁移的 duct_catalog 指标")
    for row in catalogs:
        canonical_json(row["body"])
    for row in quotes:
        canonical_json(row["body"])
        canonical_json(row["result"])

    root = mysql_connection(options)
    with root.cursor() as cursor:
        cursor.execute(
            f"CREATE DATABASE IF NOT EXISTS `{options.database}` "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        )
    root.commit()
    root.close()

    target = mysql_connection(options, options.database)
    backup = target_backup(target, options.backup_dir, options.database)
    with target.cursor() as cursor:
        for statement in split_sql(options.schema_sql.read_text(encoding="utf-8")):
            cursor.execute(statement)
        cursor.execute("SELECT COUNT(*) FROM quote_catalog")
        existing_catalogs = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM quote_order")
        existing_quotes = cursor.fetchone()[0]
        if existing_catalogs or existing_quotes:
            raise SystemExit(
                f"目标库已有报价数据（指标{existing_catalogs}、报价{existing_quotes}），为防止覆盖已停止迁移"
            )

        for row in catalogs:
            cursor.execute(
                "INSERT INTO quote_catalog"
                "(id,family,kind,version,status,body,revision,created_by,created_at,published_at) "
                "VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,%s)",
                (
                    row["id"],
                    row["family"],
                    row["kind"],
                    row["version"],
                    row["status"],
                    row["body"],
                    options.owner_id,
                    local_datetime(row["created_at"]),
                    local_datetime(row["published_at"]),
                ),
            )
        for row in quotes:
            cursor.execute(
                "INSERT INTO quote_order"
                "(id,family,version,owner_id,body,result,state,revision,created_at,updated_at) "
                "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (
                    row["id"],
                    row["family"],
                    row["version"],
                    options.owner_id,
                    row["body"],
                    row["result"],
                    row["state"],
                    row["revision"],
                    local_datetime(row["created_at"]),
                    local_datetime(row["updated_at"]),
                ),
            )
    target.commit()

    with target.cursor(pymysql.cursors.DictCursor) as cursor:
        cursor.execute("SELECT id,body FROM quote_catalog")
        target_catalogs = {row["id"]: row for row in cursor.fetchall()}
        cursor.execute("SELECT id,body,result FROM quote_order")
        target_quotes = {row["id"]: row for row in cursor.fetchall()}

    failures = []
    for row in catalogs:
        migrated = target_catalogs.get(row["id"])
        if migrated is None or digest(row["body"]) != digest(migrated["body"]):
            failures.append("指标 " + row["id"])
    for row in quotes:
        migrated = target_quotes.get(row["id"])
        if migrated is None or digest(row["body"]) != digest(migrated["body"]) or digest(row["result"]) != digest(migrated["result"]):
            failures.append("报价 " + row["id"])
    target.close()
    source.close()
    if failures:
        raise SystemExit("迁移后校验失败：" + "、".join(failures))

    report = {
        "source": str(options.source),
        "target": f"mysql://{options.host}:{options.port}/{options.database}",
        "target_backup": str(backup),
        "owner_id_mapping": options.owner_id,
        "catalog_count": len(catalogs),
        "quote_count": len(quotes),
        "excluded": ["users", "sessions", "imports", "exports", "audit", "legacy price/rule/candidate resources"],
        "verified": True,
    }
    report_path = options.backup_dir / "quotation-migration-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
