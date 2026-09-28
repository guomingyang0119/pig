"""Read the latest reference workbook and optionally create a reviewable duct draft.

This never changes the source workbook and never publishes a production catalog.
"""

import argparse
import json
import re
from copy import deepcopy
from datetime import date
from pathlib import Path

import openpyxl

from quotation.duct import DEFAULT_PAYMENT_TERMS, validate_catalog
from quotation.store import Store, dumps, now, uid


ROOT = Path(__file__).resolve().parent
DEFAULT_SOURCE = Path(r"C:\Users\HONOR\Desktop\产品基价.xlsx")
MATERIAL_GROUPS = ((2, 7, "galvanized_40"), (8, 13, "galvanized_80"),
                   (14, 19, "stainless_201"), (20, 25, "stainless_304"))


def build_catalog(source):
    source = Path(source).resolve()
    if not source.is_file():
        raise FileNotFoundError(source)
    workbook = openpyxl.load_workbook(source, data_only=True, read_only=True)
    sheet = workbook["风管类"]
    materials = []
    for first, last, material in MATERIAL_GROUPS:
        for row in range(first, last + 1):
            thickness, price = sheet[f"D{row}"].value, sheet[f"E{row}"].value
            if not isinstance(thickness, (int, float)) or not isinstance(price, (int, float)):
                raise ValueError(f"材料源单元格D{row}/E{row}无有效缓存值")
            materials.append({"name": f"{sheet[f'C{row}'].value} {thickness}mm", "material": material,
                              "thickness": str(thickness), "ton_price": str(price), "unit": "元/吨",
                              "source_cell": f"风管类!E{row}"})
    # The source workbook stops at 1.5 mm. These four 2.0 mm prices were
    # explicitly confirmed by the user on 2026-09-19; they are not written
    # back into the source workbook.
    extra_2mm = {
        "galvanized_40": ("40g镀锌板 2.0mm", "3680"),
        "galvanized_80": ("80g镀锌板 2.0mm", "4002"),
        "stainless_201": ("201不锈钢 2.0mm", "7544"),
        "stainless_304": ("304不锈钢 2.0mm", "14444"),
    }
    for material, (name, price) in extra_2mm.items():
        materials.append({"name": name, "material": material, "thickness": "2.0",
                          "ton_price": price, "unit": "元/吨",
                          "source_cell": "用户确认补充：2026-09-19"})
    flanges = []
    for row in range(38, 56):
        name = str(sheet[f"C{row}"].value or "")
        match = re.match(r"(30|40|50)", name)
        if not match:
            raise ValueError(f"角钢法兰C{row}规格不明")
        material = "hot_galvanized" if "热镀锌" in name else "black" if "黑角铁" in name else None
        if material is None:
            raise ValueError(f"角钢法兰C{row}材质不明")
        size = int(match.group(1))
        weight, price = sheet[f"D{row}"].value, sheet[f"E{row}"].value
        if not isinstance(weight, (int, float)) or not isinstance(price, (int, float)):
            raise ValueError(f"角钢法兰D{row}/E{row}价格无效")
        processing = sheet[f"F{56 if size == 30 else 57 if size == 40 else 58}"].value
        flanges.append({"name": f"{name} 计算档位{weight}", "material": material, "size": size,
                        "grade": str(weight), "ton_price": str(price),
                        "processing_per_m": str(processing), "unit": "元/米",
                        "source_cell": f"风管类!C{row}:E{row}"})
    def rate(cell):
        value = sheet[cell].value
        if not isinstance(value, (int, float)):
            raise ValueError(f"成本参数{cell}不是数值")
        return str(value)
    rates = {
        "density_galvanized": "7.85", "density_stainless": "7.93",
        "galvanized_semi": rate("F31"), "galvanized_straight": rate("F26"),
        "galvanized_irregular": rate("F29"), "galvanized_1_5_straight": rate("F28"),
        "galvanized_1_5_irregular": rate("F30"), "galvanized_1_5_unspecified": "5.5",
        "galvanized_narrow_extra": "3",
        "stainless_semi": rate("F34"), "stainless_straight": rate("F32"),
        "stainless_irregular": rate("F33"), "closing_common": rate("F35"),
        "closing_angle": rate("F36"), "closing_round": "20", "closing_0_5_extra": "5",
        "accessory_per_sqm": rate("F59"), "corner_unit_price": "0.3",
        "clip_per_meter_price": "3", "common_punch": "1", "freight_per_sqm": "1",
        "net_galvanized_common": "1.17", "net_galvanized_angle": "1.14",
        "net_stainless": "1.15", "net_round": "1.17",
        "material_tax_factor": "1.1", "installation_tax_factor": "1.01",
        "flange_area_factor": "1.72",
    }
    expressions = {
        "material_price": "thickness * density * 0.001 * ton_price",
        "body_sale": "(material_price + processing + closing + accessory_per_sqm) * area_factor",
        "flange_per_meter": "flange_grade * ton_price / 1000 / 6 + flange_processing",
        "flange_per_area": "flange_per_meter * flange_area_factor",
    }
    workbook.close()
    body = {"name": "贵州风管报价指标", "company_id": "guizhou", "source": str(source),
            "effective_from": date.today().isoformat(), "effective_to": "",
            "materials": materials, "flanges": flanges, "rates": rates, "expressions": expressions,
            "payment_terms": deepcopy(DEFAULT_PAYMENT_TERMS)}
    validate_catalog(body)
    return body


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--data-dir", type=Path, default=ROOT / "data")
    parser.add_argument("--write-draft", action="store_true", help="Write an unpublished draft into the local database")
    parser.add_argument("--replace-draft", action="store_true",
                        help="Explicitly replace the existing unpublished duct draft; never affects a published version")
    args = parser.parse_args()
    body = build_catalog(args.source)
    print(json.dumps({"name": body["name"], "source": body["source"],
                      "materials": len(body["materials"]), "flanges": len(body["flanges"]),
                      "rates": len(body["rates"]), "expressions": body["expressions"]}, ensure_ascii=False, indent=2))
    if args.write_draft:
        store = Store(args.data_dir)
        with store.db() as db:
            existing = db.execute("SELECT id FROM resources WHERE kind='duct_catalog' AND status='draft'").fetchone()
            action_text = "已创建待审核草稿"
            if existing:
                if not args.replace_draft:
                    raise RuntimeError("已有风管指标草稿；如确认重建未发布草稿，请显式增加--replace-draft")
                identifier = existing["id"]
                previous = db.execute("SELECT body FROM resources WHERE id=?", (identifier,)).fetchone()["body"]
                db.execute("UPDATE resources SET body=? WHERE id=?", (dumps(body), identifier))
                store.audit(db, "local-import", "replace_duct_catalog_draft", identifier,
                            {"source": str(args.source), "previous_body": previous})
                action_text = "已重建待审核草稿"
            else:
                identifier = uid()
                db.execute("INSERT INTO resources VALUES (?,?,?,?,?,?,?,?,?)",
                           (identifier, identifier, "duct_catalog", 1, "draft", dumps(body), "local-import", now(), None))
                store.audit(db, "local-import", "import_duct_catalog_draft", identifier, {"source": str(args.source)})
            print(action_text + "：" + identifier)


if __name__ == "__main__":
    main()
