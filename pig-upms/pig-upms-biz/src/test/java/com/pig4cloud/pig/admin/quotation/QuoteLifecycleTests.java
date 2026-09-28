package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteLifecycleTests {

	final QuoteService.Actor leader = new QuoteService.Actor(1, "测试负责人", true),
			quoter = new QuoteService.Actor(2, "报价员", false), other = new QuoteService.Actor(3, "其他报价员", false);

	QuoteService service;

	QuoteRepository repo;

	TransactionTemplate transaction;

	ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void setup() throws IOException {
		var dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:" + uuid() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		String sql = Files.readString(Path.of("..", "..", "db", "quotation.sql")).split("-- 菜单使用")[0]
			.replaceAll("(?m)^--.*$", "");
		for (String statement : sql.split(";"))
			if (!statement.isBlank())
				jdbc.execute(statement);
		// Production @Repository beans are proxied; direct field access on a proxy is
		// unsafe.
		ProxyFactory repositoryProxy = new ProxyFactory(new QuoteRepository(jdbc, json));
		repositoryProxy.setProxyTargetClass(true);
		repositoryProxy.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
		repo = (QuoteRepository) repositoryProxy.getProxy();
		service = new QuoteService(repo);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	<T> T tx(Supplier<T> action) {
		return transaction.execute(status -> action.get());
	}

	Map<String, Object> rev(Map<String, Object> q) {
		return obj("revision", q.get("revision"));
	}

	void publish() {
		String id = s(tx(() -> service.seed(leader)), "id");
		tx(() -> service.catalogAction(leader, id, "publish", rev(repo.find("quote_catalog", id, false))));
	}

	@Test
	void automaticImportCombinesEveryWorksheetWithIndependentHeadersAndOriginalOrder() throws Exception {
		publish();
		String catalog = s(tx(() -> service.createCatalog(leader, "equipment_catalog", service.equipmentSeed(leader))),
				"id");
		tx(() -> service.catalogAction(leader, catalog, "publish", rev(repo.find("quote_catalog", catalog, false))));
		for (String extension : List.of("xls", "xlsx")) {
			byte[] bytes;
			try (Workbook workbook = extension.equals("xls") ? new org.apache.poi.hssf.usermodel.HSSFWorkbook()
					: new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
				Sheet devices = workbook.createSheet("设备");
				String[][] deviceRows = { { "序号", "项目名称", "规格型号", "单位", "工程量", "历史单价" },
						{ "1", "防火阀", "500×500", "个", "2", "99999" }, { "2", "未知产品", "ABC", "台", "1", "88888" } };
				for (int r = 0; r < deviceRows.length; r++) {
					Row row = devices.createRow(r + 3);
					for (int c = 0; c < deviceRows[r].length; c++)
						row.createCell(c).setCellValue(deviceRows[r][c]);
				}
				Sheet ducts = workbook.createSheet("风管、风阀、风口");
				String[][] ductRows = { { "单位", "工程量", "规格型号", "项目名称" }, { "㎡", "12", "0.75 / 500×320", "镀锌风管" },
						{ "", "", "", "合计" } };
				for (int r = 0; r < ductRows.length; r++) {
					Row row = ducts.createRow(r);
					for (int c = 0; c < ductRows[r].length; c++)
						row.createCell(c).setCellValue(ductRows[r][c]);
				}
				workbook.createSheet("空白表");
				workbook.write(output);
				bytes = output.toByteArray();
			}
			Path fixture = Path.of("..", "..", "quotation-verification", "test-output", "整单-多工作表." + extension);
			Files.createDirectories(fixture.getParent());
			Files.write(fixture, bytes);
			var upload = tx(() -> service.upload(quoter, bytes, "整单." + extension));
			assertThrows(ResponseStatusException.class, () -> tx(
					() -> service.create(other, obj("pricing_mode", "auto_v1", "import_id", upload.get("id")))));
			String id = s(
					tx(() -> service.create(quoter, obj("pricing_mode", "auto_v1", "import_id", upload.get("id")))),
					"id");
			var q = service.detail(quoter, id);
			var body = map(q.get("body"));
			var rows = list(body.get("rows"));
			assertEquals(List.of("防火阀", "未知产品", "镀锌风管"), rows.stream().map(r -> s(r, "name")).toList());
			assertEquals(List.of("风阀", "", "风管"), rows.stream().map(r -> s(r, "category")).toList());
			assertEquals(List.of("设备", "设备", "风管、风阀、风口"), rows.stream().map(r -> s(r, "source_sheet")).toList());
			assertEquals(List.of(5, 6, 2), rows.stream().map(r -> n(r.get("source_row")).intValue()).toList());
			assertEquals(3, rows.stream().map(r -> s(r, "row_id")).distinct().count());
			assertEquals("2", rows.get(0).get("quantity"));
			assertEquals("12", rows.get(2).get("engineering_quantity"));
			assertTrue(repo.json(rows.get(0).get("original")).contains("99999"));
			assertEquals("风管、风阀、风口", list(body.get("source_special_rows")).get(0).get("source_sheet"));
			var sources = list(body.get("source_sheets"));
			assertEquals(List.of("imported", "imported", "empty"), sources.stream().map(r -> s(r, "status")).toList());
			assertEquals(5, sources.get(0).get("start_row"));
			assertEquals(2, sources.get(1).get("start_row"));
			map(body.get("duct_options")).putAll(obj("area_basis", "net", "product_state", "semi", "shape", "straight",
					"flange", "angle", "tax_included", false, "payment_option", "no_install_cash"));
			map(body.get("equipment_options")).putAll(obj("tax_included", false, "payment_option", "no_install_cash"));
			body.put("revision", q.get("revision"));
			tx(() -> service.save(quoter, id, body));
			var result = map(tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id)))).get("result"));
			assertNull(result.get("total"));
			assertEquals("252", list(result.get("rows")).get(0).get("amount"));
			assertEquals("pending", list(result.get("rows")).get(1).get("status"));
			assertEquals("510.0", list(result.get("rows")).get(2).get("amount"));
			var revised = map(service.detail(quoter, id).get("body"));
			list(revised.get("rows")).get(1).putAll(obj("excluded", true, "exclude_reason", "测试：范围外产品"));
			revised.put("revision", service.detail(quoter, id).get("revision"));
			tx(() -> service.save(quoter, id, revised));
			assertEquals("762",
					map(tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id)))).get("result"))
						.get("total"));
		}
	}

	@Test
	void automaticWorkbookImportRequiresReviewOfUnrecognizedNonemptySheets() throws Exception {
		byte[] bytes;
		try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
			workbook.createSheet("说明").createRow(0).createCell(0).setCellValue("本项目清单");
			Sheet sheet = workbook.createSheet("设备");
			sheet.createRow(0).createCell(0).setCellValue("设备名称");
			sheet.createRow(1).createCell(0).setCellValue("防火阀");
			workbook.write(output);
			bytes = output.toByteArray();
		}
		var upload = tx(() -> service.upload(quoter, bytes, "含说明.xlsx"));
		var request = obj("pricing_mode", "auto_v1", "import_id", upload.get("id"));
		assertTrue(assertThrows(RuleException.class, () -> tx(() -> service.create(quoter, request))).getMessage()
			.contains("说明"));
		for (Object invalid : List.of("invalid", List.of(obj("name", "不存在")),
				List.of(obj("name", "说明", "excluded", true)), List.of(obj("name", "说明"), obj("name", "说明")))) {
			request.put("sheet_settings", invalid);
			assertThrows(RuleException.class, () -> tx(() -> service.create(quoter, request)));
		}
		request.put("sheet_settings", List.of(obj("name", "说明", "excluded", true, "exclude_reason", "仅项目封面，无清单产品")));
		String id = s(tx(() -> service.create(quoter, request)), "id");
		var body = map(service.detail(quoter, id).get("body"));
		assertEquals(1, list(body.get("rows")).size());
		assertEquals("excluded", list(body.get("source_sheets")).get(0).get("status"));
		assertEquals("仅项目封面，无清单产品", list(body.get("source_sheets")).get(0).get("exclude_reason"));
		assertEquals("设备", list(body.get("rows")).get(0).get("source_sheet"));
	}

	@Test
	void wholeOrderFreightControlsBothEnginesAndExport() throws Exception {
		publish();
		String catalogId = s(
				tx(() -> service.createCatalog(leader, "equipment_catalog", service.equipmentSeed(leader))), "id");
		tx(() -> service.catalogAction(leader, catalogId, "publish",
				rev(repo.find("quote_catalog", catalogId, false))));
		String id = s(tx(() -> service.create(quoter, obj("pricing_mode", "auto_v1"))), "id");
		var q = service.detail(quoter, id);
		var body = map(q.get("body"));
		assertEquals(false, body.get("include_freight"));
		map(body.get("duct_options")).putAll(obj("area_basis", "net", "product_state", "semi", "shape", "straight",
				"flange", "angle", "tax_included", false, "payment_option", "no_install_cash"));
		map(body.get("equipment_options"))
			.putAll(obj("tax_included", false, "payment_option", "no_install_cash", "region", "贵州"));
		body.put("rows",
				List.of(obj("name", "镀锌风管", "spec", "0.75 / 500×320", "engineering_quantity", "12", "unit", "㎡",
						"attributes", obj()),
						obj("name", "防火阀", "spec", "500×500", "quantity", "2", "unit", "个", "attributes",
								obj())));
		body.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var without = map(tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id)))).get("result"));
		assertEquals("762", without.get("total"));
		q = service.detail(quoter, id);
		var freightBody = map(q.get("body"));
		freightBody.put("include_freight", true);
		freightBody.put("revision", q.get("revision"));
		// The single order setting is authoritative even if old client fields disagree.
		map(freightBody.get("duct_options")).put("include_freight", false);
		map(freightBody.get("equipment_options")).put("include_freight", false);
		tx(() -> service.save(quoter, id, freightBody));
		var saved = service.detail(quoter, id);
		var savedBody = map(saved.get("body"));
		assertEquals(true, map(savedBody.get("duct_options")).get("include_freight"));
		assertEquals(true, map(savedBody.get("equipment_options")).get("include_freight"));
		assertNull(saved.get("result"));
		var waiting = map(tx(() -> service.calculate(quoter, id, rev(saved))).get("result"));
		assertEquals("252", list(waiting.get("rows")).get(1).get("amount"));
		assertEquals("pending", map(map(waiting.get("fees")).get("equipment_freight")).get("status"));
		assertNull(waiting.get("total"));
		var manualFreight = service.detail(quoter, id);
		var manualFreightBody = map(manualFreight.get("body"));
		map(manualFreightBody.get("equipment_options")).put("freight_amount", "14");
		manualFreightBody.put("revision", manualFreight.get("revision"));
		tx(() -> service.save(quoter, id, manualFreightBody));
		var withFreightSaved = service.detail(quoter, id);
		var with = map(tx(() -> service.calculate(quoter, id, rev(withFreightSaved))).get("result"));
		assertTrue(n(list(with.get("rows")).get(0).get("amount")).compareTo(n("510")) > 0);
		assertEquals("252", list(with.get("rows")).get(1).get("amount"));
		assertEquals("14", map(map(with.get("fees")).get("equipment_freight")).get("amount"));
		assertEquals(add(n(with.get("subtotal")), n("14")), n(with.get("before_payment_total")));
		assertEquals(n(with.get("before_payment_total")).setScale(0, java.math.RoundingMode.CEILING), n(with.get("total")));
		var file = tx(() -> service.export(quoter, id,
				obj("revision", service.detail(quoter, id).get("revision"), "kind", "draft")));
		try (var wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(file, "id")).get("content")))) {
			StringBuilder text = new StringBuilder();
			for (Row row : wb.getSheetAt(0))
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						text.append(cell.getStringCellValue());
			assertTrue(text.toString().contains("整单含运费"));
			assertTrue(text.toString().contains("设备运费"));
			assertFalse(text.toString().contains("设备含运费；风管"));
		}
		var off = map(service.detail(quoter, id).get("body"));
		off.put("include_freight", false);
		off.put("revision", service.detail(quoter, id).get("revision"));
		tx(() -> service.save(quoter, id, off));
		var result = map(tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id)))).get("result"));
		assertEquals(without.get("total"), result.get("total"));
		var formal = tx(() -> service.export(quoter, id,
				obj("revision", service.detail(quoter, id).get("revision"), "kind", "formal")));
		byte[] historical = (byte[]) service.download(quoter, s(formal, "id")).get("content");
		var historicalBody = map(service.detail(quoter, id).get("body"));
		historicalBody.remove("include_freight");
		map(historicalBody.get("duct_options")).put("include_freight", true);
		repo.update("UPDATE quote_order SET body=? WHERE id=?", repo.json(historicalBody), id);
		var historicalQuote = service.detail(quoter, id);
		assertNotNull(historicalQuote.get("result"));
		assertEquals(false, historicalQuote.get("result_stale"));
		assertArrayEquals(historical, (byte[]) service.download(quoter, s(formal, "id")).get("content"));
		assertFalse(map(repo.find("quote_order", id, false).get("body")).containsKey("include_freight"));
	}

	@Test
	void singleCategoryLegacyFreightMigratesWithoutLosingItsChoice() {
		for (String mode : List.of("duct_v1", "equipment_v1")) {
			String id = s(tx(() -> service.create(quoter, obj("pricing_mode", mode))), "id");
			var body = map(service.detail(quoter, id).get("body"));
			body.remove("include_freight");
			map(body.get(mode.equals("duct_v1") ? "duct_options" : "equipment_options")).put("include_freight", true);
			repo.update("UPDATE quote_order SET body=? WHERE id=?", repo.json(body), id);
			var q = service.detail(quoter, id);
			assertEquals(true, map(q.get("body")).get("include_freight"));
			tx(() -> service.action(quoter, id, "auto-classify", rev(q)));
			var converted = map(service.detail(quoter, id).get("body"));
			assertEquals(true, converted.get("include_freight"));
			assertEquals(true, map(converted.get("duct_options")).get("include_freight"));
			assertEquals(true, map(converted.get("equipment_options")).get("include_freight"));
		}
	}

	@Test
	void legacyFreightConflictRequiresExplicitWholeOrderChoiceWithoutRewritingHistory() {
		String id = s(tx(() -> service.create(quoter, obj("pricing_mode", "mixed_v1"))), "id");
		var body = map(service.detail(quoter, id).get("body"));
		body.remove("include_freight");
		map(body.get("duct_options")).put("include_freight", true);
		map(body.get("equipment_options")).put("include_freight", false);
		repo.update("UPDATE quote_order SET body=? WHERE id=?", repo.json(body), id);
		var q = service.detail(quoter, id);
		assertNull(map(q.get("body")).get("include_freight"));
		assertFalse(map(repo.find("quote_order", id, false).get("body")).containsKey("include_freight"));
		assertThrows(RuleException.class, () -> tx(() -> service.calculate(quoter, id, rev(q))));
		var invalid = map(q.get("body"));
		invalid.put("revision", q.get("revision"));
		invalid.put("include_freight", "true");
		assertThrows(RuleException.class, () -> tx(() -> service.save(quoter, id, invalid)));
		invalid.put("include_freight", true);
		tx(() -> service.save(quoter, id, invalid));
		var normalized = map(service.detail(quoter, id).get("body"));
		assertEquals(true, normalized.get("include_freight"));
		assertEquals(true, map(normalized.get("equipment_options")).get("include_freight"));
	}

	@Test
	void automaticQuoteCreationAndExistingDraftConversionPreserveOriginalRows() {
		String autoId = s(tx(() -> service.create(quoter, obj("pricing_mode", "auto_v1"))), "id");
		var auto = map(service.detail(quoter, autoId).get("body"));
		assertEquals("mixed_v1", auto.get("pricing_mode"));
		assertEquals("auto_v1", auto.get("recognition_mode"));
		assertTrue(auto.containsKey("duct_options") && auto.containsKey("equipment_options"));
		var original = draft();
		String id = s(original, "id");
		var body = map(original.get("body"));
		list(body.get("rows")).add(obj("name", "防火阀", "spec", "500×500", "engineering_quantity", "2", "unit", "个",
				"attributes", obj(), "installation", obj("unit_price", "7")));
		list(body.get("rows")).add(obj("name", "未知项目", "engineering_quantity", "4", "unit", "个", "attributes", obj()));
		body.put("revision", original.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var before = service.detail(quoter, id);
		var rowsBefore = list(map(before.get("body")).get("rows"));
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.action(other, id, "auto-classify", rev(before))));
		var response = tx(() -> service.action(quoter, id, "auto-classify", rev(before)));
		assertEquals(obj("duct", 1, "equipment", 1, "pending", 1), response.get("counts"));
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.action(quoter, id, "auto-classify", rev(before))));
		var after = service.detail(quoter, id);
		var converted = map(after.get("body"));
		var rows = list(converted.get("rows"));
		assertEquals("mixed_v1", converted.get("pricing_mode"));
		assertNull(after.get("result"));
		assertEquals(rowsBefore.stream().map(r -> r.get("row_id")).toList(),
				rows.stream().map(r -> r.get("row_id")).toList());
		assertEquals(rowsBefore.stream().map(r -> r.get("original")).toList(),
				rows.stream().map(r -> r.get("original")).toList());
		assertEquals("2", rows.get(1).get("quantity"));
		assertEquals("7", map(rows.get(1).get("installation")).get("unit_price"));
		assertEquals("", rows.get(2).get("category"));
		assertEquals("4", rows.get(2).get("engineering_quantity"));
		assertEquals("no_install_cash", map(converted.get("equipment_options")).get("payment_option"));
		assertEquals(false, map(converted.get("equipment_options")).get("tax_included"));
		repo.update("UPDATE quote_order SET state='final' WHERE id=?", id);
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.action(quoter, id, "auto-classify", rev(after))));
	}

	@Test
	void automaticDuctOnlyQuoteNeedsOnlyDuctCatalogAndReclassifiesEditedRows() {
		publish();
		var original = draft();
		String id = s(original, "id");
		tx(() -> service.action(quoter, id, "auto-classify", rev(original)));
		var computed = tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id))));
		var result = map(computed.get("result"));
		assertEquals("4250", result.get("total"));
		assertEquals(0, n(result.get("pending_count")).intValue());
		var q = service.detail(quoter, id);
		var body = map(q.get("body"));
		var row = list(body.get("rows")).get(0);
		row.putAll(obj("name", "防火阀", "spec", "500×500", "unit", "个", "engineering_quantity", "2"));
		body.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var saved = service.detail(quoter, id);
		var valve = list(map(saved.get("body")).get("rows")).get(0);
		assertEquals("风阀", valve.get("category"));
		assertEquals("2", valve.get("quantity"));
		assertNull(saved.get("result"));
	}

	@Test
	void equipmentDraftConversionKeepsPaymentAndLegacyInstallationSelection() {
		String id = s(tx(() -> service.create(quoter, obj("pricing_mode", "equipment_v1"))), "id");
		var q = service.detail(quoter, id);
		var body = map(q.get("body"));
		map(body.get("equipment_options")).putAll(
				obj("tax_included", true, "payment_option", "with_install_progress_80", "include_installation", true));
		body.remove("installation_options");
		repo.update("UPDATE quote_order SET body=? WHERE id=?", repo.json(body), id);
		tx(() -> service.action(quoter, id, "auto-classify", rev(q)));
		var converted = map(service.detail(quoter, id).get("body"));
		assertEquals(true, map(converted.get("duct_options")).get("tax_included"));
		assertEquals("with_install_progress_80", map(converted.get("duct_options")).get("payment_option"));
		assertEquals(true, map(converted.get("installation_options")).get("included"));
	}

	@Test
	void installationLifecycleValidatesPreservesEntriesAndBlocksChangedCatalogExport() throws Exception {
		assertThrows(ResponseStatusException.class, () -> service.installationSeed(quoter));
		var seed = service.installationSeed(leader);
		assertEquals(56, list(seed.get("projects")).size());
		String catalogId = s(tx(() -> service.createCatalog(leader, "installation_catalog", seed)), "id");
		tx(() -> service.catalogAction(leader, catalogId, "publish",
				rev(repo.find("quote_catalog", catalogId, false))));
		publish();
		var q = draft();
		String id = s(q, "id");
		var body = map(q.get("body"));
		map(body.get("installation_options"))
			.putAll(obj("included", true, "region", "贵州", "include_auxiliaries", true, "include_site_fee", true,
					"site_amount", "0.03", "site_note", "高空施工附加费"));
		map(body.get("duct_options")).put("payment_option", "with_install_progress_80");
		list(body.get("rows")).get(0).put("installation", obj("unit_price", "7.21", "note", "人工核对安装单价"));
		body.put("installation_items",
				List.of(obj("name", "防火包裹", "project_id", "four_11", "quantity", "2", "unit", "㎡")));
		body.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var saved = service.detail(quoter, id);
		var savedBody = map(saved.get("body"));
		assertTrue(yes(map(savedBody.get("duct_options")).get("include_installation")));
		assertEquals("7.21", map(list(savedBody.get("rows")).get(0).get("installation")).get("unit_price"));
		// An older client omitting a row's installation data cannot erase it.
		list(savedBody.get("rows")).get(0).remove("installation");
		savedBody.put("revision", saved.get("revision"));
		tx(() -> service.save(quoter, id, savedBody));
		assertEquals("7.21",
				map(list(map(service.detail(quoter, id).get("body")).get("rows")).get(0).get("installation"))
					.get("unit_price"));
		var invalid = map(service.detail(quoter, id).get("body"));
		invalid.put("revision", service.detail(quoter, id).get("revision"));
		map(invalid.get("installation_options")).put("included", "true");
		assertThrows(RuleException.class, () -> tx(() -> service.save(quoter, id, invalid)));
		tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id))));
		var calculated = service.detail(quoter, id);
		var result = map(calculated.get("result"));
		assertTrue(yes(result.get("formal_ready")), str(result.get("issues")));
		var fee = map(map(result.get("fees")).get("installation"));
		assertEquals(0, n("1597.03").compareTo(n(fee.get("untaxed_amount"))));
		assertEquals(0, n("1597.03").compareTo(n(fee.get("amount"))));
		assertEquals(0, mul(n(result.get("before_payment_total")), n("1.1")).setScale(0, java.math.RoundingMode.CEILING)
			.compareTo(n(result.get("total"))));
		var draftExport = tx(
				() -> service.export(quoter, id, obj("revision", calculated.get("revision"), "kind", "draft")));
		assertNotNull(service.download(quoter, s(draftExport, "id")).get("content"));
		tx(() -> service.catalogAction(leader, catalogId, "retire", rev(repo.find("quote_catalog", catalogId, false))));
		assertThrows(ResponseStatusException.class, () -> tx(
				() -> service.export(quoter, id, obj("revision", calculated.get("revision"), "kind", "formal"))));
		tx(() -> service.catalogAction(leader, catalogId, "reactivate",
				rev(repo.find("quote_catalog", catalogId, false))));
		var formal = tx(
				() -> service.export(quoter, id, obj("revision", calculated.get("revision"), "kind", "formal")));
		byte[] historical = (byte[]) service.download(quoter, s(formal, "id")).get("content");
		try (var wb = WorkbookFactory.create(new ByteArrayInputStream(historical))) {
			assertEquals("带安装", wb.getSheetName(0));
			StringBuilder text = new StringBuilder();
			for (Row row : wb.getSheetAt(0))
				for (Cell cell : row)
					if (cell.getCellType() == CellType.STRING)
						text.append(cell.getStringCellValue());
			assertTrue(text.toString().contains("安装单价（元）"));
			assertTrue(text.toString().contains("安装金额（元）"));
			assertTrue(text.toString().contains("材料小计"));
			assertTrue(text.toString().contains("安装小计"));
			assertFalse(text.toString().contains("安装含税及取整"));
			assertFalse(text.toString().contains("整单付款调整及取整"));
			assertFalse(text.toString().contains("source_sha256"));
			assertFalse(text.toString().contains(s(seed, "source")));
		}
		result.put("installation_engine_version", "installation-old");
		repo.update("UPDATE quote_order SET result=? WHERE id=?", repo.json(result), id);
		assertNotNull(service.detail(quoter, id).get("result"));
		assertArrayEquals(historical, (byte[]) service.download(quoter, s(formal, "id")).get("content"));
	}

	@Test
	void reviewImportKeepsMaterialAmountWhenInstallationIsStillPending() throws Exception {
		var seed = service.installationSeed(leader);
		String installationCatalog = s(tx(() -> service.createCatalog(leader, "installation_catalog", seed)), "id");
		tx(() -> service.catalogAction(leader, installationCatalog, "publish",
				rev(repo.find("quote_catalog", installationCatalog, false))));
		publish();
		var q = draft();
		String id = s(q, "id");
		var body = map(q.get("body"));
		map(body.get("installation_options"))
			.putAll(obj("included", true, "region", "贵州", "include_auxiliaries", true, "site_amount", "0"));
		map(body.get("duct_options")).put("payment_option", "with_install_progress_80");
		list(body.get("rows")).get(0).put("installation", obj("unit_price", "7.21", "note", "人工核对安装单价"));
		body.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, body));
		tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id))));
		var calculated = service.detail(quoter, id);
		var draftExport = tx(() -> service.export(quoter, id,
				obj("revision", calculated.get("revision"), "kind", "draft")));
		byte[] review = (byte[]) service.download(quoter, s(draftExport, "id")).get("content"), changed;
		BigDecimal editedMaterial;
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review));
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			Row product = wb.getSheetAt(0).getRow(9);
			editedMaterial = BigDecimal.valueOf(product.getCell(8).getNumericCellValue()).add(n("100"));
			product.getCell(8).setCellValue(editedMaterial.doubleValue());
			product.getCell(9).setBlank();
			wb.write(output);
			changed = output.toByteArray();
		}
		var preview = tx(() -> service.previewReviewImport(quoter, id, changed, "安装待确认校对版.xlsx",
				n(calculated.get("revision")).intValue()));
		assertEquals(1, n(preview.get("pending_count")).intValue());
		assertEquals(0, editedMaterial.compareTo(n(preview.get("material_total"))));
		assertEquals(0, n(preview.get("installation_total")).signum());
		var applied = tx(() -> service.applyReviewImport(quoter, id, s(preview, "import_id"),
				obj("revision", calculated.get("revision"))));
		var result = map(applied.get("result"));
		assertEquals(1, n(result.get("pending_count")).intValue());
		assertEquals(0, editedMaterial.compareTo(n(result.get("material_total"))));
		assertEquals(0, editedMaterial.compareTo(n(list(result.get("rows")).get(0).get("amount"))));
		assertEquals("pending", list(result.get("rows")).get(0).get("installation_status"));
		assertNull(result.get("total"));
	}

	Map<String, Object> draft() {
		String id = s(tx(() -> service.create(quoter, obj("customer", "测试客户", "project", "验证工程"))), "id");
		Map<String, Object> q = service.detail(quoter, id), b = map(q.get("body"));
		b.put("rows",
				new ArrayList<>(List.of(obj("name", "镀锌风管", "spec", "0.75 / 800×320", "category", "风管",
						"engineering_quantity", "100", "unit", "㎡", "attributes", obj("thickness", "0.75"), "excluded",
						false, "source_issue_confirmed", false))));
		map(b.get("duct_options")).putAll(obj("area_basis", "net", "product_state", "semi", "shape", "straight",
				"flange", "angle", "tax_included", false, "payment_option", "no_install_cash"));
		b.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, b));
		return service.detail(quoter, id);
	}

	Map<String, Object> calculated() {
		publish();
		Map<String, Object> q = draft();
		tx(() -> service.calculate(quoter, s(q, "id"), rev(q)));
		return service.detail(quoter, s(q, "id"));
	}

	@Test
	void fullLifecyclePreservesHistoryAndOwnerAccess() throws IOException {
		Map<String, Object> q = calculated();
		String id = s(q, "id");
		assertTrue(yes(map(q.get("result")).get("formal_ready")));
		assertThrows(ResponseStatusException.class, () -> service.detail(other, id));
		assertTrue(service.listQuotes(other, false).isEmpty());
		assertEquals(1, service.listQuotes(leader, false).size());
		Map<String, Object> exp = tx(
				() -> service.export(quoter, id, obj("revision", q.get("revision"), "kind", "formal")));
		byte[] snapshot = (byte[]) service.download(quoter, s(exp, "id")).get("content");
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(snapshot))) {
			assertEquals(1, wb.getNumberOfSheets());
			assertEquals("单层材料", wb.getSheetName(0));
		}
		Map<String, Object> locked = service.detail(quoter, id);
		assertEquals("final", locked.get("state"));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.save(quoter, id, map(locked.get("body")))));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.action(quoter, id, "delete", rev(locked))));
		tx(() -> service.action(quoter, id, "archive", rev(locked)));
		Map<String, Object> archived = service.detail(quoter, id);
		assertEquals("archived", archived.get("state"));
		assertThrows(ResponseStatusException.class, () -> tx(
				() -> service.export(quoter, id, obj("revision", archived.get("revision"), "kind", "formal"))));
		tx(() -> service.action(quoter, id, "restore", rev(archived)));
		Map<String, Object> next = tx(() -> service.action(quoter, id, "revise", rev(service.detail(quoter, id))));
		Map<String, Object> v2 = service.detail(quoter, s(next, "id"));
		assertEquals(2, n(v2.get("version")).intValue());
		assertNull(v2.get("result"));
		assertArrayEquals(snapshot, (byte[]) service.download(quoter, s(exp, "id")).get("content"));
		assertThrows(ResponseStatusException.class, () -> service.download(other, s(exp, "id")));
	}

	Map<String, Object> publishEquipment() {
		var seed = service.equipmentSeed(leader);
		list(seed.get("purchased")).add(obj("id", "test", "name", "测试设备", "model", "TEST", "cost", "75", "unit", "台"));
		String id = s(tx(() -> service.createCatalog(leader, "equipment_catalog", seed)), "id");
		tx(() -> service.catalogAction(leader, id, "publish", rev(repo.find("quote_catalog", id, false))));
		return repo.find("quote_catalog", id, false);
	}

	Map<String, Object> equipmentQuote(String mode, List<Map<String, Object>> rows) {
		String id = s(tx(() -> service.create(quoter, obj("pricing_mode", mode, "customer", "设备验收客户"))), "id");
		var q = service.detail(quoter, id);
		var body = map(q.get("body"));
		body.put("rows", rows);
		body.put("equipment_options", obj("tax_included", false, "payment_option", "no_install_cash", "include_freight",
				false, "region", "贵州"));
		map(body.get("duct_options")).putAll(obj("area_basis", "net", "product_state", "semi", "shape", "straight",
				"flange", "angle", "tax_included", false, "payment_option", "no_install_cash"));
		body.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, body));
		tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id))));
		return service.detail(quoter, id);
	}

	Map<String, Object> equipmentRow() {
		return obj("name", "测试设备", "category", "外购设备", "quantity", "2", "unit", "台", "attributes",
				obj("equipment_kind", "purchased", "model", "TEST"));
	}

	@Test
	void equipmentSeedIsReadOnlyAndCatalogsHaveIndependentPublicationLifecycle() {
		assertThrows(ResponseStatusException.class, () -> service.equipmentSeed(quoter));
		assertEquals(743, list(service.equipmentSeed(leader).get("fan_points")).size());
		assertTrue(service.resources().isEmpty());
		var equipment = publishEquipment();
		publish();
		assertEquals(2, service.resources().size());
		var q = equipmentQuote("equipment_v1", List.of(equipmentRow()));
		assertEquals("200", map(q.get("result")).get("total"));
		String duplicate = s(
				tx(() -> service.createCatalog(leader, "equipment_catalog", service.equipmentSeed(leader))), "id");
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.catalogAction(leader, duplicate, "publish",
				rev(repo.find("quote_catalog", duplicate, false)))));
		String next = s(tx(() -> service.catalogAction(leader, s(equipment, "id"), "revise", rev(equipment))), "id");
		assertEquals("equipment_catalog", repo.find("quote_catalog", next, false).get("kind"));
		tx(() -> service.catalogAction(leader, next, "publish", rev(repo.find("quote_catalog", next, false))));
		assertThrows(ResponseStatusException.class, () -> tx(
				() -> service.export(quoter, s(q, "id"), obj("revision", q.get("revision"), "kind", "formal"))));
		tx(() -> service.catalogAction(leader, next, "retire", rev(repo.find("quote_catalog", next, false))));
		assertTrue(EquipmentCatalog.active(service.resources(), java.time.LocalDate.now()).isEmpty());
		assertEquals(1, DuctCatalog.active(service.resources(), java.time.LocalDate.now()).size());
	}

	@Test
	void mixedQuotePreservesOrderIntegerEquipmentAndDecimalDuctExports() throws IOException {
		publishEquipment();
		publish();
		var duct = obj("name", "镀锌风管", "category", "风管", "engineering_quantity", "100", "unit", "㎡", "attributes",
				obj("thickness", "0.75"));
		var q = equipmentQuote("mixed_v1", List.of(equipmentRow(), duct, equipmentRow()));
		var result = map(q.get("result"));
		assertTrue(yes(result.get("formal_ready")), str(result.get("issues")));
		assertEquals(0, n("4650").compareTo(n(result.get("total"))));
		assertEquals(List.of("100", "42.5", "100"),
				list(result.get("rows")).stream().map(r -> s(r, "unit_price")).toList());
		for (boolean formal : List.of(false, true)) {
			var export = tx(() -> service.export(quoter, s(q, "id"),
					obj("revision", q.get("revision"), "kind", formal ? "formal" : "draft")));
			try (Workbook wb = WorkbookFactory
				.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(export, "id")).get("content")))) {
				String text = workbookText(wb);
				assertTrue(text.contains("测试设备") && text.contains("镀锌风管"));
				assertFalse(text.contains("unit_cost") || text.contains("毛利") || text.contains("成本"));
				if (formal) {
					var sheet = wb.getSheetAt(0);
					assertEquals("0", sheet.getRow(9).getCell(7).getCellStyle().getDataFormatString());
					assertEquals("0.0", sheet.getRow(10).getCell(7).getCellStyle().getDataFormatString());
					assertEquals("0", sheet.getRow(11).getCell(7).getCellStyle().getDataFormatString());
				}
			}
		}
	}

	@Test
	void fanCandidatePersistsAndExportsActualSelectedPerformance() throws IOException {
		var catalog = publishEquipment();
		var row = obj("name", "风机", "category", "风机", "quantity", "1", "unit", "台", "spec", "风量1000 风压100 功率2.2kW", "remark", "客户原备注",
				"attributes",
				obj("equipment_kind", "fan", "model", "GXF", "airflow", "1000", "pressure", "100", "power", "2.2"));
		var q = equipmentQuote("equipment_v1", List.of(row));
		var r = list(map(q.get("result")).get("rows")).get(0);
		assertEquals("priced", r.get("status"), str(r.get("reason")));
		var selected = map(map(r.get("detail")).get("selected_fan"));
		var body = map(q.get("body"));
		var savedRow = list(body.get("rows")).get(0);
		savedRow.put("candidate_id", selected.get("id"));
		body.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, s(q, "id"), body));
		var current = service.detail(quoter, s(q, "id"));
		assertEquals(selected.get("id"), list(map(current.get("body")).get("rows")).get(0).get("candidate_id"));
		tx(() -> service.calculate(quoter, s(q, "id"), rev(current)));
		var calculated = service.detail(quoter, s(q, "id"));
		var export = tx(() -> service.export(quoter, s(q, "id"),
				obj("revision", calculated.get("revision"), "kind", "formal")));
		try (Workbook wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(export, "id")).get("content")))) {
			String text = workbookText(wb);
			assertTrue(text.contains(s(selected, "model")) && text.contains("风量" + s(selected, "airflow"))
					&& text.contains("风压" + s(selected, "pressure")));
			assertEquals("选择型号：" + s(selected, "model") + "；客户原备注",
					wb.getSheetAt(0).getRow(9).getCell(9).getStringCellValue());
			assertFalse(text.contains("按风量、风压选择有完整成本的最小合格规格"));
			assertFalse(text.contains("unit_cost") || text.contains("电机成本") || text.contains("壳体成本"));
		}
	}

	@Test
	void equipmentPendingRowsRemainInDraftAndCannotBeFormallyExported() throws IOException {
		publishEquipment();
		var missing = equipmentRow();
		missing.put("quantity", "");
		var q = equipmentQuote("equipment_v1", List.of(equipmentRow(), missing));
		var r = map(q.get("result"));
		assertEquals(1, n(r.get("pending_count")).intValue());
		assertNull(r.get("total"));
		assertThrows(RuleException.class, () -> tx(
				() -> service.export(quoter, s(q, "id"), obj("revision", q.get("revision"), "kind", "formal"))));
		var export = tx(() -> service.export(quoter, s(q, "id"), obj("revision", q.get("revision"), "kind", "draft")));
		assertTrue(s(export, "filename").startsWith("报价校对版_"));
		byte[] review = (byte[]) service.download(quoter, s(export, "id")).get("content"), completed;
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(review));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			assertEquals("报价校对版", wb.getSheetName(0));
			assertEquals(SheetVisibility.VERY_HIDDEN,
					wb.getSheetVisibility(wb.getSheetIndex(DuctExportService.REVIEW_METADATA_SHEET)));
			String text = workbookText(wb);
			assertTrue(text.contains("报 价 校 对 版（待确认）"));
			assertTrue(text.contains("待确认") && text.contains("已确定小计（待确认项未计入）"));
			assertEquals(CellType.BLANK, wb.getSheetAt(0).getRow(2).getCell(6).getCellType());
			assertEquals(CellType.BLANK, wb.getSheetAt(0).getRow(2).getCell(7).getCellType());
			assertEquals(CellType.BLANK, wb.getSheetAt(0).getRow(9).getCell(9).getCellType());
			Row pending = wb.getSheetAt(0).getRow(10);
			assertEquals("待确认", pending.getCell(4).getStringCellValue());
			assertEquals(IndexedColors.LIGHT_YELLOW.getIndex(), pending.getCell(1).getCellStyle().getFillForegroundColor());
			assertEquals(CellType.BLANK, pending.getCell(7).getCellType());
			assertEquals(CellType.BLANK, pending.getCell(8).getCellType());
			pending.getCell(6).setCellValue(1);
			pending.getCell(7).setCellValue(1500);
			wb.write(out);
			completed = out.toByteArray();
		}
		assertEquals("draft", service.detail(quoter, s(q, "id")).get("state"));
		var preview = tx(() -> service.previewReviewImport(quoter, s(q, "id"), completed, "报价校对版.xlsx",
				n(q.get("revision")).intValue()));
		assertEquals(0, n(preview.get("pending_count")).intValue());
		assertEquals(0, n("1700").compareTo(n(preview.get("subtotal"))));
		assertEquals(2, n(preview.get("change_count")).intValue());
		assertEquals(2, n(preview.get("derived_count")).intValue());
		assertEquals(1, n(preview.get("changed_row_count")).intValue());
		assertEquals(1, n(preview.get("unchanged_count")).intValue());
		assertEquals(11, n(list(preview.get("review_rows")).get(0).get("visible_row")).intValue());
		assertTrue(yes(list(preview.get("review_rows")).get(0).get("changed")));
		assertTrue(list(preview.get("changes")).stream().noneMatch(change -> n(change.get("line")).intValue() == 10));
		assertTrue(list(preview.get("changes")).stream()
			.anyMatch(change -> s(change, "field").equals("material_amount")
					&& s(change, "kind").equals("derived") && n("1500").compareTo(n(change.get("after"))) == 0));
		var applied = tx(() -> service.applyReviewImport(quoter, s(q, "id"), s(preview, "import_id"),
				obj("revision", q.get("revision"))));
		var current = service.detail(quoter, s(q, "id"));
		var result = map(current.get("result"));
		assertEquals(0, n(result.get("pending_count")).intValue());
		assertEquals(0, n("1700").compareTo(n(result.get("total"))));
		assertTrue(yes(result.get("formal_ready")));
		assertFalse(map(result.get("manual_review_import")).isEmpty());
		assertEquals("manual_v1", list(result.get("rows")).get(1).get("pricing_mode"));
		assertEquals(n(applied.get("revision")), n(current.get("revision")));

		var formal = tx(() -> service.export(quoter, s(q, "id"),
				obj("revision", current.get("revision"), "kind", "formal")));
		try (Workbook wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(formal, "id")).get("content")))) {
			assertTrue(workbookText(wb).contains("报 价 单"));
			Row completedRow = wb.getSheetAt(0).getRow(10);
			assertNotEquals(IndexedColors.LIGHT_YELLOW.getIndex(),
					completedRow.getCell(1).getCellStyle().getFillForegroundColor());
			assertEquals(1500D, completedRow.getCell(7).getNumericCellValue());
			assertEquals(1500D, completedRow.getCell(8).getNumericCellValue());
		}
		assertEquals("final", service.detail(quoter, s(q, "id")).get("state"));
	}

	static String workbookText(Workbook wb) {
		StringBuilder text = new StringBuilder();
		for (Sheet s : wb)
			for (Row r : s)
				for (Cell c : r)
					if (c.getCellType() == CellType.STRING)
						text.append(c.getStringCellValue()).append('\n');
		return text.toString();
	}

	@Test
	void formalExportRechecksTemplatePauseAndEffectiveCatalog() {
		Map<String, Object> q = calculated();
		String id = s(q, "id");
		Map<String, Object> settings = service.settings(leader);
		tx(() -> service.changeSettings(leader,
				obj("revision", settings.get("revision"), "formal_template_confirmed", false)));
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.export(quoter, id, obj("revision", q.get("revision"), "kind", "formal"))));
		tx(() -> service.export(quoter, id, obj("revision", q.get("revision"), "kind", "draft")));
		tx(() -> service.changeSettings(leader,
				obj("revision", service.settings(leader).get("revision"), "formal_template_confirmed", true)));
		Map<String, Object> c = service.resources().get(0);
		tx(() -> service.catalogAction(leader, s(c, "id"), "retire", rev(c)));
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.export(quoter, id, obj("revision", q.get("revision"), "kind", "formal"))));
	}

	@Test
	void staleRevisionAndSilentRowRemovalAreRejected() {
		Map<String, Object> q = draft();
		String id = s(q, "id");
		Map<String, Object> b = map(q.get("body"));
		b.put("revision", q.get("revision"));
		tx(() -> service.save(quoter, id, b));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.save(quoter, id, b)));
		Map<String, Object> current = service.detail(quoter, id);
		Map<String, Object> missing = map(current.get("body"));
		missing.put("revision", current.get("revision"));
		missing.put("rows", List.of());
		assertThrows(RuleException.class, () -> tx(() -> service.save(quoter, id, missing)));
	}

	@Test
	void pendingQuoteAllowsOnlyDraftAndStaleEngineBlocksExports() {
		Map<String, Object> q = draft();
		String id = s(q, "id");
		tx(() -> service.calculate(quoter, id, rev(q)));
		Map<String, Object> pending = service.detail(quoter, id);
		assertThrows(RuleException.class,
				() -> tx(() -> service.export(quoter, id, obj("revision", pending.get("revision"), "kind", "formal"))));
		tx(() -> service.export(quoter, id, obj("revision", pending.get("revision"), "kind", "draft")));
		Map<String, Object> stale = map(pending.get("result"));
		stale.put("engine_version", "old");
		repo.update("UPDATE quote_order SET result=? WHERE id=?", repo.json(stale), id);
		assertNull(service.detail(quoter, id).get("result"));
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.export(quoter, id, obj("revision", pending.get("revision"), "kind", "draft"))));
	}

	@Test
	void catalogChangesRequireLeaderAndPublishedVersionsAreImmutable() {
		publish();
		Map<String, Object> current = service.resources().get(0);
		String id = s(current, "id");
		assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.catalogAction(quoter, id, "retire", rev(current))));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.editCatalog(leader, id,
				obj("revision", current.get("revision"), "body", current.get("body")))));
		String next = s(tx(() -> service.catalogAction(leader, id, "revise", rev(current))), "id");
		Map<String, Object> revised = repo.find("quote_catalog", next, false);
		tx(() -> service.catalogAction(leader, next, "publish", rev(revised)));
		tx(() -> service.catalogAction(leader, next, "retire", rev(repo.find("quote_catalog", next, false))));
		assertTrue(DuctCatalog.active(service.resources(), java.time.LocalDate.now()).isEmpty());
		tx(() -> service.catalogAction(leader, id, "retire", rev(repo.find("quote_catalog", id, false))));
		assertThrows(ResponseStatusException.class, () -> tx(
				() -> service.catalogAction(leader, id, "reactivate", rev(repo.find("quote_catalog", id, false)))));
	}

	@Test
	void concurrentSavesCannotOverwriteEachOther() throws Exception {
		Map<String, Object> q = draft();
		String id = s(q, "id");
		Map<String, Object> b = map(q.get("body"));
		b.put("revision", q.get("revision"));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Boolean> save = () -> {
				try {
					tx(() -> service.save(quoter, id, b));
					return true;
				}
				catch (ResponseStatusException e) {
					return false;
				}
			};
			List<Future<Boolean>> results = pool.invokeAll(List.of(save, save));
			assertNotEquals(results.get(0).get(), results.get(1).get());
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void uploadMapsOnlyUniqueHeadersAndPreservesFormulaWarningsAndMergedRemarks() throws IOException {
		byte[] bytes;
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sh = wb.createSheet("客户");
			String[][] data = { { "产品名称", "规格", "工程量", "数量", "报价备注", "单位" }, { "镀锌风管", "0.75", "10", "2", "半成品", "㎡" },
					{ "镀锌风管", "0.75", "20", "", "", "㎡" } };
			for (int r = 0; r < data.length; r++) {
				Row row = sh.createRow(r);
				for (int c = 0; c < data[r].length; c++)
					row.createCell(c).setCellValue(data[r][c]);
			}
			sh.addMergedRegion(new CellRangeAddress(1, 2, 4, 4));
			sh.getRow(2).getCell(2).setCellFormula("1+2");
			((org.apache.poi.xssf.usermodel.XSSFCell) sh.getRow(2).getCell(2)).getCTCell().unsetV();
			wb.write(out);
			bytes = out.toByteArray();
		}
		Map<String, Object> upload = tx(() -> service.upload(quoter, bytes, "客户.xlsx"));
		Map<String, Object> sheet = list(upload.get("sheets")).get(0);
		assertFalse(map(sheet.get("suggested_mapping")).containsKey("quantity"));
		Map<String, Object> created = tx(() -> service.create(quoter, obj("import_id", upload.get("id"), "sheet", "客户",
				"start_row", 2, "mapping", sheet.get("suggested_mapping"))));
		List<Map<String, Object>> rows = list(map(service.detail(quoter, s(created, "id")).get("body")).get("rows"));
		assertEquals("semi", attr(rows.get(1), "product_state"));
		assertTrue(yes(rows.get(1).get("source_issue")));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.create(other, obj("import_id",
				upload.get("id"), "sheet", "客户", "start_row", 2, "mapping", sheet.get("suggested_mapping")))));
	}

}
