"""Deterministic, bounded Decimal formulas. No eval, exec or model calls."""
import ast
import re
from datetime import date
from decimal import Decimal, InvalidOperation, localcontext, ROUND_CEILING, ROUND_FLOOR, ROUND_HALF_UP

CATEGORIES = ["风管", "风机", "风阀", "百叶风口", "外购设备", "消声设备", "防火包裹", "其他材料"]
ATTRIBUTES = {
    "model": "型号", "material": "材质", "flange": "法兰形式",
    "area_basis": "面积口径", "product_state": "成品状态",
    "shape": "直管/异形件", "flange_material": "角钢法兰材质",
    "flange_size": "角钢规格(30/40/50)", "flange_grade": "角钢法兰计算档位值",
    "flange_weight": "旧字段：角钢法兰计算档位值",
    "flange_meters": "角钢实际米数(m)",
    "diameter": "圆风管直径(mm)", "round_flange_count": "圆法兰数量(个)",
    "width": "宽(mm)", "height": "高(mm)", "length": "长(mm)",
    "thickness": "厚度(mm)", "power": "功率(kW)", "airflow": "风量(m³/h)",
    "pressure": "风压(Pa)", "pressure_type": "风压类型", "speed": "转速(r/min)",
}
FEE_LABELS = {"installation": "安装费", "freight": "运费", "tax": "税费"}
FUNCTIONS = {"min", "max", "abs", "ceil", "floor", "round"}
KEY = re.compile(r"^[a-z][a-z0-9_]{0,39}$")
RESERVED = {"cost", "qty", "base", "installation", "freight", "tax"} | FUNCTIONS


class RuleError(ValueError):
    pass


def number(value):
    if isinstance(value, bool) or value is None or str(value).strip() == "":
        raise RuleError("数值为空或类型不正确")
    if len(str(value)) > 60:
        raise RuleError("数值过长")
    try:
        n = Decimal(str(value).strip())
    except InvalidOperation:
        raise RuleError(f"不是有效数值：{str(value)[:40]}")
    if not n.is_finite() or abs(n) > Decimal("1e18") or n.as_tuple().exponent < -100:
        raise RuleError("数值超出支持范围")
    return n


def text_number(n):
    return format(n, "f")


def syntax(expression):
    if not isinstance(expression, str) or not expression.strip():
        raise RuleError("公式未配置")
    if len(expression) > 800:
        raise RuleError("公式过长")
    try:
        tree = ast.parse(expression, mode="eval")
    except (SyntaxError, RecursionError):
        raise RuleError("公式语法不正确")
    if sum(1 for _ in ast.walk(tree)) > 160:
        raise RuleError("公式过于复杂")
    allowed = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Add, ast.Sub, ast.Mult,
               ast.Div, ast.UAdd, ast.USub, ast.Constant, ast.Name, ast.Load, ast.Call)
    for node in ast.walk(tree):
        if not isinstance(node, allowed):
            raise RuleError("公式只支持四则运算、括号和受限函数")
        if isinstance(node, ast.Constant):
            if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
                raise RuleError("公式只允许数字常量")
            number(ast.get_source_segment(expression, node))
        if isinstance(node, ast.Name) and not KEY.fullmatch(node.id):
            raise RuleError("变量名需要小写英文字母、数字或下划线")
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id not in FUNCTIONS or node.keywords:
                raise RuleError("不允许调用此函数")
            count = len(node.args)
            if not count or count > 20 or (node.func.id in {"abs", "ceil", "floor"} and count != 1) or (node.func.id == "round" and count not in (1, 2)):
                raise RuleError("函数参数数量不正确")
    return tree


def variables(expression):
    return {n.id for n in ast.walk(syntax(expression)) if isinstance(n, ast.Name)} - FUNCTIONS


def evaluate(expression, inputs):
    tree = syntax(expression)

    def visit(node):
        if isinstance(node, ast.Constant):
            return number(ast.get_source_segment(expression, node))
        if isinstance(node, ast.Name):
            if node.id not in inputs:
                raise RuleError(f"缺少变量：{node.id}")
            return number(inputs[node.id])
        if isinstance(node, ast.UnaryOp):
            n = visit(node.operand)
            return -n if isinstance(node.op, ast.USub) else n
        if isinstance(node, ast.BinOp):
            a, b = visit(node.left), visit(node.right)
            if isinstance(node.op, ast.Add): return number(a + b)
            if isinstance(node.op, ast.Sub): return number(a - b)
            if isinstance(node.op, ast.Mult): return number(a * b)
            if b == 0: raise RuleError("公式出现除以0")
            return number(a / b)
        args = [visit(a) for a in node.args]
        name = node.func.id
        if name == "min": return min(args)
        if name == "max": return max(args)
        if name == "abs": return abs(args[0])
        if name == "ceil": return args[0].to_integral_value(rounding=ROUND_CEILING)
        if name == "floor": return args[0].to_integral_value(rounding=ROUND_FLOOR)
        digits = args[1] if len(args) == 2 else Decimal(0)
        if digits != int(digits) or not 0 <= digits <= 8:
            raise RuleError("round小数位需为0到8的整数")
        return args[0].quantize(Decimal(1).scaleb(-int(digits)), rounding=ROUND_HALF_UP)

    try:
        with localcontext() as ctx:
            ctx.prec = 48
            return number(visit(tree.body))
    except (InvalidOperation, OverflowError, RecursionError):
        raise RuleError("公式数值无法计算")


def valid_date(value):
    try:
        date.fromisoformat(value)
    except (TypeError, ValueError):
        raise RuleError("请填写有效的生效/失效日期")


def active(resource, day=None):
    body = resource["body"]
    day = day or date.today().isoformat()
    return resource["status"] == "published" and body.get("effective_from", "9999") <= day and (not body.get("effective_to") or day <= body["effective_to"])


def active_catalog(resources, day=None):
    """Newest effective version wins; expiration/retirement never revives old prices."""
    day = day or date.today().isoformat()
    families = {}
    for resource in resources:
        if resource["body"].get("company_id", "guizhou") != "guizhou": continue
        if resource["status"] not in {"published", "retired"} or resource["body"].get("effective_from", "9999") > day:
            continue
        if resource["version"] > families.get(resource["family"], {}).get("version", 0):
            families[resource["family"]] = resource
    return [r for r in families.values() if active(r, day)]


def validate_resource(kind, b, calculation_only=False):
    if not b.get("name", "").strip(): raise RuleError("请填写名称")
    if not calculation_only: valid_date(b.get("effective_from"))
    if b.get("effective_to"):
        valid_date(b["effective_to"])
        if b["effective_to"] < b["effective_from"]: raise RuleError("失效日不能早于生效日")
    if not b.get("source", "").strip(): raise RuleError("请填写规则或价格的正式依据")
    if kind == "duct_catalog":
        from .duct import validate_catalog
        validate_catalog(b)
    elif kind == "price":
        if number(b.get("value")) < 0: raise RuleError("价格不能为负")
        if b.get("price_type") not in {"cost", "sale"}: raise RuleError("请确认采购/生产成本还是销售价")
        if b.get("tax_basis") not in {"included", "excluded"}: raise RuleError("请确认含税状态")
        if not b.get("unit"): raise RuleError("请填写价格单位")
    elif kind == "candidate":
        if b.get("category") not in CATEGORIES or not b.get("attributes", {}).get("model"):
            raise RuleError("请填写产品类别和完整配置中的型号")
        if not isinstance(b.get("attributes"), dict): raise RuleError("产品参数格式不正确")
    elif kind == "rule":
        if b.get("category") not in CATEGORIES: raise RuleError("请选择产品类别")
        if b.get("basis") not in {"cost", "sale"}: raise RuleError("请确认成本换算或直接销售公式")
        if not b.get("input_unit") or not b.get("billing_unit"): raise RuleError("请填写客户单位与计价单位")
        if not calculation_only and b.get("tax_basis") not in {"included", "excluded"}: raise RuleError("请明确结果的含税状态")
        if not calculation_only and (set(b.get("coverage", {})) != {"installation", "freight"} or any(not isinstance(v, bool) for v in b["coverage"].values())):
            raise RuleError("请明确售价是否包含安装、运费")
        if not isinstance(b.get("match", {}), dict): raise RuleError("匹配条件格式不正确")
        params, refs, fields = b.get("parameters", {}), b.get("price_refs", {}), b.get("inputs", [])
        if not isinstance(params, dict) or not isinstance(refs, dict) or not isinstance(fields, list): raise RuleError("变量配置格式不正确")
        names = list(params) + list(refs) + [f.get("key", "") for f in fields]
        if len(set(names)) != len(names): raise RuleError("变量名重复")
        if any(not KEY.fullmatch(n) or n in RESERVED for n in names): raise RuleError("变量名非法或与保留变量重名")
        for v in params.values(): number(v)
        for f in fields:
            if f.get("type") not in {"number", "text"} or not f.get("label"): raise RuleError("请填写变量类型和名称")
            if f.get("source", "input") not in {"input", "candidate"}: raise RuleError("变量来源不正确")
            if f.get("source") == "candidate" and not b.get("selector"): raise RuleError("产品配置变量需要配置选型条件")
        numeric = set(params) | set(refs) | {f["key"] for f in fields if f["type"] == "number"} | {"qty"}
        expressions = [("quantity_expression", numeric), ("sale_expression", numeric | ({"cost"} if b["basis"] == "cost" else set()))]
        if b["basis"] == "cost": expressions.append(("cost_expression", numeric))
        for key, available in expressions:
            unknown = variables(b.get(key, "")) - available
            if unknown: raise RuleError("公式引用未定义变量：" + ", ".join(sorted(unknown)))
        selector = b.get("selector", [])
        for condition in selector:
            if condition.get("op") not in {"eq", "ge", "le"} or condition.get("key") not in {f["key"] for f in fields if f.get("source", "input") == "input"}:
                raise RuleError("选型条件需引用已定义输入，比较方式为eq/ge/le")
    else:
        raise RuleError("不支持的资源类型")


def round_value(value, policy):
    if policy is None: return value
    if policy == "none": return value
    return value.quantize(Decimal(1).scaleb(-int(policy)), rounding=ROUND_HALF_UP)


def validate_policy(policy):
    for key in ("unit_round", "line_round"):
        value = policy.get(key)
        if value is not None and value != "none" and (type(value) is not int or not 0 <= value <= 8):
            raise RuleError("精度需选择未配置、不舍入或0到8位小数")
    if policy.get("total_step") is not None and number(policy["total_step"]) <= 0:
        raise RuleError("整单向上取整粒度必须大于0")
    for key in FEE_LABELS:
        fee = policy.get("fees", {}).get(key, {})
        if fee.get("mode") not in {"pending", "not_applicable", "formula"}: raise RuleError("费用状态不正确")
        if fee["mode"] == "formula":
            params = fee.get("parameters", {})
            if not isinstance(params, dict): raise RuleError("费用参数格式不正确")
            if any(not KEY.fullmatch(k) or k in RESERVED for k in params): raise RuleError("费用参数名非法或保留")
            for value in params.values(): number(value)
            allowed = set(params) | {"base"}
            if key == "freight": allowed.add("installation")
            if key == "tax": allowed |= {"installation", "freight"}
            if variables(fee.get("expression", "")) - allowed: raise RuleError("费用公式引用未定义变量")


def match_rule(row, rules):
    category = row.get("category")
    if category not in CATEGORIES: raise RuleError("待确认：请选择产品类别")
    relevant = [r for r in rules if r["body"].get("category") == category]
    relevant = [r for r in relevant if r["body"].get("input_unit") == row.get("unit")]
    matched = [r for r in relevant if all(str(row.get("attributes", {}).get(k, "")).strip() == str(v).strip() for k, v in r["body"].get("match", {}).items())]
    if not matched: raise RuleError("待配置：没有匹配产品条件及单位的有效规则")
    if len(matched) != 1: raise RuleError("规则冲突：命中多条规则，请领导明确适用条件")
    return matched[0]


def select_candidates(body, attrs, candidates):
    conditions = body.get("selector", [])
    if not conditions: return []
    for c in conditions:
        if attrs.get(c["key"]) in (None, ""): raise RuleError("选型缺少参数：" + c["key"])
    found = []
    for candidate in candidates:
        b = candidate["body"]
        if b.get("category") != body["category"]: continue
        values = b.get("attributes", {})
        try:
            ok = all(str(values.get(k, "")) == str(v) for k, v in body.get("match", {}).items())
            for condition in conditions:
                key, op = condition["key"], condition["op"]
                if key not in values: ok = False; break
                if op == "eq":
                    field = next(f for f in body["inputs"] if f["key"] == key)
                    ok &= number(values[key]) == number(attrs[key]) if field["type"] == "number" else str(values[key]) == str(attrs[key])
                elif op == "ge": ok &= number(values[key]) >= number(attrs[key])
                else: ok &= number(values[key]) <= number(attrs[key])
            if ok: found.append(candidate)
        except RuleError:
            continue
    return found


def calculate_row(row, resources, policy):
    output = {"status": "pending", "reason": "", "unit_price": None, "amount": None, "trace": [], "candidates": []}
    if row.get("excluded"):
        if not row.get("exclude_reason", "").strip(): output["reason"] = "排除计价需要填写原因"; return output
        output.update(status="excluded", reason=row["exclude_reason"])
        return output
    try:
        if not row.get("name", "").strip(): raise RuleError("缺少产品名称，请修正或说明排除原因")
        if row.get("source_issue") and not row.get("source_issue_confirmed"):
            raise RuleError("原文件存在未缓存公式或错误值，请核对原行并确认")
        qty = number(row.get("quantity"))
        if qty <= 0: raise RuleError("数量必须大于0")
        rule = match_rule(row, [r for r in resources if r["kind"] == "rule"])
        b = rule["body"]
        output["rule"] = rule
        attrs = row.get("attributes", {})
        env = {"qty": qty}
        for field in b.get("inputs", []):
            if field.get("source") == "candidate": continue
            value = attrs.get(field["key"])
            if value in (None, ""): raise RuleError("缺少参数：" + field["label"])
            if field["type"] == "number": env[field["key"]] = number(value)
        candidates = select_candidates(b, attrs, [r for r in resources if r["kind"] == "candidate"])
        if b.get("selector"):
            output["candidates"] = candidates
            if not candidates: raise RuleError("选型无匹配：请核对需求和正式参数表")
            picked = next((c for c in candidates if c["id"] == row.get("candidate_id")), None)
            if not picked: raise RuleError("待确认：请从符合条件的候选中确认完整型号配置")
            output["selected_candidate"] = picked
            output["requested_attributes"] = attrs.copy()
            for field in b.get("inputs", []):
                if field.get("source") != "candidate": continue
                actual = picked["body"]["attributes"].get(field["key"])
                if actual in (None, ""): raise RuleError("已选产品配置缺少：" + field["label"])
                if field["type"] == "number": env[field["key"]] = number(actual)
        env.update({k: number(v) for k, v in b.get("parameters", {}).items()})
        output["prices"] = []
        for variable, family in b.get("price_refs", {}).items():
            choices = [p for p in resources if p["kind"] == "price" and p["family"] == family]
            if len(choices) != 1: raise RuleError("价格缺失、过期或版本冲突：" + variable)
            price = choices[0]
            if price["body"]["tax_basis"] != b["tax_basis"]:
                raise RuleError("价格与规则的含税口径不一致，请领导核对")
            env[variable] = number(price["body"]["value"])
            output["prices"].append(price)
        output["inputs"] = {k: text_number(v) for k, v in env.items()}
        if b["basis"] == "cost":
            env["cost"] = evaluate(b["cost_expression"], env)
            if env["cost"] < 0: raise RuleError("计算成本不能为负")
            output["unit_cost"] = text_number(env["cost"])
            output["trace"].append({"step": "单位成本", "expression": b["cost_expression"], "value": text_number(env["cost"])})
        bill_qty = evaluate(b["quantity_expression"], env)
        if bill_qty <= 0: raise RuleError("计费数量必须大于0")
        price = round_value(evaluate(b["sale_expression"], env), policy.get("unit_round"))
        if price < 0: raise RuleError("售价不能为负")
        amount = round_value(price * bill_qty, policy.get("line_round"))
        output["trace"] += [{"step": "计费数量", "expression": b["quantity_expression"], "value": text_number(bill_qty)},
                            {"step": "销售单价", "expression": b["sale_expression"], "value": text_number(price)},
                            {"step": "行金额", "expression": "销售单价 × 计费数量", "value": text_number(amount)}]
        output.update(status="priced", reason="", unit_price=text_number(price), amount=text_number(amount),
                      billing_quantity=text_number(bill_qty), billing_unit=b["billing_unit"], coverage=b["coverage"], tax_basis=b["tax_basis"])
        if "cost" in env: output["profit"] = text_number(amount - env["cost"] * bill_qty)
    except (RuleError, KeyError, TypeError, InvalidOperation) as error:
        output["reason"] = str(error) if isinstance(error, RuleError) else "规则结构不完整，请领导检查配置"
    return output


def calculate(quote, resources, policy):
    if quote.get("pricing_mode") == "duct_v1":
        from .duct import calculate_duct
        return calculate_duct(quote, resources, policy)
    validate_policy(policy)
    resources = active_catalog(resources)
    with localcontext() as ctx:
        ctx.prec = 48
        rows = [calculate_row(row, resources, policy) for row in quote.get("rows", [])]
        priced = [r for r in rows if r["status"] == "priced"]
        subtotal = sum((number(r["amount"]) for r in priced), Decimal(0))
        issues = []
        if not rows: issues.append("清单没有产品行")
        if not priced: issues.append("没有已确定价格的产品")
        pending = sum(r["status"] == "pending" for r in rows)
        if pending: issues.append(f"有{pending}项待确认，未计入小计")
        if policy.get("unit_round") is None or policy.get("line_round") is None or policy.get("total_step") is None:
            issues.append("单价、行金额或整单取整精度待配置")
        fees, env = {}, {}
        for key, label in FEE_LABELS.items():
            cfg = policy["fees"][key]
            covered = lambda r: r["tax_basis"] == "included" if key == "tax" else r["coverage"][key]
            eligible = [r for r in priced if not covered(r)]
            base = sum((number(r["amount"]) for r in eligible), Decimal(0))
            item = {"amount": None, "base": text_number(base), "status": "pending"}
            try:
                if pending or not priced: raise RuleError("产品未全部确定，费用待核对")
                if cfg["mode"] == "pending": raise RuleError("费用规则待配置")
                if cfg["mode"] == "not_applicable":
                    value = Decimal(0)
                    item["reason"] = "已由领导配置为不另收费"
                else:
                    if not eligible:
                        raise RuleError("全部产品已含此费用；请领导核对是否不另收费")
                    value = evaluate(cfg["expression"], {**env, **cfg["parameters"], "base": base})
                    if value < 0: raise RuleError("费用不能为负")
                    item["expression"] = cfg["expression"]
                env[key] = value
                item.update(amount=text_number(value), status="ready")
            except RuleError as error:
                item["reason"] = str(error)
                issues.append(label + "：" + str(error))
            fees[key] = item
        total = rounding = None
        if not issues:
            pre = subtotal + sum(env.values(), Decimal(0))
            step = number(policy["total_step"])
            rounded = (pre / step).to_integral_value(rounding=ROUND_CEILING) * step
            total, rounding = text_number(rounded), text_number(rounded - pre)
        if not policy.get("formal_template_confirmed"):
            issues.append("正式客户模板尚未确认，当前仅可导出通用草稿")
        return {"rows": rows, "subtotal": text_number(subtotal), "pending_count": pending,
                "fees": fees, "total": total, "rounding_difference": rounding,
                "issues": issues, "formal_ready": not issues, "policy": policy, "engine_version": "0.1.0"}
