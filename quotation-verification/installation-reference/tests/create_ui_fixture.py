"""Synthetic import fixture. This is not a business price or quotation."""
from pathlib import Path
import openpyxl

target = Path(__file__).resolve().parent.parent / "test-output" / "ui-import.xlsx"
target.parent.mkdir(parents=True, exist_ok=True)
workbook = openpyxl.Workbook()
sheet = workbook.active
sheet.title = "材料清单"
sheet.append(["隔离测试用材料清单"])
sheet.append(["名称", "规格", "数量", "单位"])
sheet.append(["测试直管", "800×400", 2, "米"])
sheet.append(["测试风机", "规格待确认", 1, "台"])
sheet.append(["合计备注", "不计价行应人工明确排除"])
workbook.save(target)
