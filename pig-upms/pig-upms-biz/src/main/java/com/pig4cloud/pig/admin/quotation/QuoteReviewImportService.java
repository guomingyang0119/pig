package com.pig4cloud.pig.admin.quotation;

import org.apache.poi.ss.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 读取试算后由报价员完善金额的可编辑校对版，并生成回导差异和付款前汇总。
 * 同时读取主表参数修改供报价服务重新试算；人工价格仍独立处理，文件身份和修订号必须匹配当前报价。
 */
final class QuoteReviewImportService {

	// 与隐藏基准列一一对应，包含人工可修改金额和系统重算的本行总额。
	private static final List<String> FIELDS = List.of("name", "thickness", "spec", "process", "unit", "quantity",
			"material_unit", "material_amount", "installation_amount", "line_total", "remark");

	private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("name", "产品名称"),
			Map.entry("thickness", "厚度"), Map.entry("spec", "型号/规格"), Map.entry("process", "法兰/工艺"),
			Map.entry("unit", "单位"), Map.entry("quantity", "数量"), Map.entry("material_unit", "材料单价"),
			Map.entry("material_amount", "材料金额"), Map.entry("installation_amount", "安装费用"),
			Map.entry("line_total", "本行报价"), Map.entry("remark", "备注"));

	/**
	 * 验证系统隐藏元数据、报价 ID、修订号及兼容模板列结构，再按保存的基准行读取可见金额。
	 * 区分人工修改和自动补算字段，保留未补全行；材料、设备运费及安装分别汇总，设备运费已含在单价时不重复加总。
	 */
	Map<String, Object> read(byte[] bytes, String filename, String expectedQuoteId, int expectedRevision) {
		return read(bytes, filename, expectedQuoteId, expectedRevision, Map.of());
	}

	/** 服务端正文中的范围优先于工作簿元数据，也覆盖没有范围列的历史校对文件。 */
	Map<String, Object> read(byte[] bytes, String filename, String expectedQuoteId, int expectedRevision,
			Map<String, String> sourceScopes) {
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
			// 只兼容明确列出的回导版本，未知模板需重新导出，避免把金额按错误列位解释。
			if (!Set.of(DuctExportService.TEMPLATE_VERSION, "review-roundtrip-20261009-v13", "review-roundtrip-20261007-v12", "review-roundtrip-20261006-v11", "review-roundtrip-20261006-v10", "review-roundtrip-20260930-v9")
					.contains(properties.get("template_version")))
				throw new RuleException("校对版模板已更新，请重新导出后填写");
			int columns = integer(properties.get("column_count"), "校对版列数");
			if (!Set.of(10, 12, 13).contains(columns))
				throw new RuleException("校对版列结构不正确");
			Sheet visible = wb.getSheet(properties.get("visible_sheet"));
			if (visible == null)
				throw new RuleException("校对版工作表已被删除或改名");
			if (DuctExportService.TEMPLATE_VERSION.equals(properties.get("template_version")) && "main_parameters_v1".equals(properties.get("parameter_format"))
					&& (visible.getRow(8) == null || !QuoteExcelParameters.HEADER.equals(cellText(visible.getRow(8).getCell(columns), evaluator))))
				throw new RuleException("报价参数列已被删除或改名，请保留导出时的列结构");
			List<Map<String, Object>> lines = new ArrayList<>(), changes = new ArrayList<>(), restorations = new ArrayList<>();
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
				String scope = sourceScopes.getOrDefault(s(current, "source_row_id"), cellText(metaRow.getCell(15), evaluator));
				if (scope.isEmpty()) scope = "quoted";
				if (!Set.of("quoted", "excluded", "installation_only").contains(scope)) throw new RuleException("校对版报价范围无法识别");
				current.putAll(obj("scope", scope, "exclude_reason", cellText(metaRow.getCell(16), evaluator)));
				baseline.put("scope", scope);
				normalizeNumbers(baseline, visibleRow);
				normalizeNumbers(current, visibleRow);
				boolean restore = scope.equals("excluded") && (List.of("material_unit", "material_amount", "installation_amount", "line_total").stream().anyMatch(key -> !blank(current.get(key)))
						|| columns == 13 && !blank(cellText(source.getCell(9), evaluator)))
						|| scope.equals("installation_only") && (!blank(current.get("material_unit")) || !blank(current.get("material_amount")));
				// 原待确认行或已有金额的行仍需检查完整性，未计价说明行不要求凭空补价。
				boolean required = yes(current.get("was_pending")) || !blank(baseline.get("material_amount"))
						|| !blank(baseline.get("installation_amount"));
				if (scope.equals("excluded")) required = restore;
				else if (scope.equals("installation_only")) required = columns != 10 || restore;
				Set<String> derivedFields = new LinkedHashSet<>();
				boolean complete = complete(current, visibleRow, required, columns != 10, derivedFields);
				if (equipmentFreightSummary) {
					derivedFields.add("material_unit");
					if (freightIncludedInRows)
						derivedFields.add("material_amount");
					if (columns != 10)
						derivedFields.add("line_total");
				}
				if (yes(current.get("was_pending")) && complete) {
					if (s(current, "process").equals("待确认")) {
						current.put("process", "人工确认");
						derivedFields.add("process");
					}
					String remark = s(current, "remark");
					if (remark.equals(s(baseline, "remark"))) {
						String cleaned = resolvedRemark(remark);
						if (!cleaned.equals(remark)) {
							current.put("remark", cleaned);
							derivedFields.add("remark");
						}
					}
				}
				boolean changed = false, priceChanged = false;
				List<Map<String, Object>> rowChanges = new ArrayList<>();
				if (visible.getRow(8) != null && QuoteExcelParameters.HEADER.equals(cellText(visible.getRow(8).getCell(columns), evaluator))) {
					if (!blank(current.get("source_row_id")) && !s(current, "source_row_id").equals(cellText(source.getCell(columns + 3), evaluator)))
						throw QuoteExcelParameters.invalid(visibleRow, "产品关联ID不一致，请勿排序或替换原产品行");
					var updates = QuoteExcelParameters.changes(cellText(source.getCell(columns), evaluator), cellText(metaRow.getCell(17), evaluator), visibleRow);
					if (!updates.isEmpty() && blank(current.get("source_row_id"))) throw QuoteExcelParameters.invalid(visibleRow, "整单费用行不支持填写产品参数");
					current.put("parameter_updates", updates);
					for (var entry : updates.entrySet()) {
						var change = obj("line", visibleRow, "product", current.get("name"), "field", "attributes." + entry.getKey(),
							"label", QuoteExcelParameters.label(entry.getKey()), "before", cellText(metaRow.getCell(17), evaluator),
							"after", entry.getValue(), "kind", "manual");
						rowChanges.add(change); changes.add(change); manualChanges++; changed = true;
					}
				}
				// 补算的单价、金额和清理备注记为派生变化；真正人工改价单独统计，便于确认预览。
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
				if (restore) {
					Map<String, Object> candidate = obj();
					for (String field : List.of("quantity", "material_unit", "material_amount", "installation_amount", "line_total")) candidate.put(field, current.get(field));
					if (columns == 13) candidate.put("installation_unit", cellText(source.getCell(9), evaluator));
					current.putAll(obj("scope_restore_required", true, "candidate_values", candidate));
					var restoration = obj("source_row_id", s(current, "source_row_id"), "name", s(current, "name"), "previous_scope", scope,
							"visible_row", visibleRow, "candidate_values", candidate);
					restoration.putAll(candidate);
					restorations.add(restoration);
				}
				if (required && !complete)
					pending++;
				if (scope.equals("quoted") && s(current, "process").equals("设备运费"))
					equipmentFreightTotal = add(equipmentFreightTotal, amount(current.get("material_amount")));
				else if (scope.equals("quoted"))
					materialTotal = add(materialTotal, amount(current.get("material_amount")));
				if (!scope.equals("excluded")) installationTotal = add(installationTotal, amount(current.get("installation_amount")));
				lines.add(current);
			}
			if (lines.isEmpty())
				throw new RuleException("校对版没有可回导的报价明细");
			long unchanged = lines.stream().filter(line -> !yes(line.get("changed")) && !yes(line.get("pending"))).count();
			// 付款系数由整单报价服务继续统一应用一次；此处只返回付款前合计。
			BigDecimal beforePayment = add(materialTotal, equipmentFreightIncluded ? ZERO : equipmentFreightTotal,
					installationTotal);
			return obj("type", "review_import_v1", "quote_id", expectedQuoteId, "workbook_revision", workbookRevision,
					"parameter_input_format", "main_parameters_v1".equals(properties.get("parameter_format")),
					"location_text", cellText(visible.getRow(5) == null ? null : visible.getRow(5).getCell(columns == 10 ? 5 : 6), evaluator),
					"template_version", properties.get("template_version"), "column_count", columns, "lines", lines,
					"changes", changes, "scope_restorations", restorations, "change_count", manualChanges, "derived_count", derivedChanges,
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

	/** 仅供报价服务在核对正文和逐行显式确认后调用；未获确认的范围继续排除在合计之外。 */
	static Map<String, Object> acceptScopeRestorations(Map<String, Object> parsed, Set<String> ids) {
		boolean installation = n(parsed.get("column_count")).intValue() != 10;
		for (var line : list(parsed.get("lines"))) {
			if (!yes(line.get("scope_restore_required")) || !ids.contains(s(line, "source_row_id"))) continue;
			line.putAll(obj("scope", "quoted", "scope_restore_required", false, "exclude_reason", ""));
			line.remove("candidate_values");
			Set<String> derived = new LinkedHashSet<>();
			boolean complete = complete(line, n(line.get("visible_row")).intValue(), true, installation, derived)
					&& !blank(line.get("material_amount"));
			line.put("pending", !complete);
			if (Set.of("不报价", "仅安装").contains(s(line, "process"))) line.put("process", "人工确认");
			line.put("remark", String.join("；", Arrays.stream(s(line, "remark").split("；"))
					.filter(part -> !part.equals("仅安装") && !part.startsWith("不在本次报价范围") && !part.startsWith("材料不在本次报价范围")).toList()));
		}
		parsed.put("scope_restorations", list(parsed.get("scope_restorations")).stream()
				.filter(item -> !ids.contains(s(item, "source_row_id"))).toList());
		BigDecimal material = ZERO, freight = ZERO, installationTotal = ZERO;
		int pending = 0, unchanged = 0;
		for (var line : list(parsed.get("lines"))) {
			if (yes(line.get("pending"))) pending++;
			if (!yes(line.get("pending")) && !yes(line.get("changed"))) unchanged++;
			if (s(line, "scope").equals("excluded")) continue;
			installationTotal = add(installationTotal, amount(line.get("installation_amount")));
			if (s(line, "scope").equals("installation_only")) continue;
			if (s(line, "process").equals("设备运费")) freight = add(freight, amount(line.get("material_amount")));
			else material = add(material, amount(line.get("material_amount")));
		}
		String total = text(add(material, installationTotal, yes(parsed.get("equipment_freight_included")) ? ZERO : freight));
		parsed.putAll(obj("pending_count", pending, "unchanged_count", unchanged, "material_total", text(material), "equipment_freight_total", text(freight),
				"installation_total", text(installationTotal), "before_payment_total", total, "subtotal", total));
		return parsed;
	}

	/** 人工金额补齐后移除已解决的待确认提示和内部校对逻辑行，同时保留客户正常备注。 */
	private static String resolvedRemark(String remark) {
		List<String> kept = new ArrayList<>();
		for (String line : remark.split("\\R")) {
			String reviewNote = line.trim();
			if (reviewNote.startsWith("系统计价逻辑：") || reviewNote.startsWith("系统识别类型："))
				continue;
			for (String part : line.split("；")) {
				String text = part.trim();
				if (!text.isEmpty() && !text.startsWith("待确认：") && !text.startsWith("安装待确认：")
						&& !text.startsWith("待确定：") && !text.startsWith("安装待确定："))
					kept.add(text);
			}
		}
		return String.join("；", kept);
	}

	/** 读取隐藏元数据前 8 行的文件格式、报价身份、模板版本及可见工作表信息。 */
	private static Map<String, String> properties(Sheet metadata, FormulaEvaluator evaluator) {
		Map<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < 9; i++) {
			Row row = metadata.getRow(i);
			if (row != null)
				values.put(cellText(row.getCell(0), evaluator), cellText(row.getCell(1), evaluator));
		}
		return values;
	}

	/** 读取导出时保存的 11 项基准值，用于判断 Excel 中哪些字段被修改。 */
	private static Map<String, Object> baseline(Row row, FormulaEvaluator evaluator) {
		Map<String, Object> value = obj();
		for (int i = 0; i < FIELDS.size(); i++)
			value.put(FIELDS.get(i), cellText(row.getCell(i + 4), evaluator));
		return value;
	}

	/**
	 * 按 10、12 或 13 列校对模板读取产品字段。
	 * 无安装模板没有安装金额；旧版 12 列与新版含本行总金额的 13 列均采用各自固定列位。
	 */
	private static Map<String, Object> visible(Row row, FormulaEvaluator evaluator, int columns) {
		Map<String, Object> value = obj("name", cellText(row.getCell(1), evaluator), "thickness",
				cellText(row.getCell(2), evaluator), "spec", cellText(row.getCell(3), evaluator), "process",
				cellText(row.getCell(4), evaluator), "unit", cellText(row.getCell(5), evaluator), "quantity",
				cellText(row.getCell(6), evaluator), "material_unit", cellText(row.getCell(7), evaluator),
				"material_amount", cellText(row.getCell(8), evaluator));
		if (columns == 13)
			value.putAll(obj("installation_amount", cellText(row.getCell(10), evaluator), "line_total",
					cellText(row.getCell(11), evaluator), "remark", cellText(row.getCell(12), evaluator)));
		else if (columns == 12)
			value.putAll(obj("installation_amount", cellText(row.getCell(9), evaluator), "line_total",
					cellText(row.getCell(10), evaluator), "remark", cellText(row.getCell(11), evaluator)));
		else
			value.putAll(obj("installation_amount", "", "line_total", "", "remark",
					cellText(row.getCell(9), evaluator)));
		return value;
	}

	/** 读取合并的设备运费汇总金额单元格，并保留基准中的名称和行身份；该汇总不是普通产品行。 */
	private static Map<String, Object> visibleEquipmentFreight(Row row, FormulaEvaluator evaluator, int columns,
			Map<String, Object> baseline) {
		Map<String, Object> value = new LinkedHashMap<>(baseline);
		String amount = cellText(row.getCell(columns == 13 ? 11 : columns == 12 ? 10 : 8), evaluator);
		value.put("material_unit", amount);
		value.put("material_amount", amount);
		if (columns != 10) {
			value.put("installation_amount", "0");
			value.put("line_total", amount);
		}
		return value;
	}

	/** 统一数量与金额的小数表示，去掉无意义尾零，避免“2”和“2.0”被误报为人工修改。 */
	private static void normalizeNumbers(Map<String, Object> line, int row) {
		for (String key : List.of("quantity", "material_unit", "material_amount", "installation_amount",
				"line_total")) {
			String value = s(line, key);
			if (key.equals("quantity") && s(line, "scope").equals("excluded")) {
				try { line.put(key, value.isEmpty() ? "" : decimal(value, "数量").stripTrailingZeros().toPlainString()); }
				catch (RuleException ignored) { /* 范围外行保留客户原数量文字。 */ }
				continue;
			}
			line.put(key,
					value.isEmpty() ? "" : decimal(value, "第" + row + "行" + LABELS.get(key)).stripTrailingZeros().toPlainString());
		}
	}

	/**
	 * 判断原本需要报价的行是否已补齐：数量须为正，含安装时安装金额须明确填写。
	 * 材料单价/金额可补算一个缺项，本行总额由材料与安装金额重算；空白安装仍待确认，明确 0 有效。
	 */
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

	/**
	 * 材料单价或材料金额只填一个时按数量补算另一个，并标记为派生修改。
	 * 两项均已填写时保留人工值；从金额反算单价保留 8 位小数，避免除法造成无限循环小数。
	 */
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

	/** 汇总时把空金额当作 0；是否允许空值参与最终报价由 complete 的待确认校验决定。 */
	private static BigDecimal amount(Object value) {
		return blank(value) ? ZERO : decimal(str(value), "金额");
	}

	/** 解析含千分位、人民币符号及空白的人工数字，仅允许 0 至 1 万亿元范围。 */
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

	/** 严格解析元数据整数，拒绝非整数修订号、列数或行号。 */
	private static int integer(String value, String label) {
		try {
			return new BigDecimal(value).intValueExact();
		}
		catch (RuntimeException e) {
			throw new RuleException(label + "不正确");
		}
	}

	/** 读取导出时的待确认状态，兼容布尔文字、数字 1 和中文“是”。 */
	private static boolean booleanValue(Cell cell, FormulaEvaluator evaluator) {
		String value = cellText(cell, evaluator);
		return value.equalsIgnoreCase("true") || value.equals("1") || value.equals("是");
	}

	/**
	 * 读取单元格值并求取校对文件的公式结果，数字转为业务小数字符串。
	 * 本流程允许报价员在校对版用公式完善金额；无法读取的公式结果阻断回导。
	 */
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
