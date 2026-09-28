package com.pig4cloud.pig.admin.quotation;

import org.apache.poi.ss.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/** Reads a system-generated review workbook after a quoter completes it in Excel. */
final class QuoteReviewImportService {

	private static final List<String> FIELDS = List.of("name", "thickness", "spec", "process", "unit", "quantity",
			"material_unit", "material_amount", "installation_amount", "line_total", "remark");

	private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("name", "产品名称"),
			Map.entry("thickness", "厚度"), Map.entry("spec", "型号/规格"), Map.entry("process", "法兰/工艺"),
			Map.entry("unit", "单位"), Map.entry("quantity", "数量"), Map.entry("material_unit", "材料单价"),
			Map.entry("material_amount", "材料金额"), Map.entry("installation_amount", "安装费用"),
			Map.entry("line_total", "本行报价"), Map.entry("remark", "备注"));

	Map<String, Object> read(byte[] bytes, String filename, String expectedQuoteId, int expectedRevision) {
		if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
			throw new RuleException("请上传系统导出的xlsx校对版");
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
			Sheet metadata = wb.getSheet(DuctExportService.REVIEW_METADATA_SHEET);
			if (metadata == null)
				throw new RuleException("文件不是系统导出的可回导校对版，请重新导出后填写");
			FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
			Map<String, String> properties = properties(metadata, evaluator);
			if (!DuctExportService.REVIEW_METADATA_MAGIC.equals(properties.get("format")))
				throw new RuleException("校对版格式无法识别，请重新导出");
			if (!expectedQuoteId.equals(properties.get("quote_id")))
				throw new RuleException("此校对版不属于当前报价单");
			int workbookRevision = integer(properties.get("revision"), "报价版本");
			if (workbookRevision != expectedRevision)
				throw new RuleException("报价单在校对版导出后已有修改，请重新导出校对版");
			if (!DuctExportService.TEMPLATE_VERSION.equals(properties.get("template_version")))
				throw new RuleException("校对版模板已更新，请重新导出后填写");
			int columns = integer(properties.get("column_count"), "校对版列数");
			if (!Set.of(10, 12).contains(columns))
				throw new RuleException("校对版列结构不正确");
			Sheet visible = wb.getSheet(properties.get("visible_sheet"));
			if (visible == null)
				throw new RuleException("校对版工作表已被删除或改名");
			List<Map<String, Object>> lines = new ArrayList<>(), changes = new ArrayList<>();
			BigDecimal materialTotal = ZERO, equipmentFreightTotal = ZERO, installationTotal = ZERO;
			boolean equipmentFreightIncluded = false;
			int pending = 0, manualChanges = 0, derivedChanges = 0, changedRows = 0;
			for (int rowIndex = 10; rowIndex <= metadata.getLastRowNum(); rowIndex++) {
				Row metaRow = metadata.getRow(rowIndex);
				if (metaRow == null || blank(cellText(metaRow.getCell(0), evaluator)))
					continue;
				int visibleRow = integer(cellText(metaRow.getCell(0), evaluator), "明细行号");
				Row source = visible.getRow(visibleRow - 1);
				if (source == null)
					throw new RuleException("校对版第" + visibleRow + "行已被删除，请保留原有行结构");
				Map<String, Object> baseline = baseline(metaRow, evaluator);
				boolean equipmentFreightSummary = s(baseline, "process").equals("设备运费");
				boolean freightIncludedInRows = equipmentFreightSummary
						&& s(baseline, "remark").contains("设备运费已计入各设备单价");
				if (freightIncludedInRows)
					equipmentFreightIncluded = true;
				Map<String, Object> current = equipmentFreightSummary
						? visibleEquipmentFreight(source, evaluator, columns, baseline)
						: visible(source, evaluator, columns);
				current.putAll(obj("visible_row", visibleRow, "line_key", cellText(metaRow.getCell(1), evaluator),
						"source_row_id", cellText(metaRow.getCell(2), evaluator), "was_pending",
						booleanValue(metaRow.getCell(3), evaluator)));
				normalizeNumbers(baseline, visibleRow);
				normalizeNumbers(current, visibleRow);
				boolean required = yes(current.get("was_pending")) || !blank(baseline.get("material_amount"))
						|| !blank(baseline.get("installation_amount"));
				Set<String> derivedFields = new LinkedHashSet<>();
				boolean complete = complete(current, visibleRow, required, columns == 12, derivedFields);
				if (equipmentFreightSummary) {
					derivedFields.add("material_unit");
					if (freightIncludedInRows)
						derivedFields.add("material_amount");
					if (columns == 12)
						derivedFields.add("line_total");
				}
				if (yes(current.get("was_pending")) && complete && s(current, "process").equals("待确认")) {
					current.put("process", "人工确认");
					derivedFields.add("process");
				}
				boolean changed = false, priceChanged = false;
				List<Map<String, Object>> rowChanges = new ArrayList<>();
				for (String field : FIELDS) {
					String before = s(baseline, field), after = s(current, field);
					if (!before.equals(after)) {
						boolean derived = derivedFields.contains(field);
						if (derived)
							derivedChanges++;
						else {
							manualChanges++;
							changed = true;
							if (Set.of("quantity", "material_unit", "material_amount", "installation_amount")
								.contains(field))
								priceChanged = true;
						}
						Map<String, Object> change = obj("line", visibleRow, "product", s(current, "name"), "field",
								field, "label", LABELS.get(field), "before", before, "after", after, "kind",
								derived ? "derived" : "manual");
						changes.add(change);
						rowChanges.add(change);
					}
				}
				if (changed)
					changedRows++;
				current.putAll(obj("sequence", visibleRow - 9, "pending", required && !complete, "changed", changed,
						"price_changed", priceChanged, "before_values", baseline, "changes", rowChanges, "derived_fields",
						new ArrayList<>(derivedFields)));
				if (required && !complete)
					pending++;
				if (s(current, "process").equals("设备运费"))
					equipmentFreightTotal = add(equipmentFreightTotal, amount(current.get("material_amount")));
				else
					materialTotal = add(materialTotal, amount(current.get("material_amount")));
				installationTotal = add(installationTotal, amount(current.get("installation_amount")));
				lines.add(current);
			}
			if (lines.isEmpty())
				throw new RuleException("校对版没有可回导的报价明细");
			long unchanged = lines.stream().filter(line -> !yes(line.get("changed")) && !yes(line.get("pending"))).count();
			BigDecimal beforePayment = add(materialTotal, equipmentFreightIncluded ? ZERO : equipmentFreightTotal,
					installationTotal);
			return obj("type", "review_import_v1", "quote_id", expectedQuoteId, "workbook_revision", workbookRevision,
					"template_version", properties.get("template_version"), "column_count", columns, "lines", lines,
					"changes", changes, "change_count", manualChanges, "derived_count", derivedChanges,
					"changed_row_count", changedRows, "unchanged_count", unchanged,
					"line_count", lines.size(), "pending_count", pending, "material_total", text(materialTotal),
					"equipment_freight_total", text(equipmentFreightTotal),
					"equipment_freight_included", equipmentFreightIncluded,
					"installation_total", text(installationTotal), "before_payment_total", text(beforePayment), "subtotal",
					text(beforePayment));
		}
		catch (RuleException e) {
			throw e;
		}
		catch (IOException | RuntimeException e) {
			throw new RuleException("校对版读取失败，请确认文件未损坏且仍为xlsx格式");
		}
	}

	private static Map<String, String> properties(Sheet metadata, FormulaEvaluator evaluator) {
		Map<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < 8; i++) {
			Row row = metadata.getRow(i);
			if (row != null)
				values.put(cellText(row.getCell(0), evaluator), cellText(row.getCell(1), evaluator));
		}
		return values;
	}

	private static Map<String, Object> baseline(Row row, FormulaEvaluator evaluator) {
		Map<String, Object> value = obj();
		for (int i = 0; i < FIELDS.size(); i++)
			value.put(FIELDS.get(i), cellText(row.getCell(i + 4), evaluator));
		return value;
	}

	private static Map<String, Object> visible(Row row, FormulaEvaluator evaluator, int columns) {
		Map<String, Object> value = obj("name", cellText(row.getCell(1), evaluator), "thickness",
				cellText(row.getCell(2), evaluator), "spec", cellText(row.getCell(3), evaluator), "process",
				cellText(row.getCell(4), evaluator), "unit", cellText(row.getCell(5), evaluator), "quantity",
				cellText(row.getCell(6), evaluator), "material_unit", cellText(row.getCell(7), evaluator),
				"material_amount", cellText(row.getCell(8), evaluator));
		if (columns == 12)
			value.putAll(obj("installation_amount", cellText(row.getCell(9), evaluator), "line_total",
					cellText(row.getCell(10), evaluator), "remark", cellText(row.getCell(11), evaluator)));
		else
			value.putAll(obj("installation_amount", "", "line_total", "", "remark",
					cellText(row.getCell(9), evaluator)));
		return value;
	}

	private static Map<String, Object> visibleEquipmentFreight(Row row, FormulaEvaluator evaluator, int columns,
			Map<String, Object> baseline) {
		Map<String, Object> value = new LinkedHashMap<>(baseline);
		String amount = cellText(row.getCell(columns == 12 ? 10 : 8), evaluator);
		value.put("material_unit", amount);
		value.put("material_amount", amount);
		if (columns == 12) {
			value.put("installation_amount", "0");
			value.put("line_total", amount);
		}
		return value;
	}

	private static void normalizeNumbers(Map<String, Object> line, int row) {
		for (String key : List.of("quantity", "material_unit", "material_amount", "installation_amount",
				"line_total")) {
			String value = s(line, key);
			line.put(key,
					value.isEmpty() ? "" : decimal(value, "第" + row + "行" + LABELS.get(key)).stripTrailingZeros().toPlainString());
		}
	}

	private static boolean complete(Map<String, Object> line, int row, boolean required, boolean installation,
			Set<String> derivedFields) {
		if (!required)
			return true;
		BigDecimal quantity = amount(line.get("quantity"));
		if (quantity.signum() <= 0)
			return false;
		completePair(line, "material_unit", "material_amount", quantity, row, derivedFields);
		if (installation && blank(line.get("installation_amount")))
			return false;
		if (blank(line.get("material_amount")) && blank(line.get("installation_amount")))
			return false;
		if (installation) {
			String total = text(add(amount(line.get("material_amount")), amount(line.get("installation_amount"))));
			if (!total.equals(s(line, "line_total"))) {
				line.put("line_total", total);
				derivedFields.add("line_total");
			}
		}
		return true;
	}

	private static void completePair(Map<String, Object> line, String unitKey, String amountKey, BigDecimal quantity,
			int row, Set<String> derivedFields) {
		String unit = s(line, unitKey), total = s(line, amountKey);
		if (!unit.isEmpty() && total.isEmpty()) {
			line.put(amountKey, decimal(unit, "第" + row + "行单价").multiply(quantity, MC).stripTrailingZeros().toPlainString());
			derivedFields.add(amountKey);
		}
		else if (unit.isEmpty() && !total.isEmpty()) {
			line.put(unitKey,
					decimal(total, "第" + row + "行金额").divide(quantity, 8, RoundingMode.HALF_UP).stripTrailingZeros()
						.toPlainString());
			derivedFields.add(unitKey);
		}
	}

	private static BigDecimal amount(Object value) {
		return blank(value) ? ZERO : decimal(str(value), "金额");
	}

	private static BigDecimal decimal(String value, String label) {
		String clean = value.trim().replace(",", "").replace("，", "").replace("¥", "").replace("￥", "")
			.replaceAll("\\s+", "");
		try {
			BigDecimal result = new BigDecimal(clean);
			if (result.signum() < 0 || result.compareTo(new BigDecimal("1000000000000")) > 0)
				throw new NumberFormatException();
			return result;
		}
		catch (NumberFormatException e) {
			throw new RuleException(label + "必须是0至1万亿元之间的数字");
		}
	}

	private static int integer(String value, String label) {
		try {
			return new BigDecimal(value).intValueExact();
		}
		catch (RuntimeException e) {
			throw new RuleException(label + "不正确");
		}
	}

	private static boolean booleanValue(Cell cell, FormulaEvaluator evaluator) {
		String value = cellText(cell, evaluator);
		return value.equalsIgnoreCase("true") || value.equals("1") || value.equals("是");
	}

	private static String cellText(Cell cell, FormulaEvaluator evaluator) {
		if (cell == null)
			return "";
		CellType type = cell.getCellType();
		if (type == CellType.FORMULA) {
			CellValue value = evaluator.evaluate(cell);
			if (value == null)
				return "";
			return switch (value.getCellType()) {
				case NUMERIC -> text(BigDecimal.valueOf(value.getNumberValue()));
				case STRING -> value.getStringValue().trim();
				case BOOLEAN -> Boolean.toString(value.getBooleanValue());
				case BLANK -> "";
				default -> throw new RuleException("校对版包含无法读取的公式结果");
			};
		}
		return switch (type) {
			case NUMERIC -> text(BigDecimal.valueOf(cell.getNumericCellValue()));
			case STRING -> cell.getStringCellValue().trim();
			case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
			case BLANK -> "";
			default -> new DataFormatter(Locale.CHINA).formatCellValue(cell).trim();
		};
	}

}
