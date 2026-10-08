package com.pig4cloud.pig.admin.quotation;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import java.io.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 读取客户 Excel、建议列映射并构造风管或逐行混合报价的录入数据。
 * 保留来源工作表、原行号、原单元格及说明/汇总行；客户旧价格不作为系统计价依据。
 * 客户工作簿只作为资料读取，不执行其中的公式或宏。
 */
public final class DuctImportService {

	// 普通导入最多 24MB、每表 10000 行和 80 列；整份产品行还会再次检查合计上限。
	public static final int MAX_BYTES = 24 * 1024 * 1024, MAX_ROWS = 10000, MAX_COLUMNS = 80;

	// 可导入的独立业务参数白名单，包含风管工艺、设备工况及需人工核实的附阀参数。
	public static final List<String> ATTRIBUTES = List.of("model", "material", "flange", "area_basis", "product_state",
			"shape", "flange_material", "flange_size", "flange_grade", "flange_weight", "flange_meters", "diameter",
			"round_flange_count", "width", "height", "length", "thickness", "power", "airflow", "pressure",
			"pressure_type", "speed", "position_tag", "dimension_expression", "panel_height", "equipment_kind", "louver_type", "pricing_louver_type", "recognition_evidence",
			"attached_valve_kind", "attached_valve_width", "attached_valve_height", "attached_valve_diameter",
			"attached_valve_count", "attached_valve_cost_included", "fan_set_cost", "attached_valve_control",
			"attached_valve_material", "attached_valve_thickness", "attached_valve_splice_cost");

	// 表头按完整文字匹配，不使用包含关键词的模糊列选择，避免重复表头误配。
	private static final Map<String, Set<String>> HEADERS = Map.ofEntries(
			Map.entry("name", Set.of("名称", "产品名称", "材料名称", "物品名称", "项目名称", "构件名称")),
			Map.entry("engineering_quantity", Set.of("工程量", "总面积", "工程量(㎡)", "工程量（㎡）", "清单总量")),
			Map.entry("remark", Set.of("备注")), Map.entry("unit", Set.of("单位")),
			Map.entry("flange", Set.of("法兰方式", "法兰形式")), Map.entry("product_state", Set.of("报价备注", "成品状态", "风管状态")),
			Map.entry("round_flange_count", Set.of("圆法兰数量", "圆法兰个数", "法兰数量", "法兰个数")),
			Map.entry("diameter", Set.of("直径", "直径(mm)", "直径（mm）")));

	// 系统标准清单的来源字段只承担追溯，不作为产品参数或价格依据。
	private static final Map<String, String> SOURCE_HEADERS = Map.ofEntries(
			Map.entry("source_file", "来源文件"), Map.entry("source_sheet", "来源工作表原名"),
			Map.entry("source_sheet_index", "来源工作表序号"), Map.entry("source_row", "来源行号"),
			Map.entry("source_locator", "来源定位"), Map.entry("source_excerpt", "来源原文"),
			Map.entry("source_issue", "原资料冲突"), Map.entry("source_issue_reason", "原资料冲突原因"));

	/**
	 * 将 xlsx/xls 转为按工作表组织的文本行，同时记录错误单元格和缺失公式缓存。
	 * 先限制压缩展开规模、行列数及合并范围；纵向合并内容向下继承，随后识别表头并关联人工核对参数。
	 */
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
						// 只向下继承跨行合并的内容；横向标题合并不铺满各列，避免制造重复产品表头。
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
						List<String> originalCells = new ArrayList<>();
						List<Integer> issues = new ArrayList<>();
						Row row = sheet.getRow(r);
						for (int c = 0; c < columns; c++) {
							Cell cell = merged.containsKey((long) r * MAX_COLUMNS + c)
									? merged.get((long) r * MAX_COLUMNS + c) : row == null ? null : row.getCell(c);
							String value = value(cell);
							cells.add(value);
							originalCells.add(cell != null && cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : value);
							if (cell != null
									&& (cell.getCellType() == CellType.ERROR || (cell.getCellType() == CellType.FORMULA
											&& (cell.getCachedFormulaResultType() == CellType.ERROR
													|| value.equals("[公式无缓存值]")))))
								issues.add(c);
						}
						if (cells.stream().anyMatch(v -> !v.isBlank())) {
							var parsedRow = obj("number", r + 1, "cells", cells, "issues", issues);
							if (!originalCells.equals(cells)) parsedRow.put("original_cells", originalCells);
							rows.add(parsedRow);
						}
					}
					Map<String, Object> parsed = obj("name", sheet.getSheetName(), "sheet_index", workbook.getSheetIndex(sheet),
							"source_file", filename, "rows", rows);
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

	/**
	 * 把系统人工核对表的参数按隐藏关联 ID 挂到主表产品行。
	 * 核对表只提供参数、不生成额外产品；ID、序号及一一对应关系不完整时拒绝导入，防止串行。
	 */
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

	/** 读取已解析的原始单元格文本；短行缺少该列时返回空值。 */
	private static String cellText(Map<String, Object> row, int column) {
		List<Object> values = cells(row);
		return column < values.size() ? str(values.get(column)) : "";
	}

	/**
	 * 读取客户单元格现有文本或公式缓存，保持数字精度便于业务解析。
	 * 不计算客户公式；xlsx 公式没有缓存时返回明确标记，后续报价必须人工核对。
	 */
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
			case STRING -> cell.getCellType() == CellType.STRING ? temperatureText(cell) : cell.getStringCellValue();
			case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toString()
					: BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
			case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
			case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
			default -> "";
		};
	}

	/** 仅把实际设置上标的零识别为度数符号，防止把正常的 700C 错写为 70℃。 */
	private static String temperatureText(Cell cell) {
		RichTextString rich = cell.getRichStringCellValue();
		String text = rich.getString();
		if (rich.numFormattingRuns() == 0) return text;
		Matcher matcher = Pattern.compile("(?<!\\d)(\\d+)0\\s*[cC]").matcher(text);
		StringBuilder normalized = new StringBuilder();
		while (matcher.find()) {
			int zero = matcher.end(1);
			Font font = null;
			int formattedFrom = -1;
			for (int i = 0; i < rich.numFormattingRuns(); i++) {
				int start = rich.getIndexOfFormattingRun(i);
				int end = i + 1 < rich.numFormattingRuns() ? rich.getIndexOfFormattingRun(i + 1) : text.length();
				if (zero < start || zero >= end) continue;
				formattedFrom = start;
				if (rich instanceof XSSFRichTextString x) font = x.getFontOfFormattingRun(i);
				else if (rich instanceof HSSFRichTextString h && h.getFontOfFormattingRun(i) >= 0)
					font = cell.getSheet().getWorkbook().getFontAt(h.getFontOfFormattingRun(i));
				break;
			}
			matcher.appendReplacement(normalized, Matcher.quoteReplacement(font != null && formattedFrom == zero && font.getTypeOffset() == Font.SS_SUPER
					? matcher.group(1) + "℃" : matcher.group()));
		}
		return matcher.appendTail(normalized).toString();
	}

	/** 取得 read 方法保存的原始单元格列表，供列映射和来源记录共用。 */
	@SuppressWarnings("unchecked")
	private static List<Object> cells(Map<String, Object> row) {
		return (List<Object>) row.get("cells");
	}

	/** 按标准化后的完整表头查找所有命中列，让调用方只接受唯一匹配。 */
	private static List<Integer> indices(List<Object> cells, Set<String> names) {
		List<Integer> found = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++)
			if (names.stream().map(DuctImportService::header).toList().contains(header(str(cells.get(i)))))
				found.add(i);
		return found;
	}

	/** 统一全角字符、大小写及空白，兼容换行表头和全角参数单位。 */
	private static String header(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	/** 判断候选行是否具有产品表头意义；孤立的“名称”标签需配合规格、单位或数量列才成立。 */
	private static boolean productHeader(Map<String, Object> mapping, List<Object> cells) {
		if (!mapping.containsKey("name"))
			return false;
		return !header(str(cells.get(n(mapping.get("name")).intValue()))).equals("名称")
				|| Set.of("spec", "model", "unit", "quantity", "engineering_quantity").stream().anyMatch(mapping::containsKey);
	}

	/**
	 * 在前 20 条非空行中识别风管常用表头。
	 * 名称及规格列均要求唯一；整表有多个表头候选时返回空建议，交由报价员确认。
	 */
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
			List<Integer> specs = indices(cells, Set.of("规格范围", "规格描述", "规格", "规格参数", "技术参数", "项目特征描述")),
					models = indices(cells, Set.of("规格型号", "规格及型号"));
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

	/** 按已确认列映射读取一个业务字段；未映射或单元格缺失时保留空值。 */
	private static String get(Map<String, Object> row, Map<String, Object> mapping, String key) {
		if (!mapping.containsKey(key) || mapping.get(key) == null)
			return "";
		int index = n(mapping.get(key)).intValueExact();
		List<Object> values = cells(row);
		return index < values.size() ? str(values.get(index)) : "";
	}

	/** 工作表名可能仅有空格，追溯字段保留原字符串，不使用业务文本的 trim。 */
	private static String sourceValue(Map<String, Object> row, Map<String, Object> mapping, String key) {
		if (mapping.get(key) == null) return "";
		int index = n(mapping.get(key)).intValueExact();
		List<Object> values = cells(row);
		return index < values.size() && values.get(index) != null ? values.get(index).toString() : "";
	}

	/** 以兼容的风管模式构造单表清单，后续仍由原风管引擎处理价格。 */
	public Map<String, Object> build(Map<String, Object> sheet, Object startValue, Map<String, Object> mapping) {
		return build(sheet, startValue, mapping, "duct_v1");
	}

	/**
	 * 识别设备型号、数量、工况及人工核对参数列，支持中文表头的常见写法。
	 * 含附阀核对字段的唯一候选优先；其余仍要求整表只有一个表头候选。
	 */
	public Map<String, Object> suggestEquipment(Map<String, Object> sheet) {
		Map<String, Set<String>> headers = new LinkedHashMap<>(Map.ofEntries(
				Map.entry("name", Set.of("名称", "产品名称", "设备名称", "材料名称", "物品名称", "项目名称", "构件名称")),
				Map.entry("spec", Set.of("规格型号", "规格及型号", "项目特征描述", "规格", "型号参数", "规格描述", "规格参数", "技术参数", "型号/规格")),
				Map.entry("quantity", Set.of("数量", "设备数量", "产品数量", "材料数量")), Map.entry("unit", Set.of("单位", "计量单位")),
				Map.entry("remark", Set.of("备注")), Map.entry("engineering_quantity", Set.of("工程量", "总面积", "清单总量")),
				Map.entry("airflow", Set.of("风量", "风量(m³/h)", "风量(m3/h)")),
				Map.entry("pressure", Set.of("风压", "风压(Pa)", "全压", "全压(Pa)", "静压", "静压(Pa)")),
				Map.entry("power", Set.of("功率", "功率(kW)", "电机功率(kW)")), Map.entry("model", Set.of("型号", "设备型号", "尺寸")),
				Map.entry("width", Set.of("宽", "宽度", "宽(mm)", "宽度(mm)")),
				Map.entry("height", Set.of("高", "高度", "高(mm)", "高度(mm)")),
				Map.entry("length", Set.of("长", "长度", "长度(mm)")), Map.entry("material", Set.of("材质", "材料材质")),
				Map.entry("thickness", Set.of("厚度", "厚度(mm)", "板厚", "板厚(mm)")),
				Map.entry("speed", Set.of("转速", "转速(r/min)", "转速(rpm)")),
				Map.entry("position_tag", Set.of("原位号")), Map.entry("dimension_expression", Set.of("原尺寸算式")),
				Map.entry("panel_height", Set.of("面板附加高度(mm)")),
				Map.entry("category", Set.of("品类", "产品类型", "设备类型")), Map.entry("shape", Set.of("管件类型")),
				Map.entry("flange_material", Set.of("角钢材质", "角铁材质")), Map.entry("flange_size", Set.of("角钢规格", "角铁规格")),
				Map.entry("flange_grade", Set.of("角钢档位")), Map.entry("flange_meters", Set.of("本行角钢米数", "角钢米数"))));
		EquipmentReview.HEADERS.forEach((key, label) -> headers.put(key, Set.of(label)));
		SOURCE_HEADERS.forEach((key, label) -> headers.put(key, Set.of(label)));
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

	/**
	 * 合并风管和设备的表头建议，只保留两方不冲突的列和唯一开始行。
	 * 共用数量/工程量列只映射一次，按单位由逐行分类分派；明确的楼层及说明行另外列出。
	 */
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
	 * 识别明确的说明、合计和楼层分段行，保存为非产品来源记录。
	 * 明确金额小计和签章页脚独立保存；其余带规格、单位、数量或独立参数的行继续保留为产品。
	 */
	private static String contextKind(Map<String, Object> row, Map<String, Object> mapping) {
		String name = get(row, mapping, "name").replaceAll("\\s+", "");
		String text = String.join("", cells(row).stream().map(QuoteData::str).toList()).replaceAll("\\s+", "");
		if (text.startsWith("备注：") || text.startsWith("备注:") || text.startsWith("报价说明：") || text.startsWith("报价说明:")
				|| text.startsWith("附注：") || text.startsWith("附注:") || text.startsWith("校对版说明：")
				|| name.startsWith("备注：") || name.startsWith("备注:") || name.startsWith("报价说明：")
				|| name.startsWith("报价说明:") || name.startsWith("附注：") || name.startsWith("附注:"))
			return "note_or_total";
		String total = "(?:采购总金额(?:[（(].*?[）)])?|(?:材料|设备|风阀|风口|安装|人工|人工费|分部|工程)?(?:合计|小计)|价税合计|金额合计|金额小计|合计金额(?:[（(]大写[）)])?|总金额|总价|总计|税额|税金|含税(?:含运)?(?:总价|合价|合计)|不含税(?:含运)?(?:总价|合价|合计)|报价金额大写|大写金额)[：:]?";
		if (name.matches(total) || name.isEmpty() && cells(row).stream().map(QuoteData::str)
				.map(v -> v.replaceAll("\\s+", "")).anyMatch(v -> v.matches(total)))
			return "note_or_total";
		String spec = get(row, mapping, "spec").replaceAll("\\s+", ""), unit = get(row, mapping, "unit").replaceAll("\\s+", "");
		boolean noQuantity = get(row, mapping, "quantity").isBlank() && get(row, mapping, "engineering_quantity").isBlank();
		boolean noParameters = ATTRIBUTES.stream().allMatch(key -> get(row, mapping, key).isBlank());
		boolean titleLayout = noQuantity && unit.isEmpty() && noParameters && (spec.isEmpty() || spec.equals(name));
		String first = cells(row).stream().map(QuoteData::str).filter(v -> !v.isBlank()).findFirst().orElse("").replaceAll("\\s+", "");
		Set<String> chapterTitles = Set.of("氢气单元", "中试验证单元", "氮气及压缩空气单元", "采暖通风", "自控仪表", "工艺管道",
				"空调类", "风机类", "制氢厂房暖通设备", "除盐水站暖通设备", "给水及消防泵站暖通设备", "危废暂存间暖通设备",
				"给水及消防泵站", "碳钢管", "地管阀门", "生产给水系统(iw)", "生活水管线(ptw)", "阻尼器", "通风防排烟系统安装工程");
		if (titleLayout && chapterTitles.contains(header(name))
				&& first.matches("(?:[一二三四五六七八九十百]+|\\d+(?:\\.\\d+){0,3})")) return "section";
		if (name.isEmpty() && noQuantity && unit.isEmpty() && noParameters
				&& Set.of("通风", "卫生间", "风管", "总保温").contains(spec)) return "section";
		if (titleLayout && (Set.of("报价要求", "报价说明").contains(name)
				|| name.matches("(?:\\d+[、.．])?(?:质量技术|交货地点|运输费用|供货周期|报价时效|付款方式|其他要求)(?:[：:].*)?")))
			return "note_or_total";
		String signature = "(?:申请人|审核人|审批人|材料员|复核人|核对人|收货人|送货人)(?:签字)?(?:[（(].*?[）)])?(?:[：:].*)?";
		if (noQuantity && noParameters && first.matches(signature)
				&& (name.isEmpty() || name.matches(signature)) && (spec.isEmpty() || spec.matches(signature))
				&& (unit.isEmpty() || unit.matches(signature))) return "note_or_total";
		if (!get(row, mapping, "unit").isBlank() || !get(row, mapping, "quantity").isBlank()
				|| !get(row, mapping, "engineering_quantity").isBlank() || !get(row, mapping, "spec").isBlank()
				|| ATTRIBUTES.stream().anyMatch(key -> !get(row, mapping, key).isBlank()))
			return "";
		if (name.isEmpty() && (text.matches("(?:送货人签字|收货人签字|联系方式|车牌|日期|制单员|制表人|核价员|采购员|核准人|供方签章|需方签章|供方盖章|需方盖章)[:：].*")
				|| text.matches("[（(]请确认后签章[）)]")))
			return "note_or_total";
		if (name.isEmpty()) return "note_or_total";
		if (text.startsWith("说明") || name.matches("人工[+＋]材料[（(].*[）)][:：]?"))
			return "note_or_total";
		if (name.matches("-?\\d+(?:\\.\\d+)?米层|[一二三四五六七八九十百\\d]+层|\\d+(?:#|号)?(?:栋|幢|楼)|(?:风管|设备|风阀|风口)部分|.+(?:装置楼|泵房|机房|小间)"
				+ "|(?:\\d+(?:#|号)?[、,，])+\\d+(?:#|号)?(?:栋|幢|楼)(?:地下室)?"
				+ "|\\d+#楼(?:地下室(?:负?[一二三四五六七八九十百\\d]+层)?|(?:[一二三四五六七八九十百\\d]+层)?[~～至-](?:屋顶|楼顶))"))
			return "section";
		return "";
	}

	/**
	 * 按名称、规格及明确参数识别本行所属设备或风管类别。
	 * 尊重已明确指定的其他类别，旧默认风管允许重新识别；面积单位传给工程量，数量单位传给设备数量。
	 */
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

	/** 按指定计价模式构造单表清单；默认要求起始行后至少存在一条产品。 */
	public Map<String, Object> build(Map<String, Object> sheet, Object startValue, Map<String, Object> mapping,
			String mode) {
		return build(sheet, startValue, mapping, mode, false);
	}

	/**
	 * 逐表应用映射和起始行，按原工作表/原行顺序合并为同一报价。
	 * 空表可跳过，说明页排除需原因；非空表不能唯一识别时阻断，重复辅材汇总不能覆盖或重复计价。
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
			// 排除非空工作表必须明确说明原因，导入摘要保留排除记录以便检查是否漏表。
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
				// 不同工作表出现同类辅材汇总时要求人工合并，避免静默覆盖或把重复数量相加计费。
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

	/**
	 * 校验起始行、字段白名单和互不重复的列索引，再逐行构造清单。
	 * 说明和配件汇总保存到来源记录；产品缺项保持可见，人工核对参数覆盖对应录入字段，并记录公式问题。
	 */
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
		fields.addAll(SOURCE_HEADERS.keySet());
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
		boolean linkedStandard = list(sheet.get("rows")).stream().anyMatch(row -> row.containsKey("review_parameters"));
		for (Map<String, Object> source : list(sheet.get("rows"))) {
			if (n(source.get("number")).intValue() < start)
				continue;
			// 自有 AI 标准表的程序页脚不是客户来源行；原始客户表的小计和后续附注仍完整保存。
			if (linkedStandard && cellText(source, 0).equals("材料小计")
					&& get(source, mapping, "name").isBlank() && get(source, mapping, "quantity").isBlank()) break;
			String context = contextKind(source, mapping);
			if (!context.isEmpty()) {
				special.add(obj("source_row", source.get("number"), "source_sheet", sheet.get("name"),
						"source_sheet_index", sheet.get("sheet_index"), "source_file", sheet.get("source_file"), "kind", context,
						"original", source));
				continue;
			}
			String desc = String.join(" ", cells(source).stream().map(QuoteData::str).toList()),
					normalized = desc.replace(" ", "");
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
			// 角码、卡扣及 30/40 角钢的明确汇总提到整单条件，保留原行；它们不再作为普通产品重复计价。
			if (key != null && !mode.equals("equipment_v1")) {
				var fieldIssues = map(source.get("model_field_issues"));
				boolean pendingQuantity = raw.isBlank()
						&& (fieldIssues.containsKey("quantity") || fieldIssues.containsKey("engineering_quantity"));
				String quantity = pendingQuantity ? "" : text(positive(raw, "第" + source.get("number") + "行辅材数量/米数"));
				if (totals.containsKey(key))
					throw new RuleException("清单存在多行相同配件汇总，请先合并或明确处理");
				totals.put(key, quantity);
				special.add(obj("source_row", source.get("number"), "source_sheet", sheet.get("name"),
						"source_sheet_index", sheet.get("sheet_index"), "source_file", sheet.get("source_file"), "kind", key,
						"quantity", quantity, "original", source));
				continue;
			}
			Map<String, Object> row = obj("source_row", source.get("number"), "source_sheet", sheet.get("name"),
					"source_text", desc, "original", source);
			var origin = new LinkedHashMap<>(source);
			origin.putAll(obj("source_file", sheet.get("source_file"), "source_sheet", sheet.get("name"),
					"source_sheet_index", sheet.get("sheet_index"), "source_row", source.get("number")));
			for (String field : SOURCE_HEADERS.keySet()) {
				if (mapping.get(field) == null || field.equals("source_issue")) continue;
				String value = sourceValue(source, mapping, field);
				if (!value.isEmpty()) {
					// source_row/source_sheet 继续标识当前导入表，回导按这些行号关联；客户原定位存于 original。
					if (!Set.of("source_row", "source_sheet").contains(field)) row.put(field, value);
					origin.put(field, value);
				}
			}
			row.put("original", origin);
			for (String field : List.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark",
					"category"))
				row.put(field, get(source, mapping, field));
			if (mapping.containsKey("attached_valve_kind"))
				// 核对提示和附带组成项参数应与风机原文分开，避免风机参数解析器误读其他项目。
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
			// 系统核对表的输入优先于隐藏旧参数；可见规格修改后移除旧规格解析出的尺寸和工况。
			if (source.containsKey("review_parameters")) {
				EquipmentReview.HEADERS.keySet().forEach(attributes::remove);
				map(source.get("review_parameters")).forEach((field, value) -> { if (!blank(value)) attributes.put(field, value); });
				String originalSpec = cellText(source, 23).isBlank() ? cellText(source, 15) : cellText(source, 23);
				String editedSpec = cellText(source, 3);
				if (!editedSpec.equals(originalSpec)) {
					// 人工修改可见规格后，清除旧规格解析出的对应参数，再依据当前规格读取。
					var previous = EquipmentPricingEngine.parseAttributes(obj("name", row.get("name"), "spec", originalSpec));
					var current = EquipmentPricingEngine.parseAttributes(obj("name", row.get("name"), "spec", editedSpec));
					for (String field : List.of("model", "airflow", "pressure", "power", "width", "height", "length", "diameter"))
						if (previous.containsKey(field) || current.containsKey(field)) attributes.remove(field);
				}
			}
			boolean issue = yes(source.get("review_issue")) || get(source, mapping, "source_issue").equalsIgnoreCase("true")
					|| get(source, mapping, "source_issue").equals("是")
					|| ((List<?>) source.get("issues")).stream().anyMatch(v -> mapped.contains(n(v).intValue()));
			origin.put("source_issue", issue);
			row.putAll(obj("attributes", attributes, "excluded", false, "exclude_reason", "", "source_issue", issue,
					"source_issue_confirmed", false));
			String sourceReason = EquipmentReview.sourceReason(row);
			if (!sourceReason.isBlank()) {
				row.put("source_issue", true);
				row.put("source_issue_reason", sourceReason);
				origin.put("source_issue", true);
				origin.put("source_issue_reason", sourceReason);
			}
			EquipmentReview.refresh(row);
			QuoteRowFields.normalize(row);
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

	/** 把已确认的风管中文材质、工艺及成品状态转换为引擎代码；未知输入原样保留供核对。 */
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
