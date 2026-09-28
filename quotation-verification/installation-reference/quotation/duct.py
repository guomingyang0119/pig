"""Guizhou duct quotation V2 using only a published, versioned catalog."""

import re
from decimal import Decimal, localcontext

from .engine import RuleError, active_catalog, evaluate, number, round_value, text_number
from .installation import installation_enabled, finalize_order


STATES = {"semi", "finished"}
AREA_BASES = {"open", "net"}
FLANGES = {"common", "angle", "round"}
SHAPES = {"semi_unspecified", "straight", "irregular"}
MATERIALS = {"galvanized_40", "galvanized_80", "stainless_201", "stainless_304"}
REQUIRED_RATES = {
    "density_galvanized", "density_stainless",
    "galvanized_semi", "galvanized_straight", "galvanized_irregular",
    "galvanized_1_5_straight", "galvanized_1_5_irregular", "galvanized_narrow_extra",
    "stainless_semi", "stainless_straight", "stainless_irregular",
    "closing_common", "closing_angle", "closing_0_5_extra",
    "accessory_per_sqm", "corner_unit_price", "clip_per_meter_price", "common_punch",
    "net_galvanized_common", "net_galvanized_angle", "net_stainless",
    "material_tax_factor", "installation_tax_factor", "flange_area_factor",
}
FREIGHT_PER_SQM_LEGACY = Decimal("1")  # Confirmed for current quotations; older published catalogs lack this field.
ROUND_FLANGE_PI = Decimal("3.14")
ROUND_FLANGE_UNTAXED = Decimal("10")
ROUND_FLANGE_TAXED = Decimal("12")
ROUND_CLOSING_LEGACY = Decimal("20")  # Confirmed round-flange finished-duct closing fee.
GALVANIZED_1_5_UNSPECIFIED = Decimal("5.5")
OPTIONAL_RATES = {
    "freight_per_sqm", "round_flange_pi", "round_flange_untaxed", "round_flange_taxed",
    "net_round", "closing_round", "galvanized_1_5_unspecified",
    "common_punch_small",  # Retired compatibility field; no longer used in calculations.
}
DEFAULT_PAYMENT_TERMS = [
    {"code": "no_install_cash", "mode": "no_install", "label": "现金", "coefficient": "1.00"},
    {"code": "no_install_days_7", "mode": "no_install", "label": "付款周期≤7天", "coefficient": "1.04"},
    {"code": "no_install_days_15", "mode": "no_install", "label": "付款周期7—15天（不含7天，含15天）", "coefficient": "1.05"},
    {"code": "no_install_days_30", "mode": "no_install", "label": "付款周期15—30天（不含15天，含30天）", "coefficient": "1.06"},
    {"code": "no_install_days_45", "mode": "no_install", "label": "付款周期30—45天（不含30天，含45天）", "coefficient": "1.07"},
    {"code": "with_install_cash", "mode": "with_install", "label": "现金支付（一次性全部付清）", "coefficient": "1.00"},
    {"code": "with_install_progress_70", "mode": "with_install", "label": "付款进度70%", "coefficient": "1.20"},
    {"code": "with_install_progress_80", "mode": "with_install", "label": "付款进度80%", "coefficient": "1.10"},
]
DUCT_ENGINE_VERSION = "duct-v16"
REQUIRED_EXPRESSIONS = {"material_price", "body_sale", "flange_per_meter", "flange_per_area"}
INSTALLATION_NOTES = [
    "不含风机控制箱及控制箱到风机的电线、电器。",
    "不含风机基础预制的制作；不含抗震支架。",
    "不含设备及管道预留洞孔的开启与修复。",
]


def validate_catalog(body):
    if not isinstance(body, dict) or not body.get("name") or not body.get("source"):
        raise RuleError("请填写风管指标名称与依据")
    materials, flanges, rates, expressions = (body.get(k) for k in ("materials", "flanges", "rates", "expressions"))
    if not isinstance(materials, list) or not isinstance(flanges, list) or not isinstance(rates, dict) or not isinstance(expressions, dict):
        raise RuleError("风管指标结构不完整")
    if not REQUIRED_RATES.issubset(rates) or not set(rates).issubset(REQUIRED_RATES | OPTIONAL_RATES) or set(expressions) != REQUIRED_EXPRESSIONS:
        raise RuleError("风管成本参数或运算规则缺失、重复或未识别")
    for value in rates.values():
        if number(value) <= 0:
            raise RuleError("风管价格、费用和系数必须大于0")
    from .engine import variables
    allowed = set(rates) | {
        "ton_price", "density", "thickness", "material_price", "processing", "closing",
        "accessory_per_sqm", "area_factor", "flange_grade", "flange_processing", "flange_per_meter",
    }
    for expression in expressions.values():
        if not isinstance(expression, str) or not expression.strip():
            raise RuleError("运算规则不能为空")
        if variables(expression) - allowed:
            raise RuleError("风管运算规则引用了未知变量")
    seen = set()
    for item in materials:
        if item.get("material") not in MATERIALS:
            raise RuleError("材料类型未识别")
        thickness = number(item.get("thickness"))
        if thickness <= 0 or number(item.get("ton_price")) <= 0:
            raise RuleError("材料厚度或吨价无效")
        key = item["material"], thickness
        if key in seen:
            raise RuleError("材料规格重复，无法唯一取价")
        seen.add(key)
    seen = set()
    for item in flanges:
        if item.get("material") not in {"black", "hot_galvanized"} or item.get("size") not in {30, 40, 50}:
            raise RuleError("角钢法兰材质或规格未识别")
        grade = number(item.get("grade", item.get("kg_per_bar")))
        if grade <= 0 or number(item.get("ton_price")) <= 0 or number(item.get("processing_per_m")) <= 0:
            raise RuleError("角钢法兰计算档位或价格无效")
        key = item["material"], item["size"], grade
        if key in seen:
            raise RuleError("角钢法兰规格重复，无法唯一取价")
        seen.add(key)
    payment_terms = body.get("payment_terms")
    if payment_terms is not None:
        if not isinstance(payment_terms, list):
            raise RuleError("付款系数规则格式不正确")
        expected = {item["code"]: item for item in DEFAULT_PAYMENT_TERMS}
        if len(payment_terms) != len(expected) or {item.get("code") for item in payment_terms} != set(expected):
            raise RuleError("付款系数规则缺失、重复或包含未识别方案")
        for item in payment_terms:
            reference = expected[item["code"]]
            if any(item.get(key) != reference[key] for key in ("mode", "label")):
                raise RuleError("付款方案名称或适用场景不可擅自修改")
            if number(item.get("coefficient")) <= 0:
                raise RuleError("付款系数必须大于0")


def _catalog_payment_terms(catalog):
    terms = catalog.get("payment_terms")
    expected_codes = {item["code"] for item in DEFAULT_PAYMENT_TERMS}
    if isinstance(terms, list) and len(terms) == len(expected_codes) and {item.get("code") for item in terms} == expected_codes:
        return terms
    # Published catalogs using the discarded matrix must never revive the old rules.
    return DEFAULT_PAYMENT_TERMS


def _payment_selection(options, catalog):
    code = str(options.get("payment_option") or "").strip()
    if not code:
        raise RuleError("待确认：请选择整单付款方式")
    matches = [item for item in _catalog_payment_terms(catalog) if item.get("code") == code]
    if len(matches) != 1:
        raise RuleError("待确认：付款方案不存在或配置冲突")
    selected = matches[0]
    mode = "with_install" if options.get("include_installation") is True else "no_install"
    if selected.get("mode") != mode:
        raise RuleError("待确认：安装状态已变化，请重新选择对应的整单付款方式")
    try:
        coefficient = _positive(selected.get("coefficient"), "付款系数")
    except (KeyError, TypeError, ValueError):
        raise RuleError("付款系数配置缺失，请领导核对指标")
    return {
        "code": code, "mode": mode, "label": selected.get("label", code),
        "coefficient": coefficient,
    }


def _value(row, key):
    return row.get("attributes", {}).get(key)


def _positive(value, label):
    result = number(value)
    if result <= 0:
        raise RuleError(label + "必须大于0")
    return result


def _nonnegative(value, label):
    result = number(value)
    if result < 0:
        raise RuleError(label + "不能小于0")
    return result


def _material(row, options):
    explicit = str(_value(row, "material") or "").strip()
    description = row.get("name", "") + " " + row.get("spec", "")
    aliases = {
        "镀锌板": "galvanized", "40g镀锌板": "galvanized_40", "80g镀锌板": "galvanized_80",
        "201不锈钢": "stainless_201", "304不锈钢": "stainless_304",
    }
    explicit = aliases.get(explicit, explicit)
    if explicit in MATERIALS:
        return explicit
    source = explicit or description
    if "201" in source and "不锈钢" in source:
        return "stainless_201"
    if "304" in source and "不锈钢" in source:
        return "stainless_304"
    if "不锈钢" in source:
        raise RuleError("待确认：请选择201或304不锈钢")
    if "镀锌" in source or explicit == "galvanized":
        if re.search(r"80\s*[gG]", source):
            return "galvanized_80"
        if re.search(r"40\s*[gG]", source):
            return "galvanized_40"
        layer = str(options.get("zinc_layer") or "40")
        if layer not in {"40", "80"}:
            raise RuleError("待确认：请选择40g或80g锌层")
        return "galvanized_" + layer
    raise RuleError("待确认：请明确风管材质")


def _thickness(row):
    value = _value(row, "thickness")
    if value not in (None, ""):
        return number(value)
    description = row.get("spec", "") + " " + row.get("name", "")
    matches = re.findall(r"(?<!\d)(0\.5|0\.6|0\.75|1\.0|1\.2|1\.5|2\.0|1|2)\s*(?:mm|毫米|厚)?(?!\d)", description, re.I)
    values = {number(x) for x in matches}
    if len(values) == 1:
        return values.pop()
    raise RuleError("待确认：请明确风管厚度(mm)")


def _shape(row, options):
    aliases = {"直管": "straight", "异形件": "irregular",
               "不区分": "semi_unspecified", "半成品不区分": "semi_unspecified"}
    selected = aliases.get(options.get("shape"), options.get("shape"))
    explicit = aliases.get(_value(row, "shape"), _value(row, "shape"))
    description = row.get("name", "") + " " + row.get("spec", "")
    described = "straight" if "直管" in description and "异形" not in description else "irregular" if "异形" in description and "直管" not in description else None
    customer_value = explicit or described
    if customer_value in SHAPES:
        return customer_value
    return selected if selected in SHAPES else "semi_unspecified"


def _effective_flange(row, options):
    aliases = {"共板法兰": "common", "共板": "common", "角钢法兰": "angle", "角钢": "angle",
               "角铁法兰": "angle", "角铁": "angle", "圆法兰": "round", "圆形法兰": "round"}
    explicit = aliases.get(_value(row, "flange"), _value(row, "flange"))
    description = row.get("name", "") + " " + row.get("spec", "")
    described = "round" if "圆法兰" in description else "angle" if "角钢法兰" in description else "common" if "共板法兰" in description else None
    selected = explicit or described or options.get("flange")
    if selected == "mixed":
        raise RuleError("待确认：混合法兰模式下请明确本行法兰形式")
    if selected not in FLANGES:
        raise RuleError("待确认：请选择共板、角钢或圆法兰")
    return selected


def _effective_state(row, options):
    aliases = {"半成品": "semi", "成品": "finished"}
    explicit = aliases.get(_value(row, "product_state"), _value(row, "product_state"))
    description = " ".join((row.get("name", ""), row.get("spec", ""), row.get("remark", "")))
    described = "semi" if "半成品" in description else "finished" if "成品" in description else None
    selected = explicit or described or options.get("product_state")
    if selected not in STATES:
        raise RuleError("待确认：请选择半成品或成品")
    return selected


def _dimensions(row):
    width, height = _value(row, "width"), _value(row, "height")
    if width not in (None, "") and height not in (None, ""):
        return _positive(width, "宽"), _positive(height, "高")
    description = row.get("spec", "") + " " + str(_value(row, "model") or "")
    match = re.search(r"(?<!\d)(\d{2,4})\s*[x×*]\s*(\d{2,4})(?!\d)", description, re.I)
    if match:
        return number(match.group(1)), number(match.group(2))
    return None


def _is_small_rectangular(row):
    dimensions = _dimensions(row)
    return bool(dimensions and sum(dimensions) <= 520)


def _processing(row, options, state, material, thickness, rates):
    shape = _shape(row, options)
    if material.startswith("stainless"):
        processing = (rates["stainless_semi"] if shape == "semi_unspecified"
                      else rates["stainless_" + shape])
    elif shape == "semi_unspecified":
        processing = (rates.get("galvanized_1_5_unspecified", GALVANIZED_1_5_UNSPECIFIED)
                      if thickness == Decimal("1.5") else rates["galvanized_semi"])
    else:
        suffix = "straight" if shape == "straight" else "irregular"
        key = "galvanized_1_5_" + suffix if thickness == Decimal("1.5") else "galvanized_" + suffix
        processing = rates[key]
    small_rectangular_extra = rates["galvanized_narrow_extra"] if _is_small_rectangular(row) else Decimal(0)
    return processing + small_rectangular_extra, shape, small_rectangular_extra


def _area_factor(material, flange, area_basis, rates):
    if area_basis == "open":
        return Decimal(1)
    if flange == "round":
        return rates.get("net_round", rates["net_galvanized_common"])
    if material.startswith("stainless"):
        return rates["net_stainless"]
    return rates["net_galvanized_angle" if flange == "angle" else "net_galvanized_common"]


def _flange_material(row):
    aliases = {"黑角铁": "black", "黑角钢": "black", "热镀锌角铁": "hot_galvanized", "热镀锌角钢": "hot_galvanized"}
    value = aliases.get(_value(row, "flange_material"), _value(row, "flange_material"))
    description = row.get("name", "") + " " + row.get("spec", "")
    if value in {"black", "hot_galvanized"}:
        return value
    if "热镀锌角" in description:
        return "hot_galvanized"
    if "黑角" in description:
        return "black"
    return "black"


def _flange_size(row, thickness):
    value = _value(row, "flange_size")
    if value not in (None, ""):
        try:
            size = int(str(value).replace("角钢", "").strip())
        except ValueError:
            raise RuleError("待确认：角钢规格必须是30、40或50")
        if size not in {30, 40, 50}:
            raise RuleError("待确认：角钢规格必须是30、40或50")
        return size
    description = row.get("name", "") + " " + row.get("spec", "")
    explicit = re.search(r"(?<!\d)(30|40|50)\s*(?:角钢|角铁|法兰)", description)
    if explicit:
        return int(explicit.group(1))
    dimensions = _dimensions(row)
    if dimensions and max(dimensions) >= 1600:
        return 40
    if thickness in {Decimal("0.5"), Decimal("0.6"), Decimal("0.75"), Decimal("1"), Decimal("1.2")}:
        return 30
    if thickness in {Decimal("1.5"), Decimal("2")}:
        return 40
    raise RuleError("待确认：此厚度没有默认角钢规格")


def _flange_grade(row, size):
    value = _value(row, "flange_grade")
    if value in (None, ""):
        value = _value(row, "flange_weight")
    if value not in (None, ""):
        return _positive(value, "角钢法兰计算档位")
    description = row.get("name", "") + " " + row.get("spec", "")
    explicit = re.search(r"(?<!\d)(\d+(?:\.\d+)?)\s*档", description)
    if explicit:
        return number(explicit.group(1))
    if size == 30:
        return Decimal("7.5")
    if size == 40:
        return Decimal("12.5")
    raise RuleError("待确认：50角钢必须明确计算档位")


def _semi_accessories(options, rates, has_common, has_semi_common,
                      tax_factor, settlement_factor):
    """Return fallback per-square-metre charge and separately priced whole-quote material items."""
    if not has_common:
        return Decimal(0), [], "not_applicable", None
    corner_raw, clip_raw = options.get("corner_count"), options.get("clip_meters")
    corner_blank, clip_blank = corner_raw in (None, ""), clip_raw in (None, "")
    if corner_blank and clip_blank or not has_semi_common:
        return rates["accessory_per_sqm"], [], "per_sqm", None
    items = []
    definitions = (
        ("corner", "角码", corner_raw, corner_blank, "个", "角码数量", rates["corner_unit_price"]),
        ("clip", "卡扣", clip_raw, clip_blank, "米", "卡扣长度", rates["clip_per_meter_price"]),
    )
    for code, name, raw, blank, unit, label, base_unit in definitions:
        if blank:
            continue
        quantity = _nonnegative(raw, label)
        untaxed_unit = round_value(base_unit * settlement_factor, 1)
        unit_price = round_value(base_unit * settlement_factor * tax_factor, 1)
        untaxed_amount = round_value(untaxed_unit * quantity, 1)
        amount = round_value(unit_price * quantity, 1)
        items.append({
            "code": code, "name": name, "unit": unit, "quantity": text_number(quantity),
            "base_unit_price": text_number(base_unit), "untaxed_unit_price": text_number(untaxed_unit),
            "unit_price": text_number(unit_price), "untaxed_amount": text_number(untaxed_amount),
            "amount": text_number(amount), "tax_amount": text_number(amount - untaxed_amount),
        })
    return Decimal(0), items, "actual", None


def _whole_quote_factor(rates, area_basis, families, galvanized_rate, label):
    if not families:
        raise RuleError(f"待确认：{label}没有对应到已识别的风管材质")
    if area_basis != "net":
        return Decimal(1)
    if len(families) > 1:
        raise RuleError(f"待确认：{label}同时对应镀锌和不锈钢风管，无法确定净面积系数，请按材质分开填写")
    return rates["net_stainless" if "stainless" in families else galvanized_rate]


def _round_diameter(row):
    value = _value(row, "diameter")
    if value not in (None, ""):
        return _positive(value, "圆风管直径")
    description = " ".join((row.get("spec", ""), str(_value(row, "model") or ""), row.get("name", "")))
    match = re.search(r"(?:Φ|φ|Ø|直径)\s*(\d+(?:\.\d+)?)", description, re.I)
    if not match:
        raise RuleError("客户清单未提供圆风管直径，请报价人员填写")
    return _positive(match.group(1), "圆风管直径")


def _round_flange(row, options, rates, settlement_factor):
    tax_included = options.get("tax_included")
    if tax_included not in (True, False):
        raise RuleError("请先选择整单含税或不含税")
    diameter_mm = _round_diameter(row)
    pi = rates.get("round_flange_pi", ROUND_FLANGE_PI)
    untaxed_rate = rates.get("round_flange_untaxed", ROUND_FLANGE_UNTAXED)
    rate = rates.get("round_flange_taxed", ROUND_FLANGE_TAXED) if tax_included else untaxed_rate
    circumference = pi * diameter_mm / Decimal("1000")
    count = _value(row, "round_flange_count")
    if count in (None, ""):
        area_factor = rates["flange_area_factor"]
        return {
            "basis": "round_area", "diameter": diameter_mm, "count": None, "rate": rate,
            "untaxed_area_raw": circumference * untaxed_rate * area_factor,
            "area_raw": circumference * rate * area_factor,
            "untaxed_unit_price": None, "unit_price": None,
            "untaxed_amount": None, "amount": None,
        }
    count = _positive(count, "圆法兰数量")
    untaxed_unit_price = round_value(
        circumference * untaxed_rate * settlement_factor, 1)
    unit_price = round_value(circumference * rate * settlement_factor, 1)
    untaxed_amount = round_value(untaxed_unit_price * count, 1)
    amount = round_value(unit_price * count, 1)
    return {
        "basis": "round_count", "diameter": diameter_mm, "count": count, "rate": rate,
        "untaxed_area_raw": Decimal(0), "area_raw": Decimal(0),
        "untaxed_unit_price": untaxed_unit_price, "unit_price": unit_price,
        "untaxed_amount": untaxed_amount, "amount": amount,
    }


def _aggregate_flange_prices(options, catalog, rates, expressions,
                             tax_factor, settlement_factor):
    """Price explicit whole-quote 30#/40# meter totals using Guizhou black angle steel."""
    items = []
    for size, grade in ((30, Decimal("7.5")), (40, Decimal("12.5"))):
        raw = options.get(f"flange_{size}_meters")
        if raw in (None, ""):
            continue
        meters = _positive(raw, f"整单{size}#角钢法兰米数")
        matches = [item for item in catalog["flanges"]
                   if item["material"] == "black" and item["size"] == size
                   and number(item.get("grade", item.get("kg_per_bar"))) == grade]
        if len(matches) != 1:
            raise RuleError(f"{size}#黑角钢法兰价格缺失或档位冲突")
        selected = matches[0]
        env = {**rates, "flange_grade": grade, "ton_price": number(selected["ton_price"]),
               "flange_processing": number(selected["processing_per_m"])}
        base_unit_price = evaluate(expressions["flange_per_meter"], env)
        untaxed_unit_price = round_value(base_unit_price * settlement_factor, 1)
        unit_price = round_value(base_unit_price * settlement_factor * tax_factor, 1)
        untaxed_amount = round_value(untaxed_unit_price * meters, 1)
        amount = round_value(unit_price * meters, 1)
        items.append({"size": size, "name": selected.get("name") or f"{size}#黑角钢法兰",
                      "meters": text_number(meters), "unit_price": text_number(unit_price),
                      "base_unit_price": text_number(base_unit_price),
                      "untaxed_unit_price": text_number(untaxed_unit_price),
                      "untaxed_amount": text_number(untaxed_amount),
                      "amount": text_number(amount), "tax_amount": text_number(amount - untaxed_amount),
                      "source": "客户清单整单实际米数"})
    return items


def calculate_duct_row(row, options, catalog, catalog_resource, payment=None,
                       accessory_per_sqm=Decimal(0), aggregate_flange_mode=False):
    output = {"status": "pending", "reason": "", "unit_price": None, "amount": None,
              "trace": [], "candidates": [], "billing_unit": "㎡"}
    if row.get("excluded"):
        if not row.get("exclude_reason", "").strip():
            output["reason"] = "排除计价需要填写原因"
            return output
        output.update(status="excluded", reason=row["exclude_reason"])
        return output
    try:
        if row.get("source_issue") and not row.get("source_issue_confirmed"):
            raise RuleError("原文件存在未缓存公式或错误值，请先核对")
        if row.get("category") not in ("", "风管") or (row.get("category") == "" and "风管" not in row.get("name", "")):
            raise RuleError("首版仅对风管类自动报价")
        if not row.get("name", "").strip():
            raise RuleError("缺少产品名称")
        if catalog is None:
            raise RuleError("新版风管指标尚未发布，不能使用旧规则报价")
        if payment is None:
            raise RuleError("待确认：请选择与安装状态对应的整单付款方式")
        if options.get("tax_included") not in (True, False):
            raise RuleError("待确认：请选择整单含税或不含税")
        area_basis, state, flange = options.get("area_basis"), _effective_state(row, options), _effective_flange(row, options)
        if area_basis not in AREA_BASES:
            raise RuleError("待确认：请选择开板面积或净面积")
        punch_required = options.get("include_common_punch")
        if flange == "common" and punch_required is not True and punch_required is not False:
            raise RuleError("待确认：请选择共板法兰是否需要打孔")
        material, thickness = _material(row, options), _thickness(row)
        area = _positive(row.get("engineering_quantity"), "工程量总面积")
        matches = [m for m in catalog["materials"] if m["material"] == material and number(m["thickness"]) == thickness]
        if len(matches) != 1:
            raise RuleError("材料价格缺失或冲突：" + material + " " + text_number(thickness) + "mm")
        rates = {k: number(v) for k, v in catalog["rates"].items()}
        expressions = catalog["expressions"]
        tax_factor = rates["material_tax_factor"] if options.get("tax_included") is True else Decimal(1)
        freight_per_sqm = (rates.get("freight_per_sqm", FREIGHT_PER_SQM_LEGACY)
                           if options.get("include_freight") is True else Decimal(0))
        processing, shape, small_rectangular_extra = _processing(row, options, state, material, thickness, rates)
        small_rectangular = _is_small_rectangular(row)
        closing = Decimal(0)
        if state == "finished":
            if flange == "angle":
                closing = rates["closing_angle"]
            elif flange == "round":
                closing = rates.get("closing_round", ROUND_CLOSING_LEGACY)
            else:
                closing = rates["closing_common"]
            if thickness == Decimal("0.5"):
                closing += rates["closing_0_5_extra"]
        density = rates["density_stainless" if material.startswith("stainless") else "density_galvanized"]
        area_factor = _area_factor(material, flange, area_basis, rates)
        row_accessory_per_sqm = accessory_per_sqm if flange == "common" else Decimal(0)
        env = {**rates, "ton_price": number(matches[0]["ton_price"]), "density": density,
               "thickness": thickness, "processing": processing, "closing": closing,
               "accessory_per_sqm": row_accessory_per_sqm, "area_factor": area_factor}
        material_price = evaluate(expressions["material_price"], env)
        env["material_price"] = material_price
        # The selected net-area coefficient applies once to the complete
        # supplied-material price.  Evaluate the body without it first so
        # punch, flange and freight can join the same coefficient scope.
        body_unfactored = evaluate(expressions["body_sale"], {**env, "area_factor": Decimal(1)})
        body_raw = body_unfactored * area_factor
        flange_per_meter = flange_per_area = flange_amount = Decimal(0)
        round_flange_diameter = round_flange_count = round_flange_rate = None
        round_flange_untaxed_unit_price = round_flange_untaxed_amount = None
        round_flange_unit_price = round_flange_amount = None
        round_flange_area_raw = round_flange_untaxed_area_raw = Decimal(0)
        round_flange_area_unit_price = round_flange_untaxed_area_unit_price = None
        round_flange_basis = None
        selected_flange = None
        flange_meters = None
        use_actual_meters = False
        row_aggregate_flange_mode = aggregate_flange_mode and flange == "angle"
        punch_fee = Decimal(0)
        if flange == "common":
            if punch_required:
                punch_fee = rates["common_punch"]
            combined_unfactored = body_unfactored + punch_fee
        elif flange == "angle":
            raw_meters = _value(row, "flange_meters")
            if row_aggregate_flange_mode:
                if raw_meters not in (None, ""):
                    raise RuleError("已使用整单30#/40#法兰米数，本行不能再填写法兰米数")
                combined_unfactored = body_unfactored
            else:
                flange_material = _flange_material(row)
                size, grade = _flange_size(row, thickness), None
                grade = _flange_grade(row, size)
                flange_matches = [f for f in catalog["flanges"] if f["material"] == flange_material and f["size"] == size
                                   and number(f.get("grade", f.get("kg_per_bar"))) == grade]
                if len(flange_matches) != 1:
                    raise RuleError("角钢法兰价格缺失或档位冲突，请核对材质、规格和计算档位")
                selected_flange = flange_matches[0]
                flange_env = {**rates, "flange_grade": grade, "ton_price": number(selected_flange["ton_price"]),
                              "flange_processing": number(selected_flange["processing_per_m"])}
                flange_per_meter = evaluate(expressions["flange_per_meter"], flange_env)
            if not row_aggregate_flange_mode and raw_meters not in (None, ""):
                flange_meters = _positive(raw_meters, "角钢法兰实际米数")
                use_actual_meters, combined_unfactored = True, body_unfactored
            elif not row_aggregate_flange_mode:
                flange_env["flange_per_meter"] = flange_per_meter
                flange_per_area = evaluate(expressions["flange_per_area"], flange_env)
                combined_unfactored = body_unfactored + flange_per_area
        else:
            combined_unfactored = body_unfactored
            round_details = _round_flange(row, options, rates, area_factor)
            round_flange_basis = round_details["basis"]
            round_flange_diameter = round_details["diameter"]
            round_flange_count = round_details["count"]
            round_flange_rate = round_details["rate"]
            round_flange_untaxed_area_raw = round_details["untaxed_area_raw"]
            round_flange_area_raw = round_details["area_raw"]
            round_flange_untaxed_unit_price = round_details["untaxed_unit_price"]
            round_flange_unit_price = round_details["unit_price"]
            round_flange_untaxed_amount = round_details["untaxed_amount"]
            round_flange_amount = round_details["amount"]
        # Freight and every applicable flange/accessory item join the material
        # price before the net-area coefficient.  Round flange keeps its
        # confirmed 10/12 yuan-per-metre tax-specific rate, but is within the
        # same settlement coefficient scope.
        combined_unfactored += freight_per_sqm
        untaxed_unit_raw = (combined_unfactored + round_flange_untaxed_area_raw) * area_factor
        selected_unit_raw = (combined_unfactored * tax_factor + round_flange_area_raw) * area_factor
        untaxed_unit_price = round_value(untaxed_unit_raw, 1)
        unit_price = round_value(selected_unit_raw, 1)
        if round_flange_basis == "round_area":
            round_flange_untaxed_area_unit_price = round_value(
                round_flange_untaxed_area_raw * area_factor, 1)
            round_flange_area_unit_price = round_value(
                round_flange_area_raw * area_factor, 1)
        untaxed_body_amount = round_value(untaxed_unit_price * area, 1)
        body_amount = round_value(unit_price * area, 1)
        untaxed_amount, amount, flange_unit_price = untaxed_body_amount, body_amount, None
        flange_untaxed_unit_price = flange_untaxed_amount = None
        if use_actual_meters:
            flange_untaxed_unit_price = round_value(
                flange_per_meter * area_factor, 1)
            flange_unit_price = round_value(
                flange_per_meter * area_factor * tax_factor, 1)
            flange_untaxed_amount = round_value(flange_untaxed_unit_price * flange_meters, 1)
            flange_amount = round_value(flange_unit_price * flange_meters, 1)
            untaxed_amount += flange_untaxed_amount
            amount += flange_amount
        if round_flange_amount is not None:
            untaxed_amount += round_flange_untaxed_amount
            amount += round_flange_amount
        adjusted_flange_per_area = (round_value(
                                    flange_per_area * area_factor * tax_factor, 1)
                                    if flange == "angle" and not use_actual_meters and not row_aggregate_flange_mode else None)
        output.update(
            status="priced", unit_price=text_number(unit_price), amount=text_number(amount),
            billing_quantity=text_number(area), body_amount=text_number(body_amount),
            untaxed_unit_price=text_number(untaxed_unit_price), untaxed_amount=text_number(untaxed_amount),
            tax_amount=text_number(amount - untaxed_amount),
            body_unit_price=text_number(round_value(body_raw * tax_factor, 1)),
            body_base_unit_price=text_number(body_raw),
            flange_unit_price=text_number(flange_unit_price) if flange_unit_price is not None else None,
            flange_untaxed_unit_price=text_number(flange_untaxed_unit_price) if flange_untaxed_unit_price is not None else None,
            flange_per_area=text_number(adjusted_flange_per_area) if adjusted_flange_per_area is not None else None,
            flange_per_area_base=text_number(flange_per_area) if adjusted_flange_per_area is not None else None,
            flange_amount=text_number(flange_amount) if use_actual_meters else None,
            flange_untaxed_amount=text_number(flange_untaxed_amount) if use_actual_meters else None,
            flange_meters=text_number(flange_meters) if use_actual_meters else None,
            round_flange_diameter=text_number(round_flange_diameter) if round_flange_diameter is not None else None,
            round_flange_count=text_number(round_flange_count) if round_flange_count is not None else None,
            round_flange_rate=text_number(round_flange_rate) if round_flange_rate is not None else None,
            round_flange_untaxed_unit_price=text_number(round_flange_untaxed_unit_price) if round_flange_untaxed_unit_price is not None else None,
            round_flange_unit_price=text_number(round_flange_unit_price) if round_flange_unit_price is not None else None,
            round_flange_untaxed_amount=text_number(round_flange_untaxed_amount) if round_flange_untaxed_amount is not None else None,
            round_flange_amount=text_number(round_flange_amount) if round_flange_amount is not None else None,
            round_flange_area_unit_price=(text_number(round_flange_area_unit_price)
                                          if round_flange_area_unit_price is not None else None),
            round_flange_untaxed_area_unit_price=(text_number(round_flange_untaxed_area_unit_price)
                                                  if round_flange_untaxed_area_unit_price is not None else None),
            flange_basis="aggregate_meters" if row_aggregate_flange_mode else "actual_meters" if use_actual_meters else "per_area" if flange == "angle" else round_flange_basis if flange == "round" else "common_punch" if punch_required else "common_no_punch",
            selected_material=matches[0], selected_flange=selected_flange, selected_shape=shape,
            effective_flange=flange, effective_product_state=state,
            area_factor=text_number(area_factor), rule=catalog_resource,
            payment_coefficient=text_number(payment["coefficient"]), payment_applied=False,
            coverage={"installation": False, "freight": options.get("include_freight") is True},
            tax_basis="included" if options.get("tax_included") is True else "excluded",
            trace=[
                {"step": "铁皮材料价/㎡", "expression": expressions["material_price"], "value": text_number(material_price)},
                {"step": "风管基础加工费/㎡", "expression": "按材质、厚度及直管/异形件/不区分类型取值；半成品与成品使用同一套加工费", "value": text_number(processing - small_rectangular_extra)},
                {"step": "口型风管A+B≤520附加加工费/㎡", "expression": "能识别A×B且A+B≤520mm时，半/成品及直管/异形件均加收", "value": text_number(small_rectangular_extra)},
                {"step": "成品合管费/㎡", "expression": "半成品为0；成品按法兰形式和0.5mm附加费", "value": text_number(closing)},
                {"step": "按㎡角码卡扣/㎡", "expression": "仅共板法兰且无整单实际数量时使用", "value": text_number(row_accessory_per_sqm)},
                {"step": "净面积系数后风管本体/㎡", "expression": expressions["body_sale"], "value": text_number(body_raw)},
                {"step": "运费/㎡", "expression": "选择包含运费时计入材料单价；否则为0", "value": text_number(freight_per_sqm)},
                {"step": "面积结算系数", "expression": "开板面积取1；净面积时对材料本体、加工费、合管费、角码卡扣、打孔费、法兰和运费统一乘对应系数", "value": text_number(area_factor)},
                {"step": "整单付款条件", "expression": payment["label"] + "；材料与安装汇总后统一应用一次，本行金额未乘付款系数", "value": text_number(payment["coefficient"])},
                {"step": "材料含税系数", "expression": "含税选择1.1，不含税选择1", "value": text_number(tax_factor)},
            ],
        )
        if flange == "common":
            output["trace"].append({
                "step": "共板打孔费/㎡",
                "expression": ("报价人员确认需要打孔：统一按1元/㎡"
                               if punch_required else "报价人员确认不需要打孔"),
                "value": text_number(punch_fee),
            })
        elif flange == "round":
            if round_flange_basis == "round_count":
                output["trace"].extend([
                    {"step": "圆法兰每个销售价", "expression": ("3.14×直径(m)×含税12元/米×面积结算系数（付款前）" if options.get("tax_included") is True else "3.14×直径(m)×不含税10元/米×面积结算系数（付款前）"), "value": text_number(round_flange_unit_price)},
                    {"step": "圆法兰金额", "expression": "四舍五入后的每个价格×客户清单圆法兰数量", "value": text_number(round_flange_amount)},
                ])
            else:
                output["trace"].append({
                    "step": "圆法兰折算价/㎡",
                    "expression": ("3.14×直径(m)×含税12元/米×1.72×面积结算系数（付款前）"
                                   if options.get("tax_included") is True
                                   else "3.14×直径(m)×不含税10元/米×1.72×面积结算系数（付款前）"),
                    "value": text_number(round_flange_area_unit_price),
                })
        elif row_aggregate_flange_mode:
            output["trace"].append({"step": "角钢法兰", "expression": "整单30#/40#实际米数另行计价，本行不使用1.72换算", "value": "整单另计"})
        elif use_actual_meters:
            output["trace"].extend([
                {"step": "角钢法兰基础综合价/米", "expression": expressions["flange_per_meter"], "value": text_number(flange_per_meter)},
                {"step": "角钢法兰对客单价/米", "expression": "基础综合价×面积结算系数（付款前）×含税系数后四舍五入", "value": text_number(flange_unit_price)},
                {"step": "角钢法兰金额", "expression": "四舍五入后的对客每米价×实际米数", "value": text_number(flange_amount)},
            ])
        else:
            output["trace"].extend([
                {"step": "角钢法兰基础综合价/米", "expression": expressions["flange_per_meter"], "value": text_number(flange_per_meter)},
                {"step": "角钢法兰基础折算价/㎡", "expression": expressions["flange_per_area"], "value": text_number(flange_per_area)},
            ])
        output["trace"].append({"step": "风管综合销售单价/㎡", "expression": "各项合计后四舍五入保留一位", "value": text_number(unit_price)})
    except (RuleError, ValueError, TypeError, KeyError) as error:
        output["reason"] = str(error) if isinstance(error, RuleError) else "风管输入或指标不完整，请核对"
    return output


def calculate_duct(quote, resources, policy, supply_only=False):
    options = {**quote.get("duct_options", {}), "include_installation": installation_enabled(quote)}
    catalogs = [r for r in active_catalog(resources) if r["kind"] == "duct_catalog"]
    catalog_resource = catalogs[0] if len(catalogs) == 1 else None
    catalog = catalog_resource["body"] if catalog_resource else None
    with localcontext() as ctx:
        ctx.prec = 48
        issues = []
        if len(catalogs) != 1:
            issues.append("新版风管指标未发布或版本冲突")
        rates = {k: number(v) for k, v in catalog["rates"].items()} if catalog else None
        payment = None
        if catalog:
            try:
                payment = _payment_selection(options, catalog)
            except RuleError as error:
                issues.append(str(error))
        tax_factor = (rates["material_tax_factor"] if rates and options.get("tax_included") is True
                      else Decimal(1))
        has_common = has_semi_common = False
        angle_families, semi_common_families = set(), set()
        for source_row in quote.get("rows", []):
            if source_row.get("excluded"):
                continue
            category = source_row.get("category")
            if category not in ("", "风管") or (category == "" and "风管" not in source_row.get("name", "")):
                continue
            try:
                material = _material(source_row, options)
                flange = _effective_flange(source_row, options)
                state = _effective_state(source_row, options)
            except RuleError:
                continue
            family = "stainless" if material.startswith("stainless") else "galvanized"
            has_common = has_common or flange == "common"
            has_semi_common = has_semi_common or flange == "common" and state == "semi"
            if flange == "angle":
                angle_families.add(family)
            if flange == "common" and state == "semi":
                semi_common_families.add(family)
        aggregate_flange_mode = any(options.get(key) not in (None, "") for key in ("flange_30_meters", "flange_40_meters"))
        aggregate_flanges, aggregate_flange_amount = [], Decimal(0)
        aggregate_flange_untaxed_amount = Decimal(0)
        if aggregate_flange_mode:
            if catalog and rates and payment:
                try:
                    angle_settlement_factor = _whole_quote_factor(
                        rates, options.get("area_basis"), angle_families,
                        "net_galvanized_angle", "整单角钢法兰实际米数")
                    aggregate_flanges = _aggregate_flange_prices(
                        options, catalog, rates, catalog["expressions"],
                        tax_factor, angle_settlement_factor)
                    aggregate_flange_amount = sum((number(item["amount"]) for item in aggregate_flanges), Decimal(0))
                    aggregate_flange_untaxed_amount = sum(
                        (number(item["untaxed_amount"]) for item in aggregate_flanges), Decimal(0))
                except RuleError as error:
                    issues.append(str(error))
        accessory_per_sqm, accessory_items = Decimal(0), []
        accessory_mode = "pending"
        if rates and payment and options.get("tax_included") in (True, False):
            try:
                actual_accessories = has_semi_common and any(
                    options.get(key) not in (None, "") for key in ("corner_count", "clip_meters"))
                common_settlement_factor = (_whole_quote_factor(
                    rates, options.get("area_basis"), semi_common_families,
                    "net_galvanized_common", "整单角码、卡扣实际数量")
                    if actual_accessories else Decimal(1))
                accessory_per_sqm, accessory_items, accessory_mode, accessory_issue = _semi_accessories(
                    options, rates, has_common, has_semi_common,
                    tax_factor, common_settlement_factor)
                if accessory_issue:
                    issues.append(accessory_issue)
            except RuleError as error:
                issues.append(str(error))
        rows = [calculate_duct_row(row, options, catalog, catalog_resource, payment,
                                   accessory_per_sqm, aggregate_flange_mode)
                for row in quote.get("rows", [])]
        priced = [r for r in rows if r["status"] == "priced"]
        row_amount = sum((number(r["amount"]) for r in priced), Decimal(0))
        row_untaxed_amount = sum((number(r["untaxed_amount"]) for r in priced), Decimal(0))
        actual_accessory = sum((number(item["amount"]) for item in accessory_items), Decimal(0))
        actual_accessory_untaxed = sum((number(item["untaxed_amount"]) for item in accessory_items), Decimal(0))
        subtotal = row_amount + aggregate_flange_amount + actual_accessory
        untaxed_subtotal = row_untaxed_amount + aggregate_flange_untaxed_amount + actual_accessory_untaxed
        pending = sum(r["status"] == "pending" for r in rows)
        if not rows:
            issues.append("清单没有产品行")
        if not priced and rows:
            issues.append("没有已计算的风管项目")
        if pending:
            issues.append(f"有{pending}项待确认，未计入小计")
        if options.get("tax_included") not in (True, False):
            issues.append("请选择整单含税或不含税")
        if has_common and options.get("include_common_punch") is not True and options.get("include_common_punch") is not False:
            issues.append("请选择共板法兰是否需要打孔")

        fees = {"accessories": {"amount": text_number(round_value(actual_accessory, 1)),
                                  "status": "ready" if accessory_mode == "actual" else "included" if accessory_mode == "per_sqm" else "not_applicable" if accessory_mode == "not_applicable" else "pending",
                                  "reason": "按整单实际数量逐项计算：基础单价×面积结算系数×含税系数（付款前），最终单价保留一位；未填写项不计费" if accessory_mode == "actual" else "未使用实际数量，已仅对共板法兰行按2元/㎡计入单价，并与材料其他组成一起应用面积结算和含税系数（付款前）" if accessory_mode == "per_sqm" else "本单没有共板法兰，不计角码卡扣" if accessory_mode == "not_applicable" else "整单实际角码、卡扣尚未明确对应材质，未计入小计"}}
        fees["aggregate_flanges"] = {"amount": text_number(round_value(aggregate_flange_amount, 1)),
                                       "status": "ready" if aggregate_flanges else "pending" if aggregate_flange_mode else "not_applicable",
                                       "reason": "按客户清单30#/40#整单实际米数计价，并应用面积结算系数和含税系数（付款前）；角钢法兰行不再使用1.72换算" if aggregate_flanges else "整单实际法兰米数尚未明确对应材质，未计入小计" if aggregate_flange_mode else "本单未提供整单法兰米数"}
        freight = freight_untaxed = Decimal(0)
        if options.get("include_freight") is not True:
            fees["freight"] = {"amount": "0.0", "status": "not_applicable", "reason": "本单不需要"}
        elif rates is None or not priced or pending:
            fees["freight"] = {"amount": None, "status": "pending", "reason": "风管指标或工程量未全部确认，暂不能确定整单运费面积"}
            issues.append("运费：风管工程量总面积待确认")
        else:
            freight_rate = rates.get("freight_per_sqm", FREIGHT_PER_SQM_LEGACY)
            freight_untaxed = round_value(sum(
                (number(row["billing_quantity"]) * freight_rate * number(row["area_factor"])
                 for row in priced), Decimal(0)), 1)
            freight = round_value(sum(
                (number(row["billing_quantity"]) * freight_rate * number(row["area_factor"])
                 * tax_factor for row in priced), Decimal(0)), 1)
            freight_factors = {number(row["area_factor"]) for row in priced}
            freight_unit = (round_value(
                freight_rate * next(iter(freight_factors)) * tax_factor, 1)
                if len(freight_factors) == 1 else None)
            fees["freight"] = {"amount": text_number(freight), "status": "included",
                               "untaxed_amount": text_number(freight_untaxed),
                               "unit_price": text_number(freight_unit) if freight_unit is not None else None,
                               "reason": f"基础运费{text_number(freight_rate)}元/㎡已计入各风管材料单价，并应用各行面积结算系数（付款前）" + ("及1.1含税系数" if options.get("tax_included") is True else "（不含税）") + "；本项仅展示，不重复加总"}
        taxable_base = untaxed_subtotal
        tax_amount = (subtotal - untaxed_subtotal) if options.get("tax_included") is True else Decimal(0)
        fees["tax"] = {"amount": text_number(round_value(tax_amount, 1)),
                        "status": "ready" if options.get("tax_included") is True else "not_applicable",
                        "reason": "材料税费/含税差额已计入各材料单价：一般材料及运费×1.1，圆法兰直接使用12元/米含税价；安装税费在安装小计中另行处理；本项仅展示、不再重复加总" if options.get("tax_included") is True else "材料不含税；圆法兰按10元/米不含税价计算；安装仍统一×1.01"}
        total = None
        if not issues:
            total = text_number(subtotal)
        if not policy.get("formal_template_confirmed"):
            issues.append("正式客户模板尚未确认，当前仅可导出通用草稿")
        result = {
            "rows": rows, "subtotal": text_number(subtotal), "pending_count": pending,
            "fees": fees, "taxable_base": text_number(round_value(taxable_base, 1)),
            "total": total, "rounding_difference": "0.0" if total is not None else None,
            "issues": issues, "formal_ready": not issues, "policy": policy, "engine_version": DUCT_ENGINE_VERSION,
            "installation_notes": INSTALLATION_NOTES if options.get("include_installation") is True else [],
            "accessory_mode": accessory_mode, "accessory_items": accessory_items,
            "aggregate_flanges": aggregate_flanges,
            "payment": ({**payment, "coefficient": text_number(payment["coefficient"])} if payment else None),
            "duct_catalog": {"id": catalog_resource["id"], "version": catalog_resource["version"]} if catalog_resource else None,
        }
        return finalize_order(quote, result, resources, supply_only)
