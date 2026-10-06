package com.pig4cloud.pig.admin.quotation;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import java.io.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/** Customer workbooks are data: never execute formulas or macros. */
public final class DuctImportService {

	public static final int MAX_BYTES = 24 * 1024 * 1024, MAX_ROWS = 10000, MAX_COLUMNS = 80;

	public static final List<String> ATTRIBUTES = List.of("model", "material", "flange", "area_basis", "product_state",
			"shape", "flange_material", "flange_size", "flange_grade", "flange_weight", "flange_meters", "diameter",
			"round_flange_count", "width", "height", "length", "thickness", "power", "airflow", "pressure",
			"pressure_type", "speed", "equipment_kind", "louver_type", "pricing_louver_type", "recognition_evidence",
			"attached_valve_kind", "attached_valve_width", "attached_valve_height", "attached_valve_diameter",
			"attached_valve_count", "attached_valve_cost_included", "fan_set_cost", "attached_valve_control",
			"attached_valve_material", "attached_valve_thickness", "attached_valve_splice_cost");

	private static final Map<String, Set<String>> HEADERS = Map.ofEntries(
			Map.entry("name", Set.of("名称", "产品名称", "材料名称", "项目名称", "构件名称")),
			Map.entry("engineering_quantity", Set.of("工程量", "总面积", "工程量(㎡)", "工程量（㎡）", "清单总量")),
			Map.entry("remark", Set.of("备注")), Map.entry("unit", Set.of("单位")),
			Map.entry("flange", Set.of("法兰方式", "法兰形式")), Map.entry("product_state", Set.of("报价备注", "成品状态", "风管状态")),
			Map.entry("round_flange_count", Set.of("圆法兰数量", "圆法兰个数", "法兰数量", "法兰个数")),
			Map.entry("diameter", Set.of("直径", "直径(mm)", "直径（mm）")));

	public List<Map<String, Object>> read(byte[] content, String filename) {
		String lower = filename.toLowerCase(Locale.ROOT);
		if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls"))
			throw new RuleException("仅支持.xlsx和.xls文件");
		if (content.length > MAX_BYTES)
			throw new RuleException("Excel超过24MB，请拆分");
		try {
			if (lower.endsWith(".xlsx")) {
				long expanded = 0;
				byte[] buffer = new byte[8192];
				try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
					int entries = 0;
					while (zip.getNextEntry() != null) {
						if (++entries > 10000)
							throw new RuleException("Excel压缩条目过多");
						int count;
						while ((count = zip.read(buffer)) != -1) {
							expanded += count;
							if (expanded > 80L * 1024 * 1024)
								throw new RuleException("Excel解压后超过80MB，请拆分清单");
						}
					}
				}
			}
			List<Map<String, Object>> sheets = new ArrayList<>();
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
				for (Sheet sheet : workbook) {
					if (sheet.getLastRowNum() >= MAX_ROWS)
						throw new RuleException("工作表超过10000行，请拆分");
					Map<Long, Cell> merged = new HashMap<>();
					for (CellRangeAddress range : sheet.getMergedRegions()) {
						if (range.getLastRow() >= MAX_ROWS || range.getLastColumn() >= MAX_COLUMNS)
							throw new RuleException("合并单元格超出导入范围");
						if (range.getFirstRow() == range.getLastRow())
							continue;
						Row first = sheet.getRow(range.getFirstRow());
						Cell cell = first == null ? null : first.getCell(range.getFirstColumn());
						for (int r = range.getFirstRow(); r <= range.getLastRow(); r++)
							for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++)
								merged.put((long) r * MAX_COLUMNS + c, cell);
					}
					int columns = 0;
					for (Row row : sheet)
						columns = Math.max(columns, row.getLastCellNum());
					if (columns > MAX_COLUMNS)
						throw new RuleException("工作表超过80列，请拆分");
					List<Map<String, Object>> rows = new ArrayList<>();
					for (int r = 0; r <= sheet.getLastRowNum(); r++) {
						List<String> cells = new ArrayList<>();
						List<Integer> issues = new ArrayList<>();
						Row row = sheet.getRow(r);
						for (int c = 0; c < columns; c++) {
							Cell cell = merged.containsKey((long) r * MAX_COLUMNS + c)
									? merged.get((long) r * MAX_COLUMNS + c) : row == null ? null : row.getCell(c);
							String value = value(cell);
							cells.add(value);
							if (cell != null
									&& (cell.getCellType() == CellType.ERROR || (cell.getCellType() == CellType.FORMULA
											&& (cell.getCachedFormulaResultType() == CellType.ERROR
													|| value.equals("[公式无缓存值]")))))
								issues.add(c);
						}
						if (cells.stream().anyMatch(v -> !v.isBlank()))
							rows.add(obj("number", r + 1, "cells", cells, "issues", issues));
					}
					Map<String, Object> parsed = obj("name", sheet.getSheetName(), "rows", rows);
					if (sheet.getSheetName().equals(AiQuoteImportService.REVIEW_SHEET_NAME)
							&& sheet.getRow(0) != null && value(sheet.getRow(0).getCell(AiQuoteImportService.REVIEW_KEY_COLUMN))
									.equals(AiQuoteImportService.REVIEW_MARKER))
						parsed.put("auxiliary", true);
					parsed.putAll(suggest(parsed));
					parsed.putAll(suggestEquipment(parsed));
					parsed.putAll(suggestAuto(parsed));
					sheets.add(parsed);
				}
			}
			if (sheets.stream().allMatch(s -> list(s.get("rows")).isEmpty()))
				throw new RuleException("文件没有可读取内容");
			attachReviewParameters(sheets);
			return sheets;
		}
		catch (RuleException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuleException("无法读取Excel，请确认文件未加密且格式正确");
		}
	}

	/** Treat the generated review tab as parameters for its linked main rows, never as additional products. */
	private static void attachReviewParameters(List<Map<String, Object>> sheets) {
		var reviews = sheets.stream().filter(sheet -> yes(sheet.get("auxiliary"))).toList();
		if (reviews.isEmpty()) {
			boolean linkedMain = sheets.stream().filter(sheet -> s(sheet, "name").equals(AiQuoteImportService.SHEET_NAME))
				.flatMap(sheet -> list(sheet.get("rows")).stream())
				.anyMatch(row -> cellText(row, AiQuoteImportService.MAIN_REVIEW_KEY_COLUMN).equals(AiQuoteImportService.REVIEW_KEY_HEADER));
			if (linkedMain) throw new RuleException("本文件缺少完整的人工核对工作表，请保留整份待核验报价单后导入");
			return;
		}
		var main = sheets.stream().filter(sheet -> s(sheet, "name").equals(AiQuoteImportService.SHEET_NAME)).findFirst()
			.orElseThrow(() -> new RuleException("人工核对表缺少对应报价主表，请导入完整文件"));
		Map<String, Map<String, Object>> linked = new LinkedHashMap<>();
		for (var source : list(main.get("rows"))) {
			if (n(source.get("number")).intValue() < AiQuoteImportService.DETAIL_START_ROW) continue;
			if (cellText(source, 0).equals("材料小计")) break;
			String key = cellText(source, AiQuoteImportService.MAIN_REVIEW_KEY_COLUMN);
			if (key.isBlank() || linked.putIfAbsent(key, source) != null)
				throw new RuleException("报价主表第" + source.get("number") + "行核对关联ID缺失或重复，请保留完整产品行");
		}
		List<String> keys = new ArrayList<>(EquipmentReview.HEADERS.keySet());
		Set<String> used = new HashSet<>();
		for (var review : reviews) {
			for (var source : list(review.get("rows"))) {
				if (n(source.get("number")).intValue() < 4) continue;
				String key = cellText(source, AiQuoteImportService.REVIEW_KEY_COLUMN);
				Map<String, Object> target = linked.get(key);
				if (target == null || !used.add(key))
					throw new RuleException("人工核对第" + source.get("number") + "行关联ID无效或重复，请勿混用不同文件或删除隐藏列");
				if (!cellText(source, 0).equals(cellText(target, 0)))
					throw new RuleException("人工核对第" + source.get("number") + "行与主表序号不一致，请核对对应产品");
				Map<String, Object> attributes = obj();
				for (int i = 0; i < keys.size(); i++) attributes.put(keys.get(i), cellText(source, i + 5));
				target.put("review_parameters", attributes);
				target.put("review_issue", ((List<?>) source.get("issues")).stream()
						.anyMatch(column -> n(column).intValue() >= 5 && n(column).intValue() < AiQuoteImportService.REVIEW_KEY_COLUMN));
			}
		}
		if (used.size() != linked.size()) throw new RuleException("人工核对表存在遗漏产品，请保留与主表一一对应的完整清单");
	}

	private static String cellText(Map<String, Object> row, int column) {
		List<Object> values = cells(row);
		return column < values.size() ? str(values.get(column)) : "";
	}

	private String value(Cell cell) {
		if (cell == null)
			return "";
		CellType type = cell.getCellType();
		if (type == CellType.FORMULA) {
			if (cell instanceof XSSFCell x && (!x.getCTCell().isSetV() || blank(x.getCTCell().getV())))
				return "[公式无缓存值]";
			type = cell.getCachedFormulaResultType();
		}
		return switch (type) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toString()
					: BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
			case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
			case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
			default -> "";
		};
	}

	@SuppressWarnings("unchecked")
	private static List<Object> cells(Map<String, Object> row) {
		return (List<Object>) row.get("cells");
	}

	private static List<Integer> indices(List<Object> cells, Set<String> names) {
		List<Integer> found = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++)
			if (names.stream().map(DuctImportService::header).toList().contains(header(str(cells.get(i)))))
				found.add(i);
		return found;
	}

	private static String header(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	private static boolean productHeader(Map<String, Object> mapping, List<Object> cells) {
		if (!mapping.containsKey("name"))
			return false;
		return !header(str(cells.get(n(mapping.get("name")).intValue()))).equals("名称")
				|| Set.of("spec", "model", "unit", "quantity", "engineering_quantity").stream().anyMatch(mapping::containsKey);
	}

	public Map<String, Object> suggest(Map<String, Object> sheet) {
		List<Map<String, Object>> candidates = new ArrayList<>();
		for (Map<String, Object> row : list(sheet.get("rows")).stream().limit(20).toList()) {
			Map<String, Object> found = new LinkedHashMap<>();
			List<Object> cells = cells(row);
			HEADERS.forEach((key, names) -> {
				List<Integer> at = indices(cells, names);
				if (at.size() == 1)
					found.put(key, at.get(0));
			});
			List<Integer> specs = indices(cells, Set.of("规格范围", "规格描述", "规格", "规格参数", "技术参数")),
					models = indices(cells, Set.of("规格型号"));
			if (specs.size() == 1) {
				found.put("spec", specs.get(0));
				if (models.size() == 1)
					found.put("model", models.get(0));
			}
			else if (specs.isEmpty() && models.size() == 1)
				found.put("spec", models.get(0));
			if (productHeader(found, cells))
				candidates
					.add(obj("suggested_mapping", found, "suggested_start_row", n(row.get("number")).intValue() + 1));
		}
		return candidates.size() == 1 ? candidates.get(0)
				: obj("suggested_mapping", obj(), "suggested_start_row", null);
	}

	private static String get(Map<String, Object> row, Map<String, Object> mapping, String key) {
		if (!mapping.containsKey(key) || mapping.get(key) == null)
			return "";
		int index = n(mapping.get(key)).intValueExact();
		List<Object> values = cells(row);
		return index < values.size() ? str(values.get(index)) : "";
	}

	public Map<String, Object> build(Map<String, Object> sheet, Object startValue, Map<String, Object> mapping) {
		return build(sheet, startValue, mapping, "duct_v1");
	}

	public Map<String, Object> suggestEquipment(Map<String, Object> sheet) {
		Map<String, Set<String>> headers = new LinkedHashMap<>(Map.ofEntries(
				Map.entry("name", Set.of("名称", "产品名称", "设备名称", "材料名称", "项目名称", "构件名称")),
				Map.entry("spec", Set.of("规格型号", "规格", "型号参数", "规格描述", "规格参数", "技术参数", "型号/规格")),
				Map.entry("quantity", Set.of("数量", "设备数量", "产品数量")), Map.entry("unit", Set.of("单位", "计量单位")),
				Map.entry("remark", Set.of("备注")), Map.entry("engineering_quantity", Set.of("工程量", "总面积", "清单总量")),
				Map.entry("airflow", Set.of("风量", "风量(m³/h)", "风量(m3/h)")),
				Map.entry("pressure", Set.of("风压", "风压(Pa)", "全压", "全压(Pa)", "静压", "静压(Pa)")),
				Map.entry("power", Set.of("功率", "功率(kW)", "电机功率(kW)")), Map.entry("model", Set.of("型号", "设备型号", "尺寸")),
				Map.entry("width", Set.of("宽", "宽度", "宽(mm)", "宽度(mm)")),
				Map.entry("height", Set.of("高", "高度", "高(mm)", "高度(mm)")),
				Map.entry("length", Set.of("长", "长度", "长度(mm)")), Map.entry("material", Set.of("材质", "材料材质")),
				Map.entry("thickness", Set.of("厚度", "厚度(mm)", "板厚", "板厚(mm)")),
				Map.entry("speed", Set.of("转速", "转速(r/min)", "转速(rpm)")),
				Map.entry("category", Set.of("品类", "产品类型", "设备类型")), Map.entry("shape", Set.of("管件类型")),
				Map.entry("flange_material", Set.of("角钢材质", "角铁材质")), Map.entry("flange_size", Set.of("角钢规格", "角铁规格")),
				Map.entry("flange_grade", Set.of("角钢档位")), Map.entry("flange_meters", Set.of("本行角钢米数", "角钢米数"))));
		EquipmentReview.HEADERS.forEach((key, label) -> headers.put(key, Set.of(label)));
		List<Map<String, Object>> candidates = new ArrayList<>();
		for (var row : list(sheet.get("rows")).stream().limit(30).toList()) {
			Map<String, Object> mapping = obj();
			List<Object> normalized = cells(row).stream()
				.map(v -> (Object) str(v).replace(" ", "").replace('（', '(').replace('）', ')'))
				.toList();
			headers.forEach((key, labels) -> {
				var at = indices(normalized, labels);
				if (at.size() == 1)
					mapping.put(key, at.get(0));
			});
			if (productHeader(mapping, normalized))
				candidates
					.add(obj("equipment_mapping", mapping, "equipment_start_row", n(row.get("number")).intValue() + 1));
		}
		var reviewHeaders = candidates.stream()
			.filter(c -> map(c.get("equipment_mapping")).containsKey("attached_valve_kind")).toList();
		if (reviewHeaders.size() == 1) return reviewHeaders.get(0);
		return candidates.size() == 1 ? candidates.get(0)
				: obj("equipment_mapping", obj(), "equipment_start_row", null);
	}

	public Map<String, Object> suggestAuto(Map<String, Object> sheet) {
		var duct = suggest(sheet);
		var equipment = suggestEquipment(sheet);
		var mappings = List.of(map(duct.get("suggested_mapping")), map(equipment.get("equipment_mapping")));
		Set<String> keys = new LinkedHashSet<>();
		mappings.forEach(m -> keys.addAll(m.keySet()));
		var mapping = obj();
		for (String key : keys) {
			Set<Object> values = new HashSet<>();
			mappings.stream().filter(m -> m.containsKey(key)).forEach(m -> values.add(m.get(key)));
			if (values.size() == 1)
				mapping.put(key, values.iterator().next());
		}
		if (mapping.get("quantity") != null
				&& Objects.equals(mapping.get("quantity"), mapping.get("engineering_quantity")))
			mapping.remove("quantity");
		Set<Object> starts = new HashSet<>();
		for (Object start : Arrays.asList(duct.get("suggested_start_row"), equipment.get("equipment_start_row")))
			if (start != null)
				starts.add(start);
		Object start = starts.size() == 1 ? starts.iterator().next() : null;
		List<Map<String, Object>> context = new ArrayList<>();
		if (start != null)
			for (var row : list(sheet.get("rows"))) {
				if (n(row.get("number")).compareTo(n(start)) < 0)
					continue;
				String kind = contextKind(row, mapping);
				if (!kind.isEmpty())
					context.add(obj("number", row.get("number"), "kind", kind, "label", get(row, mapping, "name")));
			}
		return obj("auto_mapping", mapping, "auto_start_row", start, "auto_context_rows", context);
	}

	/**
	 * Only explicit document structure is skipped; incomplete products remain visible.
	 */
	private static String contextKind(Map<String, Object> row, Map<String, Object> mapping) {
		String name = get(row, mapping, "name").replaceAll("\\s+", "");
		String text = String.join("", cells(row).stream().map(QuoteData::str).toList()).replaceAll("\\s+", "");
		if (text.startsWith("备注：") || text.startsWith("备注:") || text.startsWith("报价说明：") || text.startsWith("报价说明:")
				|| name.startsWith("备注：") || name.startsWith("备注:") || name.startsWith("报价说明：")
				|| name.startsWith("报价说明:")
				|| text.contains("合计"))
			return "note_or_total";
		if (!get(row, mapping, "unit").isBlank() || !get(row, mapping, "quantity").isBlank()
				|| !get(row, mapping, "engineering_quantity").isBlank() || !get(row, mapping, "spec").isBlank()
				|| ATTRIBUTES.stream().anyMatch(key -> !get(row, mapping, key).isBlank()))
			return "";
		if (name.isEmpty() && text.matches("(?:送货人签字|收货人签字|联系方式|车牌|日期)[:：].*"))
			return "note_or_total";
		if (text.startsWith("说明") || name.matches("人工[+＋]材料[（(].*[）)][:：]?"))
			return "note_or_total";
		if (name.matches("-?\\d+(?:\\.\\d+)?米层|[一二三四五六七八九十百\\d]+层|\\d+(?:#|号)?(?:栋|幢|楼)|(?:风管|设备|风阀|风口)部分|.+(?:装置楼|泵房|机房|小间)"
				+ "|(?:\\d+(?:#|号)?[、,，])+\\d+(?:#|号)?(?:栋|幢|楼)(?:地下室)?"
				+ "|\\d+#楼(?:地下室(?:负?[一二三四五六七八九十百\\d]+层)?|(?:[一二三四五六七八九十百\\d]+层)?[~～至-](?:屋顶|楼顶))"))
			return "section";
		return "";
	}

	public static void autoClassifyRow(Map<String, Object> row) {
		String category = s(row, "category");
		String description = s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark");
		if (category.isEmpty() || category.equals("风管")) {
			var candidate = new LinkedHashMap<>(row);
			candidate.put("category", "");
			String kind = EquipmentPricingEngine.classify(candidate);
			category = kind.equals("fan") ? "风机"
					: EquipmentCatalog.VALVES.contains(kind) || kind.equals("pressure_relief") ? "风阀"
							: kind.equals("louver") ? "百叶风口" : Set.of("elbow", "plenum", "silencer").contains(kind)
									? "消声设备"
									: kind.equals("purchased") ? "外购设备" : description.contains("风管") ? "风管" : "";
		}
		if (s(row, "category").isEmpty() || s(row, "category").equals("风管"))
			row.put("category", category);
		if (blank(row.get("engineering_quantity")) && !blank(row.get("quantity"))
				&& Set.of("㎡", "m2", "m²", "平方米", "平方").contains(s(row, "unit")))
			row.put("engineering_quantity", row.get("quantity"));
		if (blank(row.get("quantity")) && !blank(row.get("engineering_quantity"))
				&& Set.of("台", "个", "只", "套", "件", "组", "樘").contains(s(row, "unit")))
			row.put("quantity", row.get("engineering_quantity"));
	}

	public Map<String, Object> build(Map<String, Object> sheet, Object startValue, Map<String, Object> mapping,
			String mode) {
		return build(sheet, startValue, mapping, mode, false);
	}

	/**
	 * Read every worksheet independently, then preserve workbook and source row order.
	 */
	public Map<String, Object> buildWorkbook(List<Map<String, Object>> sheets, Object settingsValue) {
		Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
		Set<String> names = new HashSet<>();
		sheets.forEach(sheet -> names.add(s(sheet, "name")));
		for (var setting : list(settingsValue)) {
			String name = s(setting, "name");
			if (!names.contains(name) || settings.putIfAbsent(name, setting) != null)
				throw new RuleException("工作表设置重复或不存在：" + name);
			if (setting.containsKey("excluded") && !bool(setting.get("excluded")))
				throw new RuleException("工作表【" + name + "】排除状态不正确");
		}
		List<Map<String, Object>> rows = new ArrayList<>(), special = new ArrayList<>(), sources = new ArrayList<>();
		Map<String, Object> totals = obj();
		Map<String, String> totalSources = new HashMap<>();
		Set<String> flanges = new HashSet<>();
		for (var sheet : sheets) {
			String name = s(sheet, "name");
			var setting = settings.getOrDefault(name, obj());
			if (yes(sheet.get("auxiliary"))) {
				sources.add(obj("name", name, "status", "review_parameters", "row_count", 0, "linked_sheet", AiQuoteImportService.SHEET_NAME));
				continue;
			}
			if (yes(setting.get("excluded"))) {
				String reason = s(setting, "exclude_reason");
				if (reason.isEmpty() || reason.length() > 1000)
					throw new RuleException("工作表【" + name + "】不参与报价时必须填写原因（不超过1000字）");
				sources.add(obj("name", name, "status", "excluded", "row_count", 0, "exclude_reason", reason));
				continue;
			}
			if (list(sheet.get("rows")).isEmpty()) {
				sources.add(obj("name", name, "status", "empty", "row_count", 0));
				continue;
			}
			var suggested = suggestAuto(sheet);
			Object start = setting.getOrDefault("start_row", suggested.get("auto_start_row"));
			Map<String, Object> mapping = map(setting.getOrDefault("mapping", suggested.get("auto_mapping")));
			if (blank(start) || mapping.get("name") == null)
				throw new RuleException("工作表【" + name + "】表头无法唯一识别，请核对起始行和产品名称列；说明页可注明原因后排除");
			Map<String, Object> built;
			try {
				built = build(sheet, start, mapping, "mixed_v1", true);
				if (list(sheet.get("rows")).stream().noneMatch(r -> n(r.get("number")).compareTo(n(start)) >= 0))
					throw new RuleException("起始行后没有数据，请核对起始行；说明页可注明原因后排除");
			}
			catch (RuleException e) {
				throw new RuleException("工作表【" + name + "】：" + e.getMessage());
			}
			var sheetRows = list(built.get("rows"));
			rows.addAll(sheetRows);
			if (rows.size() > MAX_ROWS)
				throw new RuleException("整份清单产品行超过10000行，请拆分文件");
			special.addAll(list(built.get("source_special_rows")));
			for (var entry : map(built.get("duct_options")).entrySet()) {
				String key = entry.getKey();
				if (key.equals("flange")) {
					flanges.add(str(entry.getValue()));
					continue;
				}
				if (totals.containsKey(key))
					throw new RuleException("工作表【" + totalSources.get(key) + "】与【" + name + "】存在相同配件汇总，请先核对并合并，避免重复计费");
				totals.put(key, entry.getValue());
				totalSources.put(key, name);
			}
			sources.add(obj("name", name, "status", "imported", "start_row", start, "mapping", mapping, "row_count",
					sheetRows.size()));
		}
		if (rows.isEmpty())
			throw new RuleException("整份清单没有产品数据，请核对各工作表的起始行和列映射");
		if (!flanges.isEmpty())
			totals.put("flange", flanges.size() == 1 ? flanges.iterator().next() : "mixed");
		return obj("rows", rows, "duct_options", totals, "source_special_rows", special, "source_sheets", sources);
	}

	private Map<String, Object> build(Map<String, Object> sheet, Object startValue, Map<String, Object> mapping,
			String mode, boolean allowEmpty) {
		int start;
		try {
			start = n(startValue).intValueExact();
		}
		catch (RuntimeException e) {
			throw new RuleException("起始行必须是正整数");
		}
		if (start < 1 || start > MAX_ROWS)
			throw new RuleException("起始行超出范围");
		if (mapping.get("name") == null)
			throw new RuleException("请映射产品名称列；工程量缺失时可逐行补填");
		Set<Integer> mapped = new HashSet<>();
		Set<String> fields = new HashSet<>(ATTRIBUTES);
		fields.addAll(Set.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark", "category"));
		for (var entry : mapping.entrySet()) {
			if (!fields.contains(entry.getKey()))
				throw new RuleException("列映射字段未识别");
			if (entry.getValue() == null)
				continue;
			int index;
			try {
				index = n(entry.getValue()).intValueExact();
			}
			catch (RuntimeException e) {
				throw new RuleException("列映射必须为整数");
			}
			if (index < 0 || index >= MAX_COLUMNS || !mapped.add(index))
				throw new RuleException("列映射超出范围或不同字段映射到同一列");
		}
		List<Map<String, Object>> rows = new ArrayList<>(), special = new ArrayList<>();
		Map<String, Object> totals = obj();
		for (Map<String, Object> source : list(sheet.get("rows"))) {
			if (n(source.get("number")).intValue() < start)
				continue;
			List<Object> visible = cells(source);
			if (!visible.isEmpty() && str(visible.get(0)).trim().equals("材料小计")
					&& get(source, mapping, "name").isBlank() && get(source, mapping, "quantity").isBlank())
				break;
			String context = mode.equals("mixed_v1") ? contextKind(source, mapping) : "";
			if (!context.isEmpty()) {
				special.add(obj("source_row", source.get("number"), "source_sheet", sheet.get("name"), "kind", context,
						"original", source));
				continue;
			}
			String desc = String.join(" ", cells(source).stream().map(QuoteData::str).toList()),
					normalized = desc.replace(" ", "");
			if (!mode.equals("equipment_v1") && (normalized.startsWith("备注：") || normalized.startsWith("备注:")
					|| normalized.startsWith("报价说明：") || normalized.startsWith("报价说明:") || normalized.contains("合计"))) {
				special.add(obj("source_row", source.get("number"), "source_sheet", sheet.get("name"), "kind",
						"note_or_total", "original", source));
				continue;
			}
			String unit = get(source, mapping, "unit").toLowerCase(Locale.ROOT), raw = get(source, mapping, "quantity"),
					key = null;
			if (raw.isBlank())
				raw = get(source, mapping, "engineering_quantity");
			if (normalized.contains("角码") && Set.of("个", "只", "件", "pcs", "pc").contains(unit))
				key = "corner_count";
			else if ((normalized.contains("卡条") || normalized.contains("卡码") || normalized.contains("卡扣"))
					&& Set.of("米", "m", "meter", "metre").contains(unit))
				key = "clip_meters";
			Matcher flange = Pattern.compile("(?<!\\d)(30|40)\\s*#?\\s*(?:角钢|角铁)").matcher(desc);
			if (key == null && flange.find() && Set.of("米", "m", "meter", "metre").contains(unit))
				key = "flange_" + flange.group(1) + "_meters";
			if (key != null && !mode.equals("equipment_v1")) {
				var fieldIssues = map(source.get("model_field_issues"));
				boolean pendingQuantity = raw.isBlank()
						&& (fieldIssues.containsKey("quantity") || fieldIssues.containsKey("engineering_quantity"));
				String quantity = pendingQuantity ? "" : text(positive(raw, "第" + source.get("number") + "行辅材数量/米数"));
				if (totals.containsKey(key))
					throw new RuleException("清单存在多行相同配件汇总，请先合并或明确处理");
				totals.put(key, quantity);
				special.add(obj("source_row", source.get("number"), "source_sheet", sheet.get("name"), "kind", key,
						"quantity", quantity, "original", source));
				continue;
			}
			Map<String, Object> row = obj("source_row", source.get("number"), "source_sheet", sheet.get("name"),
					"source_text", desc, "original", source);
			for (String field : List.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark",
					"category"))
				row.put(field, get(source, mapping, field));
			if (mapping.containsKey("attached_valve_kind"))
				// Review hints and component inputs must not leak into the fan's parameter parser.
				row.put("source_text", s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark"));
			if (mode.equals("duct_v1") && blank(row.get("unit")))
				row.put("unit", "㎡");
			if (!mode.equals("equipment_v1") && blank(row.get("category")) && s(row, "name").contains("风管"))
				row.put("category", "风管");
			Map<String, Object> attributes = obj();
			for (String field : ATTRIBUTES) {
				String v = get(source, mapping, field);
				if (!v.isEmpty())
					attributes.put(field, mode.equals("equipment_v1") ? v : attribute(field, v));
			}
			if (source.containsKey("review_parameters")) {
				EquipmentReview.HEADERS.keySet().forEach(attributes::remove);
				map(source.get("review_parameters")).forEach((field, value) -> { if (!blank(value)) attributes.put(field, value); });
				String originalSpec = cellText(source, 23).isBlank() ? cellText(source, 15) : cellText(source, 23);
				String editedSpec = cellText(source, 3);
				if (!editedSpec.equals(originalSpec)) {
					// A visible specification edit invalidates parameters parsed from the old specification.
					var previous = EquipmentPricingEngine.parseAttributes(obj("name", row.get("name"), "spec", originalSpec));
					var current = EquipmentPricingEngine.parseAttributes(obj("name", row.get("name"), "spec", editedSpec));
					for (String field : List.of("model", "airflow", "pressure", "power", "width", "height", "length", "diameter"))
						if (previous.containsKey(field) || current.containsKey(field)) attributes.remove(field);
				}
			}
			boolean issue = yes(source.get("review_issue")) || ((List<?>) source.get("issues")).stream().anyMatch(v -> mapped.contains(n(v).intValue()));
			row.putAll(obj("attributes", attributes, "excluded", false, "exclude_reason", "", "source_issue", issue,
					"source_issue_confirmed", false));
			EquipmentReview.refresh(row);
			if (!mode.equals("duct_v1"))
				autoClassifyRow(row);
			if (mode.equals("equipment_v1"))
				row.put("engineering_quantity", "");
			rows.add(row);
		}
		if (rows.isEmpty() && !allowEmpty)
			throw new RuleException("起始行后没有产品数据");
		Set<String> flanges = new HashSet<>();
		rows.forEach(r -> {
			if (!blank(attr(r, "flange")))
				flanges.add(str(attr(r, "flange")));
		});
		if (totals.containsKey("flange_30_meters") || totals.containsKey("flange_40_meters"))
			flanges.add("angle");
		if (!flanges.isEmpty())
			totals.put("flange", flanges.size() == 1 ? flanges.iterator().next() : "mixed");
		return obj("rows", rows, "duct_options", totals, "source_special_rows", special);
	}

	private String attribute(String field, String value) {
		if (field.equals("product_state"))
			return value.contains("半成品") ? "semi" : value.contains("成品") ? "finished" : value;
		return switch (field) {
			case "material" -> Map.of("镀锌板", "galvanized", "40g镀锌板", "galvanized_40", "80g镀锌板", "galvanized_80",
					"201不锈钢", "stainless_201", "304不锈钢", "stainless_304")
				.getOrDefault(value, value);
			case "shape" -> Map.of("直管", "straight", "异形件", "irregular").getOrDefault(value, value);
			case "flange" -> Map.of("共板法兰", "common", "共板", "common", "角钢法兰", "angle", "角钢", "angle", "角铁法兰", "angle",
					"角铁", "angle", "圆法兰", "round", "圆形法兰", "round")
				.getOrDefault(value, value);
			case "flange_material" -> Map.of("黑角铁", "black", "热镀锌角铁", "hot_galvanized").getOrDefault(value, value);
			case "flange_size" -> Map.of("30角钢", "30", "40角钢", "40", "50角钢", "50").getOrDefault(value, value);
			default -> value;
		};
	}

}
