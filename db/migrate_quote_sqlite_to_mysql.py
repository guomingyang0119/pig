"""把旧SQLite风管指标与报价快照迁移到独立MySQL报价库。

密码仅从QUOTATION_DB_PASSWORD读取；账号、会话、导入、导出和审计不迁移。
迁移前检查目标库并记录备份摘要，迁移后按规范化JSON哈希核对正文和计算结果。
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


# 读取迁移源库、目标报价库、原记录统一映射的owner-id及备份目录。
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


# 只从环境变量读取密码，避免把连接凭据写入命令参数或迁移报告。
def mysql_password() -> str:
    password = os.environ.get("QUOTATION_DB_PASSWORD")
    if password is None:
        raise SystemExit("缺少环境变量 QUOTATION_DB_PASSWORD")
    return password


# 以UTF-8建立目标连接并关闭自动提交，由迁移阶段显式提交。
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


# 把源ISO日期解析为MySQL datetime所用的无时区值；此处直接移除时区标记，不换算时刻。
def local_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    parsed = datetime.fromisoformat(value)
    return parsed.replace(tzinfo=None)


# 按键排序和统一分隔符规范化JSON，允许忽略空白与键顺序差异进行内容核对。
def canonical_json(value: str | None) -> str | None:
    if value is None:
        return None
    return json.dumps(json.loads(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


# 计算规范化JSON的SHA-256，验证迁移前后的业务字段一致。
def digest(value: str | None) -> str | None:
    canonical = canonical_json(value)
    return None if canonical is None else hashlib.sha256(canonical.encode("utf-8")).hexdigest()


# 与 QuoteListSummary.body/result 保持相同的轻量列表字段，避免旧库迁入后列表读到 NULL。
def quote_list_body(value: str) -> str:
    body = json.loads(value)
    if not isinstance(body, dict):
        raise ValueError("报价正文必须是JSON对象")
    summary = {key: body[key] for key in (
        "customer", "project", "quote_date", "pricing_mode", "recognition_mode", "include_freight"
    ) if key in body}
    for key in ("duct_options", "equipment_options"):
        options = body.get(key)
        summary[key] = {"include_freight": options.get("include_freight") if isinstance(options, dict) else None}
    rows = body.get("rows")
    summary["row_count"] = len(rows) if isinstance(rows, list) else 0
    return json.dumps(summary, ensure_ascii=False, separators=(",", ":"))


def quote_list_result(value: str | None) -> str | None:
    result = json.loads(value) if value is not None else None
    if not isinstance(result, dict) or not result:
        return None
    summary = {"_summary_present": True}
    for key in ("total", "pending_count", "engine_version", "product_rule_engine_version",
                "installation_engine_version", "duct_engine_version", "catalog_bindings",
                "product_rule_catalog_ids"):
        if key in result:
            summary[key] = result[key]
    bindings = result.get("product_rule_bindings")
    if "product_rule_catalog_ids" not in result and isinstance(bindings, list) and bindings:
        summary["product_rule_catalog_ids"] = ["legacy-unverified"]
    return json.dumps(summary, ensure_ascii=False, separators=(",", ":"))


# 拆分当前报价建表脚本：忽略整行SQL注释，再按分号分割简单语句。
def split_sql(script: str) -> list[str]:
    lines = [line for line in script.splitlines() if not line.lstrip().startswith("--")]
    return [statement.strip() for statement in "\n".join(lines).split(";") if statement.strip()]


# 记录目标报价表迁移前的JSON快照；二进制导出内容只存大小和哈希摘要。
# 该文件用于迁移追溯，二进制摘要不能还原完整Excel附件。
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


# 按源数据校验、目标库检查、写入和迁移后哈希复核执行迁移。
# 仅迁移旧duct_catalog及报价正文/结果，已有指标或报价时停止以免覆盖。
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
    # 在创建目标库前检查所有JSON可解析，避免坏快照进入迁移。
    for row in catalogs:
        canonical_json(row["body"])
    for row in quotes:
        canonical_json(row["body"])
        canonical_json(row["result"])
        quote_list_body(row["body"])
        quote_list_result(row["result"])

    root = mysql_connection(options)
    with root.cursor() as cursor:
        cursor.execute(
            f"CREATE DATABASE IF NOT EXISTS `{options.database}` "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        )
    root.commit()
    root.close()

    target = mysql_connection(options, options.database)
    # 先保存目标报价表快照摘要，再执行结构初始化与数据写入。
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

        # 业务系列ID和正式版本沿用源库；创建者统一映射到指定pig账号。
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
        # 旧报价正文、试算结果、状态和revision原样迁移，owner_id映射为指定账号。
        for row in quotes:
            cursor.execute(
                "INSERT INTO quote_order"
                "(id,family,version,owner_id,body,result,list_body,list_result,state,revision,created_at,updated_at) "
                "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (
                    row["id"],
                    row["family"],
                    row["version"],
                    options.owner_id,
                    row["body"],
                    row["result"],
                    quote_list_body(row["body"]),
                    quote_list_result(row["result"]),
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
        cursor.execute("SELECT id,body,result,list_body,list_result FROM quote_order")
        target_quotes = {row["id"]: row for row in cursor.fetchall()}
        cursor.execute("SELECT COUNT(*) AS missing FROM quote_order WHERE list_body IS NULL")
        missing_list_body = cursor.fetchone()["missing"]

    # 提交后按记录ID逐一核对JSON内容，不以行数相同代替报价快照校验。
    failures = []
    for row in catalogs:
        migrated = target_catalogs.get(row["id"])
        if migrated is None or digest(row["body"]) != digest(migrated["body"]):
            failures.append("指标 " + row["id"])
    for row in quotes:
        migrated = target_quotes.get(row["id"])
        if (migrated is None or digest(row["body"]) != digest(migrated["body"])
                or digest(row["result"]) != digest(migrated["result"])
                or digest(quote_list_body(row["body"])) != digest(migrated["list_body"])
                or digest(quote_list_result(row["result"])) != digest(migrated["list_result"])):
            failures.append("报价 " + row["id"])
    target.close()
    source.close()
    if missing_list_body:
        failures.append(f"{missing_list_body}份报价缺少列表摘要")
    if failures:
        raise SystemExit("迁移后校验失败：" + "、".join(failures))

    # 交付报告记录迁移范围和校验结论，不输出密码或连接凭据。
    report = {
        "source": str(options.source),
        "target": f"mysql://{options.host}:{options.port}/{options.database}",
        "target_backup": str(backup),
        "owner_id_mapping": options.owner_id,
        "catalog_count": len(catalogs),
        "quote_count": len(quotes),
        "missing_list_body": missing_list_body,
        "excluded": ["users", "sessions", "imports", "exports", "audit", "legacy price/rule/candidate resources"],
        "verified": True,
    }
    report_path = options.backup_dir / "quotation-migration-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
