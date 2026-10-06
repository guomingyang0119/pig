package com.pig4cloud.pig.admin.quotation;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.util.Units;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/** Fills the approved customer template; internal price/rule snapshots never enter it. */
public final class DuctExportService {

	public static final String TEMPLATE_VERSION = "review-roundtrip-20261006-v11";

	static final String REVIEW_METADATA_SHEET = "_系统回导";

	static final String REVIEW_METADATA_MAGIC = "PIG_QUOTE_REVIEW_V1";

	private static final byte[] PENDING_FILL_RGB = { (byte) 0xF4, (byte) 0xCC, (byte) 0xCC };

	static void pendingFill(CellStyle style) {
		((XSSFCellStyle) style).setFillForegroundColor(new XSSFColor(PENDING_FILL_RGB, null));
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
	}

	public static byte[] asset(String name) {
		try (InputStream in = DuctExportService.class.getResourceAsStream("/quotation/" + name)) {
			if (in == null)
				throw new RuleException("报价模板或Logo资源缺失：" + name);
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new RuleException("无法读取报价模板资源");
		}
	}

	public static String templateHash() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(asset("quotation-template.xlsx"));
			digest.update(TEMPLATE_VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Cell cell(Sheet sheet, int row, int col) {
		Row r = sheet.getRow(row);
		if (r == null)
			r = sheet.createRow(row);
		Cell c = r.getCell(col);
		return c == null ? r.createCell(col) : c;
	}

	private static void literal(Sheet sheet, String coordinate, Object value) {
		CellReference ref = new CellReference(coordinate);
		Cell cell = cell(sheet, ref.getRow(), ref.getCol());
		String text = str(value);
		cell.setBlank();
		if (!text.isEmpty())
			cell.setCellValue(text);
	}

	private static void number(Cell cell, Object value) {
		// POI setCellValue alone updates a formula's cached value but leaves the formula
		// alive.
		cell.setBlank();
		if (value == null || blank(value))
			cell.setBlank();
		else
			cell.setCellValue(n(value).doubleValue()); // Excel numeric serialization
														// only; all calculations are
														// BigDecimal.
	}

	/** 正式含安装模板增加本行总金额列，并把原备注列及跨列版式向右扩展一列。 */
	static void expandFormalInstallationSheet(XSSFSheet sheet) {
		int totalColumn = 11, remarkColumn = 12;
		sheet.setColumnWidth(remarkColumn, sheet.getColumnWidth(totalColumn));
		sheet.setColumnWidth(totalColumn, sheet.getColumnWidth(10));
		for (int row = 0; row <= sheet.getLastRowNum(); row++) {
			Cell oldRemark = cell(sheet, row, totalColumn), newRemark = cell(sheet, row, remarkColumn);
			newRemark.setCellStyle(oldRemark.getCellStyle());
		}
		List<CellRangeAddress> replacements = new ArrayList<>();
		for (int i = sheet.getNumMergedRegions() - 1; i >= 0; i--) {
			CellRangeAddress range = sheet.getMergedRegion(i), replacement = null;
			if (range.getFirstRow() >= 15 && range.getLastRow() <= 17 && range.getFirstColumn() == 0
					&& range.getLastColumn() == 9)
				replacement = new CellRangeAddress(range.getFirstRow(), range.getLastRow(), 0, 10);
			else if (range.getFirstRow() >= 15 && range.getLastRow() <= 17 && range.getFirstColumn() == 10
					&& range.getLastColumn() == 11)
				replacement = new CellRangeAddress(range.getFirstRow(), range.getLastRow(), 11, 12);
			else if (range.getLastColumn() == 11)
				replacement = new CellRangeAddress(range.getFirstRow(), range.getLastRow(), range.getFirstColumn(), 12);
			if (replacement != null) {
				sheet.removeMergedRegion(i);
				replacements.add(replacement);
			}
		}
		replacements.forEach(sheet::addMergedRegion);
		literal(sheet, "L9", "本行总金额（元）");
		literal(sheet, "M9", "备注");
	}

	static void addLogo(XSSFWorkbook wb, XSSFSheet sheet) {
		XSSFDrawing drawing = sheet.createDrawingPatriarch();
		drawing.getCTDrawing()
			.setTwoCellAnchorArray(new org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTTwoCellAnchor[0]);
		drawing.getCTDrawing()
			.setOneCellAnchorArray(new org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTOneCellAnchor[0]);
		drawing.getCTDrawing()
			.setAbsoluteAnchorArray(new org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTAbsoluteAnchor[0]);
		int logo = wb.addPicture(asset("keqing-logo.png"), Workbook.PICTURE_TYPE_PNG);
		XSSFClientAnchor anchor = new XSSFClientAnchor();
		anchor.setCol1(0);
		anchor.setRow1(0);
		double width = 118, height = 104;
		int col = 0, rowIndex = 0;
		while (width >= sheet.getColumnWidthInPixels(col))
			width -= sheet.getColumnWidthInPixels(col++);
		while (height >= sheet.getRow(rowIndex).getHeightInPoints() * 96 / 72)
			height -= sheet.getRow(rowIndex++).getHeightInPoints() * 96 / 72;
		anchor.setCol2(col);
		anchor.setDx2((int) (width * Units.EMU_PER_PIXEL));
		anchor.setRow2(rowIndex);
		anchor.setDy2((int) (height * Units.EMU_PER_PIXEL));
		drawing.createPicture(anchor, logo);
	}

	public byte[] write(Map<String, Object> body, Map<String, Object> result, String identifier, boolean formal) {
		return write(body, result, identifier, formal, obj());
	}

	public byte[] write(Map<String, Object> body, Map<String, Object> result, String identifier, boolean formal,
			Map<String, Object> context) {
		if (formal && !yes(result.get("formal_ready")))
			throw new RuleException("正式报价条件未满足");
		try {
			return formal ? formal(body, result, identifier) : review(body, result, identifier, context);
		}
		catch (RuleException e) {
			throw e;
		}
		catch (IOException | IllegalArgumentException e) {
			throw new RuleException("报价模板读取或填充失败，请检查模板文件");
		}
	}

	private byte[] draft(Map<String, Object> body, Map<String, Object> result, String identifier) throws IOException {
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet("报价草稿");
			String[] headers = { "序号", "产品名称", "规格", "工程量(㎡)", "综合单价", "行金额", "状态", "待确认原因/备注" };
			CellStyle red = wb.createCellStyle();
			pendingFill(red);
			CellStyle wrap = wb.createCellStyle();
			wrap.setWrapText(true);
			literal(sheet, "A1", "报价草稿 · " + identifier);
			literal(sheet, "A2", "客户：" + s(body, "customer") + "　项目：" + s(body, "project"));
			literal(sheet, "A3", "草稿仅供核对，未确认项目不计入小计");
			for (int i = 0; i < headers.length; i++)
				cell(sheet, 4, i).setCellValue(headers[i]);
			List<Map<String, Object>> source = list(body.get("rows")), rows = list(result.get("rows"));
			for (int i = 0; i < source.size(); i++) {
				Map<String, Object> r = rows.get(i), s = source.get(i);
				int row = i + 5;
				cell(sheet, row, 0).setCellValue(i + 1);
				cell(sheet, row, 1).setCellValue(s(s, "name"));
				cell(sheet, row, 2).setCellValue(s(s, "spec"));
				cell(sheet, row, 3).setCellValue(s(s, "engineering_quantity"));
				number(cell(sheet, row, 4), r.get("unit_price"));
				number(cell(sheet, row, 5), r.get("amount"));
				cell(sheet, row, 6)
					.setCellValue(Map.of("priced", "已计算", "pending", "待确认", "excluded", "不计价").get(s(r, "status")));
				cell(sheet, row, 7).setCellValue(s(r, "reason").isEmpty() ? s(s, "remark") : s(r, "reason"));
				for (int c = 0; c < 8; c++)
					cell(sheet, row, c).setCellStyle(s(r, "status").equals("pending") ? red : wrap);
			}
			int next = source.size() + 6;
			for (Map<String, Object> extra : list(result.get("aggregate_flanges"))) {
				cell(sheet, next, 1).setCellValue(s(extra, "size") + "#角钢法兰（整单）");
				number(cell(sheet, next, 5), extra.get("amount"));
				next++;
			}
			for (Map<String, Object> extra : list(result.get("accessory_items"))) {
				cell(sheet, next, 1).setCellValue(s(extra, "name") + "（整单实际数量）");
				number(cell(sheet, next, 5), extra.get("amount"));
				next++;
			}
			cell(sheet, next, 1).setCellValue("已确定材料小计");
			number(cell(sheet, next++, 5), result.get("subtotal"));
			for (var item : independentInstallationLines(result, InstallationPricingEngine.enabled(body))) {
				cell(sheet, next, 1).setCellValue(s(item, "name"));
				cell(sheet, next, 2).setCellValue(s(item, "spec"));
				number(cell(sheet, next, 3), item.get("quantity"));
				number(cell(sheet, next, 5), item.get("line_total"));
				cell(sheet, next++, 7).setCellValue(s(item, "remark"));
			}
			for (var item : list(map(map(result.get("fees")).get("installation")).get("lines")))
				if (!s(item, "status").equals("priced")) {
					cell(sheet, next, 1).setCellValue(s(item, "name") + "安装");
					cell(sheet, next++, 7).setCellValue(s(item, "reason"));
				}
			cell(sheet, next, 1).setCellValue("最终总额");
			number(cell(sheet, next++, 5), result.get("total"));
			for (Object issue : (List<?>) result.get("issues"))
				cell(sheet, ++next, 1).setCellValue(str(issue));
			sheet.createFreezePane(0, 5);
			sheet.setColumnWidth(0, 2000);
			for (int c = 1; c < 8; c++)
				sheet.setColumnWidth(c, c == 7 ? 18000 : c == 1 ? 8000 : 4500);
			wb.write(out);
			return out.toByteArray();
		}
	}

	private static boolean equipmentMode(Map<String, Object> body) {
		return Set.of("equipment_v1", "mixed_v1").contains(s(body, "pricing_mode"));
	}

	private static String installationScope(Map<String, Object> body) {
		return InstallationPricingEngine.enabled(body)
				? "含安装（安装" + (InstallationPricingEngine.taxIncluded(body) ? "含税" : "不含税") + "）" : "不含安装";
	}

	static String equipmentSpecification(Map<String, Object> row, Map<String, Object> result) {
		var point = map(map(result.get("detail")).get("selected_fan"));
		if (!point.isEmpty()) {
			String fan = s(point, "model") + "；功率"
					+ String.join("/", ((List<?>) point.get("powers")).stream().map(QuoteData::str).toList()) + "kW；风量"
					+ s(point, "airflow") + "m³/h；风压" + s(point, "pressure") + "Pa；转速" + s(point, "speed") + "r/min";
			var valve = map(map(result.get("detail")).get("attached_valve"));
			if (!valve.isEmpty()) {
				var a = map(valve.get("attributes"));
				String size = s(a, "shape").equals("round") ? "Φ" + s(a, "width") : s(a, "width") + "×" + s(a, "height");
				fan += "；附" + EquipmentReview.display("attached_valve_kind", s(valve, "kind")) + " " + size
						+ "mm，每台" + s(valve, "count_per_fan") + "个";
			}
			return fan;
		}
		String value = s(row, "spec");
		var a = map(result.getOrDefault("attributes", row.get("attributes")));
		if (value.isEmpty()) {
			value = !blank(a.get("diameter")) ? "Φ" + s(a, "diameter")
					: String.join("×",
							List.of("width", "height", "length")
								.stream()
								.filter(k -> !blank(a.get(k)))
								.map(k -> s(a, k))
								.toList());
			if (!blank(a.get("model")))
				value = s(a, "model") + (value.isEmpty() ? "" : "；" + value);
		}
		return value;
	}

	private static String quotationScope(Map<String, Object> body) {
		if (bool(body.get("include_freight"))) {
			var options = map(body.get(equipmentMode(body) ? "equipment_options" : "duct_options"));
			return (yes(options.get("tax_included")) ? "材料含税" : "材料不含税") + "；"
					+ (yes(body.get("include_freight")) ? "整单含运费" : "整单不含运费") + "；"
					+ installationScope(body);
		}
		if (!equipmentMode(body)) {
			var duct = new LinkedHashMap<>(map(body.get("duct_options")));
			duct.put("include_installation", InstallationPricingEngine.enabled(body));
			return scope(duct);
		}
		var options = map(body.get("equipment_options"));
		String freight = yes(options.get("include_freight")) ? "含运费" : "不含运费";
		if (s(body, "pricing_mode").equals("mixed_v1"))
			freight = "设备" + freight + "；风管"
					+ (yes(map(body.get("duct_options")).get("include_freight")) ? "含运费" : "不含运费");
		return (yes(options.get("tax_included")) ? "材料含税" : "材料不含税") + "；" + freight + "；"
				+ installationScope(body);
	}

	private byte[] equipmentDraft(Map<String, Object> body, Map<String, Object> result, String identifier)
			throws IOException {
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet("报价草稿");
			List<List<Object>> records = new ArrayList<>();
			records.add(List.of("科庆 · 报价草稿（待核对）"));
			records.add(List.of("客户：" + s(body, "customer") + "；项目：" + s(body, "project") + "；编号：" + identifier));
			String issues = String.join("；", ((List<?>) result.get("issues")).stream().map(QuoteData::str).toList());
			records.add(List.of(issues.isEmpty() ? "项目已试算，请核对供货范围" : issues));
			records.add(List.of("序号", "产品名称", "供货规格/型号", "单位", "数量", "单价（元）", "金额（元）", "状态/备注"));
			var source = list(body.get("rows"));
			var calculated = list(result.get("rows"));
			for (int i = 0; i < source.size(); i++) {
				var row = source.get(i);
				var r = calculated.get(i);
				if (s(r, "status").equals("priced")) {
					for (var line : lines(obj("rows", List.of(row), "duct_options", body.get("duct_options")),
							obj("rows", List.of(r), "fees", result.get("fees")), false))
						records.add(Arrays.asList(i + 1, line.get("name"), line.get("spec"), line.get("unit"),
								line.get("quantity"), line.get("material_unit"), line.get("material_amount"),
								line.get("remark")));
				}
				else
					records.add(Arrays.asList(i + 1, row.get("name"), row.get("spec"), row.get("unit"),
							row.get("quantity"), s(r, "status").equals("pending") ? "待确认" : "", "", r.get("reason")));
			}
			var extras = new LinkedHashMap<>(result);
			extras.put("rows", List.of());
			for (var line : lines(obj("rows", List.of(), "duct_options", body.get("duct_options")), extras,
					InstallationPricingEngine.enabled(body)))
				records.add(Arrays.asList("", line.get("name"), line.get("spec"), line.get("unit"),
						line.get("quantity"), line.get("material_unit"),
						line.getOrDefault("line_total", line.get("material_amount")), line.get("remark")));
			for (var item : list(map(map(result.get("fees")).get("installation")).get("lines")))
				if (!s(item, "status").equals("priced"))
					records.add(Arrays.asList("", s(item, "name") + "安装", "", "", "", "", "", item.get("reason")));
			records.add(Arrays.asList("已计算小计", "", "", "", "", "", result.get("subtotal")));
			records.add(Arrays.asList("试算总额", "", "", "", "", "",
					result.get("total") == null ? "待确认" : result.get("total")));
			for (String note : notes(body, result))
				records.add(List.of(note));
			CellStyle wrap = wb.createCellStyle();
			wrap.setWrapText(true);
			wrap.setVerticalAlignment(VerticalAlignment.CENTER);
			CellStyle money = wb.createCellStyle();
			money.cloneStyleFrom(wrap);
			money.setDataFormat(wb.createDataFormat().getFormat("#,##0.########"));
			for (int i = 0; i < records.size(); i++) {
				var values = records.get(i);
				for (int c = 0; c < values.size(); c++) {
					Cell cell = cell(sheet, i, c);
					cell.setCellStyle(wrap);
					Object value = values.get(c);
					if (i > 3 && (c == 5 || c == 6) && value != null && str(value).matches("-?\\d+(\\.\\d+)?")) {
						number(cell, value);
						cell.setCellStyle(money);
					}
					else
						cell.setCellValue(str(value));
				}
				sheet.getRow(i).setHeightInPoints(values.size() == 1 ? 30 : 42);
				if (values.size() == 1)
					sheet.addMergedRegion(new CellRangeAddress(i, i, 0, 7));
			}
			int[] widths = { 8, 25, 65, 10, 12, 16, 18, 48 };
			for (int i = 0; i < widths.length; i++)
				sheet.setColumnWidth(i, widths[i] * 256);
			sheet.getPrintSetup().setLandscape(true);
			sheet.getPrintSetup().setPaperSize(PrintSetup.A3_PAPERSIZE);
			sheet.getPrintSetup().setFitWidth((short) 1);
			sheet.getPrintSetup().setFitHeight((short) 0);
			sheet.setFitToPage(true);
			wb.write(out);
			return out.toByteArray();
		}
	}

	static String specification(Map<String, Object> row) {
		if (!blank(attr(row, "model")))
			return str(attr(row, "model"));
		String spec = s(row, "spec");
		Matcher rectangle = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+)?)\\s*[×xX*＊]\\s*(\\d+(?:\\.\\d+)?)")
			.matcher(spec);
		if (rectangle.find())
			return rectangle.group(1) + "×" + rectangle.group(2);
		Matcher round = Pattern.compile("(?:Φ|φ|Ø|ø)\\s*\\d+(?:\\.\\d+)?(?:\\s*(?:mm|毫米))?").matcher(spec);
		if (round.find())
			return round.group().replaceAll("\\s+", "");
		Matcher dn = Pattern.compile("\\bDN\\s*\\d+(?:\\.\\d+)?\\b", Pattern.CASE_INSENSITIVE).matcher(spec);
		if (dn.find())
			return dn.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
		return spec.replaceAll("(?i)(?:δ\\s*=?\\s*|厚度\\s*[:：]?\\s*)\\d+(?:\\.\\d+)?\\s*(?:mm|毫米)?", "")
			.replaceFirst("(?i)^\\s*\\d+(?:\\.\\d+)?\\s*(?:mm|毫米)?\\s*(?:[/／,，;；|｜]+\\s*)?", "")
			.replaceAll("^[ /／,，;；|｜]+|[ /／,，;；|｜]+$", "");
	}

	private List<Map<String, Object>> lines(Map<String, Object> body, Map<String, Object> result,
			boolean installation) {
		return lines(body, result, installation, false);
	}

	private List<Map<String, Object>> lines(Map<String, Object> body, Map<String, Object> result,
			boolean installation, boolean includePending) {
		List<Map<String, Object>> manual = list(result.get("manual_lines"));
		if (!manual.isEmpty()) {
			List<Map<String, Object>> exported = calculationMetadata(body, result, manual.stream()
				.filter(line -> includePending || !yes(line.get("pending")))
				.map(line -> {
					Map<String, Object> copy = new LinkedHashMap<>(line);
					copy.put("remark", stripReviewLogic(s(copy, "remark")));
					if (installation)
						copy.put("installation_unit", installationUnit(copy.get("installation_amount"), copy.get("quantity")));
					if (!includePending && !yes(copy.get("pending")) && yes(copy.get("was_pending")))
						copy.put("remark", resolvedRemark(s(copy, "remark")));
					return copy;
				})
				.toList());
			if (includePending)
				appendReviewPricingLogic(body, result, exported);
			return exported;
		}
		List<Map<String, Object>> lines = new ArrayList<>(), sources = list(body.get("rows")),
				rows = list(result.get("rows"));
		Map<Integer, Map<String, Object>> installationRows = new HashMap<>();
		if (installation)
			for (var line : list(map(map(result.get("fees")).get("installation")).get("lines")))
				if (line.get("row_index") != null)
					installationRows.put(n(line.get("row_index")).intValue(), line);
		Map<String, Object> options = map(body.get("duct_options"));
		String accessoryStatus = s(map(map(result.get("fees")).get("accessories")), "status");
		Map<String, String> flanges = Map.of("common", "共板法兰", "angle", "角钢法兰", "round", "圆法兰");
		for (int i = 0; i < sources.size(); i++) {
			Map<String, Object> source = sources.get(i), r = rows.get(i);
			if (!s(r, "status").equals("priced")) {
				if (includePending && s(r, "status").equals("pending")) {
					String remark = "待确认：" + s(r, "reason");
					if (!blank(source.get("remark")))
						remark += "；" + s(source, "remark");
					Object quantity = !blank(source.get("engineering_quantity")) ? source.get("engineering_quantity")
							: source.get("quantity");
					String thickness = blank(attr(source, "thickness")) ? "-" : str(attr(source, "thickness"));
					var line = obj("name", source.get("name"), "thickness", thickness, "spec",
							 equipmentSpecification(source, r), "process", "待确认", "unit", source.get("unit"), "quantity",
							 quantity, "material_unit", null, "material_amount", null, "installation_amount", null,
							 "line_total", null, "remark", remark, "pending", true, "source_row_id", source.get("row_id"));
					applyInstallation(line, installationRows.get(i), installation);
					lines.add(line);
				}
				continue;
			}
			if (s(r, "pricing_mode").equals("equipment_v1")) {
				var detail = map(r.get("detail"));
				String note = s(detail, "selection_note");
				String sourceRemark = s(source, "remark");
				String remark;
				if (s(r, "equipment_kind").equals("fan")) {
					String selectedModel = s(map(detail.get("selected_fan")), "model");
					String modelRemark = selectedModel.isEmpty() ? note : "选择型号：" + selectedModel;
					remark = modelRemark + (sourceRemark.isEmpty() ? "" : (modelRemark.isEmpty() ? "" : "；") + sourceRemark);
				}
				else {
					remark = sourceRemark + (note.isEmpty() ? "" : (!sourceRemark.isEmpty() ? "；" : "") + note);
				}
				var line = obj("name", source.get("name"), "thickness", "-", "spec", equipmentSpecification(source, r),
						"process", "设备供货", "unit", r.getOrDefault("billing_unit", source.get("unit")), "quantity",
						r.get("billing_quantity"), "material_unit", r.get("unit_price"), "material_amount",
						r.get("amount"), "remark", remark, "source_row_id", source.get("row_id"));
				applyInstallation(line, installationRows.get(i), installation);
				lines.add(line);
				continue;
			}
			String remark = (s(r, "effective_product_state").equals("semi") ? "半成品" : "成品") + "；"
					+ Map.of("straight", "直管", "irregular", "异形件", "semi_unspecified", "不区分直管/异形件")
						.get(s(r, "selected_shape"))
					+ "；" + (s(options, "area_basis").equals("net") ? "净面积" : "开板面积");
			if (s(r, "effective_flange").equals("common")) {
				if (accessoryStatus.equals("included"))
					remark += "；含角码卡扣";
				else if (accessoryStatus.equals("ready"))
					remark += "；含角码卡扣（按实际数量另列）";
			}
			remark += "；" + Map
				.of("common_punch", "含共板法兰打孔费", "common_no_punch", "不含共板法兰打孔费", "actual_meters", "含角钢法兰（按实际米数另列）",
						"aggregate_meters", "含角钢法兰（按整单实际米数另列）", "round_count", "圆法兰按客户数量另列", "round_area", "圆法兰按面积折算",
						"per_area", "含角钢法兰")
				.get(s(r, "flange_basis"));
			if (!blank(source.get("remark")))
				remark += "；" + s(source, "remark");
			var main = obj("name", source.get("name"), "thickness", map(r.get("selected_material")).get("thickness"),
					"spec", specification(source), "process", flanges.get(s(r, "effective_flange")), "unit", "㎡",
					"quantity", r.get("billing_quantity"), "material_unit", r.get("unit_price"), "material_amount",
					r.get("body_amount"), "remark", remark, "source_row_id", source.get("row_id"));
			applyInstallation(main, installationRows.get(i), installation);
			lines.add(main);
			if (r.get("flange_amount") != null)
				lines.add(materialOnly(obj("name", "角钢法兰", "thickness", "-", "spec", map(r.get("selected_flange")).get("name"),
						"process", "角钢法兰", "unit", "米", "quantity", r.get("flange_meters"), "material_unit",
						r.get("flange_unit_price"), "material_amount", r.get("flange_amount"), "remark",
						"含角钢法兰；按客户实际米数计价；不再乘1.72", "_calculation_source_index", i,
						"_calculation_kind", "actual_flange"), installation));
			if (r.get("round_flange_amount") != null)
				lines.add(materialOnly(obj("name", "圆法兰", "thickness", "-", "spec", "Φ" + s(r, "round_flange_diameter") + "mm",
						"process", "圆法兰", "unit", "个", "quantity", r.get("round_flange_count"), "material_unit",
						r.get("round_flange_unit_price"), "material_amount", r.get("round_flange_amount"), "remark",
						"按客户清单数量计价", "_calculation_source_index", i,
						"_calculation_kind", "round_flange"), installation));
		}
		for (Map<String, Object> item : list(result.get("accessory_items")))
			lines.add(materialOnly(obj("name", item.get("name"), "thickness", "-", "spec", "按客户清单实际数量", "process", "共板法兰配件", "unit",
					item.get("unit"), "quantity", item.get("quantity"), "material_unit", item.get("unit_price"),
					"material_amount", item.get("amount"), "remark", "含角码卡扣；按整单实际数量计价",
					"_calculation_kind", "whole_quote_item", "_base_unit_price", item.get("base_unit_price"),
					"_area_factor", item.get("area_factor"), "_tax_factor", item.get("tax_factor")), installation));
		for (Map<String, Object> item : list(result.get("aggregate_flanges")))
			lines.add(materialOnly(obj("name", s(item, "size") + "#角钢法兰", "thickness", "-", "spec", item.get("name"), "process",
					"角钢法兰", "unit", "米", "quantity", item.get("meters"), "material_unit", item.get("unit_price"),
					"material_amount", item.get("amount"), "remark", "含角钢法兰；按整单实际米数计价；不再乘1.72",
					"_calculation_kind", "whole_quote_item", "_base_unit_price", item.get("base_unit_price"),
					"_area_factor", item.get("area_factor"), "_tax_factor", item.get("tax_factor")), installation));
		lines.addAll(independentInstallationLines(result, installation));
		if (includePending)
			appendReviewPricingLogic(body, result, lines);
		return lines;
	}

	private static void appendReviewPricingLogic(Map<String, Object> body, Map<String, Object> result,
			List<Map<String, Object>> lines) {
		List<Map<String, Object>> sources = list(body.get("rows")), calculated = list(result.get("rows"));
		Map<String, Integer> sourceIndexes = new HashMap<>();
		for (int i = 0; i < sources.size(); i++)
			if (!s(sources.get(i), "row_id").isEmpty())
				sourceIndexes.put(s(sources.get(i), "row_id"), i);
		for (Map<String, Object> line : lines) {
			Integer index = sourceIndexes.get(s(line, "source_row_id"));
			if (index == null && line.get("_calculation_source_index") != null)
				index = n(line.get("_calculation_source_index")).intValue();
			Map<String, Object> source = index != null && index < sources.size() ? sources.get(index) : obj();
			Map<String, Object> row = index != null && index < calculated.size() ? calculated.get(index) : obj();
			String logic = reviewPricingLogic(line, source, row);
			if (logic.isEmpty())
				continue;
			String disclosure = s(row, "status").equals("pending")
					? "系统识别类型：" + logic + "；计价状态：待确认"
					: "系统计价逻辑：" + logic;
			String remark = stripReviewLogic(s(line, "remark"));
			line.put("remark", remark.isEmpty() ? disclosure : remark + "\n" + disclosure);
		}
	}

	private static String reviewPricingLogic(Map<String, Object> line, Map<String, Object> source,
			Map<String, Object> row) {
		String calculationKind = s(line, "_calculation_kind"), process = s(line, "process");
		if (calculationKind.equals("actual_flange"))
			return "角钢法兰（按本行实际米数）";
		if (calculationKind.equals("round_flange"))
			return "圆法兰（按客户数量）";
		if (calculationKind.equals("whole_quote_item"))
			return s(line, "name") + (process.equals("角钢法兰") ? "（按整单实际米数）" : "（按整单实际数量）");
		String kind = s(row, "equipment_kind");
		if (kind.isEmpty() && !source.isEmpty())
			kind = EquipmentPricingEngine.classify(source);
		if (!kind.isEmpty()) {
			String label = equipmentLogicLabel(kind, row);
			if (kind.equals("fan")) {
				String model = s(map(map(row.get("detail")).get("selected_fan")), "model");
				if (!model.isEmpty())
					label += "；选中型号：" + model;
			}
			return label;
		}
		boolean duct = !source.isEmpty() && (s(source, "category").contains("风管") || s(source, "name").contains("风管")
				|| !s(row, "effective_flange").isEmpty() || !s(row, "selected_shape").isEmpty());
		if (duct) {
			String state = Map.of("semi", "半成品", "finished", "成品")
				.getOrDefault(s(row, "effective_product_state"), "风管");
			String shape = Map.of("straight", "直管", "irregular", "异形件", "semi_unspecified", "不区分直管/异形件")
				.getOrDefault(s(row, "selected_shape"), "");
			String flange = Map.of("common", "共板法兰", "angle", "角钢法兰", "round", "圆法兰")
				.getOrDefault(s(row, "effective_flange"), process);
			return "风管（" + String.join("、", List.of(state, shape, flange).stream().filter(v -> !v.isEmpty()).toList()) + "）";
		}
		if (!source.isEmpty())
			return !s(source, "category").isEmpty() ? s(source, "category")
					: !s(source, "name").isEmpty() ? "未识别产品（" + s(source, "name") + "）" : "未识别产品";
		if (process.equals("安装"))
			return "安装费（" + s(line, "name") + "）";
		if (process.equals("整单汇总"))
			return s(line, "name");
		return "";
	}

	private static String equipmentLogicLabel(String kind, Map<String, Object> row) {
		if (kind.equals("louver")) {
			String type = s(map(row.get("detail")), "louver_type");
			String basis = s(map(row.get("detail")), "pricing_louver_basis");
			if (!basis.isBlank() && !basis.equals(type))
				return EquipmentReview.display("louver_type", type) + "；计价采用" + EquipmentReview.display("pricing_louver_type", basis);
			boolean abs = type.startsWith("abs_");
			String base = type.replaceFirst("^abs_", "").replaceFirst("_(?:valve|mesh)$", "");
			String label = Map.of("gravity", "自垂百叶风口", "diffuser", "散流器", "rain", "防雨百叶风口",
					"hinged", "门铰式百叶风口", "access", "检修口", "linear", "条缝/线形风口", "double", "双层百叶风口",
					"single", "单层百叶风口").getOrDefault(base, "百叶风口/散流器");
			return (abs ? "ABS " : "") + label
					+ (type.endsWith("_valve") ? "（带阀）" : type.endsWith("_mesh") ? "（带网）" : "");
		}
		return Map.ofEntries(Map.entry("fan", "风机选型"), Map.entry("purchased", "外购设备"),
				Map.entry("elbow", "消声弯头"), Map.entry("plenum", "静压箱"), Map.entry("silencer", "消声器"),
				Map.entry("plate_smoke", "板式排烟口"), Map.entry("multi_leaf", "正压送风口（多叶类）"),
				Map.entry("pressure_relief", "余压阀"), Map.entry("check", "止回阀"),
				Map.entry("electric_fire", "电动防火阀"), Map.entry("fire", "防火阀"),
				Map.entry("electric_control", "电动调节阀"), Map.entry("manual", "调节阀"))
			.getOrDefault(kind, kind);
	}

	private static String stripReviewLogic(String remark) {
		return Arrays.stream(remark.split("\\R", -1))
			.filter(line -> !line.trim().startsWith("系统计价逻辑：") && !line.trim().startsWith("系统识别类型："))
			.collect(java.util.stream.Collectors.joining("\n")).strip();
	}

	private static List<Map<String, Object>> calculationMetadata(Map<String, Object> body,
			Map<String, Object> result, List<Map<String, Object>> manualLines) {
		Map<String, Integer> sourceIndexes = new HashMap<>();
		var sources = list(body.get("rows"));
		for (int i = 0; i < sources.size(); i++)
			if (!s(sources.get(i), "row_id").isEmpty())
				sourceIndexes.put(s(sources.get(i), "row_id"), i);
		var calculated = list(result.get("rows"));
		Integer currentSource = null;
		for (var line : manualLines) {
			Integer index = sourceIndexes.get(s(line, "source_row_id"));
			if (index != null)
				currentSource = index;
			if (currentSource != null && currentSource < calculated.size()) {
				var row = calculated.get(currentSource);
				if (s(line, "name").equals("角钢法兰") && !blank(row.get("flange_amount"))
						&& s(line, "quantity").equals(s(row, "flange_meters")))
					line.putAll(obj("_calculation_source_index", currentSource, "_calculation_kind", "actual_flange"));
				else if (s(line, "name").equals("圆法兰") && !blank(row.get("round_flange_amount"))
						&& s(line, "quantity").equals(s(row, "round_flange_count")))
					line.putAll(obj("_calculation_source_index", currentSource, "_calculation_kind", "round_flange"));
			}
			if (!s(line, "_calculation_kind").isEmpty())
				continue;
			for (String key : List.of("accessory_items", "aggregate_flanges"))
				for (var item : list(result.get(key))) {
					String name = key.equals("accessory_items") ? s(item, "name") : s(item, "size") + "#角钢法兰";
					String quantity = key.equals("accessory_items") ? s(item, "quantity") : s(item, "meters");
					if (s(line, "name").equals(name) && s(line, "quantity").equals(quantity))
						line.putAll(obj("_calculation_kind", "whole_quote_item", "_base_unit_price",
								item.get("base_unit_price"), "_area_factor", item.get("area_factor"),
								"_tax_factor", item.get("tax_factor")));
				}
		}
		return manualLines;
	}

	private static String resolvedRemark(String remark) {
		List<String> kept = new ArrayList<>();
		for (String part : remark.split("；")) {
			String text = part.trim();
			if (!text.isEmpty() && !text.startsWith("待确认：") && !text.startsWith("安装待确认：")
					&& !text.startsWith("待确定：") && !text.startsWith("安装待确定："))
				kept.add(text);
		}
		return String.join("；", kept);
	}

	private static Map<String, Object> materialOnly(Map<String, Object> line, boolean installation) {
		if (installation) {
			line.put("installation_unit", "0");
			line.put("installation_amount", "0");
			line.put("line_total", line.get("material_amount"));
		}
		return line;
	}

	private static void applyInstallation(Map<String, Object> target, Map<String, Object> installation,
			boolean included) {
		if (!included)
			return;
		if (installation == null) {
			target.putAll(obj("installation_unit", null, "installation_amount", null, "line_total", null, "pending", true, "remark",
					s(target, "remark") + (blank(target.get("remark")) ? "" : "；") + "安装待确认：未找到本行安装结果"));
			return;
		}
		String status = s(installation, "status");
		Object amount = status.equals("excluded") ? "0" : installation.get("quoted_amount");
		target.put("installation_unit", installationUnit(amount, installation.get("quantity")));
		target.put("installation_amount", amount);
		if (!blank(target.get("material_amount")) && !blank(amount))
			target.put("line_total", text(add(n(target.get("material_amount")), n(amount))));
		else
			target.put("line_total", null);
		if (status.equals("pending")) {
			target.put("pending", true);
			String note = "安装待确认：" + s(installation, "reason");
			target.put("remark", s(target, "remark") + (blank(target.get("remark")) ? "" : "；") + note);
		}
	}

	private static List<Map<String, Object>> independentInstallationLines(Map<String, Object> result, boolean included) {
		List<Map<String, Object>> lines = new ArrayList<>();
		var installation = map(map(result.get("fees")).get("installation"));
		if (included) {
			boolean taxed = s(installation, "tax_factor").equals("1.01");
			for (var item : list(installation.get("lines"))) {
				if (item.get("extra_index") == null || !s(item, "status").equals("priced"))
					continue;
				lines.add(obj("name", s(item, "name") + "安装", "spec", item.get("project_name"), "process", "安装", "unit",
						item.get("unit"), "quantity", item.get("quantity"), "material_unit", null, "material_amount", null,
						"installation_unit", installationUnit(item.get("quoted_amount"), item.get("quantity")),
						"installation_amount", item.get("quoted_amount"), "line_total", item.get("quoted_amount"),
						"installation_extra_index", item.get("extra_index"), "remark",
						(taxed ? "本项安装费含税并单独向上取整" : "本项安装费不含税并单独向上取整")
								+ (blank(item.get("note")) ? "" : "；" + s(item, "note"))));
			}
			Object siteQuoted = installation.get("site_quoted_amount");
			String siteNote = s(installation, "site_note");
			if (!blank(siteQuoted) && (n(siteQuoted).signum() != 0 || !siteNote.isEmpty())) {
				String remark = taxed
						? "录入未税金额" + s(installation, "site_amount") + "元，×1.01后向上取整数元"
						: "按录入的不含税金额直接计入";
				if (!siteNote.isEmpty())
					remark += "；附加原因：" + siteNote;
				lines.add(obj("name", "现场附加费", "thickness", "-", "spec", "现场施工附加", "process", "整单汇总", "unit", "项",
						"quantity", "1", "material_unit", null, "material_amount", null, "installation_amount", siteQuoted,
						"installation_unit", siteQuoted, "line_total", siteQuoted, "remark", remark));
			}
		}
		return lines;
	}

	private static Object installationUnit(Object amount, Object quantity) {
		if (blank(amount) || blank(quantity))
			return null;
		BigDecimal qty = n(quantity);
		return qty.signum() == 0 ? null : text(n(amount).divide(qty, MC));
	}

	private static void appendEquipmentFreightLine(List<Map<String, Object>> lines, Map<String, Object> result,
			boolean installation, boolean includePending) {
		if (lines.stream().anyMatch(line -> s(line, "process").equals("设备运费")))
			return;
		var freight = map(map(result.get("fees")).get("equipment_freight"));
		String status = s(freight, "status");
		if (freight.isEmpty() || status.equals("not_applicable")
				|| status.equals("pending") && (!includePending || !s(freight, "basis").equals("manual_order")))
			return;
		Object amount = freight.get("amount");
		boolean included = yes(freight.get("included_in_rows"));
		if (included)
			return;
		String remark = s(freight, "reason")
				+ "；本项为报价人员填写的整单设备运费，单独计入报价合计";
		var line = obj("name", "设备运费", "thickness", "-", "spec", s(freight, "region"), "process", "设备运费",
				"unit", "项", "quantity", "1", "material_unit", amount, "material_amount", amount, "remark",
				remark, "pending", status.equals("pending"), "included_in_rows", included);
		if (installation)
			line.putAll(obj("installation_amount", "0", "line_total", amount));
		lines.add(line);
	}

	private static String scope(Map<String, Object> options) {
		return (yes(options.get("tax_included")) ? "含税" : "不含税") + "；"
				+ (yes(options.get("include_freight")) ? "含运费" : "不含运费") + "；"
				+ (yes(options.get("include_installation"))
						? "含安装（安装" + (yes(options.get("tax_included")) ? "含税" : "不含税") + "）" : "不含安装");
	}

	private List<String> notes(Map<String, Object> body, Map<String, Object> result) {
		if (equipmentMode(body)) {
			List<String> notes = new ArrayList<>(List.of("报价范围：" + quotationScope(body) + "。"));
			if (!map(result.get("payment")).isEmpty())
				notes.add("付款条件：" + s(map(result.get("payment")), "label") + "；整单付款系数"
						+ s(map(result.get("payment")), "coefficient") + "，产品行、另列设备运费与安装费合计后统一乘一次并向上取整数元；自动设备运费已计入设备单价，明细单价为付款前价格。");
			notes.add("风机按明细所列实际供货型号、功率及工况报价。");
			notes.add("实际数量、规格及工艺，以双方确认的图纸、清单或订单为准。");
			if (InstallationPricingEngine.enabled(body)) {
				int last = notes.size() - 1;
				notes.set(last, notes.get(last) + " 安装不含控制箱及相关电线电器、风机基础预制、抗震支架、预留洞开启与修复。");
			}
			if (!blank(body.get("note")))
				notes.add("报价备注：" + s(body, "note"));
			return notes;
		}
		Map<String, Object> o = map(body.get("duct_options")), p = map(result.get("payment"));
		List<String> notes = new ArrayList<>(List.of("报价范围：" + quotationScope(body) + "。",
				"付款条件：" + s(p, "label") + "；整单付款系数" + s(p, "coefficient") + "，材料与安装合计后统一乘一次，最终向上取整数元；明细单价为付款前价格。",
				"计价口径：" + (s(o, "area_basis").equals("net") ? "净面积" : "开板面积") + "；材料规格、成品状态、管件类型、法兰形式及特殊费用以明细为准。",
				"实际供货数量、厚度、规格型号及工艺，以双方确认的图纸、清单或订单为准。"));
		if (InstallationPricingEngine.enabled(body))
			notes.add("安装报价排除范围：①不含风机控制箱及控制箱到风机的电线、电器；②不含风机基础预制的制作及抗震支架；③不含设备及管道预留洞孔的开启与修复。");
		if (!blank(body.get("note"))) {
			if (notes.size() < 5)
				notes.add("报价备注：" + s(body, "note"));
			else
				notes.set(4, notes.get(4) + "\n报价备注：" + s(body, "note"));
		}
		return notes;
	}

	private byte[] formal(Map<String, Object> body, Map<String, Object> result, String identifier) throws IOException {
		return customerTemplate(body, result, identifier, false);
	}

	private byte[] review(Map<String, Object> body, Map<String, Object> result, String identifier,
			Map<String, Object> context) throws IOException {
		return customerTemplate(body, result, identifier, true, context);
	}

	private byte[] customerTemplate(Map<String, Object> body, Map<String, Object> result, String identifier,
			boolean review) throws IOException {
		return customerTemplate(body, result, identifier, review, obj());
	}

	private byte[] customerTemplate(Map<String, Object> body, Map<String, Object> result, String identifier,
			boolean review, Map<String, Object> context) throws IOException {
		boolean installation = InstallationPricingEngine.enabled(body);
		String sheetName = installation ? "带安装" : "单层材料";
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(asset("quotation-template.xlsx")));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XSSFSheet sheet = wb.getSheet(sheetName);
			if (sheet == null)
				throw new RuleException("正式报价模板缺少工作表：" + sheetName);
			for (int i = wb.getNumberOfSheets() - 1; i >= 0; i--)
				if (!wb.getSheetName(i).equals(sheetName))
					wb.removeSheetAt(i);
			if (review) {
				wb.setSheetName(wb.getSheetIndex(sheet), "报价校对版");
			}
			if (installation)
				expandFormalInstallationSheet(sheet);
			addLogo(wb, sheet);
			List<Map<String, Object>> lines = new ArrayList<>(lines(body, result, installation, review));
			var freight = map(map(result.get("fees")).get("equipment_freight"));
			lines.removeIf(line -> s(line, "process").equals("设备运费") && (yes(freight.get("included_in_rows"))
					|| yes(line.get("pending")) && !s(freight, "basis").equals("manual_order")));
			appendEquipmentFreightLine(lines, result, installation, review);
			if (lines.isEmpty() || (!review && result.get("total") == null))
				throw new RuleException("正式报价没有明细或总额未确认");
			Map<String, Object> equipmentFreightLine = lines.stream()
				.filter(line -> s(line, "process").equals("设备运费"))
				.findFirst()
				.orElse(null);
			List<Map<String, Object>> detailLines = lines.stream()
				.filter(line -> !s(line, "process").equals("设备运费"))
				.toList();
			int columns = installation ? 13 : 10;
			int amountColumn = installation ? 11 : 8;
			int freightRows = equipmentFreightLine == null ? 0 : 1;
			int delta = detailLines.size() + freightRows - 6;
			CellStyle[] styles = new CellStyle[columns];
			for (int c = 0; c < columns; c++)
				styles[c] = cell(sheet, 9, c).getCellStyle();
			List<CellRangeAddress> merged = new ArrayList<>();
			for (int i = sheet.getNumMergedRegions() - 1; i >= 0; i--) {
				CellRangeAddress range = sheet.getMergedRegion(i);
				if (range.getFirstRow() >= 15) {
					merged.add(range.copy());
					sheet.removeMergedRegion(i);
				}
			}
			if (delta != 0)
				sheet.shiftRows(15, sheet.getLastRowNum(), delta, true, false);
			for (CellRangeAddress range : merged)
				sheet.addMergedRegion(new CellRangeAddress(range.getFirstRow() + delta, range.getLastRow() + delta,
						range.getFirstColumn(), range.getLastColumn()));
			short format = wb.createDataFormat().getFormat("0.0");
			Map<Integer, CellStyle> equipmentStyles = new HashMap<>();
			Map<Integer, CellStyle> settlementStyles = new HashMap<>();
			Map<Integer, CellStyle> pendingStyles = new HashMap<>();
			for (int c = 0; c < columns; c++) {
				CellStyle pending = wb.createCellStyle();
				pending.cloneStyleFrom(styles[c]);
				pendingFill(pending);
				pendingStyles.put(c, pending);
			}
			for (int c = 7; c <= amountColumn; c++) {
				CellStyle numeric = wb.createCellStyle();
				numeric.cloneStyleFrom(styles[c]);
				numeric.setDataFormat(format);
				numeric.setAlignment(HorizontalAlignment.CENTER);
				styles[c] = numeric;
				CellStyle integer = wb.createCellStyle();
				integer.cloneStyleFrom(numeric);
				integer.setDataFormat(wb.createDataFormat().getFormat("0"));
				equipmentStyles.put(c, integer);
				CellStyle decimal = wb.createCellStyle();
				decimal.cloneStyleFrom(numeric);
				decimal.setDataFormat(wb.createDataFormat().getFormat("0.0########"));
				settlementStyles.put(c, decimal);
			}
			for (int i = 0; i < detailLines.size(); i++) {
				Map<String, Object> line = detailLines.get(i);
				int row = i + 9;
				line.put("_visible_row", row + 1);
				String[] keys = installation
						? new String[] { "name", "thickness", "spec", "process", "unit", "quantity", "material_unit",
								"material_amount", "installation_unit", "installation_amount", "line_total", "remark" }
						: new String[] { "name", "thickness", "spec", "process", "unit", "quantity", "material_unit",
								"material_amount", "remark" };
				cell(sheet, row, 0).setCellValue(i + 1);
				for (int c = 0; c < columns; c++) {
					Cell cell = cell(sheet, row, c);
					if (c > 0) {
						cell.setBlank();
						String key = keys[c - 1];
						if (Set
							.of("quantity", "material_unit", "material_amount", "installation_unit", "installation_amount", "line_total")
							.contains(key))
							number(cell, line.get(key));
						else {
							String text = str(line.get(key));
							if (!text.isEmpty())
								cell.setCellValue(text);
						}
					}
					cell.setCellStyle(yes(line.get("pending")) ? pendingStyles.get(c)
							: s(line, "process").equals("设备供货") && equipmentStyles.containsKey(c)
									? equipmentStyles.get(c)
									: Set.of("安装", "安装辅材", "整单汇总").contains(s(line, "process"))
											&& settlementStyles.containsKey(c) ? settlementStyles.get(c) : styles[c]);
				}
				sheet.getRow(row).setHeightInPoints(34);
			}
			String[] coords = installation ? new String[] { "I1", "I2", "B5", "G5", "B6", "G6", "B7", "G7" }
					: new String[] { "H1", "H2", "B5", "F5", "B6", "F6", "B7", "F7" };
			Object[] values = { (review ? "校对-" : "KQ-") + identifier, body.get("quote_date"), body.get("customer"), body.get("project"),
					body.get("contact"), body.get("delivery_location"), quotationScope(body),
					map(result.get("payment")).get("label") };
			for (int i = 0; i < coords.length; i++)
				literal(sheet, coords[i], values[i]);
			for (String coord : installation ? List.of("H3", "I3") : List.of("G3", "H3"))
				literal(sheet, coord, "");
			int end = 8 + detailLines.size(), summaryRow = end + 1;
			boolean materialPending = detailLines.stream().anyMatch(line -> !Set.of("安装", "整单汇总")
					.contains(s(line, "process")) && (blank(line.get("material_unit")) || blank(line.get("material_amount"))));
			var installationFee = map(map(result.get("fees")).get("installation"));
			boolean installationPending = installation && (s(installationFee, "status").equals("pending")
					|| detailLines.stream().anyMatch(line -> blank(line.get("installation_amount"))));
			Object displayedTotal = materialPending || installationPending
					|| equipmentFreightLine != null && yes(equipmentFreightLine.get("pending")) ? null : result.get("total");
			if (equipmentFreightLine != null) {
				prepareEquipmentFreightSummaryRow(sheet, summaryRow, summaryRow + 1, columns, amountColumn);
				literal(sheet, "A" + (summaryRow + 1), "设备运费");
				number(cell(sheet, summaryRow, amountColumn), equipmentFreightLine.get("material_amount"));
				if (yes(equipmentFreightLine.get("pending")))
					markPendingSummaryRow(wb, sheet, summaryRow, columns);
				equipmentFreightLine.put("_visible_row", summaryRow + 1);
				summaryRow++;
			}
			literal(sheet, "A" + (summaryRow + 1), "材料小计");
			number(cell(sheet, summaryRow, amountColumn), materialPending ? null : result.get("subtotal"));
			summaryRow++;
			if (installation) {
				literal(sheet, "A" + (summaryRow + 1), "安装小计");
				number(cell(sheet, summaryRow, amountColumn), installationPending ? null : installationFee.get("amount"));
				summaryRow++;
			}
			int totalRow = summaryRow, uppercase = totalRow + 1;
			literal(sheet, "A" + (totalRow + 1), "价税合计");
			number(cell(sheet, totalRow, amountColumn), displayedTotal);
			literal(sheet, "D" + (uppercase + 1), blank(displayedTotal) ? "" : upperMoney(n(displayedTotal)));
			List<String> notes = notes(body, result);
			if (review)
				notes.add(0, installation
						? "校对版说明：红色项目仍待确认；材料和安装分别全部确认后显示对应小计，整单确认后显示合计。请填写安装金额，本行总金额在回导时重新计算。"
						: "校对版说明：红色项目仍待确认；材料全部确认后显示材料小计，整单确认后显示合计。本文件可编辑，补充后可导回系统。");
			for (int i = 0; i < 5; i++) {
				int r = uppercase + 2 + i;
				Cell cell = cell(sheet, r, 0);
				String note = i < notes.size() ? notes.get(i) : "";
				cell.setCellValue(note);
				CellStyle style = wb.createCellStyle();
				style.cloneStyleFrom(cell.getCellStyle());
				style.setWrapText(true);
				style.setAlignment(HorizontalAlignment.LEFT);
				style.setVerticalAlignment(VerticalAlignment.CENTER);
				cell.setCellStyle(style);
				sheet.getRow(r)
					.setHeightInPoints(Math.max(31,
							(float) Math.ceil(note.codePoints().map(c -> c > 127 ? 2 : 1).sum() / 105.0) * 18));
			}
			sheet.setRepeatingRows(new CellRangeAddress(0, 8, -1, -1));
			sheet.setFitToPage(true);
			sheet.setHorizontallyCenter(true);
			sheet.getPrintSetup().setLandscape(true);
			sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
			sheet.getPrintSetup().setFitWidth((short) 1);
			sheet.getPrintSetup().setFitHeight((short) (lines.size() <= 12 ? 1 : 0));
			wb.setPrintArea(0, 0, columns - 1, 0, uppercase + 6);
			if (review) {
				QuoteCalculationDetails.addSheet(wb, body, result, lines, installation);
				reviewMetadata(wb, lines, context, installation);
			}
			wb.write(out);
			return out.toByteArray();
		}
	}

	private static void prepareEquipmentFreightSummaryRow(XSSFSheet sheet, int row, int styleRow, int columns,
			int amountColumn) {
		CellStyle label = cell(sheet, styleRow, 0).getCellStyle(), amount = cell(sheet, styleRow, amountColumn).getCellStyle();
		for (int i = sheet.getNumMergedRegions() - 1; i >= 0; i--) {
			CellRangeAddress range = sheet.getMergedRegion(i);
			if (range.getFirstRow() == row && range.getLastRow() == row)
				sheet.removeMergedRegion(i);
		}
		for (int c = 0; c < columns; c++) {
			Cell target = cell(sheet, row, c);
			target.setBlank();
			target.setCellStyle(c >= amountColumn ? amount : label);
		}
		sheet.addMergedRegion(new CellRangeAddress(row, row, 0, amountColumn - 1));
		sheet.addMergedRegion(new CellRangeAddress(row, row, amountColumn, columns - 1));
		sheet.getRow(row).setHeightInPoints(34);
	}

	private static void markPendingSummaryRow(XSSFWorkbook wb, XSSFSheet sheet, int row, int columns) {
		for (int c = 0; c < columns; c++) {
			Cell target = cell(sheet, row, c);
			CellStyle pending = wb.createCellStyle();
			pending.cloneStyleFrom(target.getCellStyle());
			pendingFill(pending);
			target.setCellStyle(pending);
		}
	}

	private static void reviewMetadata(XSSFWorkbook wb, List<Map<String, Object>> lines,
			Map<String, Object> context, boolean installation) {
		XSSFSheet metadata = wb.createSheet(REVIEW_METADATA_SHEET);
		String[][] properties = { { "format", REVIEW_METADATA_MAGIC }, { "quote_id", s(context, "quote_id") },
				{ "revision", s(context, "revision") }, { "family", s(context, "family") },
				{ "version", s(context, "version") }, { "template_version", TEMPLATE_VERSION },
				{ "visible_sheet", "报价校对版" }, { "column_count", installation ? "13" : "10" } };
		for (int i = 0; i < properties.length; i++) {
			cell(metadata, i, 0).setCellValue(properties[i][0]);
			cell(metadata, i, 1).setCellValue(properties[i][1]);
		}
		String[] headers = { "visible_row", "line_key", "source_row_id", "was_pending", "name", "thickness",
				"spec", "process", "unit", "quantity", "material_unit", "material_amount", "installation_amount",
				"line_total", "remark" };
		for (int i = 0; i < headers.length; i++)
			cell(metadata, 9, i).setCellValue(headers[i]);
		String[] fields = { "name", "thickness", "spec", "process", "unit", "quantity", "material_unit",
				"material_amount", "installation_amount", "line_total", "remark" };
		for (int i = 0; i < lines.size(); i++) {
			Map<String, Object> line = lines.get(i);
			int row = i + 10;
			cell(metadata, row, 0).setCellValue(line.get("_visible_row") == null ? i + 10 : n(line.get("_visible_row")).intValue());
			String sourceRowId = s(line, "source_row_id");
			cell(metadata, row, 1).setCellValue(sourceRowId.isEmpty() ? "line-" + (i + 1) : sourceRowId);
			cell(metadata, row, 2).setCellValue(sourceRowId);
			cell(metadata, row, 3).setCellValue(yes(line.get("pending")));
			for (int j = 0; j < fields.length; j++) {
				String value = s(line, fields[j]);
				if (!value.isEmpty())
					cell(metadata, row, j + 4).setCellValue(value);
			}
		}
		wb.setSheetVisibility(wb.getSheetIndex(metadata), SheetVisibility.VERY_HIDDEN);
	}

	static String upperMoney(BigDecimal value) {
		if (value.signum() < 0)
			throw new RuleException("正式报价总额不能为负数");
		String[] digits = { "零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖" }, groups = { "", "万", "亿", "兆", "京" };
		BigDecimal amount = value.setScale(2, RoundingMode.HALF_UP);
		String integer = amount.toBigInteger().toString();
		StringBuilder text = new StringBuilder();
		boolean zero = false;
		for (int i = 0; i < integer.length(); i++) {
			int digit = integer.charAt(i) - '0', remaining = integer.length() - i - 1, within = remaining % 4;
			if (digit == 0)
				zero = text.length() > 0;
			else {
				if (zero)
					text.append("零");
				text.append(digits[digit]).append(new String[] { "", "拾", "佰", "仟" }[within]);
				zero = false;
			}
			if (within == 0 && remaining > 0) {
				int from = Math.max(0, i - 3);
				if (Integer.parseInt(integer.substring(from, i + 1)) != 0)
					text.append(groups[remaining / 4]);
			}
		}
		if (text.length() == 0)
			text.append("零");
		int cents = amount.remainder(ONE).movePointRight(2).intValue();
		String ending = cents == 0 ? "整"
				: (cents / 10 > 0 ? digits[cents / 10] + "角" : "零") + (cents % 10 > 0 ? digits[cents % 10] + "分" : "");
		return "人民币" + text + "元" + ending;
	}

}
