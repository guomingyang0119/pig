package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证客户工作簿导入与正式/校对 Excel 导出的完整报价口径。
 * 覆盖混合产品数量、法兰附项、材料/安装汇总、运费展示、中文金额及可读计算明细。
 */
class DuctWorkbookTests {

	final DuctExportService exporter = new DuctExportService();

	/** 验证共用工程量列按单位分派风管面积和设备数量，识别不同设备类别并保留未知材料的来源行。 */
	@Test
	void automaticImportUsesOneQuantityColumnAndKeepsUnknownRows() throws IOException {
		byte[] bytes;
		try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
			var sheet = wb.createSheet("混合客户清单");
			String[][] values = { { "产品名称", "规格型号", "工程量", "单位", "备注" }, { "镀锌钢板风管", "0.75 / 500×320", "12", "㎡", "" },
					{ "防火阀", "500×500", "2", "个", "" }, { "壁式轴流风机", "T1 0.18kW", "1", "台", "" },
					{ "换气扇", "TEST", "3", "台", "" }, { "未知材料", "", "4", "个", "需确认" } };
			for (int r = 0; r < values.length; r++) {
				var row = sheet.createRow(r);
				for (int c = 0; c < values[r].length; c++)
					row.createCell(c).setCellValue(values[r][c]);
			}
			wb.write(out);
			bytes = out.toByteArray();
		}
		var importer = new DuctImportService();
		var sheet = importer.read(bytes, "混合客户清单.xlsx").get(0);
		assertEquals(2, sheet.get("auto_start_row"));
		var mapping = map(sheet.get("auto_mapping"));
		assertEquals(0, mapping.get("name"));
		var rows = list(importer.build(sheet, sheet.get("auto_start_row"), mapping, "mixed_v1").get("rows"));
		assertEquals(List.of("风管", "风阀", "风机", "外购设备", ""), rows.stream().map(r -> s(r, "category")).toList());
		assertEquals("12", rows.get(0).get("engineering_quantity"));
		assertEquals(List.of("2", "1", "3", "4"), rows.subList(1, 5).stream().map(r -> s(r, "quantity")).toList());
		assertEquals("4", rows.get(4).get("engineering_quantity"));
		assertEquals(6, rows.get(4).get("source_row"));
		Path dir = Path.of("..", "..", "quotation-verification", "test-output");
		Files.createDirectories(dir);
		Files.write(dir.resolve("自动识别-混合客户清单.xlsx"), bytes);
	}

	Map<String, Object> body, result;

	/** 用指定产品数、安装开关和法兰形式建立正式可报价测试样例。 */
	void calculate(int count, boolean installation, String flange) throws IOException {
		calculate(count, installation, flange, null);
	}

	/** 从固定种子加载风管/安装指标并试算，支持实际角钢米数，确认测试结果具备正式导出条件。 */
	void calculate(int count, boolean installation, String flange, String actualFlangeMeters) throws IOException {
		Map<String, Object> catalog = new ObjectMapper().readValue(DuctExportService.asset("duct-catalog-seed.json"),
				Map.class);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			var attributes = obj("thickness", "0.75", "duct_cross_section", flange.equals("round") ? "circular" : "rectangular");
			if (flange.equals("round")) attributes.putAll(obj("diameter", "500", "round_flange_count", "3"));
			if (actualFlangeMeters != null)
				attributes.put("flange_meters", actualFlangeMeters);
			rows.add(obj("row_id", "row-" + i, "name", "镀锌风管", "spec", flange.equals("round") ? "0.75 / Φ500" : "0.75 / 800×320", "category", "风管", "engineering_quantity", "100",
					"unit", "㎡", "attributes", attributes,
					"remark", "客户备注", "installation", obj("unit_price", "2.031")));
		}
		body = obj("customer", "=1+1", "project", "导出验收工程", "contact", "测试联系人", "quote_date", "2026-09-23", "note",
				"=HYPERLINK(\"https://example.com\")", "duct_options",
				obj("area_basis", "net", "product_state", "semi", "shape", "straight", "flange", flange, "zinc_layer",
						"40", "tax_included", true, "include_common_punch", true, "include_freight", true,
						"include_installation", installation, "installation_amount", "200", "payment_option",
						installation ? "with_install_progress_70" : "no_install_days_15"),
				"rows", rows);
		body.put("installation_options",
				obj("included", installation, "region", "贵州", "include_auxiliaries", true, "site_amount", "0.03",
						"site_note", "高空施工附加费"));
		var installCatalog = new ObjectMapper().readValue(DuctExportService.asset("installation-catalog-seed.json"),
				Map.class);
		installCatalog.put("effective_from", "2020-01-01");
		result = new DuctPricingEngine().calculate(body,
				List.of(obj("id", "test", "family", "test", "version", 1, "kind", "duct_catalog", "status", "published",
						"body", catalog),
						obj("id", "installation", "family", "installation", "version", 1, "kind",
								"installation_catalog", "status", "published", "body", installCatalog)),
				obj("formal_template_confirmed", true), LocalDate.of(2026, 9, 23));
		assertTrue(yes(result.get("formal_ready")), str(result.get("issues")));
	}

	/** 验证按本行实际 12 米另列的角钢法兰展示基础价和报价步骤，不再套按面积折算系数。 */
	@Test
	void actualAngleFlangeHasItsOwnReadableLineCalculation() throws IOException {
		calculate(1, false, "angle", "12");
		byte[] review = exporter.write(body, result, "TEST-ANGLE-DETAIL", false,
				obj("quote_id", "quote-angle", "revision", 1, "family", "family-angle", "version", 1));
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review))) {
			StringBuilder explanation = new StringBuilder();
			for (Row row : wb.getSheet("计算明细"))
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						explanation.append(cell.getStringCellValue()).append(' ');
			assertTrue(explanation.toString().contains("角钢法兰基础价/米"));
			assertTrue(explanation.toString().contains("角钢法兰报价/米"));
			assertTrue(explanation.toString().contains("按实际米数另列，本行不乘角钢法兰按面积折算系数"));
			assertTrue(explanation.toString().contains("12米"));
		}
	}

	/**
	 * 验证校对版显示圆管含法兰及安装的代入数字明细；人工改价后原主行轨迹退出，未改安装附项轨迹继续保留。
	 * 正式文件不包含内部计算明细页。
	 */
	@Test
	void reviewExportShowsCalculationDetailsForEachPricedLine() throws IOException {
		calculate(1, true, "round");
		byte[] review = exporter.write(body, result, "TEST-CALCULATION", false,
				obj("quote_id", "quote-calculation", "revision", 1, "family", "family-calculation", "version", 1));
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review))) {
			String remark = wb.getSheetAt(0).getRow(9).getCell(12).getStringCellValue();
			assertTrue(remark.contains("圆形风管"), remark);
			assertTrue(remark.contains("已含法兰"), remark);
			assertFalse(remark.contains("；null"), remark);
			Sheet details = wb.getSheet("计算明细");
			assertNotNull(details);
			assertTrue(details.getRow(0).getCell(0).getStringCellValue().startsWith("镀锌风管"));
			assertEquals("材料计算明细", details.getRow(1).getCell(0).getStringCellValue());
			assertTrue(details.getRow(2).getCell(0).getStringCellValue().startsWith("铁皮材料价/㎡："),
					"材料分区标题下一行应直接列出代入数字的算式");
			assertFalse(details.getRow(2).getCell(0).getStringCellValue().contains("镀锌风管"));
			StringBuilder text = new StringBuilder();
			for (Row row : details)
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						text.append(cell.getStringCellValue()).append(' ');
			assertTrue(text.toString().contains("铁皮材料价/㎡"));
			assertTrue(text.toString().contains("0.75 × "));
			assertTrue(text.toString().contains("吨价"));
			assertFalse(text.toString().contains("material_price + processing"), "不能把程序公式原样给用户看");
			assertTrue(text.toString().contains("方形直管基准单价/㎡"));
			assertTrue(text.toString().contains("圆形风管半成品单价/㎡"));
			assertTrue(text.toString().contains("+ 30（已含法兰）"));
			assertFalse(text.toString().contains("圆法兰报价/个"), "圆管不再按个数另收法兰费用");
			assertNull(list(result.get("rows")).get(0).get("round_flange_amount"));
			assertTrue(text.toString().contains("安装人工"));
			assertTrue(text.toString().contains("安装计算明细"));
			assertTrue(text.toString().contains("向上取整(0.03 × 1.01)"));
			assertTrue(text.toString().contains("本行报价"));
			assertTrue(text.toString().contains("付款系数"));
			assertTrue(text.toString().contains(s(result, "total")));
		}
		var imported = new QuoteReviewImportService().read(review, "校对版.xlsx", "quote-calculation", 1);
		assertNotNull(imported);
		var manualLines = list(imported.get("lines"));
		manualLines.get(0).put("price_changed", true);
		manualLines.get(0).put("material_amount", "999");
		result.put("manual_lines", manualLines);
		byte[] reExport = exporter.write(body, result, "TEST-CALCULATION", false,
				obj("quote_id", "quote-calculation", "revision", 2, "family", "family-calculation", "version", 1));
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(reExport))) {
			StringBuilder explanation = new StringBuilder();
			for (Row row : wb.getSheet("计算明细"))
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						explanation.append(cell.getStringCellValue()).append(' ');
			assertTrue(explanation.toString().contains("人工校对"));
			assertFalse(explanation.toString().contains("铁皮材料价/㎡"), "人工改价后不能再显示旧试算作为该行依据");
			assertFalse(explanation.toString().contains("圆形风管半成品单价/㎡"), "人工改价后不能继续显示旧材料报价依据");
			assertTrue(explanation.toString().contains("向上取整(0.03 × 1.01)"),
					"未改价的现场附加费仍应保留原计算明细");
		}
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(exporter.write(body, result, "TEST-CALCULATION", true)))) {
			assertNull(wb.getSheet("计算明细"));
		}
	}

	/** 验证设备校对明细显示阀体、人工、机构、毛利、材料税和本行取整，并在整单展示一次付款系数。 */
	@Test
	void equipmentReviewDetailsShowCostPartsAndFinalRowArithmetic() throws IOException {
		var quote = obj("pricing_mode", "equipment_v1", "customer", "测试客户", "project", "排错工程",
				"equipment_options", obj("tax_included", true), "rows",
				List.of(obj("row_id", "valve-1", "name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2")));
		var calculated = obj("rows", List.of(obj("status", "priced", "pricing_mode", "equipment_v1",
				"equipment_kind", "fire", "billing_unit", "个", "billing_quantity", "2", "unit_price", "100",
				"amount", "200", "unit_cost", "50", "supply_unit_unrounded", "100",
				"detail", obj("components", obj("frame", "25", "labor", "15", "mechanism", "10"),
						"gross_margin", "0.4", "tax_factor", "1.2", "price_before_tax", "83.333333333",
						"price_after_tax", "100", "mechanism_base", "6", "remote_extra", "4",
						"component_inputs", obj("width", "500", "height", "500"),
						"component_formulas", obj("frame", "500 * 0.05", "labor", "15",
								"mechanism", "6 + 4")),
				"trace", List.of(obj("step", "不含税成本", "expression", "框架+人工", "value", "50")))),
				"fees", obj(), "issues", List.of(), "subtotal", "200", "before_payment_total", "200",
				"payment", obj("coefficient", "1.05"), "total", "210", "formal_ready", true);
		byte[] review = exporter.write(quote, calculated, "TEST-EQUIPMENT-DETAIL", false,
				obj("quote_id", "quote-equipment", "revision", 2, "family", "family-equipment", "version", 1));
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review))) {
			Sheet details = wb.getSheet("计算明细");
			assertNotNull(details);
			assertTrue(details.getRow(0).getCell(0).getStringCellValue().startsWith("防火阀"));
			assertTrue(details.getRow(1).getCell(0).getStringCellValue().startsWith("阀体及人工成本/个："));
			assertTrue(details.getRow(2).getCell(0).getStringCellValue().startsWith("机构与远控成本（本行）："));
			StringBuilder text = new StringBuilder();
			for (Row row : details)
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						text.append(cell.getStringCellValue()).append(' ');
			assertTrue(text.toString().contains("阀体框架"));
			assertTrue(text.toString().contains("(6 + 4) × 2 = 20"));
			assertTrue(text.toString().contains("25 + 15 + 10 = 50"));
			assertTrue(text.toString().contains("50 ÷ (1 - 0.4)"));
			assertTrue(text.toString().contains("向上取整(100元/个 × 2个) = 200"));
			assertTrue(text.toString().contains("200 × 1.05"));
			assertFalse(text.toString().contains("CEILING("));
		}
	}

	/** 验证校对备注带设备计价分支、选型和待确认识别类型；正式备注保留客户内容，去掉内部识别说明。 */
	@Test
	void reviewRemarksShowActualPricingLogicButFormalRemarksStayCustomerFacing() throws IOException {
		var sources = List.of(
				obj("row_id", "electric-1", "name", "电动阀", "spec", "500×500", "unit", "个", "quantity", "1",
						"remark", "客户原备注", "attributes", obj("equipment_kind", "electric_control")),
				obj("row_id", "fan-1", "name", "轴流风机", "spec", "风量1000 风压100", "unit", "台", "quantity", "1",
						"remark", "风机原备注", "attributes", obj("equipment_kind", "fan")),
				obj("row_id", "relief-1", "name", "泄压阀", "spec", "600×400", "unit", "个", "quantity", "1",
						"remark", "现场复核", "attributes", obj("equipment_kind", "pressure_relief")));
		var calculatedRows = List.of(
				obj("status", "priced", "pricing_mode", "equipment_v1", "equipment_kind", "electric_control",
						"billing_unit", "个", "billing_quantity", "1", "unit_price", "100", "amount", "100", "detail", obj()),
				obj("status", "priced", "pricing_mode", "equipment_v1", "equipment_kind", "fan", "billing_unit", "台",
						"billing_quantity", "1", "unit_price", "200", "amount", "200", "detail",
						obj("selected_fan", obj("model", "SWF-5", "powers", List.of("0.55"), "airflow", "1000",
								"pressure", "100", "speed", "1450"))),
				obj("status", "pending", "pricing_mode", "equipment_v1", "equipment_kind", "pressure_relief",
						"reason", "缺少宽度（mm）", "detail", obj()));
		var body = obj("pricing_mode", "equipment_v1", "customer", "测试客户", "project", "计价逻辑备注",
				"quote_date", "2026-09-30", "equipment_options", obj("tax_included", false),
				"installation_options", obj("included", false), "rows", sources);
		var reviewResult = obj("rows", calculatedRows, "fees", obj(), "issues", List.of("缺少宽度（mm）"),
				"subtotal", "300", "total", null, "payment", obj(), "formal_ready", false);

		byte[] review = exporter.write(body, reviewResult, "TEST-LOGIC-REMARK", false,
				obj("quote_id", "quote-logic", "revision", 1, "family", "family-logic", "version", 1));
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review))) {
			Sheet sheet = wb.getSheetAt(0);
			assertEquals("客户原备注\n系统计价逻辑：电动调节阀", sheet.getRow(9).getCell(9).getStringCellValue());
			assertEquals("选择型号：SWF-5；风机原备注；客户要求规格：风量1000 风压100\n系统计价逻辑：风机选型；选中型号：SWF-5",
					sheet.getRow(10).getCell(9).getStringCellValue());
			assertEquals("待确认：缺少宽度（mm）；现场复核\n系统识别类型：余压阀；计价状态：待确认",
					sheet.getRow(11).getCell(9).getStringCellValue());
		}

		var formalBody = new LinkedHashMap<>(body);
		formalBody.put("rows", sources.subList(0, 2));
		var formalResult = obj("rows", calculatedRows.subList(0, 2), "fees", obj(), "issues", List.of(),
				"subtotal", "300", "total", "300", "payment", obj(), "formal_ready", true);
		byte[] formal = exporter.write(formalBody, formalResult, "TEST-LOGIC-REMARK", true);
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(formal))) {
			Sheet sheet = wb.getSheetAt(0);
			assertEquals("客户原备注", sheet.getRow(9).getCell(9).getStringCellValue());
			assertEquals("选择型号：SWF-5；风机原备注；客户要求规格：风量1000 风压100", sheet.getRow(10).getCell(9).getStringCellValue());
			for (Row row : sheet)
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						assertFalse(cell.getStringCellValue().contains("系统计价逻辑："));
		}
	}

	/**
	 * 组合短/长清单、安装开关及三种法兰验证模板明细与付款前合计相等。
	 * 同时核对公式清空、Logo、合并区域、打印设置和材料/安装列位，防止动态扩展破坏版式。
	 */
	@TestFactory
	List<DynamicTest> templatesBalanceAcrossShortLongAndSeparateFlangeLines() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean installation : List.of(false, true))
			for (int count : List.of(1, 6, 16))
				for (String flange : List.of("common", "angle", "round"))
					tests.add(DynamicTest.dynamicTest(installation + "-" + count + "-" + flange, () -> {
						calculate(count, installation, flange);
						byte[] bytes = exporter.write(body, result, "TEST-V1", true);
						try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
							assertEquals(1, wb.getNumberOfSheets());
							XSSFSheet sh = wb.getSheetAt(0);
							assertEquals(installation ? "带安装" : "单层材料", sh.getSheetName());
							assertEquals(installation ? "安装单价（元）" : "备注",
									sh.getRow(8).getCell(installation ? 9 : 9).getStringCellValue());
							if (installation)
								assertAll(
										() -> assertEquals("安装金额（元）", sh.getRow(8).getCell(10).getStringCellValue()),
										() -> assertEquals("本行总金额（元）", sh.getRow(8).getCell(11).getStringCellValue()),
										() -> assertEquals("备注", sh.getRow(8).getCell(12).getStringCellValue()));
							assertEquals(CellType.STRING, sh.getRow(4).getCell(1).getCellType());
							assertEquals("=1+1", sh.getRow(4).getCell(1).getStringCellValue());
							int lines = count + (installation ? 1 : 0);
							BigDecimal sum = ZERO;
							for (int r = 9; r < 9 + lines; r++) {
								for (Cell c : sh.getRow(r))
									assertNotEquals(CellType.FORMULA, c.getCellType(),
											"exported values must not retain template formulas");
								Cell materialAmount = sh.getRow(r).getCell(8);
								if (installation) {
									Cell installationAmount = sh.getRow(r).getCell(10), lineTotal = sh.getRow(r).getCell(11);
									if (lineTotal.getCellType() == CellType.NUMERIC)
										sum = sum.add(BigDecimal.valueOf(lineTotal.getNumericCellValue()));
									if (materialAmount.getCellType() == CellType.NUMERIC
											&& installationAmount.getCellType() == CellType.NUMERIC)
										assertEquals(materialAmount.getNumericCellValue() + installationAmount.getNumericCellValue(),
												lineTotal.getNumericCellValue(), 0.0000001,
												"本行总金额应等于材料金额加安装金额");
								}
								else if (materialAmount.getCellType() == CellType.NUMERIC)
									sum = sum.add(BigDecimal.valueOf(materialAmount.getNumericCellValue()));
									assertEquals(r < 9 + count ? "0.0" : "0.0########",
										sh.getRow(r).getCell(7).getCellStyle().getDataFormatString());
							}
							assertEquals(0, n(result.get("before_payment_total")).compareTo(sum),
									"product line totals " + sum + " must equal payment-before total "
											+ result.get("before_payment_total"));
							if (installation) {
								Row first = sh.getRow(9);
								assertEquals(first.getCell(10).getNumericCellValue() / first.getCell(6).getNumericCellValue(),
										first.getCell(9).getNumericCellValue(), 0.0000001,
										"正式报价中的安装单价与安装金额应相互对应");
							}
							int materialSummaryRow = 9 + lines;
							int totalRow = materialSummaryRow + (installation ? 2 : 1);
							assertEquals(n(result.get("total")).doubleValue(),
									sh.getRow(totalRow).getCell(installation ? 11 : 8).getNumericCellValue());
							assertEquals("材料小计", sh.getRow(materialSummaryRow).getCell(0).getStringCellValue());
							assertEquals(n(result.get("subtotal")).doubleValue(),
									sh.getRow(materialSummaryRow).getCell(installation ? 11 : 8).getNumericCellValue());
							if (installation) {
								Row summary = sh.getRow(materialSummaryRow + 1);
								assertEquals("安装小计", summary.getCell(0).getStringCellValue());
								assertEquals(n(map(map(result.get("fees")).get("installation")).get("amount")).doubleValue(),
										summary.getCell(11).getNumericCellValue());
							}
							assertEquals("价税合计", sh.getRow(totalRow).getCell(0).getStringCellValue());
							StringBuilder text = new StringBuilder();
							for (Row r : sh)
								for (Cell c : r)
									if (c.getCellType() == CellType.STRING)
										text.append(c.getStringCellValue());
							assertTrue(text.toString().contains(installation ? "付款进度70%" : "付款周期7—15天"));
							if (installation) {
								assertTrue(text.toString().contains("现场附加费"));
								assertTrue(text.toString().contains("高空施工附加费"));
							}
							if (flange.equals("common"))
								assertTrue(text.toString().contains("含角码卡扣"));
							if (flange.equals("angle"))
								assertTrue(text.toString().contains("含角钢法兰"));
							if (flange.equals("round")) {
								assertTrue(text.toString().contains("圆形风管"));
								assertTrue(text.toString().contains("已含法兰"));
								assertFalse(text.toString().contains("圆法兰按客户数量另列"));
								assertFalse(text.toString().contains("；null"));
							}
							assertFalse(text.toString().contains("no_install_days_15"));
							assertTrue(text.toString().contains("人民币"));
							assertTrue(sh.getPrintSetup().getLandscape());
							assertEquals(1, sh.getPrintSetup().getFitWidth());
							assertEquals(1, sh.getDrawingPatriarch().getShapes().size());
							XSSFClientAnchor anchor = ((XSSFPicture) sh.getDrawingPatriarch().getShapes().get(0))
								.getClientAnchor();
							assertTrue(anchor.getDx2() >= 0 && anchor.getDy2() >= 0);
							assertNotNull(wb.getPrintArea(0));
							assertTrue(wb.getNumCellStyles() < 250, "styles must be reused");
							sh.validateMergedRegions();
						}
						if (count == 6 && flange.equals("angle")) {
							Path dir = Path.of("..", "..", "quotation-verification", "test-output");
							Files.createDirectories(dir);
							Files.write(dir.resolve(installation ? "带安装-验收样例.xlsx" : "单层材料-验收样例.xlsx"), bytes);
							Files.writeString(dir.resolve("sample-" + installation + ".json"),
									new ObjectMapper().writeValueAsString(obj("body", body, "result", result)));
						}
					}));
		return tests;
	}

	/** 验证付款或安装指标尚未确认时仍可生成校对版，保留材料金额，安装和本行合计空白并标红。 */
	@Test
	void equipmentInstallationReviewExportsBeforePaymentIsSelected() throws IOException {
		var quote = obj("pricing_mode", "equipment_v1", "customer", "测试客户", "project", "安装待确认工程",
				"quote_date", "2026-09-26", "installation_options", obj("included", true), "equipment_options",
				obj("tax_included", false, "payment_option", ""), "rows",
				List.of(obj("row_id", "row-1", "name", "轴流风机", "spec", "GXF", "unit", "台", "quantity", "1")));
		var calculated = obj("rows",
				List.of(obj("status", "priced", "pricing_mode", "equipment_v1", "billing_unit", "台",
						"billing_quantity", "1", "unit_price", "1000", "amount", "1000")),
				"fees", obj("installation", obj("status", "pending", "amount", null, "lines", List.of())),
				"issues", List.of("请选择整单付款方式", "安装费：需要一个已发布且生效的安装指标版本"),
				"subtotal", "1000", "total", null, "payment", obj(), "formal_ready", false);

		byte[] bytes = exporter.write(quote, calculated, "TEST-REVIEW", false,
				obj("quote_id", "quote-1", "revision", 1, "family", "family-1", "version", 1));
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
			assertEquals("报价校对版", wb.getSheetName(0));
			Row product = wb.getSheetAt(0).getRow(9);
			assertEquals(1000D, product.getCell(8).getNumericCellValue(), "安装待确认时仍保留材料金额");
			assertEquals(CellType.BLANK, product.getCell(9).getCellType());
			assertEquals(CellType.BLANK, product.getCell(10).getCellType());
			assertEquals(CellType.BLANK, product.getCell(11).getCellType());
			assertArrayEquals(new byte[] { (byte) 0xF4, (byte) 0xCC, (byte) 0xCC },
					((XSSFCellStyle) product.getCell(1).getCellStyle()).getFillForegroundXSSFColor().getRGB());
			StringBuilder text = new StringBuilder();
			for (Row row : wb.getSheetAt(0))
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						text.append(cell.getStringCellValue());
			assertTrue(text.toString().contains("安装不含控制箱"));
		}
	}

	/** 验证人工只填写产品行安装总额，回导重新计算材料加安装的本行报价，并标记该合计为派生变化。 */
	@Test
	void installationReviewImportsOneRowTotalAndRecalculatesCombinedAmount() throws IOException {
		calculate(1, true, "common");
		byte[] review = exporter.write(body, result, "TEST-INSTALL-REVIEW", false,
				obj("quote_id", "quote-install", "revision", 3, "family", "family-install", "version", 1));
		byte[] completed;
		BigDecimal material, changedInstallation;
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.getSheetAt(0);
			assertEquals("安装单价（元）", sheet.getRow(8).getCell(9).getStringCellValue());
			assertEquals("安装金额（元）", sheet.getRow(8).getCell(10).getStringCellValue());
			assertEquals("本行总金额（元）", sheet.getRow(8).getCell(11).getStringCellValue());
			material = BigDecimal.valueOf(sheet.getRow(9).getCell(8).getNumericCellValue());
			changedInstallation = BigDecimal.valueOf(sheet.getRow(9).getCell(10).getNumericCellValue()).add(n("100"));
			sheet.getRow(9).getCell(10).setCellValue(changedInstallation.doubleValue());
			wb.write(out);
			completed = out.toByteArray();
		}
		var parsed = new QuoteReviewImportService().read(completed, "校对版.xlsx", "quote-install", 3);
		var first = list(parsed.get("lines")).get(0);
		assertFalse(first.containsKey("installation_unit"));
		assertEquals(0, changedInstallation.compareTo(n(first.get("installation_amount"))));
		assertEquals(0, add(material, changedInstallation).compareTo(n(first.get("line_total"))));
		assertTrue(((List<?>) first.get("derived_fields")).contains("line_total"));
		assertEquals(0, n(parsed.get("before_payment_total"))
			.compareTo(add(n(parsed.get("material_total")), n(parsed.get("installation_total")))));
	}

	/** 验证人工整单设备运费作为汇总行展示，修改金额后正确回导，材料和安装产品列位不被挤占。 */
	@Test
	void equipmentFreightUsesSummaryRowWithoutChangingReviewImport() throws IOException {
		for (boolean installation : List.of(false, true)) {
			var quote = obj("pricing_mode", "equipment_v1", "customer", "测试客户", "project", "设备运费布局",
					"quote_date", "2026-09-27", "equipment_options", obj("tax_included", false),
					"installation_options", obj("included", installation));
			var product = obj("name", "轴流风机", "thickness", "-", "spec", "GXF-5", "process", "设备供货",
					"unit", "台", "quantity", "1", "material_unit", "1000", "material_amount", "1000",
					"remark", "测试设备");
			if (installation)
				product.putAll(obj("installation_amount", "100", "line_total", "1100"));
			var calculated = obj("manual_lines", List.of(product), "fees",
					obj("equipment_freight", obj("status", "ready", "amount", "88", "region", "贵州", "reason", "人工运费"),
							"installation", obj("amount", installation ? "100" : "0", "lines", List.of())),
					"subtotal", "1000", "total", installation ? "1188" : "1088", "payment", obj(), "formal_ready", true);
			byte[] formal = exporter.write(quote, calculated, "TEST-FORMAL-FREIGHT-" + installation, true);
			try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(formal))) {
				Sheet sheet = wb.getSheetAt(0);
				assertEquals("设备运费", sheet.getRow(10).getCell(0).getStringCellValue());
				assertEquals(88D, sheet.getRow(10).getCell(installation ? 11 : 8).getNumericCellValue());
			}
			byte[] review = exporter.write(quote, calculated, "TEST-FREIGHT-" + installation, false,
					obj("quote_id", "quote-freight-" + installation, "revision", 2, "family", "family-freight", "version", 1));
			byte[] edited;
			try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review));
					ByteArrayOutputStream out = new ByteArrayOutputStream()) {
				Sheet sheet = wb.getSheetAt(0);
				int freightRow = 10;
				int materialSummaryRow = 11;
				int totalRow = materialSummaryRow + (installation ? 2 : 1);
				assertEquals("设备运费", sheet.getRow(freightRow).getCell(0).getStringCellValue());
				assertEquals(88D, sheet.getRow(freightRow).getCell(installation ? 11 : 8).getNumericCellValue());
				assertEquals("材料小计", sheet.getRow(materialSummaryRow).getCell(0).getStringCellValue());
				if (installation)
					assertEquals("安装小计", sheet.getRow(materialSummaryRow + 1).getCell(0).getStringCellValue());
				assertTrue(sheet.getRow(totalRow).getCell(0).getStringCellValue().contains("合计"));
				assertNotEquals("设备运费", sheet.getRow(9).getCell(4).getStringCellValue(),
						"设备运费不能再占用产品明细行");
				sheet.getRow(freightRow).getCell(installation ? 11 : 8).setCellValue(99D);
				wb.write(out);
				edited = out.toByteArray();
			}
			var parsed = new QuoteReviewImportService().read(edited, "设备运费校对版.xlsx",
					"quote-freight-" + installation, 2);
			assertEquals(0, n("99").compareTo(n(parsed.get("equipment_freight_total"))));
			assertEquals(0, n(installation ? "1199" : "1099").compareTo(n(parsed.get("before_payment_total"))));
		}
	}

	/** 验证人工已补齐行的正式报价清除待确认及内部计价提示，客户指定品牌等正常备注继续保留。 */
	@Test
	void formalExportRemovesResolvedPendingRemarkFromPreviouslyImportedLines() throws IOException {
		var quote = obj("pricing_mode", "equipment_v1", "customer", "测试客户", "project", "备注清理",
				"quote_date", "2026-09-29", "equipment_options", obj("tax_included", false),
				"installation_options", obj("included", false));
		var product = obj("name", "混流风机", "thickness", "-", "spec", "SP(8)-ST1-RF", "process", "人工确认",
				"unit", "台", "quantity", "1", "material_unit", "500", "material_amount", "500", "pending", false,
				"was_pending", true, "remark", "待确认：缺少风压；客户指定品牌；安装待确认：缺少安装参数\n系统计价逻辑：风机选型；选中型号：SP(8)-ST1-RF");
		var calculated = obj("manual_lines", List.of(product), "fees",
				obj("installation", obj("amount", "0", "lines", List.of())), "subtotal", "500", "total", "500",
				"payment", obj(), "formal_ready", true);

		byte[] formal = exporter.write(quote, calculated, "TEST-RESOLVED-REMARK", true);
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(formal))) {
			assertEquals("客户指定品牌", wb.getSheetAt(0).getRow(9).getCell(9).getStringCellValue());
		}
	}

	/** 验证自动运费已在设备单价中时不再另列；人工改材料金额回导后，汇总不重复追加自动运费。 */
	@Test
	void automaticEquipmentFreightStaysInProductPriceWithoutSummaryRow() throws IOException {
		var quote = obj("pricing_mode", "equipment_v1", "customer", "测试客户", "project", "自动运费布局",
				"quote_date", "2026-09-28", "equipment_options", obj("tax_included", false),
				"installation_options", obj("included", false));
		var product = obj("name", "轴流风机", "thickness", "-", "spec", "GXF-5", "process", "设备供货",
				"unit", "台", "quantity", "1", "material_unit", "1100", "material_amount", "1100", "remark", "含自动运费");
		var calculated = obj("manual_lines", List.of(product), "fees",
				obj("equipment_freight", obj("status", "ready", "amount", "100", "region", "贵州", "included_in_rows", true,
						"reason", "整单试算销售额达到8000元，设备运费已逐行计入设备单台售价"),
						"installation", obj("amount", "0", "lines", List.of())),
				"subtotal", "1100", "total", "1100", "payment", obj(), "formal_ready", true);
		byte[] formal = exporter.write(quote, calculated, "TEST-INCLUDED-FREIGHT-FORMAL", true);
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(formal))) {
			Sheet sheet = wb.getSheetAt(0);
			assertEquals("材料小计", sheet.getRow(10).getCell(0).getStringCellValue());
			assertEquals(1100D, sheet.getRow(9).getCell(8).getNumericCellValue());
			for (Row row : sheet) {
				Cell label = row.getCell(0);
				if (label != null && label.getCellType() == CellType.STRING)
					assertFalse(label.getStringCellValue().contains("设备运费"));
			}
		}
		byte[] review = exporter.write(quote, calculated, "TEST-INCLUDED-FREIGHT", false,
				obj("quote_id", "quote-included-freight", "revision", 2, "family", "family-freight", "version", 1));
		byte[] edited;
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.getSheetAt(0);
			assertEquals("材料小计", sheet.getRow(10).getCell(0).getStringCellValue());
			sheet.getRow(9).getCell(7).setCellValue(1200D);
			sheet.getRow(9).getCell(8).setCellValue(1200D);
			wb.write(out);
			edited = out.toByteArray();
		}
		var parsed = new QuoteReviewImportService().read(edited, "自动设备运费校对版.xlsx", "quote-included-freight", 2);
		assertEquals(false, parsed.get("equipment_freight_included"));
		assertEquals(0, n("1200").compareTo(n(parsed.get("material_total"))));
		assertEquals(0, n("0").compareTo(n(parsed.get("equipment_freight_total"))));
		assertEquals(0, n("1200").compareTo(n(parsed.get("before_payment_total"))));
	}

	/** 验证旧 xls 客户公式只读取缓存结果；两个业务字段映射同列时拒绝导入。 */
	@Test
	void legacyXlsReadsCachedValuesAndRequiresUniqueMappings() throws IOException {
		byte[] bytes;
		try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sh = wb.createSheet("清单");
			Row header = sh.createRow(0);
			String[] titles = { "产品名称", "规格", "工程量", "单位" };
			for (int i = 0; i < titles.length; i++)
				header.createCell(i).setCellValue(titles[i]);
			Row row = sh.createRow(1);
			row.createCell(0).setCellValue("镀锌风管");
			row.createCell(1).setCellValue("0.75");
			row.createCell(2).setCellFormula("2*5");
			row.createCell(3).setCellValue("㎡");
			wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
			wb.write(out);
			bytes = out.toByteArray();
		}
		DuctImportService importer = new DuctImportService();
		Map<String, Object> sh = importer.read(bytes, "旧格式.xls").get(0);
		Path dir = Path.of("..", "..", "quotation-verification", "test-output");
		Files.createDirectories(dir);
		Files.write(dir.resolve("客户验收.xls"), bytes);
		Map<String, Object> mapping = map(sh.get("suggested_mapping"));
		Map<String, Object> imported = importer.build(sh, 2, mapping);
		assertEquals("10", list(imported.get("rows")).get(0).get("engineering_quantity"));
		assertFalse(yes(list(imported.get("rows")).get(0).get("source_issue")));
		mapping.put("remark", mapping.get("name"));
		assertThrows(RuleException.class, () -> importer.build(sh, 2, mapping));
	}

	/** 仅依据实际上标零把 70⁰C 还原为 70℃；普通 700C 不改写，并保留原字符证据。 */
	@Test
	void richTextTemperatureUsesSuperscriptEvidenceInXlsxAndXls() throws IOException {
		for (boolean legacy : List.of(false, true)) {
			byte[] bytes;
			try (Workbook wb = legacy ? new HSSFWorkbook() : new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
				Sheet sheet = wb.createSheet("温度清单");
				Row header = sheet.createRow(0);
				String[] headings = { "物品名称", "规格及型号", "单位", "材料数量" };
				for (int i = 0; i < headings.length; i++) header.createCell(i).setCellValue(headings[i]);
				for (int i = 0; i < 4; i++) {
					Row row = sheet.createRow(i + 1);
					row.createCell(0).setCellValue("防火阀");
					String value = i == 2 ? "7000C 500×500" : "700C 500×500";
					RichTextString text = wb.getCreationHelper().createRichTextString(value);
					if (i != 1) {
						Font superscript = wb.createFont();
						superscript.setTypeOffset(Font.SS_SUPER);
						int offset = i == 3 ? 0 : i == 2 ? 3 : 2;
						text.applyFont(offset, i == 3 ? 3 : offset + 1, superscript);
					}
					row.createCell(1).setCellValue(text);
					row.createCell(2).setCellValue("个");
					row.createCell(3).setCellValue(1);
				}
				wb.write(out);
				bytes = out.toByteArray();
			}
			var importer = new DuctImportService();
			var sheet = importer.read(bytes, legacy ? "温度.xls" : "温度.xlsx").get(0);
			assertEquals(0, sheet.get("sheet_index"));
			var rows = list(importer.buildWorkbook(List.of(sheet), null).get("rows"));
			assertEquals(List.of("70℃ 500×500", "700C 500×500", "700℃ 500×500", "700C 500×500"), rows.stream().map(r -> s(r, "spec")).toList());
			var original = map(rows.get(0).get("original"));
			assertEquals("700C 500×500", ((List<?>) original.get("original_cells")).get(1));
			assertFalse(map(rows.get(1).get("original")).containsKey("original_cells"));
		}
	}

	/** 真实产品数量异常仍可校对原文，金额为空且正式报价阻断，不以 0 代替数量。 */
	@Test
	void reviewExportKeepsInvalidProductQuantityAsTextWithoutPricingIt() throws IOException {
		for (String quantity : List.of("待确认", "2台", "采购总金额（人民币元）：")) {
			var source = obj("row_id", "invalid-1", "name", "待核实风阀", "spec", "500×500", "unit", "个", "quantity", quantity);
			var quote = obj("pricing_mode", "equipment_v1", "customer", "客户", "project", "异常数量核对", "quote_date", "2026-10-07",
					"equipment_options", obj(), "rows", List.of(source));
			var calculation = obj("rows", List.of(obj("status", "pending", "pricing_mode", "equipment_v1", "reason", "数量不是有效数值")),
					"fees", obj(), "subtotal", null, "total", null, "payment", obj(), "formal_ready", false);
			byte[] bytes = exporter.write(quote, calculation, "INVALID-QUANTITY", false);
			try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
				Sheet sheet = wb.getSheet("报价校对版");
				assertEquals(quantity, sheet.getRow(9).getCell(6).getStringCellValue());
				assertEquals(CellType.BLANK, sheet.getRow(9).getCell(7).getCellType());
				assertEquals(CellType.BLANK, sheet.getRow(9).getCell(8).getCellType());
				assertEquals(quantity, wb.getSheet(DuctExportService.REVIEW_METADATA_SHEET).getRow(10).getCell(9).getStringCellValue());
			}
			assertThrows(RuleException.class, () -> exporter.write(quote, calculation, "INVALID-QUANTITY", true));
		}
	}

	/** 验证人民币中文大写支持零、万/亿分组及角分，正确处理数字中间和小数前的零。 */
	@Test
	void chineseAmountsHandleZerosAndCents() {
		assertEquals("人民币零元整", DuctExportService.upperMoney(ZERO));
		assertEquals("人民币壹万零壹元壹角", DuctExportService.upperMoney(n("10001.1")));
		assertEquals("人民币壹亿元整", DuctExportService.upperMoney(n("100000000")));
		assertEquals("人民币壹亿零壹万零壹元零伍分", DuctExportService.upperMoney(n("100010001.05")));
	}

	/** 验证设备模式保留单位、工况和需人工判断的行，混合模式将平方米分派到风管工程量、个数分派到设备数量。 */
	@Test
	void equipmentAndMixedImportsPreserveUnitsQuantitiesAndUncertainRows() throws IOException {
		byte[] bytes;
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet("设备清单");
			String[][] data = { { "设备名称", "规格型号", "工程量", "单位", "功率（kW）", "备注" }, { "风机", "GXF", "2", "台", "2.2", "" },
					{ "防火阀", "500×500", "3", "个", "", "" }, { "未知项目", "", "4", "", "", "" },
					{ "合计", "", "9", "", "", "" } };
			for (int i = 0; i < data.length; i++)
				for (int j = 0; j < data[i].length; j++) {
					Row row = sheet.getRow(i);
					if (row == null)
						row = sheet.createRow(i);
					row.createCell(j).setCellValue(data[i][j]);
				}
			wb.write(out);
			bytes = out.toByteArray();
		}
		var importer = new DuctImportService();
		var sheet = importer.read(bytes, "设备.xlsx").get(0);
		var mapping = map(sheet.get("equipment_mapping"));
		assertEquals(4, mapping.get("power"));
		assertEquals(2, sheet.get("equipment_start_row"));
		var rows = list(importer.build(sheet, 2, mapping, "equipment_v1").get("rows"));
		assertEquals(3, rows.size(), "Confirmed summary rows remain source records rather than products");
		assertEquals("2", rows.get(0).get("quantity"));
		assertEquals("风机", rows.get(0).get("category"));
		assertEquals("2.2", map(rows.get(0).get("attributes")).get("power"));
		assertEquals("风阀", rows.get(1).get("category"));
		assertEquals("", rows.get(1).get("engineering_quantity"));
		assertEquals("", rows.get(2).get("quantity"));
		assertEquals("", rows.get(2).get("unit"));
		mapping.put("quantity", mapping.remove("engineering_quantity"));
		var mixed = obj("name", "复合", "rows",
				List.of(obj("number", 2, "cells", List.of("镀锌风管", "0.75", "100", "㎡", "", ""), "issues", List.of()),
						obj("number", 3, "cells", List.of("防火阀", "500×500", "2", "个", "", ""), "issues", List.of())));
		var mixedRows = list(importer.build(mixed, 2, mapping, "mixed_v1").get("rows"));
		assertEquals("100", mixedRows.get(0).get("engineering_quantity"));
		assertEquals("风管", mixedRows.get(0).get("category"));
		assertEquals("2", mixedRows.get(1).get("quantity"));
		assertEquals("", mixedRows.get(1).get("engineering_quantity"));
		Path dir = Path.of("..", "..", "quotation-verification", "test-output");
		Files.createDirectories(dir);
		Files.write(dir.resolve("设备导入验收.xlsx"), bytes);
	}

}
