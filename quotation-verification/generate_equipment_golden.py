# 设备迁移基准离线生成器，重放冻结 equipment-reference 中的规则和原测试。
# 执行会覆盖设备压缩 golden 文件；脚本和 Python 规则均不参与 pig 生产运行。
"""离线生成设备 Python 对照基线，pig 运行不执行 Python 或本脚本。"""
import copy
import gzip
import hashlib
import importlib.util
import itertools
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 从专用设备参考副本导入算法，与安装迁移参考副本分开。
REF = Path(__file__).parent / "equipment-reference"
sys.path.insert(0, str(REF))
from quotation.engine import calculate
from quotation.equipment_catalog import default_catalog
sys.path.insert(0, str(REF / "tests"))
from test_duct_pricing import catalog as duct_catalog, quote as duct_quote

# 读取设备快照并增加仅供测试的外购型号 TEST，价格不会直接写入业务数据库。
seed = json.loads((REF / "assets/equipment_catalog_20260924.json").read_text(encoding="utf-8"))
seed["effective_from"] = "2020-01-01"
seed["purchased"].append({"id": "test", "model": "TEST", "name": "测试设备", "cost": "75", "unit": "台"})
policy = {"formal_template_confirmed": True}
catalogs, cases = {}, []

# 把设备快照包装成单个发布态测试资源，报价可按系列与版本识别。
def resource(body=seed):
    return dict(id="equipment", family="equipment", kind="equipment_catalog", status="published", version=1, body=body)

# 构造带外购测试型号的单行基准，再由用例覆盖实际类别、尺寸、数量及整单选项。
def quote(kind="purchased", attrs=None, **options):
    return {"pricing_mode": "equipment_v1", "equipment_options": {
        "payment_option": "no_install_cash", "tax_included": False, "include_freight": False, "region": "贵州", **options},
        "rows": [{"name": "测试设备", "spec": "", "category": "外购设备", "quantity": "2", "unit": "台",
                  "attributes": {"equipment_kind": kind, "model": "TEST", **(attrs or {})}}]}

# 资源 JSON 按字段排序计算 SHA-256，共享相同指标以压缩基线体积；输入与结果均复制保存。
def append(name, q, resources=None, p=None):
    rs, p = [resource()] if resources is None else resources, policy if p is None else p
    payload = json.dumps(rs, ensure_ascii=False, sort_keys=True)
    key = hashlib.sha256(payload.encode()).hexdigest()
    catalogs[key] = json.loads(payload)
    result = calculate(q, rs, p)
    cases.append({"name": name, "quote": copy.deepcopy(q), "resources_key": key, "policy": copy.deepcopy(p),
                  "expected": copy.deepcopy(result)})
    return result

# 调用冻结副本的原计算回归案例，仅在内存替换调用入口以捕获期望结果。
spec = importlib.util.spec_from_file_location("equipment_tests", REF / "tests/test_equipment.py")
tests = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tests)
# 替换已加载原测试模块的 calculate 调用，捕获原断言执行时的输入及完整期望结果。
def capture(q, rs, p):
    return append("source-regression-" + str(len(cases)), q, rs, p)
tests.calculate = capture
for name in ["test_catalog_needs_publication_and_never_falls_back", "test_exact_pricing_order_manual_freight_and_upward_rounding",
             "test_outside_region_and_unknown_payment_never_free", "test_repeating_decimal_at_freight_boundary_is_exact",
             "test_source_minimum_louver_cost_applied_after_attachment_factor", "test_remote_cost_before_margin_and_panel_once",
             "test_current_row_blade_count_and_round_price_before_rounding",
             "test_silencer_dimensions_plenum_alias_and_elbow_fixed_taxed_price", "test_mixed_excluded_group_does_not_require_its_catalog"]:
    getattr(tests, name)(tests.ecatalog.__wrapped__())
# 重放冻结参考实现当时的按行运费边界，此处保持历史算法口径。
for region, factor in seed["regions"].items():
    tests.test_freight_boundary_is_per_row_unrounded_supply(tests.ecatalog.__wrapped__(), region, factor)
# 防爆档位按成本而非售价判断，测试上限本数和紧邻上限值。
for cost, factor in [("1000", "1.4"), ("1000.01", "1.6"), ("2000", "1.6"), ("2000.01", "2")]:
    tests.test_explosion_tiers_on_cost_not_sale(tests.ecatalog.__wrapped__(), cost, factor)

# 穷举阀门类型、分组/拼接尺寸边界、远控、材质、外形和税口径，双边超限另增加人工拼接确认。
for kind, dims, control, material, shape, tax in itertools.product(
        [*seed["valves"], "pressure_relief"],
        [("400","450"), ("1250","1000"), ("1251","1000"), ("2000","1000"), ("2001","1000"), ("1200","1001"), ("2001","1001")],
        ["near","remote"], ["galvanized","stainless_304"], ["square","round"], [False,True]):
    q = quote(kind, dict(width=dims[0], height=dims[1], control=control, material=material, shape=shape), tax_included=tax)
    append("valve-" + str(len(cases)), q)
    if dims == ("2001","1001"):
        q["rows"][0]["attributes"]["splice_cost"] = "66"
        append("splice-confirmed-" + str(len(cases)), q)
# 核对所有风口类型的面积价、最低成本及开口/喉部尺寸口径。
for typ, size, basis in itertools.product(seed["louvers"], ["100","800"], ["opening","throat"]):
    append("louver-" + str(len(cases)), quote("louver", dict(width=size,height=size,louver_type=typ,dimension_basis=basis)))
# 消声器/静压箱覆盖层数、板厚与角钢系数；不锈钢既测缺面积成本，也测人工确认成本。
for kind, thickness, layer, flange, material in itertools.product(
        ["silencer","plenum"], [".75","1","1.2","2"], ["single","double"], ["","angle"], ["","stainless_304"]):
    q = quote(kind, dict(width="320",height="250",length="1500",thickness=thickness,layer=layer,flange=flange,material=material))
    append("box-" + str(len(cases)), q)
    if material:
        q["rows"][0]["attributes"]["area_cost"] = "250"
        append("box-reviewed-area-" + str(len(cases)), q)
# 消声弯头按个或㎡不同口径计价，错误单位需返回待确认，不能重复乘面积。
for unit, tax in itertools.product(["台","㎡","m2","m²","平方米","樘"], [False,True]):
    q = quote("elbow", {"area_per_unit":"2.5"}, tax_included=tax); q["rows"][0].update(unit=unit,quantity="1.5")
    append("elbow-" + str(len(cases)),q)
# 每条工况分别测试明确候选编号与自动选择，包含被隔离或成本不完整的数据。
for point in seed["fan_points"]:
    attrs = {k:point[k] for k in ("model","airflow","pressure","speed")}
    attrs["power"] = "/".join(point["powers"])
    q = quote("fan",attrs); q["rows"][0]["name"]="风机"; q["rows"][0]["candidate_id"]=point["id"]
    append("fan-confirmed-"+point["id"],q)
    del q["rows"][0]["candidate_id"]
    append("fan-select-"+point["id"],q)
# 补充无需求、仅功率、其他系列替代和双速低档数据等选型边界。
for attrs in [{"model":"GXF","airflow":"14000","pressure":"650"}, {"model":"HTF-I-5","power":"4"}, {},
              {"power":"2.2"}, {"airflow":"1000","pressure":"100"}, {"model":"SWF-II-5","power":"2.2/1.5"},
              {"model":"HTF(A)-5","power":"2.2"}, {"model":"SWF-5","power":"2.2"},
              {"model":"HTFC-12-1","power":"2.2/1.5","airflow_low":"1000","pressure_low":"100"}]:
    append("fan-edge-"+str(len(cases)),quote("fan",attrs))
# 中文名称及复杂规格测试类别、尺寸、工况和附阀识别，价格仍来自冻结指标。
for name, desc in [("正压送风口","350*（1800+250）"),("接管箱","320×250×1500"),("单层百叶风口","Φ500"),
                   ("斜流式风机 GXF-9","L=30894m³/h P=583pa N=7.5KW"),("风机 HL3-2A-NO6A","L=14000m³/h H=650pa N=5.5KW"),
                   ("远控板式排烟口","400×600"),("防火阀","304不锈钢500×500"),("风机带止回阀","HTF-I-5")]:
    q=quote();q["rows"][0].update(name=name,spec=desc,attributes={})
    append("parse-"+name,q)
# 不同成本、数量、付款与地区组合验证当时的运费门槛、取整和人工明确零费用。
for cost, qty, term, region in itertools.product(["1","50","75.01"], ["1.5","6000","79.999","80"],
        ["no_install_cash","no_install_days_7","no_install_days_15"], ["贵州","海南","湖南"]):
    b=copy.deepcopy(seed);b["purchased"][-1]["cost"]=cost
    q=quote(include_freight=True,payment_option=term,region=region);q["rows"][0]["quantity"]=qty
    append("freight-"+str(len(cases)),q,[resource(b)])
    q["rows"][0]["attributes"]["freight_per_unit"]="0"
    append("zero-freight-"+str(len(cases)),q,[resource(b)])

# 混合报价核对正常汇总，以及税费/付款冲突、安装、待确认和整组排除等情况。
for case in ("normal", "tax", "payment", "installation", "pending", "excluded"):
    q = quote(); q.update(pricing_mode="mixed_v1", duct_options=duct_quote()["duct_options"],
                          rows=[copy.deepcopy(q["rows"][0]), duct_quote()["rows"][0], copy.deepcopy(q["rows"][0])])
    if case == "tax": q["equipment_options"]["tax_included"] = True
    if case == "payment": q["equipment_options"]["payment_option"] = "no_install_days_7"
    if case == "installation": q["duct_options"]["include_installation"] = True
    if case == "pending": q["rows"][0]["quantity"] = ""
    if case == "excluded": q["rows"][1].update(excluded=True, exclude_reason="业主自供")
    append("mixed-"+case,q,[resource(),duct_catalog()])

# 最终保存设备对照输入、去重资源集合及期望结果，不改变冻结 Python 源码。
path=ROOT/"pig-upms/pig-upms-biz/src/test/resources/quotation/equipment-golden.json.gz"
with gzip.open(path,"wt",encoding="utf-8") as out:
    json.dump({"catalogs":catalogs,"cases":cases},out,ensure_ascii=False,separators=(",",":"))
print(json.dumps({"cases":len(cases),"resource_sets":len(catalogs),"priced_rows":sum(r["status"]=="priced" for c in cases for r in c["expected"]["rows"]),"output":str(path)}))
