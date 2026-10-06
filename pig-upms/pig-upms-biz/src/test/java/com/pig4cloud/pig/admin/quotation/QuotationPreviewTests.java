package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * Opt-in loopback-only browser verification with real Java services and temporary H2
 * data. Never packaged in the application. This adapter does NOT test pig
 * login/gateway/security filters.
 */
@EnabledIfSystemProperty(named = "quotation.preview", matches = "true")
class QuotationPreviewTests {

	@Test
	void serveTemporaryPreview() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:quotation_preview;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
				"sa", "");
		var jdbc = new JdbcTemplate(source);
		// Keep the in-memory database alive for this test only.
		try (var keeper = source.getConnection()) {
			String sql = Files.readString(Path.of("..", "..", "db", "quotation.sql")).split("-- 菜单使用")[0]
				.replaceAll("(?m)^--.*$", "");
			for (String statement : sql.split(";"))
				if (!statement.isBlank())
					jdbc.execute(statement);
			ObjectMapper json = new ObjectMapper();
			var repo = new QuoteRepository(jdbc, json);
			var service = new QuoteService(repo);
			var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
			var actor = new QuoteService.Actor(1, "隔离验收负责人", true);
			var stopped = new CountDownLatch(1);
			int port = Integer.getInteger("quotation.preview.port", 18762);
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
			var executor = Executors.newFixedThreadPool(4);
			server.setExecutor(executor);
			server.createContext("/", exchange -> {
				try {
					String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
					if (path.equals("/__stop") && method.equals("POST")) {
						exchange.sendResponseHeaders(204, -1);
						stopped.countDown();
						return;
					}
					String relative = path.replaceFirst("^/admin/quotation/", "");
					String[] parts = relative.split("/");
					byte[] raw = exchange.getRequestBody().readNBytes(DuctImportService.MAX_BYTES + 1);
					Map<String, Object> data = relative.equals("imports") || raw.length == 0 ? obj()
							: json.readValue(raw, Map.class);
					Object response = tx.execute(status -> {
						String id = parts.length > 1 ? parts[1] : "", action = parts.length > 2 ? parts[2] : "";
						return switch (parts[0]) {
							case "product-rules" -> parts.length == 1
									? (method.equals("GET") ? service.productRules(actor)
											: service.createProductRule(actor, map(data.get("body"))))
									: id.equals("recommendations") ? service.productRuleRecommendations(actor, data)
											: id.equals("trial") ? service.trialProductRule(actor, data)
													: method.equals("PUT") ? service.editProductRule(actor, id, data)
															: service.productRuleAction(actor, id, action, data);
							case "resources" -> parts.length == 1
									? (method.equals("GET") ? service.resources() : service.createCatalog(actor,
											str(data.getOrDefault("kind", "duct_catalog")), map(data.get("body"))))
									: id.equals("seed") ? service.seed(actor)
											: method.equals("PUT") ? service.editCatalog(actor, id, data)
													: service.catalogAction(actor, id, action, data);
							case "equipment" -> service.equipmentSeed(actor);
							case "installation" -> service.installationSeed(actor);
							case "settings" ->
								method.equals("GET") ? service.settings(actor) : service.changeSettings(actor, data);
							case "audit" -> service.audit(actor);
							case "imports" -> service.upload(actor, raw, URLDecoder
								.decode(exchange.getRequestHeaders().getFirst("X-Filename"), StandardCharsets.UTF_8));
							case "exports" -> service.download(actor, id);
							case "quotes" -> parts.length == 1
									? (method.equals("GET")
											? service.listQuotes(actor,
													"archived=true".equals(exchange.getRequestURI().getQuery()))
											: service.create(actor, data))
									: method.equals("GET") ? service.detail(actor, id)
											: method.equals("PUT") ? service.save(actor, id, data)
													: action.equals("calculate") ? service.calculate(actor, id, data)
															: action.equals("export") ? service.export(actor, id, data)
																	: service.action(actor, id, action, data);
							default -> throw new RuleException("Unknown verification route");
						};
					});
					byte[] output;
					if (parts[0].equals("exports")) {
						output = (byte[]) map(response).get("content");
						exchange.getResponseHeaders()
							.set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
					}
					else {
						output = json.writeValueAsBytes(obj("code", 0, "data", response, "msg", ""));
						exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
					}
					exchange.sendResponseHeaders(200, output.length);
					exchange.getResponseBody().write(output);
				}
				catch (Exception e) {
					int code = e instanceof ResponseStatusException r ? r.getStatusCode().value() : 400;
					String message = e instanceof ResponseStatusException r ? r.getReason() : e.getMessage();
					byte[] output = json.writeValueAsBytes(obj("code", 1, "msg", message));
					exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
					exchange.sendResponseHeaders(code, output.length);
					exchange.getResponseBody().write(output);
				}
				finally {
					exchange.close();
				}
			});
			server.start();
			Files.createDirectories(Path.of("..", "..", "quotation-verification", "test-output"));
			Files.writeString(Path.of("..", "..", "quotation-verification", "test-output", "preview-ready.txt"),
					"127.0.0.1:" + port);
			try {
				stopped.await(45, TimeUnit.MINUTES);
			}
			finally {
				server.stop(0);
				executor.shutdownNow();
			}
		}
	}

}
