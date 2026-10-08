package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 报价业务流程回归：使用隔离H2数据库验证归属权限、修订并发、AI核验、试算、回导与历史导出。
 * 通过真实仓储及事务执行服务方法，覆盖多步骤操作之间的数据保留和价格有效性。
 */
class QuoteLifecycleTests {

	final QuoteService.Actor leader = new QuoteService.Actor(1, "测试负责人", true),
			quoter = new QuoteService.Actor(2105194329570525185L, "报价员", false),
			other = new QuoteService.Actor(2105194329570525186L, "其他报价员", false);

	QuoteService service;

	QuoteRepository repo;

	TransactionTemplate transaction;

	ObjectMapper json = new ObjectMapper();

	/**
	 * 每个用例创建独立内存库及报价业务表，使用代理仓储和事务模拟实际持久化操作。
	 */
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
		// 模拟生产仓储代理，防止服务通过代理对象字段取连接而绕过正确的仓储调用。
		ProxyFactory repositoryProxy = new ProxyFactory(new QuoteRepository(jdbc, json));
		repositoryProxy.setProxyTargetClass(true);
		repositoryProxy.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
		repo = (QuoteRepository) repositoryProxy.getProxy();
		service = new QuoteService(repo);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	/**
	 * 在报价事务中执行单步操作，让行锁、回滚和多次请求的修订检查具有实际效果。
	 */
	<T> T tx(Supplier<T> action) {
		return transaction.execute(status -> action.get());
	}

	/**
	 * 从已读取记录取得当前revision，模拟页面提交的并发校验字段。
	 */
	Map<String, Object> rev(Map<String, Object> q) {
		return obj("revision", q.get("revision"));
	}

	/**
	 * 发布风管指标测试种子，供后续草稿试算与正式导出使用。
	 */
	void publish() {
		String id = s(tx(() -> service.seed(leader)), "id");
		tx(() -> service.catalogAction(leader, id, "publish", rev(repo.find("quote_catalog", id, false))));
	}

	/**
	 * 覆盖完整long范围的账号ID，验证导入和报价归属不因数字范围或精度产生串号。
	 * 相邻账号不能读入对方文件或报价，负责人仍可访问。
	 */
	@ParameterizedTest
	@ValueSource(longs = { 1, 1000000000000000001L, 2105194329570525185L, Long.MAX_VALUE })
	void accountIdsUseTheFullLongRangeWithoutAllowingAnotherAccountToImportOrRead(long ownerId) throws IOException {
		var owner = new QuoteService.Actor(ownerId, "文件所有者", false);
		var neighbor = new QuoteService.Actor(ownerId == 1 ? 2 : ownerId - 1, "其他报价员", false);
		byte[] bytes;
		try (Workbook workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("设备");
			String[][] data = { { "产品名称", "规格型号", "单位", "数量" }, { "防火阀", "500×500", "个", "2" } };
			for (int r = 0; r < data.length; r++) {
				Row row = sheet.createRow(r);
				for (int c = 0; c < data[r].length; c++)
					row.createCell(c).setCellValue(data[r][c]);
			}
			workbook.write(out);
			bytes = out.toByteArray();
		}
		var upload = tx(() -> service.upload(owner, bytes, "设备报价.xlsx"));
		var request = obj("pricing_mode", "auto_v1", "import_id", upload.get("id"), "sheet_settings", List.of());
		String id = s(tx(() -> service.create(owner, request)), "id");
		var quote = service.detail(owner, id);
		assertEquals(ownerId, Long.parseLong(str(quote.get("owner_id"))));
		var rows = list(map(quote.get("body")).get("rows"));
		assertEquals(1, rows.size());
		assertEquals("防火阀", rows.get(0).get("name"));
		assertEquals("2", rows.get(0).get("quantity"));
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> tx(() -> service.create(neighbor, request))).getStatusCode().value());
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> service.detail(neighbor, id)).getStatusCode().value());
		assertTrue(service.listQuotes(neighbor, false).isEmpty());
		assertEquals(id, service.detail(leader, id).get("id"));
	}

	/**
	 * 账号ID支持扩大后，计价数字的绝对值上限仍保持不变，避免放宽金额输入。
	 */
	@Test
	void accountIdFixKeepsQuotationNumericRangeLimits() {
		assertEquals(0, n("1000000000000000000").compareTo(new BigDecimal("1e18")));
		assertThrows(RuleException.class, () -> n("1000000000000000001"));
		assertThrows(RuleException.class, () -> n("-1000000000000000001"));
	}

	/**
	 * AI字段类型错误时保留原始行和来源，问题字段留空并在核验Excel中提示。
	 * 人工补齐字段后重新下载，应反映当前草稿并移除对应问题提示。
	 */
	@Test
	void malformedModelFieldKeepsAiDraftRowsAndReviewWarningAfterDownloadingCurrentDraft() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/completions", exchange -> {
			String content = json.writeValueAsString(obj("rows", List.of(
					obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2", "source_locator", "第2行",
							"attached_valve_cost_included", false, "attached_valve_width", obj("value", 500)),
					obj("name", "防火阀", "spec", "", "unit", "个", "quantity", "3", "source_locator", "第3行",
							"width", List.of(500, 600)))));
			byte[] answer = json.writeValueAsBytes(obj("choices",
					List.of(obj("finish_reason", "stop", "message", obj("content", content)))));
			exchange.sendResponseHeaders(200, answer.length);
			try (var output = exchange.getResponseBody()) { output.write(answer); }
		});
		server.start();
		try {
			byte[] originalBytes;
			try (Workbook workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
				Sheet sheet = workbook.createSheet("客户清单");
				String[][] cells = { { "产品名称", "规格型号", "单位", "数量", "备注" },
						{ "防火阀", "500×500", "个", "2", "" }, { "防火阀", "", "个", "3", "" } };
				for (int r = 0; r < cells.length; r++) {
					Row row = sheet.createRow(r);
					for (int c = 0; c < cells[r].length; c++) row.createCell(c).setCellValue(cells[r][c]);
				}
				workbook.write(output);
				originalBytes = output.toByteArray();
			}
			var file = new org.springframework.mock.web.MockMultipartFile("files", "客户清单.xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", originalBytes);
			org.springframework.web.multipart.MultipartFile[] files = { file };
			var ai = new AiQuoteImportService(json, "test-key", "http://127.0.0.1:" + server.getAddress().getPort());
			var normalized = ai.normalize(files);
			assertEquals(2, normalized.size());
			assertEquals("", s(normalized.get(0), "remark"));
			assertEquals("", s(normalized.get(0), "attached_valve_cost_included"));
			assertEquals("", s(normalized.get(0), "attached_valve_width"));
			assertEquals("", s(normalized.get(1), "width"));
			assertEquals("", s(normalized.get(1), "remark"));
			assertTrue(map(normalized.get(1).get("model_field_issues")).containsKey("width"));
			String id = s(tx(() -> service.createAiDraft(quoter, normalized, AiQuoteImportService.sourceSnapshots(files))), "id");
			var draft = service.detail(quoter, id);
			var body = map(draft.get("body"));
			assertEquals("pending", body.get("ai_review_status"));
			assertNull(draft.get("result"));
			var products = list(body.get("rows"));
			assertEquals(2, products.size());
			assertEquals("2", products.get(0).get("quantity"));
			assertEquals("3", products.get(1).get("quantity"));
			assertEquals("", s(map(products.get(1).get("attributes")), "width"));
			assertEquals("", s(products.get(1), "remark"));
			assertTrue(map(map(products.get(1).get("original")).get("model_field_issues")).containsKey("width"));
			assertArrayEquals(originalBytes, (byte[]) service.aiSource(quoter, id, 0).get("content"));
			assertThrows(RuleException.class, () -> tx(() -> service.calculate(quoter, id, rev(draft))));
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)))) {
				Sheet main = workbook.getSheet(AiQuoteImportService.SHEET_NAME), review = workbook.getSheet(AiQuoteImportService.REVIEW_SHEET_NAME);
				byte[] red = { (byte) 0xF4, (byte) 0xCC, (byte) 0xCC };
				var normalColor = ((XSSFCellStyle) main.getRow(9).getCell(1).getCellStyle()).getFillForegroundXSSFColor();
				assertTrue(normalColor == null || !Arrays.equals(red, normalColor.getRGB()));
				assertEquals("", review.getRow(3).getCell(4).getStringCellValue());
				assertArrayEquals(red, ((XSSFCellStyle) main.getRow(10).getCell(1).getCellStyle()).getFillForegroundXSSFColor().getRGB());
				String reason = review.getRow(4).getCell(4).getStringCellValue();
				assertTrue(reason.contains("待确认"));
				assertTrue(reason.contains("宽度"));
				assertEquals("", main.getRow(10).getCell(12).getStringCellValue());
				for (int r : List.of(9, 10)) for (int c = 7; c <= 11; c++)
					assertEquals(CellType.BLANK, main.getRow(r).getCell(c).getCellType());
			}
			map(products.get(1).get("attributes")).put("width", "500");
			body.put("revision", draft.get("revision"));
			tx(() -> service.save(quoter, id, body));
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)))) {
				var correctedColor = ((XSSFCellStyle) workbook.getSheet(AiQuoteImportService.SHEET_NAME).getRow(10).getCell(1).getCellStyle()).getFillForegroundXSSFColor();
				assertTrue(correctedColor == null || !Arrays.equals(new byte[]{ (byte)0xF4, (byte)0xCC, (byte)0xCC }, correctedColor.getRGB()));
				assertEquals("", workbook.getSheet(AiQuoteImportService.REVIEW_SHEET_NAME).getRow(4).getCell(4).getStringCellValue());
			}
		}
		finally { server.stop(0); }
	}

	/**
	 * AI辅材数量异常时保留特殊来源行并阻止核验确认；补齐后可继续确认和试算。
	 */
	@Test
	void malformedAccessoryQuantityKeepsPendingSpecialAndCanBeCorrectedBeforeApproval() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/completions", exchange -> {
			String content = json.writeValueAsString(obj("rows", List.of(
					obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2", "source_locator", "第2行"),
					obj("name", "角码", "spec", "", "unit", "个", "quantity", List.of(3, 4), "source_locator", "第3行"))));
			byte[] answer = json.writeValueAsBytes(obj("choices",
					List.of(obj("finish_reason", "stop", "message", obj("content", content)))));
			exchange.sendResponseHeaders(200, answer.length);
			try (var output = exchange.getResponseBody()) { output.write(answer); }
		});
		server.start();
		try {
			byte[] originalBytes;
			try (Workbook workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
				Sheet sheet = workbook.createSheet("客户清单");
				String[][] cells = { { "产品名称", "规格型号", "单位", "数量", "备注" },
						{ "防火阀", "500×500", "个", "2", "" }, { "角码", "", "个", "", "" } };
				for (int r = 0; r < cells.length; r++) {
					Row row = sheet.createRow(r);
					for (int c = 0; c < cells[r].length; c++) row.createCell(c).setCellValue(cells[r][c]);
				}
				workbook.write(output);
				originalBytes = output.toByteArray();
			}
			var file = new org.springframework.mock.web.MockMultipartFile("files", "客户清单.xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", originalBytes);
			org.springframework.web.multipart.MultipartFile[] files = { file };
			var ai = new AiQuoteImportService(json, "test-key", "http://127.0.0.1:" + server.getAddress().getPort());
			var normalized = ai.normalize(files);
			assertEquals(2, normalized.size());
			assertEquals("", s(normalized.get(1), "quantity"));
			assertTrue(map(normalized.get(1).get("model_field_issues")).containsKey("quantity"));
			var created = tx(() -> service.createAiDraft(quoter, normalized, AiQuoteImportService.sourceSnapshots(files)));
			assertEquals(2, created.get("row_count"));
			assertEquals(1, created.get("product_row_count"));
			assertEquals(1, created.get("special_row_count"));
			String id = s(created, "id");
			var draft = service.detail(quoter, id);
			var body = map(draft.get("body"));
			assertEquals("pending", body.get("ai_review_status"));
			assertNull(draft.get("result"));
			assertEquals("2", list(body.get("rows")).get(0).get("quantity"));
			var special = list(body.get("source_special_rows")).get(0);
			assertEquals("corner_count", special.get("kind"));
			assertEquals("", special.get("quantity"));
			assertEquals("", map(body.get("duct_options")).get("corner_count"));
			assertTrue(map(map(special.get("original")).get("model_field_issues")).containsKey("quantity"));
			assertArrayEquals(originalBytes, (byte[]) service.aiSource(quoter, id, 0).get("content"));
			byte[] unchanged;
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)));
					var output = new ByteArrayOutputStream()) {
				Sheet main = workbook.getSheet(AiQuoteImportService.SHEET_NAME), review = workbook.getSheet(AiQuoteImportService.REVIEW_SHEET_NAME);
				byte[] red = { (byte) 0xF4, (byte) 0xCC, (byte) 0xCC };
				var normalColor = ((XSSFCellStyle) main.getRow(9).getCell(1).getCellStyle()).getFillForegroundXSSFColor();
				assertTrue(normalColor == null || !Arrays.equals(red, normalColor.getRGB()));
				assertEquals("", review.getRow(3).getCell(4).getStringCellValue());
				assertArrayEquals(red, ((XSSFCellStyle) main.getRow(10).getCell(1).getCellStyle()).getFillForegroundXSSFColor().getRGB());
				assertEquals("", new DataFormatter().formatCellValue(main.getRow(10).getCell(6)));
				String reason = review.getRow(4).getCell(4).getStringCellValue();
				assertTrue(reason.contains("待确认"));
				assertTrue(reason.contains("数量"));
				workbook.write(output);
				unchanged = output.toByteArray();
			}
			assertThrows(RuleException.class, () -> tx(() -> service.action(quoter, id, "approve-ai",
					obj("revision", draft.get("revision"), "review_confirmed", true))));
			var unchangedPreview = tx(() -> service.previewAiInputImport(quoter, id, unchanged, "未补齐数量.xlsx", n(draft.get("revision")).intValue()));
			assertEquals(0, unchangedPreview.get("change_count"));
			tx(() -> service.applyAiInputImport(quoter, id, s(unchangedPreview, "import_id"), obj("revision", draft.get("revision"), "review_confirmed", true)));
			var stillPending = service.detail(quoter, id);
			assertEquals("", list(map(stillPending.get("body")).get("source_special_rows")).get(0).get("quantity"));
			assertEquals("", map(map(stillPending.get("body")).get("duct_options")).get("corner_count"));
			assertEquals("pending", map(stillPending.get("body")).get("ai_review_status"));
			assertNull(stillPending.get("result"));
			assertThrows(RuleException.class, () -> tx(() -> service.action(quoter, id, "approve-ai",
					obj("revision", stillPending.get("revision"), "review_confirmed", true))));
			byte[] corrected;
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)));
					var output = new ByteArrayOutputStream()) {
				workbook.getSheet(AiQuoteImportService.SHEET_NAME).getRow(10).getCell(6).setCellValue(3);
				workbook.write(output);
				corrected = output.toByteArray();
			}
			var preview = tx(() -> service.previewAiInputImport(quoter, id, corrected, "补齐数量.xlsx", n(stillPending.get("revision")).intValue()));
			tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), obj("revision", stillPending.get("revision"), "review_confirmed", true)));
			var updated = service.detail(quoter, id);
			var updatedBody = map(updated.get("body"));
			assertEquals("3", list(updatedBody.get("source_special_rows")).get(0).get("quantity"));
			assertEquals("3", map(updatedBody.get("duct_options")).get("corner_count"));
			assertEquals("pending", updatedBody.get("ai_review_status"));
			assertNull(updated.get("result"));
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)))) {
				var color = ((XSSFCellStyle) workbook.getSheet(AiQuoteImportService.SHEET_NAME).getRow(10).getCell(1).getCellStyle()).getFillForegroundXSSFColor();
				assertTrue(color == null || !Arrays.equals(new byte[]{ (byte)0xF4, (byte)0xCC, (byte)0xCC }, color.getRGB()));
				assertEquals("", workbook.getSheet(AiQuoteImportService.REVIEW_SHEET_NAME).getRow(4).getCell(4).getStringCellValue());
			}
			tx(() -> service.action(quoter, id, "approve-ai", obj("revision", updated.get("revision"), "review_confirmed", true)));
			assertEquals("approved", map(service.detail(quoter, id).get("body")).get("ai_review_status"));
			assertNull(service.detail(quoter, id).get("result"));
		}
		finally { server.stop(0); }
	}

	/**
	 * 验证AI标准清单建立未计价草稿，必须经过人工确认才能进入规则试算。
	 */
	@Test
	void aiStandardWorkbookCreatesDraftAndRequiresReviewBeforeCalculation() throws Exception {
		publish();
		String equipmentCatalog = s(tx(() -> service.createCatalog(leader, "equipment_catalog", service.equipmentSeed(leader))),
				"id");
		tx(() -> service.catalogAction(leader, equipmentCatalog, "publish", rev(repo.find("quote_catalog", equipmentCatalog, false))));
		var source = obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2", "source_file",
				"微信截图.png", "source_sheet", "图片", "source_locator", "第1条", "source_excerpt", "防火阀500×500两个");
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(source), List.of(obj("filename", "微信截图.png",
				"media_type", "image/png", "content_base64", Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }))))), "id");
		var draft = service.detail(quoter, id);
		var body = map(draft.get("body"));
		assertEquals("pending", body.get("ai_review_status"));
		assertNull(draft.get("result"));
		assertEquals("防火阀", list(body.get("rows")).get(0).get("name"));
		assertEquals("微信截图.png", map(list(body.get("rows")).get(0).get("original")).get("source_file"));
		assertArrayEquals(new byte[] { 1, 2, 3 }, (byte[]) service.aiSource(quoter, id, 0).get("content"));
		assertThrows(ResponseStatusException.class, () -> service.aiSource(other, id, 0));
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)))) {
			assertEquals("产品名称", workbook.getSheetAt(0).getRow(8).getCell(1).getStringCellValue());
			assertEquals("防火阀", workbook.getSheetAt(0).getRow(9).getCell(1).getStringCellValue());
			assertEquals(CellType.BLANK, workbook.getSheetAt(0).getRow(9).getCell(7).getCellType());
		}
		assertThrows(RuleException.class, () -> tx(() -> service.calculate(quoter, id, rev(draft))));
		assertThrows(RuleException.class,
				() -> tx(() -> service.action(quoter, id, "approve-ai", rev(draft))));
		map(body.get("equipment_options")).putAll(obj("tax_included", false, "payment_option", "no_install_cash"));
		map(body.get("duct_options")).putAll(obj("tax_included", false, "payment_option", "no_install_cash"));
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var readyForReview = service.detail(quoter, id);
		tx(() -> service.action(quoter, id, "approve-ai",
				obj("revision", readyForReview.get("revision"), "review_confirmed", true)));
		var confirmed = service.detail(quoter, id);
		assertEquals("approved", map(confirmed.get("body")).get("ai_review_status"));
		var calculated = map(tx(() -> service.calculate(quoter, id, rev(confirmed))).get("result"));
		assertEquals("252", list(calculated.get("rows")).get(0).get("amount"));
		var approved = service.detail(quoter, id);
		var edit = map(approved.get("body"));
		list(edit.get("rows")).get(0).put("quantity", "3");
		edit.put("revision", approved.get("revision"));
		tx(() -> service.save(quoter, id, edit));
		assertEquals("pending", map(service.detail(quoter, id).get("body")).get("ai_review_status"));
	}

	/**
	 * 修改草稿后下载标准清单，内容应更新，源行标识仍保持稳定便于回导。
	 */
	@Test
	void aiWorkbookDownloadReflectsCurrentDraftEditsWithStableRowKeys() throws Exception {
		String id = s(tx(() -> service.createAiDraft(quoter,
				List.of(obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2")), List.of())), "id");
		var draft = service.detail(quoter, id);
		var body = map(draft.get("body"));
		var row = list(body.get("rows")).get(0);
		row.put("quantity", "12");
		row.put("spec", "600×600");
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, id, body));
		for (int download = 0; download < 2; download++) {
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)))) {
				Row exported = workbook.getSheet(AiQuoteImportService.SHEET_NAME).getRow(9);
				assertEquals(12, exported.getCell(6).getNumericCellValue());
				assertEquals("600×600", exported.getCell(3).getStringCellValue());
				assertEquals(s(row, "row_id"), exported.getCell(AiQuoteImportService.MAIN_REVIEW_KEY_COLUMN).getStringCellValue());
			}
		}
	}

	/**
	 * 人工核验Excel先生成字段差异，再写回原草稿，保留来源证据且不自动生成价格。
	 */
	@Test
	void correctedAiWorkbookPreviewsAndUpdatesOriginalWithoutPricingOrLosingEvidence() throws Exception {
		var source = obj("filename", "客户.png", "media_type", "image/png", "content_base64", Base64.getEncoder().encodeToString(new byte[]{4, 5}));
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(
				obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2"),
				obj("name", "角码", "unit", "个", "quantity", "8000"),
				obj("name", "卡条", "unit", "米", "quantity", "2628")), List.of(source))), "id");
		var before = service.detail(quoter, id);
		var originalBody = map(before.get("body"));
		var originalEvidence = map(list(originalBody.get("rows")).get(0).get("original"));
		byte[] downloaded = service.aiStandardWorkbook(quoter, id), corrected;
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(downloaded)); var out = new ByteArrayOutputStream()) {
			Sheet main = workbook.getSheet(AiQuoteImportService.SHEET_NAME);
			main.getRow(9).getCell(6).setCellValue(12);
			main.getRow(9).getCell(3).setCellValue("600×600");
			main.getRow(10).getCell(6).setCellValue(8100);
			workbook.write(out);
			corrected = out.toByteArray();
		}
		var preview = tx(() -> service.previewAiInputImport(quoter, id, corrected, "校验.xlsx", n(before.get("revision")).intValue()));
		assertEquals(3, preview.get("line_count"));
		assertEquals(2, preview.get("changed_row_count"));
		assertEquals("2", list(map(service.detail(quoter, id).get("body")).get("rows")).get(0).get("quantity"));
		assertThrows(RuleException.class, () -> tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), rev(before))));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.applyAiInputImport(other, id, s(preview, "import_id"), obj("revision", before.get("revision"), "review_confirmed", true))));
		var applied = tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), obj("revision", before.get("revision"), "review_confirmed", true)));
		var after = service.detail(quoter, id);
		var updated = map(after.get("body"));
		var row = list(updated.get("rows")).get(0);
		assertEquals("12", row.get("quantity"));
		assertEquals("600×600", row.get("spec"));
		assertEquals(originalEvidence, row.get("original"));
		assertEquals(originalBody.get("import_id"), updated.get("import_id"));
		assertEquals("8100", map(updated.get("duct_options")).get("corner_count"));
		assertEquals("2628", map(updated.get("duct_options")).get("clip_meters"));
		assertEquals("pending", updated.get("ai_review_status"));
		assertNull(after.get("result"));
		assertArrayEquals(new byte[]{4,5}, (byte[]) service.aiSource(quoter, id, 0).get("content"));
		assertThrows(RuleException.class, () -> tx(() -> service.calculate(quoter, id, rev(after))));
		assertThrows(RuleException.class, () -> tx(() -> service.previewAiInputImport(quoter, id, downloaded, "旧.xlsx", n(after.get("revision")).intValue())));
		assertThrows(ResponseStatusException.class, () -> tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), obj("revision", after.get("revision"), "review_confirmed", true))));
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id)))) {
			assertEquals(12, workbook.getSheetAt(0).getRow(9).getCell(6).getNumericCellValue());
			assertEquals(8100, workbook.getSheetAt(0).getRow(10).getCell(6).getNumericCellValue());
		}
		var stored = map(repo.find("quote_import", s(preview, "import_id"), false).get("parsed"));
		assertArrayEquals(corrected, Base64.getDecoder().decode(s(stored, "content_base64")));
		assertEquals(applied.get("revision"), after.get("revision"));
	}

	/**
	 * 相同原件复用人工核验输入，但新草稿不能复用金额或跳过本次核验。
	 */
	@Test
	void repeatedAiSourceKeepsConfirmedInputsAndRequiresFreshReviewWithoutReusingPrices() {
		var sources = List.of(obj("filename", "客户原单.xlsx", "media_type", "application/octet-stream",
				"content_base64", Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 })));
		var source = obj("name", "多叶送风口", "spec", "500×(600+250)", "unit", "个", "quantity", "2",
				"source_file", "客户原单.xlsx", "source_sheet", "设备", "source_locator", "第93行");
		String first = s(tx(() -> service.createAiDraft(quoter, List.of(source), sources)), "id");
		var draft = service.detail(quoter, first);
		var body = map(draft.get("body"));
		var row = list(body.get("rows")).get(0);
		row.put("quantity", "3");
		map(row.get("attributes")).putAll(obj("equipment_kind", "multi_leaf", "width", "500", "height", "600"));
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, first, body));
		var saved = service.detail(quoter, first);
		tx(() -> service.action(quoter, first, "approve-ai", obj("revision", saved.get("revision"), "review_confirmed", true)));
		repo.update("UPDATE quote_order SET result=? WHERE id=?", repo.json(obj("total", "9999")), first);
		var renamed = List.of(obj("filename", "原单副本.xlsx", "media_type", "application/octet-stream",
				"content_base64", sources.get(0).get("content_base64")));
		var nextSource = new LinkedHashMap<>(source);
		nextSource.putAll(obj("source_file", "原单副本.xlsx", "height", "850"));
		String second = s(tx(() -> service.createAiDraft(quoter, List.of(nextSource), renamed)), "id");
		var repeated = service.detail(quoter, second);
		var repeatedBody = map(repeated.get("body"));
		var repeatedRow = list(repeatedBody.get("rows")).get(0);
		assertEquals("3", repeatedRow.get("quantity"));
		assertEquals("600", map(repeatedRow.get("attributes")).get("height"));
		assertEquals(first, map(repeatedBody.get("ai_reused_from")).get("quote_id"));
		assertEquals("pending", repeatedBody.get("ai_review_status"));
		assertNull(repeated.get("result"));
		assertThrows(RuleException.class, () -> tx(() -> service.calculate(quoter, second, rev(repeated))));
		assertEquals("9999", map(repo.find("quote_order", first, false).get("result")).get("total"));
		assertEquals("原单副本.xlsx", map(repeatedRow.get("original")).get("source_file"));
	}

	@Test
	void aiConfirmedInputReuseIsLimitedToIdenticalSourcesAndTheSameOwner() {
		var source = obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2",
				"source_file", "客户原单.xlsx", "source_sheet", "设备", "source_locator", "第7行");
		var sources = List.of(obj("filename", "客户原单.xlsx", "media_type", "application/octet-stream",
				"content_base64", Base64.getEncoder().encodeToString(new byte[] { 1, 2 })));
		String first = s(tx(() -> service.createAiDraft(quoter, List.of(source), sources)), "id");
		var draft = service.detail(quoter, first);
		var body = map(draft.get("body"));
		list(body.get("rows")).get(0).put("quantity", "5");
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, first, body));
		String unconfirmed = s(tx(() -> service.createAiDraft(quoter, List.of(source), sources)), "id");
		assertEquals("2", list(map(service.detail(quoter, unconfirmed).get("body")).get("rows")).get(0).get("quantity"));
		var saved = service.detail(quoter, first);
		tx(() -> service.action(quoter, first, "approve-ai", obj("revision", saved.get("revision"), "review_confirmed", true)));
		for (var actor : List.of(other, leader)) {
			String own = s(tx(() -> service.createAiDraft(actor, List.of(source), sources)), "id");
			assertEquals("2", list(map(service.detail(actor, own).get("body")).get("rows")).get(0).get("quantity"));
		}
		var changed = List.of(obj("filename", "客户原单.xlsx", "media_type", "application/octet-stream",
				"content_base64", Base64.getEncoder().encodeToString(new byte[] { 1, 9 })));
		String changedId = s(tx(() -> service.createAiDraft(quoter, List.of(source), changed)), "id");
		assertEquals("2", list(map(service.detail(quoter, changedId).get("body")).get("rows")).get(0).get("quantity"));
	}

	/** 人工改为性能选型并清空型号后，同原件重复处理不能恢复被否定的型号。 */
	@Test
	void repeatedAiSourceRetainsConfirmedEmptyFanModel() {
		var source = obj("name", "风机", "spec", "SWF-I-7 L=6000 P=400 N=2.2", "model", "SWF-I-7",
				"unit", "台", "quantity", "1", "source_file", "原单.xlsx", "source_sheet", "风机", "source_locator", "第2行");
		var sources = List.of(obj("filename", "原单.xlsx", "content_base64", Base64.getEncoder().encodeToString(new byte[] { 4, 5 })));
		String first = s(tx(() -> service.createAiDraft(quoter, List.of(source), sources)), "id");
		var draft = service.detail(quoter, first);
		var body = map(draft.get("body"));
		var row = list(body.get("rows")).get(0);
		row.put("spec", "L=6000 P=400 N=2.2");
		map(row.get("attributes")).putAll(obj("model", "", "raw_model", ""));
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, first, body));
		var saved = service.detail(quoter, first);
		tx(() -> service.action(quoter, first, "approve-ai", obj("revision", saved.get("revision"), "review_confirmed", true)));
		String second = s(tx(() -> service.createAiDraft(quoter, List.of(source), sources)), "id");
		var reused = list(map(service.detail(quoter, second).get("body")).get("rows")).get(0);
		assertEquals("L=6000 P=400 N=2.2", reused.get("spec"));
		assertEquals("", s(map(reused.get("attributes")), "model"));
		assertEquals("", s(map(reused.get("attributes")), "raw_model"));
	}

	/** 不同空格表名仍是两张独立表，同原件复用不能把它们合并为歧义行。 */
	@Test
	void repeatedAiSourceKeepsDistinctWhitespaceSheets() {
		var firstRow = obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2",
				"source_file", "原单.xlsx", "source_sheet", " ", "source_sheet_index", 0, "source_locator", "第7行");
		var secondRow = new LinkedHashMap<>(firstRow);
		secondRow.putAll(obj("source_sheet", "     ", "source_sheet_index", 1));
		var input = List.of(firstRow, secondRow);
		var sources = List.of(obj("filename", "原单.xlsx", "content_base64", Base64.getEncoder().encodeToString(new byte[] { 6, 7 })));
		String first = s(tx(() -> service.createAiDraft(quoter, input, sources)), "id");
		var draft = service.detail(quoter, first);
		var body = map(draft.get("body"));
		list(body.get("rows")).get(0).put("quantity", "3");
		list(body.get("rows")).get(1).put("quantity", "4");
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, first, body));
		var saved = service.detail(quoter, first);
		tx(() -> service.action(quoter, first, "approve-ai", obj("revision", saved.get("revision"), "review_confirmed", true)));
		String next = s(tx(() -> service.createAiDraft(quoter, input, sources)), "id");
		var reused = list(map(service.detail(quoter, next).get("body")).get("rows"));
		assertEquals("3", reused.get(0).get("quantity"));
		assertEquals("4", reused.get(1).get("quantity"));
	}

	@Test
	void savingAiInputKeepsSourceIssueEvidenceAndInvalidatesPriorConfirmationAfterChanges() {
		var source = obj("name", "风口800×(1050+250)", "spec", "850×(1000+250)", "unit", "个", "quantity", "2",
				"source_file", "原单.xlsx", "source_sheet", "设备", "source_locator", "第99行",
				"source_issue", true, "source_issue_reason", "名称与技术特征的尺寸不一致，请核实");
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(source), List.of())), "id");
		var draft = service.detail(quoter, id);
		var body = map(draft.get("body"));
		var row = list(body.get("rows")).get(0);
		row.put("source_issue_confirmed", true);
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var saved = service.detail(quoter, id);
		var savedBody = map(saved.get("body"));
		var savedRow = list(savedBody.get("rows")).get(0);
		assertTrue(yes(savedRow.get("source_issue")));
		assertEquals("名称与技术特征的尺寸不一致，请核实", savedRow.get("source_issue_reason"));
		assertTrue(yes(savedRow.get("source_issue_confirmed")));
		savedRow.put("spec", "900×(1000+250)");
		savedBody.put("revision", saved.get("revision"));
		tx(() -> service.save(quoter, id, savedBody));
		assertFalse(yes(list(map(service.detail(quoter, id).get("body")).get("rows")).get(0).get("source_issue_confirmed")));
	}

	/**
	 * 拒绝错误报价、缺失核验结构及重排行的AI清单，防止差异套用到错误产品。
	 */
	@Test
	void correctedAiWorkbookRejectsWrongQuoteMissingReviewAndReorderedMainRows() throws Exception {
		var rows = List.of(obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2"),
				obj("name", "风机", "spec", "L=600 P=40 N=0.1", "unit", "台", "quantity", "1"));
		String id = s(tx(() -> service.createAiDraft(quoter, rows, List.of())), "id");
		String second = s(tx(() -> service.createAiDraft(quoter, rows, List.of())), "id");
		byte[] downloaded = service.aiStandardWorkbook(quoter, id);
		assertThrows(RuleException.class, () -> tx(() -> service.previewAiInputImport(quoter, second, downloaded, "错误.xlsx", 1)));
		for (boolean removeReview : List.of(true, false)) {
			byte[] broken;
			try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(downloaded)); var out = new ByteArrayOutputStream()) {
				if (removeReview) workbook.removeSheetAt(workbook.getSheetIndex(AiQuoteImportService.REVIEW_SHEET_NAME));
				else workbook.getSheetAt(0).getRow(9).getCell(AiQuoteImportService.MAIN_REVIEW_KEY_COLUMN).setCellValue("错行");
				workbook.write(out);
				broken = out.toByteArray();
			}
			assertThrows(RuleException.class, () -> tx(() -> service.previewAiInputImport(quoter, id, broken, "错误.xlsx", 1)));
		}
	}

	/**
	 * 核验文件未改动时不误报差异，并按当前草稿辅材选项处理原清单。
	 */
	@Test
	void correctedAiWorkbookUnchangedFallbacksHaveNoDifferencesAndUseCurrentAccessoryOptions() throws Exception {
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(
				obj("name", "风机", "spec", "", "model", "HTF-II-9", "unit", "㎡", "quantity", "", "engineering_quantity", "12"),
				obj("name", "角码", "unit", "个", "quantity", "8000")), List.of())), "id");
		var draft = service.detail(quoter, id);
		var body = map(draft.get("body"));
		map(body.get("duct_options")).put("corner_count", "8500");
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var saved = service.detail(quoter, id);
		byte[] file = service.aiStandardWorkbook(quoter, id);
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file))) {
			assertEquals(8500, workbook.getSheetAt(0).getRow(10).getCell(6).getNumericCellValue());
		}
		var preview = tx(() -> service.previewAiInputImport(quoter, id, file, "未修改.xlsx", n(saved.get("revision")).intValue()));
		assertEquals(0, preview.get("change_count"));
		assertEquals(0, preview.get("changed_row_count"));
	}

	/**
	 * 产品描述变化后清除旧识别结果，保留人工明确填写的输入供重新核验。
	 */
	@Test
	void correctedAiWorkbookTextEditClearsOldClassificationAndParametersButKeepsExplicitInputs() throws Exception {
		String id = s(tx(() -> service.createAiDraft(quoter,
				List.of(obj("name", "风机HTF-II-9", "spec", "L=600 P=40 N=0.1", "unit", "台", "quantity", "2",
						"equipment_kind", "fan", "model", "HTF-II-9", "airflow", "600", "pressure", "40", "power", "0.1")), List.of())), "id");
		var draft = service.detail(quoter, id);
		byte[] corrected;
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id))); var out = new ByteArrayOutputStream()) {
			var main = workbook.getSheetAt(0).getRow(9);
			main.getCell(1).setCellValue("防火阀");
			main.getCell(3).setCellValue("800×600");
			workbook.getSheet(AiQuoteImportService.REVIEW_SHEET_NAME).getRow(3).getCell(5).setCellValue("防火阀");
			workbook.write(out);
			corrected = out.toByteArray();
		}
		var preview = tx(() -> service.previewAiInputImport(quoter, id, corrected, "改产品.xlsx", n(draft.get("revision")).intValue()));
		tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), obj("revision", draft.get("revision"), "review_confirmed", true)));
		var row = list(map(service.detail(quoter, id).get("body")).get("rows")).get(0);
		assertEquals("风阀", row.get("category"));
		assertEquals("fire", map(row.get("attributes")).get("equipment_kind"));
		assertFalse(map(row.get("attributes")).containsKey("model"));
		assertFalse(s(row, "source_text").contains("HTF"));
		assertEquals("fire", EquipmentPricingEngine.classify(row));
		assertEquals("800", EquipmentPricingEngine.parseAttributes(row).get("width"));
	}

	/**
	 * 核验文件清空数量时应如实回写，同时保留既有整单条件和明确排除项。
	 */
	@Test
	void correctedAiWorkbookClearsQuantityAndPreservesExistingQuoteConditionsAndExclusions() throws Exception {
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(obj("name", "防火阀", "spec", "500×500", "unit", "个", "quantity", "2", "engineering_quantity", "2")), List.of())), "id");
		var draft = service.detail(quoter, id);
		var body = map(draft.get("body"));
		body.put("customer", "测试客户");
		map(body.get("equipment_options")).put("payment_option", "no_install_cash");
		var original = list(body.get("rows")).get(0);
		original.putAll(obj("excluded", true, "exclude_reason", "客户取消"));
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var saved = service.detail(quoter, id);
		tx(() -> service.action(quoter, id, "approve-ai", obj("revision", saved.get("revision"), "review_confirmed", true)));
		var approved = service.detail(quoter, id);
		repo.update("UPDATE quote_order SET result=? WHERE id=?", repo.json(obj("pending_count", 0)), id);
		byte[] corrected;
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(service.aiStandardWorkbook(quoter, id))); var out = new ByteArrayOutputStream()) {
			workbook.getSheetAt(0).getRow(9).getCell(6).setBlank();
			workbook.write(out);
			corrected = out.toByteArray();
		}
		var preview = tx(() -> service.previewAiInputImport(quoter, id, corrected, "清空.xlsx", n(approved.get("revision")).intValue()));
		tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), obj("revision", approved.get("revision"), "review_confirmed", true)));
		var after = service.detail(quoter, id);
		var updated = map(after.get("body"));
		var row = list(updated.get("rows")).get(0);
		assertEquals("", row.get("quantity"));
		assertEquals("2", row.get("engineering_quantity"));
		assertTrue(yes(row.get("excluded")));
		assertEquals("客户取消", row.get("exclude_reason"));
		assertEquals("测试客户", updated.get("customer"));
		assertEquals("no_install_cash", map(updated.get("equipment_options")).get("payment_option"));
		assertEquals("pending", updated.get("ai_review_status"));
		assertNull(after.get("result"));
	}

	/**
	 * 解析规则升级后拒绝旧预览记录，要求重新上传以生成当前规则下的差异。
	 */
	@Test
	void correctedAiWorkbookPreviewFromOldParserRulesCannotBeApplied() throws Exception {
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(obj("name", "止回阀", "spec", "500×500", "unit", "个", "quantity", "13")), List.of())), "id");
		var before = service.detail(quoter, id);
		byte[] file = service.aiStandardWorkbook(quoter, id);
		var preview = tx(() -> service.previewAiInputImport(quoter, id, file, "人工校验.xlsx", n(before.get("revision")).intValue()));
		var parsed = map(repo.find("quote_import", s(preview, "import_id"), false).get("parsed"));
		parsed.remove("parser_version");
		repo.update("UPDATE quote_import SET parsed=? WHERE id=?", repo.json(parsed), preview.get("import_id"));
		var exception = assertThrows(RuleException.class, () -> tx(() -> service.applyAiInputImport(quoter, id, s(preview, "import_id"), obj("revision", before.get("revision"), "review_confirmed", true))));
		assertTrue(exception.getMessage().contains("重新上传"));
		var after = service.detail(quoter, id);
		assertEquals(before.get("revision"), after.get("revision"));
		assertEquals(before.get("body"), after.get("body"));
	}

	/**
	 * AI标准清单中的辅材汇总来源行仍可核对，不能在建立草稿时静默丢弃。
	 */
	@Test
	void aiDraftKeepsAccessorySummaryRowsForHumanReview() {
		var materials = List.of(
				obj("name", "防火阀", "unit", "个", "quantity", "2"),
				obj("name", "角码", "unit", "个", "quantity", "8000"),
				obj("name", "卡条", "unit", "米", "quantity", "2628"));
		var created = tx(() -> service.createAiDraft(quoter, materials, List.of()));
		assertEquals(3, created.get("row_count"));
		assertEquals(1, created.get("product_row_count"));
		assertEquals(2, created.get("special_row_count"));
		String id = s(created, "id");
		var body = map(service.detail(quoter, id).get("body"));
		assertEquals(1, list(body.get("rows")).size());
		assertEquals(2, list(body.get("source_special_rows")).size());
		assertEquals("8000", map(body.get("duct_options")).get("corner_count"));
		assertEquals("2628", map(body.get("duct_options")).get("clip_meters"));
		assertEquals("pending", body.get("ai_review_status"));
	}

	/**
	 * AI建稿阶段保留设备清单，计价是否完整交给人工核验后的报价引擎判断。
	 */
	@Test
	void aiDraftDoesNotPrejudgeWhetherEquipmentCanBePriced() {
		var material = obj("name", "壁式轴流风机带防火阀", "spec", "L=600m³/h P=40Pa", "unit", "台", "quantity", "1");
		String id = s(tx(() -> service.createAiDraft(quoter, List.of(material), List.of())), "id");
		var draft = service.detail(quoter, id);
		var body = map(draft.get("body"));
		var row = list(body.get("rows")).get(0);
		assertFalse(map(row.get("attributes")).containsKey("review_reason"));
		body.put("revision", draft.get("revision"));
		tx(() -> service.save(quoter, id, body));
		var saved = map(service.detail(quoter, id).get("body"));
		assertFalse(map(list(saved.get("rows")).get(0).get("attributes")).containsKey("review_reason"));
	}

	/**
	 * 验证整份Excel各表独立识别列映射，合并后保持表/行顺序、来源与唯一产品行ID。
	 */
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

	/**
	 * 无法识别的非空表必须人工核对或说明排除原因，避免漏报整张客户清单。
	 */
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

	/**
	 * 验证唯一整单运费选择同时影响风管和设备计算，导出文案与试算口径一致。
	 */
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
		// 即使旧客户端分别传入矛盾值，也必须以唯一整单运费选择为准。
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

	/**
	 * 单类历史草稿重新识别时继承已有运费选择，避免转换模式改变报价条件。
	 */
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

	/**
	 * 旧复合草稿的两个运费值冲突时要求明确选择，已正式导出的历史文件仍保持原快照。
	 */
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

	/**
	 * 新建自动报价及旧草稿转换都保留原行、来源与安装等条件，并清除失效试算。
	 */
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

	/**
	 * 纯风管自动报价可仅用风管指标计算；编辑产品后按当前清单重新识别类别。
	 */
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

	/**
	 * 设备草稿转换到逐行识别时保留付款和旧安装选择，避免默认选项覆盖已有条件。
	 */
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

	/**
	 * 覆盖安装指标校验、项目保存及发布后只读；生效指标变更后拒绝旧结果正式导出。
	 */
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
		// 旧客户端遗漏行级安装字段时，已保存的安装条件仍应保留。
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

	/**
	 * 安装仍待补价时，校对回导保留已完成材料金额，整单继续阻止正式报价。
	 */
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
			product.getCell(10).setBlank();
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

	/**
	 * 建立带原始风管产品行的可编辑草稿，供保存、权限和状态测试复用。
	 */
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

	/**
	 * 建立已发布风管指标及完整试算报价，供正式导出条件测试复用。
	 */
	Map<String, Object> calculated() {
		publish();
		Map<String, Object> q = draft();
		tx(() -> service.calculate(quoter, s(q, "id"), rev(q)));
		return service.detail(quoter, s(q, "id"));
	}

	/**
	 * 串联草稿、试算、正式导出、归档恢复及新修订，核对归属权限与历史文件保留。
	 */
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

	/**
	 * 保存并发布独立设备指标测试版本，供设备及复合报价测试使用。
	 */
	Map<String, Object> publishEquipment() {
		var seed = service.equipmentSeed(leader);
		list(seed.get("purchased")).add(obj("id", "test", "name", "测试设备", "model", "TEST", "cost", "75", "unit", "台"));
		String id = s(tx(() -> service.createCatalog(leader, "equipment_catalog", seed)), "id");
		tx(() -> service.catalogAction(leader, id, "publish", rev(repo.find("quote_catalog", id, false))));
		return repo.find("quote_catalog", id, false);
	}

	/**
	 * 按指定内部计价模式和产品行建立设备测试报价，并配置明确的供货条件。
	 */
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

	/**
	 * 提供尺寸、数量和类别明确的设备行，便于核对金额与导出。
	 */
	Map<String, Object> equipmentRow() {
		return obj("name", "测试设备", "category", "外购设备", "quantity", "2", "unit", "台", "attributes",
				obj("equipment_kind", "purchased", "model", "TEST"));
	}

	/**
	 * 读取设备种子不写库，设备与风管指标仍分别维护系列和发布生命周期。
	 */
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

	/**
	 * 复合报价保持原产品顺序，设备整数金额与风管小数金额按各自规则导出。
	 */
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
				if (formal) {
					assertNull(wb.getSheet("计算明细"));
					assertFalse(text.contains("unit_cost") || text.contains("毛利") || text.contains("成本"));
					var sheet = wb.getSheetAt(0);
					assertEquals("0", sheet.getRow(9).getCell(7).getCellStyle().getDataFormatString());
					assertEquals("0.0", sheet.getRow(10).getCell(7).getCellStyle().getDataFormatString());
					assertEquals("0", sheet.getRow(11).getCell(7).getCellStyle().getDataFormatString());
				}
				else {
					assertNotNull(wb.getSheet("计算明细"));
					assertTrue(text.contains("成本"));
				}
			}
		}
	}

	/**
	 * 人工确认风机候选后保存选择，正式文件展示所选工况而非原需求占位值。
	 */
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
		var review = tx(() -> service.export(quoter, s(q, "id"),
				obj("revision", calculated.get("revision"), "kind", "draft")));
		try (Workbook wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(review, "id")).get("content")))) {
			var details = wb.getSheet("计算明细");
			assertNotNull(details);
			String explanation = workbookText(wb);
			assertTrue(explanation.contains(s(selected, "model")));
			assertTrue(explanation.contains("本体材料") && explanation.contains("电机成本"));
			assertTrue(explanation.contains("加毛利") && explanation.contains("本行材料金额"));
			assertFalse(explanation.contains("CEILING("));
		}
		var export = tx(() -> service.export(quoter, s(q, "id"),
				obj("revision", calculated.get("revision"), "kind", "formal")));
		try (Workbook wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(export, "id")).get("content")))) {
			String text = workbookText(wb);
			assertTrue(text.contains(s(selected, "model")) && text.contains("风量" + s(selected, "airflow"))
					&& text.contains("风压" + s(selected, "pressure")));
			assertEquals("选择型号：" + s(selected, "model") + "；客户原备注；客户要求规格：风量1000 风压100 功率2.2kW",
					wb.getSheetAt(0).getRow(9).getCell(9).getStringCellValue());
			assertFalse(text.contains("按风量、风压选择有完整成本的最小合格规格"));
			assertFalse(text.contains("unit_cost") || text.contains("电机成本") || text.contains("壳体成本"));
		}
	}

	/**
	 * 设备缺少选型或价格时保留在校对版，人工补价回导前不得正式导出。
	 */
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
			assertTrue(text.contains("单 层 材 料 报 价 单") && text.contains("校对-"));
			assertTrue(text.contains("价税合计") && text.contains("材料小计"));
			assertFalse(text.contains("已确定设备小计") || text.contains("价税合计（待确认）"));
			for (Row row : wb.getSheetAt(0))
				if (row.getCell(0) != null && Set.of("材料小计", "价税合计").contains(row.getCell(0).toString()))
					assertEquals(CellType.BLANK, row.getCell(8).getCellType());
			assertEquals(CellType.BLANK, wb.getSheetAt(0).getRow(2).getCell(6).getCellType());
			assertEquals(CellType.BLANK, wb.getSheetAt(0).getRow(2).getCell(7).getCellType());
			assertTrue(wb.getSheetAt(0).getRow(9).getCell(9).getStringCellValue()
				.contains("系统计价逻辑：外购设备"));
			Row pending = wb.getSheetAt(0).getRow(10);
			assertEquals("待确认", pending.getCell(4).getStringCellValue());
			assertArrayEquals(new byte[] { (byte) 0xF4, (byte) 0xCC, (byte) 0xCC },
					((XSSFCellStyle) pending.getCell(1).getCellStyle()).getFillForegroundXSSFColor().getRGB());
			assertTrue(pending.getCell(9).getStringCellValue().contains("待确认："));
			assertTrue(pending.getCell(9).getStringCellValue().contains("系统识别类型：外购设备；计价状态：待确认"));
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
		assertEquals(3, n(preview.get("derived_count")).intValue());
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
			var completedColor = ((XSSFCellStyle) completedRow.getCell(1).getCellStyle()).getFillForegroundXSSFColor();
			assertTrue(completedColor == null || !Arrays.equals(new byte[] { (byte) 0xF4, (byte) 0xCC, (byte) 0xCC },
					completedColor.getRGB()));
			assertEquals(1500D, completedRow.getCell(7).getNumericCellValue());
			assertEquals(1500D, completedRow.getCell(8).getNumericCellValue());
			assertEquals(CellType.BLANK, completedRow.getCell(9).getCellType());
		}
		assertEquals("final", service.detail(quoter, s(q, "id")).get("state"));
	}

	/**
	 * 设备补价后重新判断整单运费门槛，自动计入地区运费并修复旧回导遗留的待确认行。
	 */
	@Test
	void reviewImportRecalculatesAutomaticEquipmentFreightAfterPendingPricesAreCompleted() throws IOException {
		publishEquipment();
		var pending = obj("name", "待确认设备", "category", "外购设备", "quantity", "1", "unit", "台", "attributes",
				obj("equipment_kind", "purchased", "model", "UNKNOWN"));
		String id = s(tx(() -> service.create(quoter, obj("pricing_mode", "equipment_v1", "customer", "运费回导客户"))),
				"id");
		var quote = service.detail(quoter, id);
		var body = map(quote.get("body"));
		body.put("include_freight", true);
		body.put("rows", List.of(equipmentRow(), pending));
		body.put("equipment_options", obj("tax_included", false, "payment_option", "no_install_cash", "include_freight",
				true, "region", "贵州", "freight_amount", ""));
		body.put("revision", quote.get("revision"));
		tx(() -> service.save(quoter, id, body));
		quote = tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id))));
		var calculated = service.detail(quoter, id);
		assertEquals("pending", map(map(map(calculated.get("result")).get("fees")).get("equipment_freight")).get("status"));

		var draft = tx(() -> service.export(quoter, id,
				obj("revision", calculated.get("revision"), "kind", "draft")));
		byte[] edited;
		try (Workbook wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(draft, "id")).get("content")));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.getSheetAt(0);
			for (Row row : sheet) {
				if (row.getCell(0) != null)
					assertNotEquals("设备运费", row.getCell(0).toString(), "未确定门槛时不输出运费占位行");
				if (row.getCell(1) != null && row.getCell(1).getCellType() == CellType.STRING
						&& row.getCell(1).getStringCellValue().equals("待确认设备"))
					row.getCell(7).setCellValue(10000D);
			}
			wb.write(out);
			edited = out.toByteArray();
		}

		var preview = tx(() -> service.previewReviewImport(quoter, id, edited, "设备运费回导.xlsx",
				n(calculated.get("revision")).intValue()));
		assertEquals(0, n(preview.get("pending_count")).intValue());
		assertEquals(true, preview.get("equipment_freight_included"));
		var applied = tx(() -> service.applyReviewImport(quoter, id, s(preview, "import_id"),
				obj("revision", calculated.get("revision"))));
		var result = map(applied.get("result"));
		var freight = map(map(result.get("fees")).get("equipment_freight"));
		assertEquals("ready", freight.get("status"));
		assertEquals(true, freight.get("included_in_rows"));
		assertEquals(0, n(result.get("pending_count")).intValue());
		assertTrue(yes(result.get("formal_ready")), str(result.get("issues")));
		assertTrue(n(result.get("total")).compareTo(n("8000")) >= 0);
		assertTrue(list(result.get("manual_lines")).stream().noneMatch(line -> s(line, "process").equals("设备运费")));
		assertTrue(n(list(result.get("rows")).get(1).get("unit_price")).compareTo(n("10000")) > 0);

		// 模拟升级前已经回导、但仍遗留一行“设备运费待确认”的报价，正式导出应自动修复。
		BigDecimal freightAmount = n(result.get("equipment_freight_total"));
		for (Map<String, Object> line : list(result.get("manual_lines"))) {
			if (!yes(line.get("freight_adjusted")))
				continue;
			line.put("material_unit", line.remove("base_material_unit"));
			line.put("material_amount", line.remove("base_material_amount"));
			line.remove("freight_adjusted");
			line.remove("freight_factor");
			line.remove("freight_per_unit");
			line.remove("freight_amount");
		}
		list(result.get("manual_lines")).add(obj("name", "设备运费", "process", "设备运费", "unit", "项",
				"quantity", "1", "material_unit", "", "material_amount", "", "pending", true));
		freight.putAll(obj("status", "pending", "amount", null, "reason", "设备材料尚有待确认项，无法计算整单设备运费",
				"basis", "pending", "included_in_rows", false));
		BigDecimal baseMaterialTotal = n(result.get("material_total")).subtract(freightAmount);
		result.putAll(obj("subtotal", text(baseMaterialTotal), "material_total", text(baseMaterialTotal),
				"equipment_freight_total", null, "total", null, "pending_count", 1, "issues",
				List.of("有1项Excel校对价格仍待确认"), "formal_ready", false));
		tx(() -> repo.update("UPDATE quote_order SET result=? WHERE id=?", repo.json(result), id));

		var formal = tx(() -> service.export(quoter, id,
				obj("revision", applied.get("revision"), "kind", "formal")));
		var repaired = map(service.detail(quoter, id).get("result"));
		assertTrue(yes(repaired.get("formal_ready")));
		assertEquals(0, n(repaired.get("pending_count")).intValue());
		assertEquals("ready", map(map(repaired.get("fees")).get("equipment_freight")).get("status"));
		try (Workbook wb = WorkbookFactory
			.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(formal, "id")).get("content")))) {
			for (Row row : wb.getSheetAt(0)) {
				Cell label = row.getCell(0);
				if (label != null && label.getCellType() == CellType.STRING)
					assertNotEquals("设备运费", label.getStringCellValue());
			}
		}
	}

	/**
	 * 小额整单设备补价后仍需明确人工运费；再次校对填0也应视为已确认并允许正式报价。
	 */
	@Test
	void completingSmallOrderWithoutFreightPlaceholderStillRequiresManualFreight() throws IOException {
		publishEquipment();
		String id = s(tx(() -> service.create(quoter, obj("pricing_mode", "equipment_v1", "customer", "小单运费客户"))), "id");
		var quote = service.detail(quoter, id);
		var body = map(quote.get("body"));
		body.putAll(obj("include_freight", true, "rows", List.of(obj("name", "待确认设备", "category", "外购设备",
				"quantity", "1", "unit", "台", "attributes", obj("equipment_kind", "purchased", "model", "UNKNOWN"))),
				"equipment_options", obj("tax_included", false, "payment_option", "no_install_cash", "include_freight", true,
						"region", "贵州", "freight_amount", ""), "revision", quote.get("revision")));
		tx(() -> service.save(quoter, id, body));
		tx(() -> service.calculate(quoter, id, rev(service.detail(quoter, id))));
		var calculated = service.detail(quoter, id);
		var draft = tx(() -> service.export(quoter, id, obj("revision", calculated.get("revision"), "kind", "draft")));
		byte[] completed;
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(draft, "id")).get("content")));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (Row row : wb.getSheetAt(0))
				if (row.getCell(0) != null)
					assertNotEquals("设备运费", row.getCell(0).toString());
			wb.getSheetAt(0).getRow(9).getCell(7).setCellValue(100D);
			wb.write(out);
			completed = out.toByteArray();
		}
		var preview = tx(() -> service.previewReviewImport(quoter, id, completed, "小单校对.xlsx", n(calculated.get("revision")).intValue()));
		assertEquals(1, n(preview.get("pending_count")).intValue());
		var applied = tx(() -> service.applyReviewImport(quoter, id, s(preview, "import_id"), obj("revision", calculated.get("revision"))));
		var result = map(applied.get("result"));
		assertFalse(yes(result.get("formal_ready")));
		assertNull(result.get("total"));
		assertEquals("manual_order", map(map(result.get("fees")).get("equipment_freight")).get("basis"));
		var next = tx(() -> service.export(quoter, id, obj("revision", applied.get("revision"), "kind", "draft")));
		byte[] freightCompleted;
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream((byte[]) service.download(quoter, s(next, "id")).get("content")));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			assertEquals("设备运费", wb.getSheetAt(0).getRow(10).getCell(0).toString());
			wb.getSheetAt(0).getRow(10).getCell(8).setCellValue(0D);
			wb.write(out);
			freightCompleted = out.toByteArray();
		}
		var nextPreview = tx(() -> service.previewReviewImport(quoter, id, freightCompleted, "小单运费.xlsx", n(applied.get("revision")).intValue()));
		var finalQuote = tx(() -> service.applyReviewImport(quoter, id, s(nextPreview, "import_id"), obj("revision", applied.get("revision"))));
		assertTrue(yes(map(finalQuote.get("result")).get("formal_ready")));
		assertEquals(0, n(map(finalQuote.get("result")).get("total")).compareTo(n("100")));
	}

	/**
	 * 收集Excel中的文字单元格，供导出文案断言使用。
	 */
	static String workbookText(Workbook wb) {
		StringBuilder text = new StringBuilder();
		for (Sheet s : wb)
			for (Row r : s)
				for (Cell c : r)
					if (c.getCellType() == CellType.STRING)
						text.append(c.getStringCellValue()).append('\n');
		return text.toString();
	}

	/**
	 * 试算后暂停模板或停用指标时，正式导出必须再次校验，校对版仍可用于核价。
	 */
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

	/**
	 * 旧修订号不能覆盖新保存，原产品行不能通过提交空列表静默删除。
	 */
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

	/**
	 * 存在待确认项时仅能导出校对版，引擎版本过期后必须重新试算才能继续导出。
	 */
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

	/**
	 * 限制负责人权限及已发布版本修改，并验证停用最新版本不会自动退回旧价格。
	 */
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

	/**
	 * 两个请求使用相同修订号并发保存时只能一个成功，验证行锁与revision共同防止覆盖。
	 */
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

	/**
	 * 表头映射有歧义时不自动选列；公式警告、合并备注和文件归属继续保留。
	 */
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
