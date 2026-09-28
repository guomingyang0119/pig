import hashlib
from contextlib import closing
import io
import json
import os
import secrets
import sqlite3
import tempfile
import time
from datetime import date
from functools import wraps
from pathlib import Path
from urllib.parse import urlsplit

from flask import Flask, request, jsonify, g, render_template, send_file
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.exceptions import HTTPException

from . import VERSION
from .store import Store, dumps, uid, now
from .engine import (RuleError, CATEGORIES, ATTRIBUTES, validate_resource, validate_policy,
                     calculate, calculate_row, active_catalog)
from .excel_io import (read_workbook, build_quote_rows, suggest_duct_mapping,
                       extract_duct_flange_meter_totals, write_quote)
from .duct import DUCT_ENGINE_VERSION


def create_app(data_dir=None, testing=False):
    root = Path(__file__).resolve().parent.parent
    app = Flask(__name__, template_folder=str(root / "templates"), static_folder=str(root / "static"))
    app.config.update(TESTING=testing, MAX_CONTENT_LENGTH=256*1024*1024)
    store = Store(data_dir or os.environ.get("QUOTE_DATA_DIR") or root / "data")
    app.extensions["store"] = store
    login_failures = {}

    def fail(message, status=400):
        error = RuleError(message)
        error.status = status
        raise error

    def payload():
        value = request.get_json(silent=True)
        if not isinstance(value, dict): fail("请求需要JSON对象")
        return value

    def safe_string(value, limit=500):
        if not isinstance(value, str) or len(value) > limit: fail("文本类型不正确或超过长度限制")
        return value.strip()

    def public_user(row):
        return {k: row[k] for k in ("id", "username", "display_name", "role", "active")}

    def resource_dict(row):
        return {**dict(row), "body": json.loads(row["body"])}

    def resources(db):
        return [resource_dict(r) for r in db.execute("SELECT * FROM resources ORDER BY created_at DESC,version DESC")]

    def quote_access(db, identifier, editable=False):
        q = db.execute("SELECT * FROM quotes WHERE id=?", (identifier,)).fetchone()
        if not q or (q["owner_id"] != g.user["id"] and g.user["role"] != "leader"): fail("报价不存在或无权访问", 404)
        if editable and q["state"] != "draft": fail("报价已锁定；归档报价请先恢复，正式报价请创建修订版", 409)
        return q

    def quote_dict(row):
        body = json.loads(row["body"])
        result = json.loads(row["result"]) if row["result"] else None
        stale = bool(result and body.get("pricing_mode") == "duct_v1" and result.get("engine_version") != DUCT_ENGINE_VERSION)
        if stale and row["state"] == "draft": result = None
        return {**dict(row), "body": body, "result": result, "result_stale": stale}

    def revision_check(q, data):
        if data.get("revision") != q["revision"]: fail("报价已在另一页面修改，请刷新后再操作", 409)

    def leader(function):
        @wraps(function)
        def checked(*args, **kwargs):
            if g.user["role"] != "leader": fail("只有领导可以执行此操作", 403)
            return function(*args, **kwargs)
        return checked

    @app.before_request
    def guard():
        host = urlsplit("http://" + request.host).hostname
        if host not in {"127.0.0.1", "localhost", "::1"}: fail("仅允许本机访问", 403)
        request.max_content_length = (256 if request.path == "/api/restore" else 24)*1024*1024
        if request.path.startswith("/api/") and request.path != "/api/health":
            store.lock.acquire()
            g.store_locked = True
        if request.method not in {"GET", "HEAD", "OPTIONS"}:
            origin = request.headers.get("Origin")
            if origin and origin != request.host_url.rstrip("/"): fail("不允许跨站请求", 403)
            if request.path in {"/api/setup", "/api/login"} and not request.is_json: fail("请求格式不正确", 415)
        g.user, g.session = None, None
        raw_token = request.cookies.get("quote_session")
        if raw_token:
            with store.db() as db:
                session = db.execute("SELECT * FROM sessions WHERE token=? AND expires>?", (hashlib.sha256(raw_token.encode()).hexdigest(), time.time())).fetchone()
                if session:
                    user = db.execute("SELECT * FROM users WHERE id=? AND active=1", (session["user_id"],)).fetchone()
                    if user: g.user, g.session = dict(user), dict(session)
        if request.path.startswith("/api/") and request.path not in {"/api/bootstrap", "/api/setup", "/api/login", "/api/health"}:
            if not g.user: fail("请先登录", 401)
            if request.method not in {"GET", "HEAD"} and not secrets.compare_digest(request.headers.get("X-CSRF-Token", ""), g.session["csrf"]):
                fail("页面凭证已失效，请刷新重试", 403)

    @app.teardown_request
    def unlock(_error):
        if getattr(g, "store_locked", False):
            g.store_locked = False
            store.lock.release()

    @app.after_request
    def headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.errorhandler(RuleError)
    def rule_error(error):
        return jsonify(error=str(error)), getattr(error, "status", 400)

    @app.errorhandler(HTTPException)
    def http_error(error):
        limit = "256MB" if request.path == "/api/restore" else "24MB"
        return jsonify(error=f"请求超过{limit}，请缩小文件" if error.code == 413 else error.description), error.code

    @app.errorhandler(Exception)
    def unexpected(error):
        if testing: raise error
        app.logger.exception("Application operation failed")
        return jsonify(error="操作未完成，请查看本地日志或重试；数据未提交"), 500

    @app.get("/")
    def index(): return render_template("index.html")

    @app.get("/api/health")
    def health(): return jsonify(app="guizhou-quotation", version=VERSION)

    @app.get("/api/bootstrap")
    def bootstrap():
        with store.db() as db:
            setup = not db.execute("SELECT 1 FROM users LIMIT 1").fetchone()
        return jsonify(setup_required=setup, user=public_user(g.user) if g.user else None,
                       csrf=g.session["csrf"] if g.session else None, categories=CATEGORIES,
                       attributes=ATTRIBUTES, version=VERSION)

    def credentials(data):
        username = safe_string(data.get("username", ""), 64)
        password = data.get("password", "")
        if not username or not isinstance(password, str) or not 8 <= len(password) <= 128: fail("账号不能为空，密码需8—128个字符")
        return username, password

    @app.post("/api/setup")
    def setup():
        data = payload()
        username, password = credentials(data)
        with store.db() as db:
            if db.execute("SELECT 1 FROM users LIMIT 1").fetchone(): fail("系统已经初始化", 409)
            identifier = uid()
            db.execute("INSERT INTO users VALUES (?,?,?,?,?,?,?)", (identifier, username, safe_string(data.get("display_name", "负责人"), 80), generate_password_hash(password), "leader", 1, now()))
            store.audit(db, identifier, "setup", identifier)
        return jsonify(ok=True)

    @app.post("/api/login")
    def login():
        data = payload()
        username = safe_string(data.get("username", ""), 64)
        password = data.get("password", "")
        if not isinstance(password, str) or len(password) > 128: fail("账号或密码错误", 401)
        failures = [t for t in login_failures.get(username, []) if t > time.time()-60]
        if len(failures) >= 8: fail("尝试次数过多，请1分钟后重试", 429)
        with store.db() as db:
            user = db.execute("SELECT * FROM users WHERE username=? AND active=1", (username,)).fetchone()
            if not user or not check_password_hash(user["password_hash"], password):
                login_failures[username] = failures + [time.time()]
                fail("账号或密码错误", 401)
            login_failures.pop(username, None)
            token, csrf = secrets.token_urlsafe(40), secrets.token_urlsafe(32)
            db.execute("DELETE FROM sessions WHERE expires<?", (time.time(),))
            db.execute("INSERT INTO sessions VALUES (?,?,?,?)", (hashlib.sha256(token.encode()).hexdigest(), user["id"], csrf, time.time()+8*3600))
            store.audit(db, user["id"], "login", user["id"])
        response = jsonify(user=public_user(user), csrf=csrf)
        response.set_cookie("quote_session", token, httponly=True, samesite="Strict", max_age=8*3600)
        return response

    @app.post("/api/logout")
    def logout():
        with store.db() as db: db.execute("DELETE FROM sessions WHERE token=?", (g.session["token"],))
        response = jsonify(ok=True)
        response.delete_cookie("quote_session")
        return response

    @app.get("/api/users")
    @leader
    def users():
        with store.db() as db: return jsonify([public_user(r) for r in db.execute("SELECT * FROM users ORDER BY created_at")])

    @app.post("/api/users")
    @leader
    def new_user():
        data = payload()
        username, password = credentials(data)
        if data.get("role") not in {"leader", "quoter"}: fail("角色不正确")
        with store.db() as db:
            if db.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone(): fail("账号已存在")
            identifier = uid()
            db.execute("INSERT INTO users VALUES (?,?,?,?,?,?,?)", (identifier, username, safe_string(data.get("display_name", username), 80), generate_password_hash(password), data["role"], 1, now()))
            store.audit(db, g.user["id"], "create_user", identifier)
        return jsonify(id=identifier)

    @app.post("/api/users/<identifier>/change")
    @leader
    def change_user(identifier):
        data = payload()
        with store.db() as db:
            user = db.execute("SELECT * FROM users WHERE id=?", (identifier,)).fetchone()
            if not user: fail("账号不存在", 404)
            active_flag = 1 if data.get("active", bool(user["active"])) else 0
            if not active_flag and user["role"] == "leader" and db.execute("SELECT COUNT(*) FROM users WHERE role='leader' AND active=1").fetchone()[0] <= 1:
                fail("不能停用最后一个领导账号")
            password_hash = user["password_hash"]
            if data.get("password"):
                _, password = credentials({"username": user["username"], "password": data["password"]})
                password_hash = generate_password_hash(password)
            db.execute("UPDATE users SET active=?,password_hash=? WHERE id=?", (active_flag, password_hash, identifier))
            db.execute("DELETE FROM sessions WHERE user_id=?", (identifier,))
            store.audit(db, g.user["id"], "change_user", identifier, {"active": bool(active_flag)})
        return jsonify(ok=True)

    @app.get("/api/resources")
    def list_resources():
        with store.db() as db: return jsonify(resources(db))

    @app.post("/api/resources")
    @leader
    def create_resource():
        data = payload()
        if data.get("kind") not in {"price", "rule", "candidate", "duct_catalog"}: fail("资源类型不正确")
        body = data.get("body")
        if not isinstance(body, dict) or len(dumps(body)) > 100000: fail("资源内容格式不正确或过长")
        body["company_id"] = "guizhou"
        identifier = uid()
        with store.db() as db:
            db.execute("INSERT INTO resources VALUES (?,?,?,?,?,?,?,?,?)", (identifier, identifier, data["kind"], 1, "draft", dumps(body), g.user["id"], now(), None))
            store.audit(db, g.user["id"], "create_resource", identifier, {"kind": data["kind"]})
        return jsonify(id=identifier)

    @app.put("/api/resources/<identifier>")
    @leader
    def edit_resource(identifier):
        body = payload().get("body")
        if not isinstance(body, dict) or len(dumps(body)) > 100000: fail("资源内容格式不正确或过长")
        body["company_id"] = "guizhou"
        with store.db() as db:
            item = db.execute("SELECT * FROM resources WHERE id=?", (identifier,)).fetchone()
            if not item: fail("记录不存在", 404)
            if item["status"] != "draft": fail("已发布版本不可修改，请创建新版本", 409)
            db.execute("UPDATE resources SET body=? WHERE id=?", (dumps(body), identifier))
            store.audit(db, g.user["id"], "edit_resource", identifier)
        return jsonify(ok=True)

    @app.delete("/api/resources/<identifier>")
    @leader
    def delete_resource(identifier):
        with store.db() as db:
            item = db.execute("SELECT id,status,family,version FROM resources WHERE id=?", (identifier,)).fetchone()
            if not item: fail("指标不存在", 404)
            if item["status"] != "draft": fail("已发布或已停用指标不能删除，请保留历史版本", 409)
            db.execute("DELETE FROM resources WHERE id=? AND status='draft'", (identifier,))
            store.audit(db, g.user["id"], "delete_resource_draft", identifier,
                        {"family": item["family"], "version": item["version"]})
        return jsonify(ok=True)

    def trial(db, item, sample):
        validate_resource(item["kind"], item["body"])
        if item["kind"] != "rule":
            comparisons = []
            catalog = active_catalog(resources(db))
            if item["kind"] == "price":
                candidate = {**item, "status": "published"}
                policy = store.settings(db)["policy"]
                proposed = [r for r in catalog if r["family"] != item["family"]] + [candidate]
                for rule in catalog:
                    if rule["kind"] == "rule" and item["family"] in rule["body"].get("price_refs", {}).values() and rule["body"].get("approval_sample"):
                        sample_row = rule["body"]["approval_sample"]
                        old = calculate_row(sample_row, catalog, policy)
                        new = calculate_row(sample_row, proposed, policy)
                        comparisons.append({"rule": rule["body"]["name"], "old": old.get("amount"), "new": new.get("amount"), "reason": new.get("reason", "")})
            return {"status": "validated", "comparisons": comparisons}
        if not isinstance(sample, dict): fail("规则发布前请填写一个业务核对样例并试算")
        catalog = [r for r in resources(db) if r["family"] != item["family"]]
        trial_item = {**item, "status": "published"}
        trial_item["body"] = {**item["body"], "effective_from": date.today().isoformat(), "effective_to": ""}
        catalog = active_catalog(catalog) + [trial_item]
        policy = store.settings(db)["policy"]
        result = calculate_row(sample, catalog, policy)
        previous = [r for r in active_catalog(resources(db)) if r["family"] == item["family"]]
        if previous:
            old_result = calculate_row(sample, [r for r in catalog if r["family"] != item["family"]]+previous, policy)
            result["previous"] = {"version": previous[0]["version"], "amount": old_result.get("amount"), "reason": old_result.get("reason")}
        return result

    @app.post("/api/resources/<identifier>/<action>")
    @leader
    def resource_action(identifier, action):
        data = payload()
        with store.db() as db:
            row = db.execute("SELECT * FROM resources WHERE id=?", (identifier,)).fetchone()
            if not row: fail("记录不存在", 404)
            item = resource_dict(row)
            if action == "preview":
                if item["kind"] != "rule": fail("仅计算规则支持草稿算式测试")
                sample = data.get("sample")
                if not isinstance(sample, dict): fail("请填写测试样例")
                validate_resource("rule", item["body"], calculation_only=True)
                catalog = [r for r in active_catalog(resources(db)) if r["kind"] != "rule"] + [item]
                result = calculate_row(sample, catalog, {"unit_round": None, "line_round": None})
                result["preview_only"] = True
                result["warning"] = "仅测试所选草稿算式，不代表正式报价；未应用正式舍入及整单税费、安装、运费，也未确认选型、生效日期与价格有效性。"
                return jsonify(result)
            if action == "revise":
                draft = db.execute("SELECT id FROM resources WHERE family=? AND status='draft'", (item["family"],)).fetchone()
                if draft: return jsonify(id=draft["id"])
                version = db.execute("SELECT MAX(version) FROM resources WHERE family=?", (item["family"],)).fetchone()[0]+1
                new_id = uid()
                body = {k: v for k, v in item["body"].items() if k not in {"approval_sample", "approval_result"}}
                db.execute("INSERT INTO resources VALUES (?,?,?,?,?,?,?,?,?)", (new_id, item["family"], item["kind"], version, "draft", dumps(body), g.user["id"], now(), None))
                store.audit(db, g.user["id"], "revise_resource", new_id, {"from": identifier})
                return jsonify(id=new_id)
            if action == "reactivate":
                if item["status"] != "retired": fail("只有已停用指标可以重新启用", 409)
                newer = db.execute("SELECT 1 FROM resources WHERE family=? AND version>? AND status IN ('published','retired') LIMIT 1",
                                   (item["family"], item["version"])).fetchone()
                if newer: fail("已有更新的正式或已停用版本，不能重新启用旧版本；请创建新版本", 409)
                validate_resource(item["kind"], item["body"])
                today_value = date.today().isoformat()
                body = item["body"]
                if body.get("effective_from", "9999") > today_value or (body.get("effective_to") and body["effective_to"] < today_value):
                    fail("此版本不在生效日期内，请创建新版本调整日期后发布", 409)
                if item["kind"] == "duct_catalog":
                    others = [r for r in active_catalog(resources(db)) if r["kind"] == "duct_catalog" and r["family"] != item["family"]]
                    if others: fail("已有其他生效中的风管指标，不能同时启用两套", 409)
                db.execute("UPDATE resources SET status='published' WHERE id=? AND status='retired'", (identifier,))
                store.audit(db, g.user["id"], "reactivate_resource", identifier, {"version": item["version"]})
                return jsonify(ok=True)
            if action in {"trial", "publish"}:
                if item["status"] != "draft": fail("只有草稿可以试算或发布")
                result = trial(db, item, data.get("sample"))
                if action == "trial": return jsonify(result)
                if result["status"] not in {"priced", "validated"}: fail("试算未通过：" + result.get("reason", ""))
                if item["kind"] == "rule":
                    expected = data.get("expected_amount")
                    from .engine import number
                    if expected is None or number(expected) != number(result["amount"]): fail("请填写与业务确认结果一致的预期行金额；当前试算与预期不一致")
                    item["body"]["approval_sample"] = data["sample"]
                    item["body"]["approval_result"] = result["amount"]
                db.execute("UPDATE resources SET status='published',published_at=?,body=? WHERE id=?", (now(), dumps(item["body"]), identifier))
            elif action == "retire":
                if item["status"] != "published": fail("只有已发布记录可以停用")
                db.execute("UPDATE resources SET status='retired' WHERE id=?", (identifier,))
            else: fail("不支持的操作", 404)
            store.audit(db, g.user["id"], action+"_resource", identifier)
        return jsonify(ok=True)

    @app.post("/api/imports")
    def upload():
        file = request.files.get("file")
        if not file or not file.filename: fail("请选择Excel文件")
        filename = Path(file.filename).name
        content = file.read()
        parsed = read_workbook(content, filename)
        for sheet in parsed:
            suggestion = suggest_duct_mapping(sheet)
            sheet["suggested_mapping"] = suggestion["mapping"]
            sheet["suggested_start_row"] = suggestion["start_row"]
        identifier = uid()
        with store.db() as db:
            db.execute("INSERT INTO imports VALUES (?,?,?,?,?,?)", (identifier, g.user["id"], filename, content, dumps(parsed), now()))
        return jsonify(id=identifier, filename=filename, sheets=parsed)

    def metadata(data):
        return {k: safe_string(data.get(k, ""), 1000 if k == "note" else 200)
                for k in ("customer", "project", "contact", "delivery_location", "quote_date", "note")}

    @app.post("/api/quotes")
    def create_quote():
        data = payload()
        body = metadata(data)
        body["company_id"] = "guizhou"
        if data.get("pricing_mode") == "duct_v1":
            body["pricing_mode"] = "duct_v1"
            body["duct_options"] = {"area_basis": "", "product_state": "", "shape": "",
                                    "zinc_layer": "40", "flange": "", "include_common_punch": None, "tax_included": None,
                                    "corner_count": "", "clip_meters": "",
                                    "flange_30_meters": "", "flange_40_meters": "",
                                    "payment_option": "",
                                    "include_installation": False, "installation_amount": "",
                                    "include_freight": False}
        body["quote_date"] = body["quote_date"] or date.today().isoformat()
        body["rows"] = []
        with store.db() as db:
            if data.get("import_id"):
                source = db.execute("SELECT * FROM imports WHERE id=? AND owner_id=?", (data["import_id"], g.user["id"])).fetchone()
                if not source: fail("导入文件不存在", 404)
                sheets = json.loads(source["parsed"])
                sheet = next((s for s in sheets if s["name"] == data.get("sheet")), None)
                if not sheet: fail("请选择工作表")
                mapping, start_row = data.get("mapping", {}), data.get("start_row")
                flange_totals = {"totals": {}, "source_rows": set(), "sources": []}
                if body.get("pricing_mode") == "duct_v1":
                    flange_totals = extract_duct_flange_meter_totals(sheet, start_row, mapping)
                    body["duct_options"].update(flange_totals["totals"])
                    if flange_totals["sources"]:
                        body["source_special_rows"] = flange_totals["sources"]
                body["rows"] = build_quote_rows(sheet, start_row, mapping, body.get("pricing_mode", "legacy"),
                                                flange_totals["source_rows"])
                if body.get("pricing_mode") == "duct_v1":
                    detected_flanges = {row.get("attributes", {}).get("flange") for row in body["rows"]}
                    detected_flanges.discard(None)
                    detected_flanges.discard("")
                    if any(key in flange_totals["totals"] for key in ("flange_30_meters", "flange_40_meters")):
                        detected_flanges.add("angle")
                    if len(detected_flanges) > 1:
                        body["duct_options"]["flange"] = "mixed"
                    elif len(detected_flanges) == 1:
                        body["duct_options"]["flange"] = next(iter(detected_flanges))
                body.update(import_id=source["id"], source_filename=source["filename"], source_sheet=sheet["name"],
                            import_mapping=data["mapping"], start_row=data["start_row"], available_sheets=[s["name"] for s in sheets])
            identifier = uid()
            db.execute("INSERT INTO quotes VALUES (?,?,?,?,?,?,?,?,?,?)", (identifier, identifier, 1, g.user["id"], dumps(body), None, "draft", 1, now(), now()))
            store.audit(db, g.user["id"], "create_quote", identifier)
        return jsonify(id=identifier)

    @app.get("/api/quotes")
    def list_quotes():
        with store.db() as db:
            archived = request.args.get("archived") == "1"
            query = ("SELECT q.*,u.display_name,(SELECT COUNT(*) FROM exports x WHERE x.quote_id=q.id) AS export_count "
                     "FROM quotes q JOIN users u ON u.id=q.owner_id WHERE q.state " + ("=" if archived else "!=") + " 'archived'")
            args = ()
            if g.user["role"] != "leader": query += " AND owner_id=?"; args = (g.user["id"],)
            query += " ORDER BY q.updated_at DESC"
            result = []
            for row in db.execute(query, args):
                q = quote_dict(row)
                body = q.pop("body")
                q.update({k: body.get(k, "") for k in ("customer", "project", "quote_date")})
                q["row_count"] = len(body["rows"])
                computed = q.pop("result")
                q["subtotal"] = computed["subtotal"] if computed else None
                q["pending_count"] = computed["pending_count"] if computed else len(body["rows"])
                result.append(q)
        return jsonify(result)

    @app.get("/api/quotes/<identifier>")
    def get_quote(identifier):
        with store.db() as db:
            q = quote_dict(quote_access(db, identifier))
            q["exports"] = [dict(r) for r in db.execute("SELECT id,kind,filename,created_at FROM exports WHERE quote_id=? ORDER BY created_at DESC", (identifier,))]
            return jsonify(q)

    @app.delete("/api/quotes/<identifier>")
    def delete_quote(identifier):
        data = payload()
        with store.db() as db:
            q = quote_access(db, identifier)
            revision_check(q, data)
            if q["state"] != "draft": fail("只有未导出的草稿报价可删除；已导出的报价请归档", 409)
            if db.execute("SELECT 1 FROM exports WHERE quote_id=? LIMIT 1", (identifier,)).fetchone():
                fail("此报价已有导出记录，请改用归档", 409)
            db.execute("DELETE FROM quotes WHERE id=? AND state='draft'", (identifier,))
            store.audit(db, g.user["id"], "delete_quote_draft", identifier,
                        {"family": q["family"], "version": q["version"]})
        return jsonify(ok=True)

    @app.post("/api/quotes/<identifier>/archive")
    def archive_quote(identifier):
        data = payload()
        with store.db() as db:
            q = quote_access(db, identifier)
            revision_check(q, data)
            if q["state"] not in {"draft", "final"}: fail("报价已归档", 409)
            if not db.execute("SELECT 1 FROM exports WHERE quote_id=? LIMIT 1", (identifier,)).fetchone():
                fail("没有导出记录的草稿报价请直接删除", 409)
            body = json.loads(q["body"])
            body["_archived_from_state"] = q["state"]
            db.execute("UPDATE quotes SET state='archived',body=?,revision=revision+1,updated_at=? WHERE id=?",
                       (dumps(body), now(), identifier))
            store.audit(db, g.user["id"], "archive_quote", identifier, {"previous_state": q["state"]})
        return jsonify(ok=True, revision=q["revision"]+1)

    @app.post("/api/quotes/<identifier>/restore")
    def restore_quote(identifier):
        data = payload()
        with store.db() as db:
            q = quote_access(db, identifier)
            revision_check(q, data)
            if q["state"] != "archived": fail("报价未归档", 409)
            body = json.loads(q["body"])
            previous = body.pop("_archived_from_state", None)
            if previous not in {"draft", "final"}: fail("归档前状态缺失，请勿擅自恢复", 409)
            db.execute("UPDATE quotes SET state=?,body=?,revision=revision+1,updated_at=? WHERE id=?",
                       (previous, dumps(body), now(), identifier))
            store.audit(db, g.user["id"], "restore_quote", identifier, {"restored_state": previous})
        return jsonify(ok=True, revision=q["revision"]+1)

    @app.put("/api/quotes/<identifier>")
    def edit_quote(identifier):
        data = payload()
        with store.db() as db:
            q = quote_access(db, identifier, True)
            revision_check(q, data)
            old = json.loads(q["body"])
            body = {**old, **metadata(data)}
            if old.get("pricing_mode") == "duct_v1":
                incoming_options = data.get("duct_options", {})
                if not isinstance(incoming_options, dict): fail("风管报价条件格式不正确")
                options = dict(old.get("duct_options", {}))
                for key in ("area_basis", "product_state", "shape", "zinc_layer", "flange",
                            "corner_count", "clip_meters", "flange_30_meters", "flange_40_meters",
                            "payment_option", "installation_amount"):
                    options[key] = safe_string(str(incoming_options.get(key, options.get(key, ""))), 100)
                for key in ("tax_included", "include_common_punch", "include_installation", "include_freight"):
                    value = incoming_options.get(key, options.get(key))
                    if value is not True and value is not False and value is not None: fail("风管报价条件必须明确选择")
                    options[key] = value
                if options["product_state"] in {"semi", "finished"} and options["shape"] not in {
                        "semi_unspecified", "straight", "irregular"}:
                    options["shape"] = "semi_unspecified"
                body["duct_options"] = options
            incoming = data.get("rows")
            if not isinstance(incoming, list) or not len(old["rows"]) <= len(incoming) <= 10000:
                fail("产品行不能静默删除，请保留原行并标记不计价及原因")
            rows = []
            for index, value in enumerate(incoming):
                original = old["rows"][index] if index < len(old["rows"]) else {"source_row": f"手工{index+1}", "source_sheet": "手工", "original": None, "source_issue": False}
                row = {k: original.get(k) for k in ("source_row", "source_sheet", "original", "source_issue")}
                for key in ("name", "spec", "quantity", "unit", "remark", "category", "exclude_reason", "candidate_id"):
                    row[key] = safe_string(value.get(key, ""), 1000)
                if old.get("pricing_mode") == "duct_v1":
                    row["engineering_quantity"] = safe_string(value.get("engineering_quantity", ""), 100)
                row["excluded"] = bool(value.get("excluded"))
                row["source_issue_confirmed"] = bool(value.get("source_issue_confirmed"))
                attrs = value.get("attributes", {})
                if not isinstance(attrs, dict) or len(attrs) > 100: fail("产品参数格式不正确")
                row["attributes"] = {safe_string(k, 40): safe_string(str(v), 300) for k, v in attrs.items()}
                if row["excluded"] and not row["exclude_reason"]: fail("不计价行必须说明原因")
                rows.append(row)
            body["rows"] = rows
            store.audit(db, g.user["id"], "edit_quote", identifier, {"previous_body": old})
            db.execute("UPDATE quotes SET body=?,result=NULL,revision=revision+1,updated_at=? WHERE id=?", (dumps(body), now(), identifier))
        return jsonify(ok=True, revision=q["revision"]+1)

    @app.post("/api/quotes/<identifier>/calculate")
    def compute_quote(identifier):
        data = payload()
        with store.db() as db:
            q = quote_access(db, identifier, True)
            revision_check(q, data)
            result = calculate(json.loads(q["body"]), resources(db), store.settings(db)["policy"])
            result["calculated_at"] = now()
            db.execute("UPDATE quotes SET result=?,revision=revision+1,updated_at=? WHERE id=?", (dumps(result), now(), identifier))
            store.audit(db, g.user["id"], "calculate_quote", identifier, {"pending": result["pending_count"]})
        return jsonify(result=result, revision=q["revision"]+1)

    @app.post("/api/quotes/<identifier>/revise")
    def revise_quote(identifier):
        with store.db() as db:
            q = quote_access(db, identifier)
            if q["state"] == "archived": fail("归档报价请先恢复，再创建修订版", 409)
            version = db.execute("SELECT MAX(version) FROM quotes WHERE family=?", (q["family"],)).fetchone()[0]+1
            new_id = uid()
            db.execute("INSERT INTO quotes VALUES (?,?,?,?,?,?,?,?,?,?)", (new_id, q["family"], version, q["owner_id"], q["body"], None, "draft", 1, now(), now()))
            store.audit(db, g.user["id"], "revise_quote", new_id, {"from": identifier})
        return jsonify(id=new_id)

    @app.post("/api/quotes/<identifier>/export")
    def export_quote(identifier):
        data = payload()
        formal = data.get("kind") == "formal"
        with store.db() as db:
            q = quote_access(db, identifier)
            if q["state"] == "archived": fail("归档报价不可重新导出，请先恢复", 409)
            revision_check(q, data)
            if not q["result"]: fail("请先保存并计算报价")
            result, body = json.loads(q["result"]), json.loads(q["body"])
            if body.get("pricing_mode") == "duct_v1" and result.get("engine_version") != DUCT_ENGINE_VERSION:
                fail("风管运算规则已更新，请重新保存并试算后导出")
            if formal and not result["formal_ready"]: fail("正式报价条件未满足：" + "；".join(result["issues"]))
            eid = uid()
            filename = f"{'正式报价' if formal else '报价草稿'}_{identifier[:8]}_V{q['version']}_{eid[:6]}.xlsx"
            content = write_quote(body, result, f"{q['family'][:8]}-V{q['version']}", formal)
            directory = store.settings(db)["export_directory"]
            if directory:
                target = Path(directory).resolve()
                if not target.is_dir(): fail("导出目录不存在，请在设置中修改")
                with (target / filename).open("xb") as handle: handle.write(content)
            db.execute("INSERT INTO exports VALUES (?,?,?,?,?,?)", (eid, identifier, "formal" if formal else "draft", filename, content, now()))
            if formal: db.execute("UPDATE quotes SET state='final',revision=revision+1,updated_at=? WHERE id=?", (now(), identifier))
            store.audit(db, g.user["id"], "export_quote", identifier, {"kind": "formal" if formal else "draft", "export_id": eid})
        return jsonify(download=f"/api/exports/{eid}", filename=filename)

    @app.get("/api/exports/<identifier>")
    def download_export(identifier):
        with store.db() as db:
            item = db.execute("SELECT * FROM exports WHERE id=?", (identifier,)).fetchone()
            if not item: fail("文件不存在", 404)
            quote_access(db, item["quote_id"])
            return send_file(io.BytesIO(item["content"]), download_name=item["filename"], as_attachment=True,
                             mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

    @app.get("/api/settings")
    def get_settings():
        with store.db() as db:
            settings = store.settings(db)
            if g.user["role"] == "leader": settings["data_directory"] = str(store.directory)
            else: settings.pop("export_directory", None)
            return jsonify(settings)

    @app.put("/api/settings")
    @leader
    def change_settings():
        data = payload()
        with store.db() as db:
            current = store.settings(db)
            policy = data.get("policy", current["policy"])
            validate_policy(policy)
            if policy.get("version") != current["policy"]["version"]: fail("设置已变化，请刷新", 409)
            policy["version"] += 1
            path = safe_string(data.get("export_directory", current["export_directory"]), 1000)
            if path and not Path(path).is_dir(): fail("请填写已存在的本地导出目录")
            db.execute("UPDATE settings SET value=? WHERE key='policy'", (dumps(policy),))
            db.execute("UPDATE settings SET value=? WHERE key='export_directory'", (dumps(path),))
            store.audit(db, g.user["id"], "change_settings", "policy", {"previous": current["policy"], "current": policy})
        return jsonify(ok=True)

    @app.post("/api/backup")
    @leader
    def backup():
        folder = store.directory / "backups"
        folder.mkdir(exist_ok=True)
        identifier = uid()
        target = folder / (identifier + ".sqlite3")
        store.backup(target)
        with store.db() as db: store.audit(db, g.user["id"], "backup", identifier)
        return jsonify(download=f"/api/backup/{identifier}")

    @app.get("/api/backup/<identifier>")
    @leader
    def download_backup(identifier):
        if len(identifier) != 32 or any(c not in "0123456789abcdef" for c in identifier): fail("备份不存在", 404)
        target = store.directory / "backups" / (identifier + ".sqlite3")
        if not target.is_file(): fail("备份不存在", 404)
        return send_file(target, as_attachment=True, download_name=f"报价系统备份_{date.today()}_{identifier[:6]}.sqlite3")

    @app.post("/api/restore")
    @leader
    def restore():
        if request.form.get("confirmation") != "恢复备份": fail("请输入恢复备份确认文字")
        file = request.files.get("file")
        if not file: fail("请选择本系统的备份文件")
        fd, name = tempfile.mkstemp(suffix=".sqlite3", dir=store.directory)
        os.close(fd)
        target = Path(name)
        try:
            file.save(target)
            with store.lock:
                store.validate_backup(target)
                folder = store.directory / "backups"
                folder.mkdir(exist_ok=True)
                safety = folder / ("before_restore_"+uid()+".sqlite3")
                store.backup(safety)
                with closing(sqlite3.connect(target)) as db:
                    db.execute("DELETE FROM sessions")
                    db.commit()
                os.replace(target, store.path)
                with store.db() as db: store.audit(db, g.user["id"], "restore", "database", {"safety_backup": str(safety)})
        except (sqlite3.DatabaseError, ValueError) as error:
            fail("备份校验失败：" + str(error))
        finally:
            if target.exists(): target.unlink()
        response = jsonify(ok=True, message="恢复完成，请使用备份中的账号重新登录；恢复前数据已自动备份")
        response.delete_cookie("quote_session")
        return response

    @app.get("/api/audit")
    @leader
    def audit():
        with store.db() as db:
            return jsonify([dict(r) for r in db.execute("SELECT id,actor,action,target,created_at FROM audit ORDER BY id DESC LIMIT 100")])

    return app
