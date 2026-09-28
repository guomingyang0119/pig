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

class DuctWorkbookTests {

	final DuctExportService exporter = new DuctExportService();

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

	void calculate(int count, boolean installation, String flange) throws IOException {
		Map<String, Object> catalog = new ObjectMapper().readValue(DuctExportService.asset("duct-catalog-seed.json"),
				Map.class);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < count; i++)
			rows.add(obj("name", "镀锌风管", "spec", "0.75 / 800×320", "category", "风管", "engineering_quantity", "100",
					"unit", "㎡", "attributes", obj("thickness", "0.75", "diameter", "500", "round_flange_count", "3"),
					"remark", "客户备注", "installation", obj("unit_price", "2.031")));
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
							int lines = count * (flange.equals("round") ? 2 : 1) + (installation ? 1 : 0);
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
								assertEquals(r < 9 + count * (flange.equals("round") ? 2 : 1) ? "0.0" : "0.0########",
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
			assertEquals(IndexedColors.LIGHT_YELLOW.getIndex(), product.getCell(1).getCellStyle().getFillForegroundColor());
			StringBuilder text = new StringBuilder();
			for (Row row : wb.getSheetAt(0))
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						text.append(cell.getStringCellValue());
			assertTrue(text.toString().contains("安装不含控制箱"));
		}
	}

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
			assertEquals("安装费用（元）", sheet.getRow(8).getCell(9).getStringCellValue());
			assertEquals("本行报价（元）", sheet.getRow(8).getCell(10).getStringCellValue());
			material = BigDecimal.valueOf(sheet.getRow(9).getCell(8).getNumericCellValue());
			changedInstallation = BigDecimal.valueOf(sheet.getRow(9).getCell(9).getNumericCellValue()).add(n("100"));
			sheet.getRow(9).getCell(9).setCellValue(changedInstallation.doubleValue());
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
				assertEquals(88D, sheet.getRow(freightRow).getCell(installation ? 10 : 8).getNumericCellValue());
				assertEquals("材料小计", sheet.getRow(materialSummaryRow).getCell(0).getStringCellValue());
				if (installation)
					assertEquals("安装小计", sheet.getRow(materialSummaryRow + 1).getCell(0).getStringCellValue());
				assertTrue(sheet.getRow(totalRow).getCell(0).getStringCellValue().contains("合计"));
				assertNotEquals("设备运费", sheet.getRow(9).getCell(4).getStringCellValue(),
						"设备运费不能再占用产品明细行");
				sheet.getRow(freightRow).getCell(installation ? 10 : 8).setCellValue(99D);
				wb.write(out);
				edited = out.toByteArray();
			}
			var parsed = new QuoteReviewImportService().read(edited, "设备运费校对版.xlsx",
					"quote-freight-" + installation, 2);
			assertEquals(0, n("99").compareTo(n(parsed.get("equipment_freight_total"))));
			assertEquals(0, n(installation ? "1199" : "1099").compareTo(n(parsed.get("before_payment_total"))));
		}
	}

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

	@Test
	void chineseAmountsHandleZerosAndCents() {
		assertEquals("人民币零元整", DuctExportService.upperMoney(ZERO));
		assertEquals("人民币壹万零壹元壹角", DuctExportService.upperMoney(n("10001.1")));
		assertEquals("人民币壹亿元整", DuctExportService.upperMoney(n("100000000")));
		assertEquals("人民币壹亿零壹万零壹元零伍分", DuctExportService.upperMoney(n("100010001.05")));
	}

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
		assertEquals(4, rows.size(), "Equipment import keeps unclassified totals for human review");
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
