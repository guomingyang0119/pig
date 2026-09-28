"""One-time test fixture generation only. Never imported by the Java application."""
import copy
import gzip
import itertools
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).parent / "reference"))
from import_duct_base_prices import build_catalog
from quotation.duct import calculate_duct

source = Path(sys.argv[1])
catalog = build_catalog(source)
catalog.update(source="产品基价.xlsx / 已确认风管规则（2026-09-22）", effective_from="2026-09-22")
resources = [{"id": "golden-catalog", "family": "golden-family", "kind": "duct_catalog", "version": 1,
              "status": "published", "body": catalog}]
policy = {"formal_template_confirmed": True}
seed = ROOT / "pig-upms/pig-upms-biz/src/main/resources/quotation/duct-catalog-seed.json"
seed.parent.mkdir(parents=True, exist_ok=True)
seed.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

ROW_KEYS = """status unit_price amount billing_quantity body_amount untaxed_unit_price untaxed_amount tax_amount
body_unit_price body_base_unit_price flange_unit_price flange_untaxed_unit_price flange_per_area flange_per_area_base
flange_amount flange_untaxed_amount flange_meters round_flange_diameter round_flange_count round_flange_rate
round_flange_untaxed_unit_price round_flange_unit_price round_flange_untaxed_amount round_flange_amount
round_flange_area_unit_price round_flange_untaxed_area_unit_price flange_basis selected_shape effective_flange
effective_product_state area_factor payment_coefficient""".split()

def projection(result):
    return {
        **{key: result.get(key) for key in ("subtotal", "pending_count", "total", "formal_ready", "taxable_base", "accessory_mode")},
        "rows": [{key: row.get(key) for key in ROW_KEYS} for row in result["rows"]],
        "fees": {key: {field: value.get(field) for field in ("amount", "status", "untaxed_amount", "unit_price")}
                 for key, value in result["fees"].items()},
        "accessory_items": result["accessory_items"], "aggregate_flanges": result["aggregate_flanges"],
    }

cases = []
def append(name, quote, rs=None, p=None):
    selected = resources if rs is None else rs
    selected_policy = policy if p is None else p
    item = {"name": name, "quote": quote, "expected": projection(calculate_duct(quote, selected, selected_policy))}
    if rs is not None: item["resources"] = rs
    if p is not None: item["policy"] = p
    cases.append(item)

def quote():
    return {"pricing_mode": "duct_v1", "customer": "回归测试客户", "project": "隔离验证",
            "duct_options": {"area_basis": "net", "product_state": "semi", "shape": "straight",
                             "zinc_layer": "40", "flange": "angle", "include_common_punch": False,
                             "tax_included": False, "payment_option": "no_install_cash",
                             "include_freight": False, "include_installation": False, "installation_amount": "",
                             "corner_count": "", "clip_meters": "", "flange_30_meters": "", "flange_40_meters": ""},
            "rows": [{"name": "镀锌风管", "spec": "0.75 / 800×320", "category": "风管",
                      "engineering_quantity": "12.35", "unit": "㎡", "attributes": {"thickness": "0.75"}}]}

# Cover every seeded material/thickness and all three flanges under both tax/area modes.
for material, flange, state, area, tax in itertools.product(catalog["materials"], ["common","angle","round"],
                                                         ["semi","finished"], ["open","net"], [False,True]):
    q = quote()
    q["duct_options"].update(flange=flange, product_state=state, area_basis=area, tax_included=tax)
    q["rows"][0]["attributes"].update(material=material["material"], thickness=material["thickness"], diameter="500")
    append(f"matrix-{len(cases)}", q)

for state, shape, size, flange in itertools.product(["semi","finished"], ["straight","irregular","semi_unspecified"],
                                                 ["200×320","201×320","1599×400","1600×400"],["angle","common","round"]):
    for term in catalog["payment_terms"]:
        q=quote()
        q["duct_options"].update(product_state=state,shape=shape,flange=flange,tax_included=True,include_freight=True,
                                 payment_option=term["code"],include_installation=term["mode"]=="with_install",
                                 installation_amount="1234.55",include_common_punch=True)
        q["rows"][0]["spec"]=size
        q["rows"][0]["attributes"].update(diameter="500", round_flange_count="13")
        append(f"boundary-payment-{len(cases)}", q)
for corners, clips, tax, area in itertools.product(["","0","400"],["","0","110"],[False,True],["open","net"]):
    q=quote(); q["duct_options"].update(flange="common",corner_count=corners,clip_meters=clips,tax_included=tax,area_basis=area)
    append(f"accessory-{len(cases)}",q)
for tax, area, mode in itertools.product([False,True],["open","net"],["row","aggregate","conflict"]):
    q=quote(); q["duct_options"].update(tax_included=tax,area_basis=area)
    if mode in ("row","conflict"): q["rows"][0]["attributes"]["flange_meters"]="103.25"
    if mode in ("aggregate","conflict"): q["duct_options"].update(flange_30_meters="125",flange_40_meters="75")
    append(f"angle-meters-{len(cases)}",q)
for key, value in [("payment_option",""),("tax_included",None),("area_basis",""),("product_state",""),
                   ("flange","mixed"),("installation_amount","0")]:
    q=quote(); q["duct_options"][key]=value
    if key=="installation_amount": q["duct_options"].update(include_installation=True,payment_option="with_install_cash")
    append("missing-"+key,q)
for key,value in [("engineering_quantity",""),("engineering_quantity","-1"),("source_issue",True),
                  ("excluded",True),("category","风机"),("name","")]:
    q=quote(); q["rows"][0][key]=value; append("row-"+key+"-"+str(value),q)
q=quote(); q["rows"].append(copy.deepcopy(q["rows"][0]));q["rows"][1]["attributes"]["material"]="stainless_304";append("mixed-material-per-row",q)
q=quote(); q["rows"].append(copy.deepcopy(q["rows"][0]));q["rows"][1]["attributes"]["flange"]="common";append("mixed-flange-allowed",q)
q=quote();q["rows"][0].update(excluded=True,exclude_reason="标题行");append("all-excluded",q)
q=quote();q["rows"]=[];append("empty",q)
append("unpublished",quote(),[])
append("template-unconfirmed",quote(),p={"formal_template_confirmed":False})
new=copy.deepcopy(resources[0]);new.update(version=2,status="retired")
append("retired-no-fallback",quote(),resources+[new])
new=copy.deepcopy(resources[0]);new.update(version=2);new["body"]["effective_to"]="2026-09-22"
append("expired-no-fallback",quote(),resources+[new])
out=ROOT/"pig-upms/pig-upms-biz/src/test/resources/quotation/duct-golden.json.gz"
out.parent.mkdir(parents=True,exist_ok=True)
with gzip.open(out,"wt",encoding="utf-8") as f:
    json.dump({"resources":resources,"policy":policy,"row_keys":ROW_KEYS,"cases":cases},f,ensure_ascii=False,separators=(",",":"))
print(f"Generated {len(cases)} Python duct-v15 golden cases; seed contains only business rules.")
