package com.pig4cloud.pig.admin.quotation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 报价单业务流程编排服务。
 * <p>
 * 本类负责报价单的保存、试算、校对版回导、状态流转和导出校验；具体金额公式分别由风管、设备和安装运算引擎负责。
 * 所有会改变报价结果的操作都在这里统一做版本校验和审计记录，避免旧页面或旧导出文件覆盖新数据。
 */
@Service
public class QuoteService {

	public record Actor(long id, String name, boolean manager) {
	}

	private final QuoteRepository repo;

	private final DuctPricingEngine engine = new DuctPricingEngine();

	private final EquipmentPricingEngine equipment = new EquipmentPricingEngine();

	private static boolean equipmentMode(Map<String, Object> body) {
		return Set.of("equipment_v1", "mixed_v1").contains(s(body, "pricing_mode"));
	}

	/**
	 * 读取整单运费选择。新字段 {@code include_freight} 是唯一有效口径；旧报价没有该字段时，才从风管和设备选项兼容读取。
	 * 复合报价中两个旧值冲突时返回 {@code null}，要求报价人员重新确认，不能静默选择其中一个。
	 */
	private static Boolean freightIncluded(Map<String, Object> body) {
		if (body.containsKey("include_freight"))
			return bool(body.get("include_freight")) ? yes(body.get("include_freight")) : null;
		Object duct = map(body.get("duct_options")).get("include_freight");
		Object equipment = map(body.get("equipment_options")).get("include_freight");
		return switch (s(body, "pricing_mode")) {
			case "equipment_v1" -> bool(equipment) ? yes(equipment) : null;
			case "mixed_v1" -> bool(duct) && bool(equipment) && duct.equals(equipment) ? yes(duct) : null;
			default -> bool(duct) ? yes(duct) : null;
		};
	}

	private static void applyFreight(Map<String, Object> body, Object value) {
		if (value != null && !bool(value))
			throw new RuleException("整单是否含运费必须明确选择");
		body.put("include_freight", value);
		// 旧报价中的风管、设备运费选择发生冲突时，先保留原值；只有人工确认整单口径后才同步覆盖。
		if (value != null)
			for (String key : List.of("duct_options", "equipment_options"))
				if (body.containsKey(key))
					map(body.get(key)).put("include_freight", value);
	}

	private static boolean freightUnconfirmed(Map<String, Object> quote) {
		return s(quote, "state").equals("draft") && freightIncluded(map(quote.get("body"))) == null;
	}

	/**
	 * 判断已保存的试算结果是否由当前版本规则生成。任一参与计算的引擎版本变化，都必须重新试算后才能正式导出。
	 */
	private static boolean stale(Map<String, Object> body, Map<String, Object> result) {
		String version = switch (s(body, "pricing_mode")) {
			case "equipment_v1" -> EquipmentPricingEngine.VERSION;
			case "mixed_v1" -> EquipmentPricingEngine.MIXED_VERSION;
			default -> DuctPricingEngine.VERSION;
		};
		return !result.isEmpty() && (!version.equals(s(result, "engine_version"))
				|| (result.containsKey("product_rule_engine_version")
						&& !ProductRuleCatalog.VERSION.equals(s(result, "product_rule_engine_version")))
				|| !InstallationPricingEngine.VERSION.equals(s(result, "installation_engine_version"))
				|| (s(body, "pricing_mode").equals("mixed_v1")
						&& !DuctPricingEngine.VERSION.equals(s(result, "duct_engine_version"))));
	}

	/**
	 * 报价运算的统一入口：先确认整单运费口径，再按报价模式分派到风管、设备或复合报价引擎。
	 */
	private Map<String, Object> compute(Map<String, Object> body, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		Boolean freight = freightIncluded(body);
		if (freight == null)
			throw new RuleException("请统一选择整单是否含运费后重新试算");
		applyFreight(body, freight);
		var rows = list(body.get("rows")).stream()
			.map(row -> ProductRuleCatalog.prepareRow(row, resources, LocalDate.now()))
			.toList();
		enableProductRuleMode(body, rows);
		Map<String, Object> prepared = repo.object(repo.json(body));
		prepared.put("rows", rows);
		Map<String, Object> result = switch (s(body, "pricing_mode")) {
			case "equipment_v1" -> equipment.calculate(prepared, resources, policy);
			case "mixed_v1" -> equipment.mixed(prepared, resources, policy);
			default -> engine.calculate(prepared, resources, policy);
		};
		for (int i = 0; i < rows.size(); i++)
			if (rows.get(i).containsKey("_product_rule_resource"))
				list(result.get("rows")).get(i).put("product_rule", rows.get(i).get("_product_rule_resource"));
		result.put("product_rule_bindings", productRuleBindings(rows));
		result.put("product_rule_engine_version", ProductRuleCatalog.VERSION);
		return result;
	}

	private static void enableProductRuleMode(Map<String, Object> body, List<Map<String, Object>> rows) {
		if (!s(body, "pricing_mode").equals("duct_v1") || rows.stream().noneMatch(row ->
				row.containsKey("_product_rule_resource") && !s(row, "category").equals("风管")))
			return;
		var duct = map(body.get("duct_options"));
		var options = equipmentDefaults();
		for (String key : List.of("tax_included", "payment_option", "include_freight"))
			if (duct.containsKey(key))
				options.put(key, duct.get(key));
		body.putAll(obj("pricing_mode", "mixed_v1", "recognition_mode", "auto_v1", "equipment_options", options));
	}

	private static List<Map<String, Object>> productRuleBindings(List<Map<String, Object>> rows) {
		return list(ProductRuleCatalog.bindings(rows)).stream()
			.filter(binding -> !map(binding.get("resource")).isEmpty() || !blank(binding.get("issue")))
			.toList();
	}

	private boolean productRulesChanged(Map<String, Object> body, Map<String, Object> result,
			List<Map<String, Object>> resources) {
		if (result.isEmpty())
			return false;
		var rows = list(body.get("rows")).stream()
			.map(row -> ProductRuleCatalog.prepareRow(row, resources, LocalDate.now()))
			.toList();
		return !repo.json(result.getOrDefault("product_rule_bindings", List.of()))
			.equals(repo.json(productRuleBindings(rows)));
	}

	private final DuctImportService importer = new DuctImportService();

	private final DuctExportService exporter = new DuctExportService();

	private final QuoteReviewImportService reviewImporter = new QuoteReviewImportService();
	private final AiQuoteReviewImportService aiReviewImporter = new AiQuoteReviewImportService();

	public QuoteService(QuoteRepository repo) {
		this.repo = repo;
	}

	private static void conflict(String message) {
		throw new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

	private static void leader(Actor actor) {
		if (!actor.manager())
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有报价负责人可以执行此操作");
	}

	private static String textValue(Object value, int max) {
		if (value == null)
			return "";
		if (!(value instanceof String) || str(value).length() > max)
			throw new RuleException("文本类型不正确或超过长度限制");
		return str(value);
	}

	private static void revision(Map<String, Object> row, Map<String, Object> data) {
		if (data.get("revision") == null || n(data.get("revision")).compareTo(n(row.get("revision"))) != 0)
			conflict("记录已在另一页面修改，请刷新后重试");
	}

	private Map<String, Object> quote(String id, Actor actor, boolean lock, boolean editable) {
		Map<String, Object> q = repo.find("quote_order", id, lock);
		if (q == null || (!actor.manager() && Long.parseLong(str(q.get("owner_id"))) != actor.id()))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报价不存在或无权访问");
		if (editable && !s(q, "state").equals("draft"))
			conflict("报价已锁定；归档请先恢复，正式报价请创建修订版");
		return q;
	}

	private Map<String, Object> catalog(String id) {
		Map<String, Object> r = repo.find("quote_catalog", id, true);
		if (r == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在");
		return r;
	}

	public List<Map<String, Object>> resources() {
		return repo.catalogs();
	}

	public List<Map<String, Object>> productRules(Actor actor) {
		return repo.catalogs().stream().filter(r -> s(r, "kind").equals("product_rule"))
			.filter(r -> actor.manager() || !s(r, "status").equals("draft")
					|| Long.parseLong(str(r.get("created_by"))) == actor.id())
			.toList();
	}

	public Map<String, Object> productRuleRecommendations(Actor actor, Map<String, Object> data) {
		return obj("templates", ProductRuleCatalog.templates(), "recommendations",
				ProductRuleCatalog.recommendations(map(data.get("row")), repo.catalogs(), LocalDate.now()),
				"catalog_rates", ProductRuleCatalog.catalogRates(), "can_publish", actor.manager());
	}

	/** Trial never changes a catalog or quotation; monetary results use the native settlement pipeline. */
	public Map<String, Object> trialProductRule(Actor actor, Map<String, Object> data) {
		Map<String, Object> definition = map(data.get("body"));
		ProductRuleCatalog.validate(definition);
		var row = repo.object(repo.json(map(data.get("row"))));
		row.put("product_rule", definition);
		row.remove("product_rule_family");
		var options = equipmentDefaults();
		var duct = ductDefaults();
		duct.putAll(map(data.get("duct_options")));
		for (String key : List.of("tax_included", "payment_option"))
			if (map(data.get("duct_options")).containsKey(key))
				options.put(key, duct.get(key));
		options.putAll(map(data.get("equipment_options")));
		boolean installationIncluded = data.get("installation_options") == null
				? yes(options.getOrDefault("include_installation", duct.get("include_installation")))
				: yes(map(data.get("installation_options")).get("included"));
		var body = obj("pricing_mode", "mixed_v1", "rows", List.of(row), "equipment_options", options,
				"duct_options", duct, "include_freight", data.getOrDefault("include_freight", false),
				"installation_options", obj("included", installationIncluded), "installation_items", List.of());
		var result = compute(body, repo.catalogs(), map(settings(actor).get("policy")));
		return obj("result", list(result.get("rows")).get(0));
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> createProductRule(Actor actor, Map<String, Object> body) {
		ProductRuleCatalog.validate(body);
		repo.catalogLock();
		return insertCatalog(actor, "product_rule", body);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> editProductRule(Actor actor, String id, Map<String, Object> data) {
		repo.catalogLock();
		if (!s(catalog(id), "kind").equals("product_rule"))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "产品规则不存在");
		return editCatalog(actor, id, data);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> productRuleAction(Actor actor, String id, String action, Map<String, Object> data) {
		repo.catalogLock();
		if (!s(catalog(id), "kind").equals("product_rule"))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "产品规则不存在");
		return catalogAction(actor, id, action, data);
	}

	private static void catalogAuthority(Actor actor, Map<String, Object> resource, String action) {
		if (!s(resource, "kind").equals("product_rule")) {
			leader(actor);
			return;
		}
		if (!actor.manager() && (Long.parseLong(str(resource.get("created_by"))) != actor.id()
				|| Set.of("publish", "retire", "reactivate").contains(action)))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能维护自己的规则草稿；通用规则由报价负责人发布");
	}

	public Map<String, Object> settings(Actor actor) {
		Map<String, Object> stored = repo.find("quote_setting", "policy", false);
		if (stored == null)
			throw new RuleException("请先执行报价模块初始化SQL");
		return obj("policy", stored.get("body"), "revision", stored.get("revision"), "can_manage", actor.manager(),
				"template_version", DuctExportService.TEMPLATE_VERSION, "template_sha256",
				DuctExportService.templateHash(), "engine_version", DuctPricingEngine.VERSION);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> changeSettings(Actor actor, Map<String, Object> data) {
		leader(actor);
		Map<String, Object> old = repo.find("quote_setting", "policy", true);
		revision(old, data);
		Object confirmed = data.get("formal_template_confirmed");
		if (!bool(confirmed))
			throw new RuleException("请明确是否确认正式模板");
		Map<String, Object> policy = envPolicy(confirmed);
		repo.update("UPDATE quote_setting SET body=?,revision=revision+1 WHERE id='policy'", repo.json(policy));
		repo.audit(actor, "update_policy", "policy", policy);
		return settings(actor);
	}

	private Map<String, Object> envPolicy(Object confirmed) {
		return obj("formal_template_confirmed", confirmed, "template_version", DuctExportService.TEMPLATE_VERSION,
				"template_sha256", DuctExportService.templateHash(), "unit_round", 1, "line_round", 1);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> seed(Actor actor) {
		leader(actor);
		repo.catalogLock();
		List<Map<String, Object>> existing = repo.catalogs()
			.stream()
			.filter(c -> s(c, "kind").equals("duct_catalog"))
			.toList();
		if (!existing.isEmpty())
			return obj("id", existing.get(0).get("id"), "existing", true);
		return createCatalog(actor, repo.object(new String(DuctExportService.asset("duct-catalog-seed.json"),
				java.nio.charset.StandardCharsets.UTF_8)));
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> createCatalog(Actor actor, Map<String, Object> body) {
		return createCatalog(actor, "duct_catalog", body);
	}

	public Map<String, Object> equipmentSeed(Actor actor) {
		leader(actor);
		Map<String, Object> body = repo.object(new String(DuctExportService.asset("equipment-catalog-seed.json"),
				java.nio.charset.StandardCharsets.UTF_8));
		body.put("effective_from", LocalDate.now().toString());
		return body;
	}

	public Map<String, Object> installationSeed(Actor actor) {
		leader(actor);
		Map<String, Object> body = repo.object(new String(DuctExportService.asset("installation-catalog-seed.json"),
				java.nio.charset.StandardCharsets.UTF_8));
		body.put("effective_from", LocalDate.now().toString());
		return body;
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> createCatalog(Actor actor, String kind, Map<String, Object> body) {
		leader(actor);
		if (!Set.of("duct_catalog", "equipment_catalog", "installation_catalog", "product_rule").contains(kind))
			throw new RuleException("资源类型不正确");
		repo.catalogLock();
		if (kind.equals("product_rule"))
			ProductRuleCatalog.validate(body);
		return insertCatalog(actor, kind, body);
	}

	private Map<String, Object> insertCatalog(Actor actor, String kind, Map<String, Object> body) {
		if (kind.equals("product_rule") && blank(body.get("effective_from")))
			body.put("effective_from", LocalDate.now().toString());
		String id = uuid();
		checkCatalogDraft(body);
		repo.update(
				"INSERT INTO quote_catalog(id,family,kind,version,status,body,revision,created_by,created_at) VALUES(?,?,?,1,'draft',?,1,?,CURRENT_TIMESTAMP)",
				id, id, kind, repo.json(body), actor.id());
		repo.audit(actor, "create_catalog", id, null);
		return obj("id", id);
	}

	private void checkCatalogDraft(Map<String, Object> body) {
		body.put("company_id", "guizhou");
		if (repo.json(body).length() > 2 * 1024 * 1024)
			throw new RuleException("指标内容过长");
		// Drafts may be incomplete; publication performs the complete validation.
		if (blank(body.get("name")))
			throw new RuleException("请填写指标名称");
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> editCatalog(Actor actor, String id, Map<String, Object> data) {
		repo.catalogLock();
		Map<String, Object> r = catalog(id);
		catalogAuthority(actor, r, "edit");
		revision(r, data);
		if (!s(r, "status").equals("draft"))
			conflict("已发布或停用的指标不可修改，请创建新版本");
		Map<String, Object> body = map(data.get("body"));
		if (s(r, "kind").equals("product_rule")) {
			if (blank(body.get("effective_from")))
				body.put("effective_from", map(r.get("body")).getOrDefault("effective_from", LocalDate.now().toString()));
			ProductRuleCatalog.validate(body);
		}
		checkCatalogDraft(body);
		repo.update("UPDATE quote_catalog SET body=?,revision=revision+1 WHERE id=?", repo.json(body), id);
		repo.audit(actor, "edit_catalog", id, obj("previous_body", r.get("body")));
		return obj("revision", n(r.get("revision")).intValue() + 1);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> catalogAction(Actor actor, String id, String action, Map<String, Object> data) {
		repo.catalogLock();
		Map<String, Object> r = catalog(id);
		catalogAuthority(actor, r, action);
		revision(r, data);
		String status = s(r, "status");
		Map<String, Object> body = map(r.get("body"));
		if (action.equals("revise")) {
			Map<String, Object> existing = repo.catalogs()
				.stream()
				.filter(x -> s(x, "family").equals(s(r, "family")) && s(x, "status").equals("draft"))
				.findFirst()
				.orElse(null);
			if (existing != null)
				return obj("id", existing.get("id"));
			int version = repo.catalogs()
				.stream()
				.filter(x -> s(x, "family").equals(s(r, "family")))
				.mapToInt(x -> n(x.get("version")).intValue())
				.max()
				.orElse(0) + 1;
			String next = uuid();
			repo.update(
					"INSERT INTO quote_catalog(id,family,kind,version,status,body,revision,created_by,created_at) VALUES(?,?,?,?,'draft',?,1,?,CURRENT_TIMESTAMP)",
					next, r.get("family"), r.get("kind"), version, repo.json(body), actor.id());
			repo.audit(actor, "revise_catalog", next, obj("from", id));
			return obj("id", next);
		}
		if (action.equals("delete")) {
			if (!status.equals("draft"))
				conflict("只有指标草稿可以删除");
			repo.update("DELETE FROM quote_catalog WHERE id=?", id);
		}
		else if (action.equals("retire")) {
			if (!status.equals("published"))
				conflict("只有已发布指标可以停用");
			repo.update("UPDATE quote_catalog SET status='retired',revision=revision+1 WHERE id=?", id);
		}
		else if (Set.of("trial", "publish", "reactivate").contains(action)) {
			if (!status.equals(action.equals("reactivate") ? "retired" : "draft"))
				conflict("当前指标状态不能执行此操作");
			if (Set.of("equipment_catalog", "installation_catalog", "product_rule").contains(s(r, "kind"))) {
				if (s(r, "kind").equals("installation_catalog"))
					InstallationCatalog.validate(body);
				else if (s(r, "kind").equals("product_rule"))
					ProductRuleCatalog.validate(body);
				else
					EquipmentCatalog.validate(body);
				try {
					LocalDate.parse(s(body, "effective_from"));
					if (!blank(body.get("effective_to")) && LocalDate.parse(s(body, "effective_to"))
						.isBefore(LocalDate.parse(s(body, "effective_from"))))
						throw new RuleException("失效日期不能早于生效日期");
				}
				catch (java.time.format.DateTimeParseException e) {
					throw new RuleException("指标生效日期格式不正确");
				}
			}
			else
				DuctCatalog.validate(body);
			if (action.equals("trial"))
				return obj("status", "validated", "message", s(r, "kind").equals("product_rule")
						? "产品规则、组成项、计价单位和公式校验通过" : "指标结构、价格、公式和付款方案校验通过");
			List<Map<String, Object>> all = repo.catalogs();
			if (action.equals("reactivate")) {
				boolean newer = all.stream()
					.anyMatch(x -> s(x, "family").equals(s(r, "family"))
							&& n(x.get("version")).compareTo(n(r.get("version"))) > 0
							&& Set.of("published", "retired").contains(s(x, "status")));
				if (newer)
					conflict("已有更新的正式版本，不能重新启用旧版本");
				String day = LocalDate.now().toString();
				if (s(body, "effective_from").compareTo(day) > 0
						|| (!blank(body.get("effective_to")) && s(body, "effective_to").compareTo(day) < 0))
					conflict("此版本不在生效日期内，请创建新版本调整日期");
			}
			// Check overlap across families, including future versions, before
			// publishing.
			for (Map<String, Object> other : all) {
				if (!s(other, "kind").equals(s(r, "kind")) || s(other, "family").equals(s(r, "family"))
						|| !s(other, "status").equals("published"))
					continue;
				Map<String, Object> b = map(other.get("body"));
				if (s(r, "kind").equals("product_rule") && !ProductRuleCatalog.conflicts(body, b))
					continue;
				String end = blank(body.get("effective_to")) ? "9999-12-31" : s(body, "effective_to"),
						otherEnd = blank(b.get("effective_to")) ? "9999-12-31" : s(b, "effective_to");
				if (s(body, "effective_from").compareTo(otherEnd) <= 0 && s(b, "effective_from").compareTo(end) <= 0)
					conflict(s(r, "kind").equals("product_rule") ? "相同产品名称、计价单位和适用条件已有生效规则，请调整条件或创建该规则的新版本"
							: "已有其他系列的生效指标，请先停用冲突系列");
			}
			repo.update(
					"UPDATE quote_catalog SET status='published',published_at=CURRENT_TIMESTAMP,revision=revision+1 WHERE id=?",
					id);
		}
		else
			throw new RuleException("不支持的指标操作");
		repo.audit(actor, action + "_catalog", id, obj("version", r.get("version")));
		return obj("ok", true);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> upload(Actor actor, byte[] bytes, String filename) {
		if (filename == null || filename.length() > 255)
			throw new RuleException("文件名无效");
		String clean = filename.replace('\\', '/');
		clean = clean.substring(clean.lastIndexOf('/') + 1);
		List<Map<String, Object>> sheets = importer.read(bytes, clean);
		String id = uuid();
		repo.update(
				"INSERT INTO quote_import(id,owner_id,filename,parsed,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
				id, actor.id(), clean, repo.json(obj("sheets", sheets)));
		repo.audit(actor, "import_file", id, obj("filename", clean));
		return obj("id", id, "filename", clean, "sheets", sheets);
	}

	/** Store the generated standard workbook and create an unpriced draft in one transaction. */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> createAiDraft(Actor actor, List<Map<String, Object>> normalizedRows,
			List<Map<String, Object>> sources) {
		byte[] workbook = AiQuoteImportService.standardWorkbook(normalizedRows);
		List<Map<String, Object>> sheets = importer.read(workbook, "AI标准清单.xlsx");
		List<Map<String, Object>> sourceRows = list(sheets.get(0).get("rows"));
		List<Map<String, Object>> detailRows = sourceRows.stream()
				.filter(row -> n(row.get("number")).intValue() >= AiQuoteImportService.DETAIL_START_ROW
						&& n(row.get("number")).intValue() < AiQuoteImportService.DETAIL_START_ROW + normalizedRows.size())
				.toList();
		if (detailRows.size() != normalizedRows.size())
			throw new RuleException("标准清单行数不一致，请重新生成");
		for (int i = 0; i < normalizedRows.size(); i++) {
			Map<String, Object> raw = normalizedRows.get(i), source = detailRows.get(i);
			source.putAll(obj("source_file", s(raw, "source_file"), "source_sheet", s(raw, "source_sheet"),
					"source_locator", s(raw, "source_locator"), "source_excerpt", s(raw, "source_excerpt")));
			if (!map(raw.get("model_field_issues")).isEmpty())
				source.put("model_field_issues", raw.get("model_field_issues"));
		}
		String importId = uuid();
		List<String> sourceFiles = sources.stream().map(source -> s(source, "filename")).toList();
		repo.update("INSERT INTO quote_import(id,owner_id,filename,parsed,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
				importId, actor.id(), "AI标准清单.xlsx", repo.json(obj("type", "ai_normalized_v1", "sheets", sheets,
						"normalized_rows", normalizedRows, "ai_sources", sources)));
		Map<String, Object> created = create(actor, obj("pricing_mode", "auto_v1", "import_id", importId,
				"sheet_settings", List.of(obj("name", AiQuoteImportService.SHEET_NAME, "start_row", AiQuoteImportService.DETAIL_START_ROW, "mapping",
						AiQuoteImportService.mapping()))));
		String quoteId = s(created, "id");
		Map<String, Object> quote = repo.find("quote_order", quoteId, false), body = map(quote.get("body"));
		if (list(body.get("rows")).size() + list(body.get("source_special_rows")).size() != normalizedRows.size())
			throw new RuleException("标准清单导入时有识别行未被保留，请调整来源内容后重试");
		for (Map<String, Object> row : list(body.get("rows")))
			map(row.get("attributes")).remove("review_reason");
		body.putAll(obj("ai_review_status", "pending", "ai_source_files", sourceFiles,
				"ai_generated_at", OffsetDateTime.now().toString()));
		repo.update("UPDATE quote_order SET body=? WHERE id=?", repo.json(body), quoteId);
		repo.audit(actor, "create_ai_quote", quoteId, obj("import_id", importId, "row_count", normalizedRows.size(),
				"source_files", sourceFiles));
		return obj("id", quoteId, "row_count", normalizedRows.size(), "product_row_count", list(body.get("rows")).size(),
				"special_row_count", list(body.get("source_special_rows")).size());
	}

	public byte[] aiStandardWorkbook(Actor actor, String id) {
		aiImport(actor, id);
		var quote = quote(id, actor, false, false);
		return AiQuoteImportService.standardWorkbook(AiQuoteReviewImportService.currentRows(map(quote.get("body"))),
				id, n(quote.get("revision")).intValue());
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> previewAiInputImport(Actor actor, String id, byte[] bytes, String filename,
			int expectedRevision) {
		var quote = quote(id, actor, true, true);
		revision(quote, obj("revision", expectedRevision));
		var body = map(quote.get("body"));
		if (blank(body.get("ai_review_status"))) throw new RuleException("只有AI生成的草稿可以导回已校验清单");
		String clean = cleanFilename(filename);
		var parsed = aiReviewImporter.read(bytes, clean, id, expectedRevision, body);
		String importId = uuid();
		parsed.putAll(obj("filename", clean, "content_base64", Base64.getEncoder().encodeToString(bytes)));
		repo.update("INSERT INTO quote_import(id,owner_id,filename,parsed,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
				importId, actor.id(), clean, repo.json(parsed));
		repo.audit(actor, "preview_ai_input_import", id, obj("import_id", importId, "filename", clean,
				"base_revision", expectedRevision, "change_count", parsed.get("change_count")));
		var response = obj("import_id", importId, "filename", clean);
		for (String key : List.of("parser_version", "line_count", "product_row_count", "special_row_count", "change_count",
				"changed_row_count", "unchanged_count", "changes", "review_rows")) response.put(key, parsed.get(key));
		return response;
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> applyAiInputImport(Actor actor, String id, String importId, Map<String, Object> data) {
		var quote = quote(id, actor, true, true);
		revision(quote, data);
		var old = map(quote.get("body"));
		if (blank(old.get("ai_review_status"))) throw new RuleException("只有AI生成的草稿可以导回已校验清单");
		if (!yes(data.get("review_confirmed"))) throw new RuleException("请先查看修改差异，再确认导回清单");
		var imported = repo.find("quote_import", importId, false);
		if (imported == null || !actor.manager() && Long.parseLong(str(imported.get("owner_id"))) != actor.id())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回导清单不存在或无权访问");
		var parsed = map(imported.get("parsed"));
		if (!s(parsed, "type").equals("ai_input_review_v1") || !s(parsed, "quote_id").equals(id))
			throw new RuleException("回导清单不属于当前AI草稿");
		if (!s(parsed, "parser_version").equals(AiQuoteReviewImportService.PARSER_VERSION))
			throw new RuleException("清单校验规则已更新，请重新上传已校验清单并确认新的差异");
		if (n(parsed.get("base_revision")).compareTo(n(quote.get("revision"))) != 0)
			conflict("预览后草稿已修改，请重新上传校验清单");
		var body = new LinkedHashMap<>(old);
		for (String key : List.of("rows", "source_special_rows", "duct_options")) body.put(key, parsed.get(key));
		body.putAll(obj("ai_review_status", "pending", "ai_input_review_import", obj("import_id", importId,
				"filename", imported.get("filename"), "imported_by", actor.id(), "imported_at", OffsetDateTime.now().toString(),
				"change_count", parsed.get("change_count"))));
		body.remove("ai_reviewed_by");
		body.remove("ai_reviewed_at");
		if (repo.json(body).length() > 16 * 1024 * 1024) throw new RuleException("报价内容过长，请拆分");
		repo.update("UPDATE quote_order SET body=?,result=NULL,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				repo.json(body), id);
		repo.audit(actor, "apply_ai_input_import", id, obj("import_id", importId, "previous_body", old,
				"changes", parsed.get("changes")));
		return obj("revision", n(quote.get("revision")).intValue() + 1, "line_count", parsed.get("line_count"),
				"product_row_count", parsed.get("product_row_count"), "special_row_count", parsed.get("special_row_count"));
	}

	public Map<String, Object> aiSource(Actor actor, String id, int index) {
		List<Map<String, Object>> sources = list(aiImport(actor, id).get("ai_sources"));
		if (index < 0 || index >= sources.size())
			throw new RuleException("客户原始资料不存在");
		Map<String, Object> source = sources.get(index);
		return obj("filename", s(source, "filename"), "media_type", s(source, "media_type"), "content",
				Base64.getDecoder().decode(s(source, "content_base64")));
	}

	private Map<String, Object> aiImport(Actor actor, String id) {
		Map<String, Object> quote = quote(id, actor, false, false), body = map(quote.get("body"));
		if (blank(body.get("ai_review_status")))
			throw new RuleException("这份报价没有AI生成的标准清单");
		Map<String, Object> imported = repo.find("quote_import", s(body, "import_id"), false);
		if (imported == null || !"ai_normalized_v1".equals(s(map(imported.get("parsed")), "type")))
			throw new RuleException("标准清单不存在");
		return map(imported.get("parsed"));
	}

	private Map<String, Object> metadata(Map<String, Object> input) {
		Map<String, Object> body = obj();
		for (String key : List.of("customer", "project", "contact", "delivery_location", "quote_date", "note"))
			body.put(key, textValue(input.get(key), key.equals("note") ? 1000 : 200));
		if (!blank(body.get("quote_date"))) {
			try {
				LocalDate.parse(s(body, "quote_date"));
			}
			catch (RuntimeException e) {
				throw new RuleException("报价日期格式不正确");
			}
		}
		return body;
	}

	private static Map<String, Object> ductDefaults() {
		return obj("area_basis", "", "product_state", "", "shape", "", "zinc_layer", "40", "flange", "",
				"include_common_punch", null, "tax_included", null, "corner_count", "", "clip_meters", "",
				"flange_30_meters", "", "flange_40_meters", "", "payment_option", "", "include_installation", false,
				"installation_amount", "", "include_freight", false);
	}

	private static Map<String, Object> equipmentDefaults() {
		return obj("tax_included", null, "payment_option", "", "include_freight", false, "region", "", "freight_amount", "");
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> create(Actor actor, Map<String, Object> data) {
		Map<String, Object> body = metadata(data), options = ductDefaults();
		if (blank(body.get("quote_date")))
			body.put("quote_date", LocalDate.now().toString());
		String mode = str(data.getOrDefault("pricing_mode", "duct_v1"));
		boolean wholeWorkbook = !blank(data.get("import_id"))
				&& (data.containsKey("sheet_settings") || (mode.equals("auto_v1") && blank(data.get("sheet"))));
		if (wholeWorkbook)
			mode = "auto_v1";
		if (mode.equals("auto_v1")) {
			mode = "mixed_v1";
			body.put("recognition_mode", "auto_v1");
		}
		if (!Set.of("duct_v1", "equipment_v1", "mixed_v1").contains(mode))
			throw new RuleException("报价类型未识别");
		body.putAll(
				obj("company_id", "guizhou", "pricing_mode", mode, "rows", new ArrayList<>(), "duct_options", options));
		body.put("installation_options", obj("included", false, "region", "", "include_auxiliaries", false,
				"include_site_fee", false, "height", "", "cross_work", false, "cross_work_fee_decision", "",
				"site_amount", "", "site_note", "", "site_waive_reason", ""));
		body.put("installation_items", new ArrayList<>());
		if (equipmentMode(body))
			body.put("equipment_options", equipmentDefaults());
		applyFreight(body, data.getOrDefault("include_freight", false));
		if (!blank(data.get("import_id"))) {
			Map<String, Object> imported = repo.find("quote_import", s(data, "import_id"), false);
			if (imported == null || Long.parseLong(str(imported.get("owner_id"))) != actor.id())
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入文件不存在或无权访问");
			List<Map<String, Object>> sheets = list(map(imported.get("parsed")).get("sheets"));
			Map<String, Object> built;
			if (wholeWorkbook) {
				built = importer.buildWorkbook(sheets, data.get("sheet_settings"));
				body.put("source_sheet",
						String.join("、",
								list(built.get("source_sheets")).stream()
									.filter(s -> s(s, "status").equals("imported"))
									.map(s -> s(s, "name"))
									.toList()));
			}
			else {
				Map<String, Object> sheet = sheets.stream()
					.filter(s -> s(s, "name").equals(s(data, "sheet")))
					.findFirst()
					.orElseThrow(() -> new RuleException("请选择工作表"));
				built = importer.build(sheet, data.get("start_row"), map(data.get("mapping")), mode);
				body.putAll(obj("source_sheet", data.get("sheet"), "import_mapping", data.get("mapping"), "start_row",
						data.get("start_row")));
			}
			options.putAll(map(built.remove("duct_options")));
			body.putAll(built);
			body.putAll(obj("source_filename", imported.get("filename"), "import_id", data.get("import_id")));
			list(body.get("rows")).forEach(r -> r.put("row_id", uuid()));
		}
		if (repo.json(body).length() > 16 * 1024 * 1024)
			throw new RuleException("报价内容过长，请拆分");
		String id = uuid();
		repo.update(
				"INSERT INTO quote_order(id,family,version,owner_id,body,state,revision,created_at,updated_at) VALUES(?,?,1,?,?,'draft',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				id, id, actor.id(), repo.json(body));
		repo.audit(actor, "create_quote", id, null);
		return obj("id", id);
	}

	public List<Map<String, Object>> listQuotes(Actor actor, boolean archived) {
		String sql = "SELECT q.*, (SELECT COUNT(*) FROM quote_export x WHERE x.quote_id=q.id) export_count FROM quote_order q WHERE state "
				+ (archived ? "=" : "<>") + " 'archived'" + (actor.manager() ? "" : " AND owner_id=?")
				+ " ORDER BY updated_at DESC";
		List<Map<String, Object>> result = new ArrayList<>();
		var resources = repo.catalogs();
		for (Map<String, Object> value : repo.query(sql,
				actor.manager() ? new Object[] {} : new Object[] { actor.id() })) {
			Map<String, Object> q = repo.decode(value), body = map(q.remove("body")),
					calculation = map(q.remove("result"));
			// pig serializes Long values as strings; keep UI counters numeric, including
			// zero.
			q.put("export_count", n(q.get("export_count")).intValueExact());
			q.putAll(obj("customer", body.get("customer"), "project", body.get("project"), "quote_date",
					body.get("quote_date"), "row_count", list(body.get("rows")).size()));
			q.put("pricing_mode", body.get("pricing_mode"));
			q.put("recognition_mode", body.get("recognition_mode"));
			boolean stale = stale(body, calculation)
					|| (s(q, "state").equals("draft") && (freightIncluded(body) == null
							|| productRulesChanged(body, calculation, resources)));
			q.putAll(obj("result_stale", stale, "total", stale ? null : calculation.get("total"), "pending_count",
					stale ? null : calculation.get("pending_count")));
			result.add(q);
		}
		return result;
	}

	public Map<String, Object> detail(Actor actor, String id) {
		Map<String, Object> q = quote(id, actor, false, false), calculation = map(q.get("result"));
		var body = map(q.get("body"));
		boolean stale = stale(body, calculation) || freightUnconfirmed(q)
				|| (s(q, "state").equals("draft") && productRulesChanged(body, calculation, repo.catalogs()));
		body.put("include_freight", freightIncluded(body));
		q.put("result_stale", stale);
		if (stale && s(q, "state").equals("draft"))
			q.put("result", null);
		q.put("exports", repo.query(
				"SELECT id,kind,filename,created_at,template_version,template_sha256 FROM quote_export WHERE quote_id=? ORDER BY created_at DESC",
				id));
		return q;
	}

	private static Map<String, Object> installationEntry(Map<String, Object> value) {
		var entry = obj();
		for (String key : List.of("name", "category", "project_id", "unit_price", "quantity", "unit", "product_state",
				"power", "auxiliary_type", "auxiliary_price", "note", "exclude_reason"))
			entry.put(key, textValue(value.get(key), Set.of("note", "exclude_reason").contains(key) ? 500 : 200));
		Object excluded = value.getOrDefault("excluded", false);
		if (!bool(excluded))
			throw new RuleException("安装排除状态必须明确选择");
		entry.put("excluded", excluded);
		if (yes(excluded) && blank(entry.get("exclude_reason")))
			throw new RuleException("不含安装的项目必须填写原因");
		return entry;
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> save(Actor actor, String id, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, true);
		revision(q, data);
		Map<String, Object> old = map(q.get("body")), body = new LinkedHashMap<>(old);
		body.putAll(metadata(data));
		if (equipmentMode(old)) {
			var incomingEquipment = map(data.get("equipment_options"));
			var equipmentOptions = new LinkedHashMap<>(map(old.get("equipment_options")));
			for (String key : List.of("payment_option", "region", "freight_amount"))
				if (incomingEquipment.containsKey(key))
					equipmentOptions.put(key, textValue(incomingEquipment.get(key), 100));
			for (String key : List.of("tax_included", "include_freight"))
				if (incomingEquipment.containsKey(key)) {
					Object value = incomingEquipment.get(key);
					if (value != null && !bool(value))
						throw new RuleException("设备报价条件必须明确选择");
					equipmentOptions.put(key, value);
				}
			body.put("equipment_options", equipmentOptions);
		}
		Map<String, Object> incoming = map(data.get("duct_options")),
				options = new LinkedHashMap<>(map(old.get("duct_options")));
		for (String key : List.of("area_basis", "product_state", "shape", "zinc_layer", "flange", "corner_count",
				"clip_meters", "flange_30_meters", "flange_40_meters", "payment_option", "installation_amount"))
			if (incoming.containsKey(key))
				options.put(key, textValue(incoming.get(key), 100));
		for (String key : List.of("tax_included", "include_common_punch", "include_installation", "include_freight"))
			if (incoming.containsKey(key)) {
				Object value = incoming.get(key);
				if (value != null && !bool(value))
					throw new RuleException("报价条件必须明确选择");
				options.put(key, value);
			}
		if (data.containsKey("installation_options")) {
			if (!(data.get("installation_options") instanceof Map))
				throw new RuleException("安装条件格式不正确");
			var incomingInstallation = map(data.get("installation_options"));
			var installation = new LinkedHashMap<>(map(old.get("installation_options")));
			for (String key : List.of("included", "include_auxiliaries", "include_site_fee", "cross_work")) {
				Object value = incomingInstallation.getOrDefault(key, installation.getOrDefault(key, false));
				if (!bool(value))
					throw new RuleException("安装条件必须明确选择");
				installation.put(key, value);
			}
			for (String key : List.of("region", "height", "site_amount", "site_note", "cross_work_fee_decision",
					"site_waive_reason"))
				installation.put(key, textValue(incomingInstallation.getOrDefault(key, installation.get(key)), 500));
			body.put("installation_options", installation);
			options.put("include_installation", installation.get("included"));
			if (body.containsKey("equipment_options"))
				map(body.get("equipment_options")).put("include_installation", installation.get("included"));
		}
		var installationItems = list(data.getOrDefault("installation_items", old.get("installation_items")));
		if (installationItems.size() > 1000)
			throw new RuleException("独立安装项目不能超过1000行");
		body.put("installation_items", installationItems.stream().map(QuoteService::installationEntry).toList());
		List<Map<String, Object>> rows = list(data.get("rows")), previous = list(old.get("rows"));
		if (rows.size() < previous.size() || rows.size() > 10000)
			throw new RuleException("产品行不能静默删除，请保留原行并标记不计价及原因");
		List<Map<String, Object>> safe = new ArrayList<>();
		List<Map<String, Object>> preparedRules = new ArrayList<>();
		List<Map<String, Object>> ruleResources = repo.catalogs();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i), original = i < previous.size() ? previous.get(i) : obj("source_row",
					"手工" + (i + 1), "source_sheet", "手工", "original", null, "source_issue", false, "row_id", uuid());
			if (i < previous.size() && !s(row, "row_id").equals(s(original, "row_id")))
				throw new RuleException("原始清单行顺序不可变更或替换");
			Map<String, Object> cleaned = obj();
			for (String key : List.of("row_id", "source_row", "source_sheet", "original", "source_issue"))
				cleaned.put(key, original.get(key));
			for (String key : List.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark", "category",
					"exclude_reason", "candidate_id"))
				cleaned.put(key, textValue(row.get(key), 1000));
			for (String key : List.of("excluded", "source_issue_confirmed")) {
				if (row.containsKey(key) && !bool(row.get(key)))
					throw new RuleException("行确认状态不正确");
				cleaned.put(key, yes(row.get(key)));
			}
			if (yes(cleaned.get("excluded")) && blank(cleaned.get("exclude_reason")))
				throw new RuleException("不计价行必须说明原因");
			Map<String, Object> attrs = map(row.get("attributes")), safeAttrs = obj();
			if (attrs.size() > 100)
				throw new RuleException("产品参数过多");
			attrs.forEach((k, v) -> {
				if (k.length() > 40)
					throw new RuleException("参数名称过长");
				safeAttrs.put(k, textValue(v, 300));
			});
			cleaned.put("attributes", safeAttrs);
			if (blank(old.get("ai_review_status")))
				EquipmentReview.refresh(cleaned);
			else {
				EquipmentReview.normalizeInputs(safeAttrs);
				safeAttrs.remove("review_reason");
			}
			String ruleFamily = textValue(row.get("product_rule_family"), 100);
			if (!blank(ruleFamily))
				cleaned.put("product_rule_family", ruleFamily);
			if (row.get("product_rule") != null) {
				if (!blank(ruleFamily))
					throw new RuleException("本行只能选择本单规则或通用规则中的一种");
				var definition = map(row.get("product_rule"));
				ProductRuleCatalog.validate(definition);
				cleaned.put("product_rule", definition);
			}
			if (row.containsKey("installation") || original.containsKey("installation"))
				cleaned.put("installation",
						installationEntry(map(row.getOrDefault("installation", original.get("installation")))));
			if (s(old, "recognition_mode").equals("auto_v1"))
				DuctImportService.autoClassifyRow(cleaned);
			var preparedRow = ProductRuleCatalog.prepareRow(cleaned, ruleResources, LocalDate.now());
			preparedRules.add(preparedRow);
			if (preparedRow.containsKey("_product_rule_resource"))
				cleaned.put("category", preparedRow.get("category"));
			safe.add(cleaned);
		}
		body.put("rows", safe);
		if (!blank(old.get("ai_review_status")))
			body.put("ai_review_status", "pending");
		body.put("duct_options", options);
		enableProductRuleMode(body, preparedRules);
		applyFreight(body, data.containsKey("include_freight") ? data.get("include_freight") : freightIncluded(body));
		if (repo.json(body).length() > 16 * 1024 * 1024)
			throw new RuleException("报价内容过长，请拆分");
		repo.update(
				"UPDATE quote_order SET body=?,result=NULL,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				repo.json(body), id);
		repo.audit(actor, "edit_quote", id, obj("previous_body", old));
		return obj("revision", n(q.get("revision")).intValue() + 1);
	}

	/**
	 * 对当前修订版执行试算并原子保存结果。行级待确认项由运算引擎返回，不会用猜测值强行补齐。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> calculate(Actor actor, String id, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, true);
		revision(q, data);
		var body = map(q.get("body"));
		if (!blank(body.get("ai_review_status")) && !s(body, "ai_review_status").equals("approved"))
			throw new RuleException("请先人工核对并确认AI生成的清单，再试算报价");
		Map<String, Object> result = compute(body, repo.catalogs(), map(settings(actor).get("policy")));
		result.put("calculated_at", OffsetDateTime.now().toString());
		result.put("template_sha256", DuctExportService.templateHash());
		repo.update(
				"UPDATE quote_order SET body=?,result=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				repo.json(body), repo.json(result), id);
		repo.audit(actor, "calculate_quote", id, obj("pending_count", result.get("pending_count")));
		return obj("result", result, "revision", n(q.get("revision")).intValue() + 1);
	}

	/**
	 * 读取报价人员修改后的校对版 Excel，仅生成预览，不立即写回报价单。
	 * 预览按“已修改、待确认、未修改”的顺序展示，方便先处理真正需要关注的行。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> previewReviewImport(Actor actor, String id, byte[] bytes, String filename,
			int expectedRevision) {
		Map<String, Object> q = quote(id, actor, true, true);
		revision(q, obj("revision", expectedRevision));
		Map<String, Object> result = map(q.get("result"));
		if (result.isEmpty())
			throw new RuleException("当前报价尚未试算，不能回导校对版");
		if (stale(map(q.get("body")), result))
			conflict("运算规则已更新，请重新试算并导出新的校对版");
		String clean = cleanFilename(filename);
		Map<String, Object> parsed = reviewImporter.read(bytes, clean, id, expectedRevision);
		resolveReviewEquipmentFreight(map(q.get("body")), result, parsed, repo.catalogs());
		String importId = uuid();
		parsed.putAll(obj("filename", clean, "base_revision", expectedRevision));
		repo.update(
				"INSERT INTO quote_import(id,owner_id,filename,parsed,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
				importId, actor.id(), clean, repo.json(parsed));
		List<Map<String, Object>> changes = list(parsed.get("changes"));
		List<Map<String, Object>> reviewRows = new ArrayList<>(list(parsed.get("lines")));
		reviewRows.sort(Comparator
			.comparingInt((Map<String, Object> line) -> yes(line.get("changed")) ? 0 : yes(line.get("pending")) ? 1 : 2)
			.thenComparingInt(line -> n(line.get("visible_row")).intValue()));
		return obj("import_id", importId, "filename", clean, "line_count", parsed.get("line_count"), "change_count",
				parsed.get("change_count"), "changes", changes.stream().limit(200).toList(), "changes_truncated",
				changes.size() > 200, "derived_count", parsed.get("derived_count"), "changed_row_count",
				parsed.get("changed_row_count"), "unchanged_count", parsed.get("unchanged_count"), "pending_count",
				parsed.get("pending_count"), "subtotal", parsed.get("subtotal"), "material_total",
				parsed.get("material_total"), "equipment_freight_total", parsed.get("equipment_freight_total"),
				"equipment_freight_included", parsed.get("equipment_freight_included"),
				"installation_total", parsed.get("installation_total"),
				"before_payment_total", parsed.get("before_payment_total"), "column_count", parsed.get("column_count"),
				"review_rows", reviewRows);
	}

	/**
	 * 校对版可能是在设备价格尚未补齐时导出的，此时整单设备运费只能先留空。产品补齐后在这里重新执行地区系数和8000元门槛，
	 * 避免空白的临时运费汇总行继续阻止正式报价。
	 */
	private static boolean resolveReviewEquipmentFreight(Map<String, Object> body, Map<String, Object> result,
			Map<String, Object> parsed, List<Map<String, Object>> resources) {
		if (!equipmentMode(body) || !Boolean.TRUE.equals(freightIncluded(body)))
			return false;
		if (!map(parsed.get("equipment_freight_resolution")).isEmpty())
			return true;
		List<Map<String, Object>> lines = list(parsed.get("lines"));
		Map<String, Object> freightLine = lines.stream()
			.filter(line -> s(line, "process").equals("设备运费"))
			.findFirst()
			.orElse(null);
		var originalFreight = map(map(result.get("fees")).get("equipment_freight"));
		if (freightLine == null && !s(originalFreight, "status").equals("pending") || lines.stream()
			.anyMatch(line -> line != freightLine && yes(line.get("pending"))))
			return false;
		Map<String, Object> term = map(result.get("payment"));
		if (term.isEmpty())
			return false;
		List<Map<String, Object>> active = EquipmentCatalog.active(resources, LocalDate.now());
		if (active.size() != 1)
			return false;
		Map<String, Object> catalog = map(active.get(0).get("body"));
		try {
			EquipmentCatalog.validate(catalog);
		}
		catch (RuleException e) {
			return false;
		}
		String region = s(map(body.get("equipment_options")), "region").replaceFirst("省$", "")
			.replaceFirst("壮族自治区$", "");
		Map<String, Object> regions = map(catalog.get("regions"));
		if (!regions.containsKey(region))
			return false;
		BigDecimal factor = n(regions.get(region)), threshold = rate(map(catalog.get("rates")), "freight_threshold");
		Map<String, Map<String, Object>> sourceRows = new HashMap<>();
		for (Map<String, Object> source : list(body.get("rows")))
			sourceRows.put(s(source, "row_id"), source);
		List<Map<String, Object>> adjustments = new ArrayList<>();
		BigDecimal calculatedFreight = ZERO;
		for (Map<String, Object> line : lines) {
			Map<String, Object> source = sourceRows.get(s(line, "source_row_id"));
			if (source == null || yes(source.get("excluded")) || s(source, "category").equals("风管")
					|| blank(line.get("material_unit")) || blank(line.get("material_amount"))
					|| blank(line.get("quantity")))
				continue;
			BigDecimal baseUnit = n(line.get("material_unit")), baseAmount = n(line.get("material_amount")),
					quantity = n(line.get("quantity")), finalUnit = mul(baseUnit, factor).setScale(0, RoundingMode.CEILING),
					finalAmount = mul(finalUnit, quantity).setScale(0, RoundingMode.CEILING),
					freightAmount = finalAmount.subtract(baseAmount, MC);
			calculatedFreight = add(calculatedFreight, freightAmount);
			adjustments.add(obj("line", line, "base_unit", baseUnit, "base_amount", baseAmount, "final_unit", finalUnit,
					"final_amount", finalAmount, "freight_per_unit", finalUnit.subtract(baseUnit, MC), "freight_amount",
					freightAmount));
		}
		if (adjustments.isEmpty())
			return false;
		BigDecimal trialBeforePayment = add(n(parsed.get("material_total")), calculatedFreight,
				n(parsed.get("installation_total")));
		BigDecimal trialTotal = mul(trialBeforePayment, n(term.get("coefficient"))).setScale(0, RoundingMode.CEILING);
		if (trialTotal.compareTo(threshold) < 0) {
			if (freightLine == null) {
				String reason = "整单试算销售额低于8000元，请填写整单设备运费（可填0）";
				parsed.putAll(obj("pending_count", n(parsed.get("pending_count")).intValue() + 1,
						"equipment_freight_resolution", obj("status", "pending", "amount", null, "reason", reason,
								"basis", "manual_order", "included_in_rows", false, "region", region,
								"trial_order_total", text(trialTotal))));
			}
			return false;
		}
		for (Map<String, Object> adjustment : adjustments) {
			Map<String, Object> line = map(adjustment.get("line"));
			line.putAll(obj("base_material_unit", text(n(adjustment.get("base_unit"))), "base_material_amount",
					text(n(adjustment.get("base_amount"))), "material_unit", text(n(adjustment.get("final_unit"))),
					"material_amount", text(n(adjustment.get("final_amount"))), "freight_per_unit",
					text(n(adjustment.get("freight_per_unit"))), "freight_amount",
					text(n(adjustment.get("freight_amount"))), "freight_factor", text(factor), "freight_adjusted", true));
			if (parsed.get("column_count") != null && n(parsed.get("column_count")).intValue() != 10)
				line.put("line_total", text(add(n(adjustment.get("final_amount")),
						blank(line.get("installation_amount")) ? ZERO : n(line.get("installation_amount")))));
		}
		lines.remove(freightLine);
		BigDecimal materialTotal = add(n(parsed.get("material_total")), calculatedFreight), installationTotal = n(parsed.get("installation_total")),
				beforePayment = add(materialTotal, installationTotal);
		Map<String, Object> resolution = obj("status", "ready", "amount", text(calculatedFreight), "reason",
				"整单试算销售额达到8000元，设备运费已逐行计入设备单台售价", "basis", "regional_per_unit",
				"included_in_rows", true, "region", region, "factor", text(factor), "threshold", text(threshold),
				"trial_order_before_payment_total", text(trialBeforePayment), "trial_order_total", text(trialTotal));
		parsed.putAll(obj("pending_count", 0, "material_total", text(materialTotal), "equipment_freight_total",
				text(calculatedFreight), "equipment_freight_included", true, "before_payment_total", text(beforePayment),
				"subtotal", text(beforePayment), "equipment_freight_resolution", resolution));
		return true;
	}

	private static void applyReviewFreightAdjustments(Map<String, Object> body, Map<String, Object> result,
			List<Map<String, Object>> manualLines) {
		Map<String, Integer> rowIndexes = new HashMap<>();
		List<Map<String, Object>> bodyRows = list(body.get("rows")), resultRows = list(result.get("rows"));
		for (int i = 0; i < bodyRows.size(); i++)
			rowIndexes.put(s(bodyRows.get(i), "row_id"), i);
		for (Map<String, Object> line : manualLines) {
			if (!yes(line.get("freight_adjusted")))
				continue;
			Integer index = rowIndexes.get(s(line, "source_row_id"));
			if (index == null || index >= resultRows.size())
				continue;
			Map<String, Object> row = resultRows.get(index);
			row.putAll(obj("base_unit_price", line.get("base_material_unit"), "base_amount",
					line.get("base_material_amount"), "unit_price", line.get("material_unit"), "amount",
					line.get("material_amount"), "body_amount", line.get("material_amount"), "freight_per_unit",
					line.get("freight_per_unit"), "freight_amount", line.get("freight_amount")));
			Map<String, Object> coverage = map(row.get("coverage"));
			coverage.put("freight", true);
			row.put("coverage", coverage);
			Map<String, Object> detail = map(row.get("detail"));
			detail.putAll(obj("freight_basis", "regional_per_unit", "freight_factor", line.get("freight_factor")));
			row.put("detail", detail);
		}
	}

	/**
	 * 兼容已经回导、但仍被旧逻辑保留为“设备运费待确认”的报价。正式导出时仅重算这一个派生项，
	 * 不改人工录入的价格，也不增加报价修订号。
	 */
	private static boolean repairImportedReviewFreight(Map<String, Object> body, Map<String, Object> result,
			List<Map<String, Object>> resources) {
		List<Map<String, Object>> manualLines = list(result.get("manual_lines"));
		Map<String, Object> fees = map(result.get("fees"));
		Map<String, Object> freightFee = map(fees.get("equipment_freight"));
		if (manualLines.isEmpty() || !s(freightFee, "status").equals("pending")
				|| manualLines.stream().noneMatch(line -> s(line, "process").equals("设备运费")))
			return false;
		Map<String, Object> parsed = obj("lines", manualLines, "material_total",
				result.getOrDefault("material_total", result.get("subtotal")), "installation_total",
				map(fees.get("installation")).getOrDefault("amount", "0"), "pending_count",
				result.getOrDefault("pending_count", 0), "column_count",
				InstallationPricingEngine.enabled(body) ? 12 : 10);
		if (!resolveReviewEquipmentFreight(body, result, parsed, resources))
			return false;
		applyReviewFreightAdjustments(body, result, manualLines);
		Map<String, Object> resolution = map(parsed.get("equipment_freight_resolution"));
		freightFee.putAll(resolution);
		fees.put("equipment_freight", freightFee);

		List<String> issues = new ArrayList<>();
		for (Object issue : (List<?>) result.getOrDefault("issues", List.of())) {
			String message = str(issue);
			if (!message.matches("有\\d+项Excel校对价格仍待确认"))
				issues.add(message);
		}
		int pending = n(parsed.get("pending_count")).intValue();
		if (pending > 0)
			issues.add("有" + pending + "项Excel校对价格仍待确认");
		BigDecimal materialTotal = n(parsed.get("material_total")), installationTotal = n(parsed.get("installation_total")),
				beforePayment = add(materialTotal, installationTotal), total = null, paymentAmount = null,
				roundingDifference = null;
		if (issues.isEmpty()) {
			BigDecimal unrounded = mul(beforePayment, n(map(result.get("payment")).get("coefficient")));
			total = unrounded.setScale(0, RoundingMode.CEILING);
			paymentAmount = unrounded.subtract(beforePayment, MC);
			roundingDifference = total.subtract(unrounded, MC);
		}
		result.putAll(obj("manual_lines", manualLines, "subtotal", text(materialTotal), "material_total",
				text(materialTotal), "equipment_freight_total", parsed.get("equipment_freight_total"),
				"before_payment_total", text(beforePayment), "total", text(total), "payment_amount", text(paymentAmount),
				"rounding_difference", text(roundingDifference), "pending_count", pending, "issues", issues,
				"formal_ready", issues.isEmpty(), "calculated_at", OffsetDateTime.now().toString()));
		return true;
	}

	/**
	 * 将已经预览确认的 Excel 校对结果写回当前报价修订版，并重新汇总材料、设备运费、安装费和付款条件。
	 * 回导只更新 Excel 中实际修改过的行；未修改的系统试算结果保持原样。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> applyReviewImport(Actor actor, String id, String importId,
			Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, true);
		revision(q, data);
		Map<String, Object> imported = repo.find("quote_import", importId, true);
		if (imported == null || Long.parseLong(str(imported.get("owner_id"))) != actor.id())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "校对版导入记录不存在或无权访问");
		Map<String, Object> parsed = map(imported.get("parsed"));
		if (!s(parsed, "type").equals("review_import_v1") || !s(parsed, "quote_id").equals(id))
			throw new RuleException("校对版导入记录与当前报价不匹配");
		if (n(parsed.get("base_revision")).compareTo(n(q.get("revision"))) != 0)
			conflict("报价单在校对版预览后已有修改，请重新上传校对版");
		if (n(parsed.get("change_count")).signum() == 0)
			throw new RuleException("校对版没有检测到任何人工修改");
		Map<String, Object> body = map(q.get("body")), result = map(q.get("result"));
		if (result.isEmpty())
			throw new RuleException("当前报价尚未试算，不能应用校对结果");
		// 预览记录可能由旧服务生成，应用时也重新判断一次，确保升级后无需重新导出校对版。
		resolveReviewEquipmentFreight(body, result, parsed, repo.catalogs());
		List<Map<String, Object>> resultRows = list(result.get("rows")), bodyRows = list(body.get("rows")),
				manualLines = list(parsed.get("lines"));
		Map<String, Integer> rowIndexes = new HashMap<>();
		for (int i = 0; i < bodyRows.size(); i++)
			rowIndexes.put(s(bodyRows.get(i), "row_id"), i);
		// 使用稳定的 row_id 对齐 Excel 行与系统行，避免排序或展示行号变化后改错产品。
		for (Map<String, Object> line : manualLines) {
			String rowId = s(line, "source_row_id");
			Integer index = rowIndexes.get(rowId);
			if (index == null || index >= resultRows.size() || !yes(line.get("changed")))
				continue;
			Map<String, Object> row = resultRows.get(index);
			if (yes(line.get("pending"))) {
				if (!blank(line.get("material_amount")))
					row.putAll(obj("status", "priced", "reason", "", "pricing_mode", "manual_v1", "billing_unit",
							line.get("unit"), "billing_quantity", line.get("quantity"), "unit_price",
							line.get("material_unit"), "amount", line.get("material_amount"), "body_amount",
							line.get("material_amount"), "installation_status", "pending", "installation_reason",
							"Excel校对版中的安装费用仍未填写", "installation_amount", null, "line_total", null));
				else
					row.putAll(obj("status", "pending", "reason", "Excel校对版中的材料价格仍未填写", "unit_price", null,
							"amount", null));
			}
			else if (yes(line.get("price_changed")) || yes(line.get("was_pending"))) {
				BigDecimal materialAmount = blank(line.get("material_amount")) ? ZERO : n(line.get("material_amount")),
						installationAmount = blank(line.get("installation_amount")) ? ZERO
								: n(line.get("installation_amount")),
						lineAmount = add(materialAmount, installationAmount);
				row.putAll(obj("status", "priced", "reason", "", "pricing_mode", "manual_v1", "billing_unit",
						line.get("unit"), "billing_quantity", line.get("quantity"), "unit_price",
						line.get("material_unit"), "amount", text(materialAmount), "body_amount", text(materialAmount),
						"installation_status", "priced", "installation_amount", text(installationAmount), "line_total",
						text(lineAmount), "detail",
						obj("manual_review", true, "source", imported.get("filename")), "trace",
						List.of(obj("step", "人工校对", "expression", "以回导Excel中的报价为准", "value",
								text(lineAmount)))));
			}
		}
		applyReviewFreightAdjustments(body, result, manualLines);
		int pending = n(parsed.get("pending_count")).intValue();
		Map<String, Object> policy = map(settings(actor).get("policy"));
		List<String> issues = new ArrayList<>();
		if (pending > 0)
			issues.add("有" + pending + "项Excel校对价格仍待确认");
		if (freightIncluded(body) == null)
			issues.add("请统一选择整单是否含运费");
		if (map(result.get("payment")).isEmpty())
			issues.add("请选择整单付款方式");
		if (!yes(policy.get("formal_template_confirmed")))
			issues.add("正式客户模板尚未确认");
		var freightResolution = map(parsed.get("equipment_freight_resolution"));
		var effectiveFreight = freightResolution.isEmpty()
				? map(map(result.get("fees")).get("equipment_freight")) : freightResolution;
		if (s(effectiveFreight, "status").equals("pending")
				&& (manualLines.stream().noneMatch(line -> s(line, "process").equals("设备运费"))
						|| !freightResolution.isEmpty()))
			issues.add("设备运费：" + s(effectiveFreight, "reason"));
		boolean equipmentFreightIncluded = yes(parsed.get("equipment_freight_included"));
		BigDecimal materialTotal = n(parsed.get("material_total")),
				equipmentFreightTotal = n(parsed.getOrDefault("equipment_freight_total", "0")),
				installationTotal = n(parsed.get("installation_total")),
				beforePayment = add(materialTotal, equipmentFreightIncluded ? ZERO : equipmentFreightTotal,
						installationTotal),
				total = null, paymentAmount = null, roundingDifference = null;
		if (issues.isEmpty()) {
			BigDecimal unrounded = mul(beforePayment, n(map(result.get("payment")).get("coefficient")));
			total = unrounded.setScale(0, RoundingMode.CEILING);
			paymentAmount = unrounded.subtract(beforePayment, MC);
			roundingDifference = total.subtract(unrounded, MC);
		}
		Map<String, Object> installationFee = map(map(result.get("fees")).get("installation"));
		boolean installationIncluded = InstallationPricingEngine.enabled(body);
		boolean installationPending = installationIncluded && manualLines.stream()
			.anyMatch(line -> yes(line.get("pending")) && !s(line, "process").equals("设备运费"));
		installationFee.putAll(obj("amount", text(installationTotal), "status",
				installationIncluded ? (!installationPending ? "ready" : "pending") : "not_applicable", "reason",
				installationIncluded ? "安装费用已按回导校对版的每行总额汇总" : "本单不含安装"));
		var fees = map(result.get("fees"));
		var equipmentFreightFee = map(fees.get("equipment_freight"));
		boolean hasEquipmentFreightLine = manualLines.stream().anyMatch(line -> s(line, "process").equals("设备运费"));
		if (!freightResolution.isEmpty()) {
			equipmentFreightFee.putAll(freightResolution);
			fees.put("equipment_freight", equipmentFreightFee);
		}
		else if (hasEquipmentFreightLine) {
			boolean freightPending = manualLines.stream()
				.anyMatch(line -> s(line, "process").equals("设备运费") && yes(line.get("pending")));
			equipmentFreightFee.putAll(obj("amount", freightPending ? null : text(equipmentFreightTotal), "status",
					freightPending ? "pending" : "ready", "reason",
					freightPending ? "Excel校对版中的整单设备运费仍未填写"
							: equipmentFreightIncluded ? "设备运费已计入校对版设备单价" : "设备运费已按回导校对版确认并单独列示",
					"basis", "manual_review", "included_in_rows", equipmentFreightIncluded));
			fees.put("equipment_freight", equipmentFreightFee);
			if (!freightPending && !equipmentFreightIncluded && body.containsKey("equipment_options"))
				map(body.get("equipment_options")).put("freight_amount", text(equipmentFreightTotal));
		}
		Map<String, Object> review = obj("import_id", importId, "filename", imported.get("filename"), "imported_at",
				OffsetDateTime.now().toString(), "actor", actor.name(), "change_count", parsed.get("change_count"));
		result.putAll(obj("rows", resultRows, "manual_lines", manualLines, "subtotal", text(materialTotal),
				"material_total", text(materialTotal), "total", text(total), "pending_count", pending, "issues", issues,
				"equipment_freight_total", text(equipmentFreightTotal), "formal_ready", issues.isEmpty(),
				"before_payment_total", text(beforePayment), "payment_amount",
				text(paymentAmount), "rounding_difference", text(roundingDifference), "manual_review_import", review,
				"policy", policy, "calculated_at",
				OffsetDateTime.now().toString(), "template_sha256", DuctExportService.templateHash()));
		repo.update("UPDATE quote_order SET body=?,result=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				repo.json(body), repo.json(result), id);
		repo.audit(actor, "apply_review_import", id,
				obj("import_id", importId, "change_count", parsed.get("change_count"), "pending_count", pending, "subtotal",
						parsed.get("subtotal")));
		return obj("result", result, "revision", n(q.get("revision")).intValue() + 1);
	}

	private static String cleanFilename(String filename) {
		if (filename == null || filename.length() > 255)
			throw new RuleException("文件名无效");
		String clean = filename.replace('\\', '/');
		return clean.substring(clean.lastIndexOf('/') + 1);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> action(Actor actor, String id, String action, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, false);
		revision(q, data);
		String state = s(q, "state");
		boolean exported = repo.scalar("SELECT COUNT(*) FROM quote_export WHERE quote_id=?", Integer.class, id) > 0;
		Map<String, Object> body = map(q.get("body"));
		switch (action) {
			case "approve-ai" -> {
				if (!state.equals("draft") || !s(body, "ai_review_status").equals("pending"))
					conflict("只有待核对的AI草稿可以确认");
				if (!yes(data.get("review_confirmed")) || list(body.get("rows")).isEmpty())
					throw new RuleException("请先核对所有清单行，再确认AI草稿");
				for (var special : list(body.get("source_special_rows"))) {
					var issues = map(map(special.get("original")).get("model_field_issues"));
					if (Set.of("corner_count", "clip_meters", "flange_30_meters", "flange_40_meters").contains(s(special, "kind"))
							&& (issues.containsKey("quantity") || issues.containsKey("engineering_quantity"))) {
						String label = "第" + special.get("source_row") + "行辅材数量/米数";
						Object quantity = map(body.get("duct_options")).get(s(special, "kind"));
						if (blank(quantity)) throw new RuleException(label + "待核实，请补齐后再确认AI草稿");
						positive(quantity, label);
					}
				}
				body.putAll(obj("ai_review_status", "approved", "ai_reviewed_by", actor.id(),
						"ai_reviewed_at", OffsetDateTime.now().toString()));
				repo.update("UPDATE quote_order SET body=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
						repo.json(body), id);
			}
			case "auto-classify" -> {
				// 自动识别只补充产品分类和初始化整单选项，不在此处执行价格计算。
				if (!state.equals("draft"))
					conflict("只有草稿可以重新识别，请先创建修订版");
				String oldMode = s(body, "pricing_mode");
				Boolean freight = freightIncluded(body);
				boolean included = InstallationPricingEngine.enabled(body);
				boolean hadDuct = body.containsKey("duct_options") && !oldMode.equals("equipment_v1");
				boolean hadEquipment = body.containsKey("equipment_options");
				body.putIfAbsent("duct_options", ductDefaults());
				body.putIfAbsent("equipment_options", equipmentDefaults());
				var duct = map(body.get("duct_options"));
				var equipment = map(body.get("equipment_options"));
				for (String key : List.of("tax_included", "payment_option")) {
					if (!hadEquipment && hadDuct && duct.containsKey(key))
						equipment.put(key, duct.get(key));
					if (!hadDuct && hadEquipment && equipment.containsKey(key))
						duct.put(key, equipment.get(key));
				}
				body.putIfAbsent("installation_options", obj("included", included, "region", "", "include_auxiliaries",
						false, "include_site_fee", false, "height", "", "cross_work", false, "cross_work_fee_decision", "",
						"site_amount", "", "site_note", "", "site_waive_reason", ""));
				body.putIfAbsent("installation_items", new ArrayList<>());
				body.putAll(obj("pricing_mode", "mixed_v1", "recognition_mode", "auto_v1"));
				if (!blank(body.get("ai_review_status")))
					body.put("ai_review_status", "pending");
				applyFreight(body, freight);
				int ducts = 0, equipmentRows = 0, pending = 0;
				for (var row : list(body.get("rows"))) {
					DuctImportService.autoClassifyRow(row);
					if (s(row, "category").equals("风管"))
						ducts++;
					else if (!blank(row.get("category")))
						equipmentRows++;
					else
						pending++;
				}
				var counts = obj("duct", ducts, "equipment", equipmentRows, "pending", pending);
				repo.update(
						"UPDATE quote_order SET body=?,result=NULL,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
						repo.json(body), id);
				repo.audit(actor, "auto_classify_quote", id, obj("from_mode", oldMode, "counts", counts));
				return obj("ok", true, "revision", n(q.get("revision")).intValue() + 1, "counts", counts);
			}
			case "delete" -> {
				if (!state.equals("draft") || exported)
					conflict("只有未导出的草稿可删除；已有导出记录请归档");
				repo.update("DELETE FROM quote_order WHERE id=?", id);
				if (!blank(body.get("ai_review_status"))
						&& repo.scalar("SELECT COUNT(*) FROM quote_order WHERE family=?", Integer.class, q.get("family")) == 0)
					repo.update("DELETE FROM quote_import WHERE id=?", s(body, "import_id"));
			}
			case "archive" -> {
				if (state.equals("archived") || !exported)
					conflict("只有已导出报价可以归档");
				body.put("_archived_from_state", state);
				repo.update(
						"UPDATE quote_order SET state='archived',body=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
						repo.json(body), id);
			}
			case "restore" -> {
				String previous = s(body, "_archived_from_state");
				if (!state.equals("archived") || !Set.of("draft", "final").contains(previous))
					conflict("报价归档状态无效");
				body.remove("_archived_from_state");
				repo.update(
						"UPDATE quote_order SET state=?,body=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
						previous, repo.json(body), id);
			}
			case "revise" -> {
				if (state.equals("archived"))
					conflict("归档报价请先恢复，再创建修订版");
				// A persistent lock also works if an unexported family root was deleted.
				repo.catalogLock();
				int version = repo.scalar("SELECT MAX(version) FROM quote_order WHERE family=?", Integer.class,
						q.get("family")) + 1;
				String next = uuid();
				if (!blank(body.get("ai_review_status")))
					body.put("ai_review_status", "pending");
				repo.update(
						"INSERT INTO quote_order(id,family,version,owner_id,body,state,revision,created_at,updated_at) VALUES(?,?,?,?,?,'draft',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
						next, q.get("family"), version, q.get("owner_id"), repo.json(body));
				repo.audit(actor, "revise_quote", next, obj("from", id));
				return obj("id", next);
			}
			default -> throw new RuleException("不支持的报价操作");
		}
		repo.audit(actor, action + "_quote", id, null);
		return obj("ok", true);
	}

	/**
	 * 导出校对版或正式报价。正式导出前会再次核对规则版本、当前生效指标和模板哈希，防止输出过期价格。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> export(Actor actor, String id, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, false);
		revision(q, data);
		if (s(q, "state").equals("archived"))
			conflict("归档报价不可重新导出，请先恢复");
		String kind = s(data, "kind");
		if (!Set.of("draft", "formal").contains(kind))
			throw new RuleException("请选择校对版或正式导出");
		if (kind.equals("formal") && freightUnconfirmed(q))
			conflict("请统一选择整单是否含运费并重新试算后生成正式报价");
		Map<String, Object> result = map(q.get("result"));
		if (result.isEmpty())
			throw new RuleException("请先保存并试算");
		if (stale(map(q.get("body")), result))
			conflict("运算规则已更新，请重新试算后导出");
		if (kind.equals("formal") && repairImportedReviewFreight(map(q.get("body")), result, repo.catalogs())) {
			repo.update("UPDATE quote_order SET result=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", repo.json(result), id);
			repo.audit(actor, "repair_review_equipment_freight", id,
					obj("amount", result.get("equipment_freight_total"), "pending_count", result.get("pending_count")));
		}
		if (kind.equals("formal") && !yes(result.get("formal_ready")))
			throw new RuleException("正式报价条件未满足："
					+ String.join("；", ((List<?>) result.get("issues")).stream().map(QuoteData::str).toList()));
		if (kind.equals("formal")) {
			List<Map<String, Object>> existing = repo.query(
					"SELECT id,filename FROM quote_export WHERE quote_id=? AND kind='formal' ORDER BY created_at", id);
			if (!existing.isEmpty())
				return existing.get(0);
			repo.catalogLock();
			Map<String, Object> policy = repo.find("quote_setting", "policy", true);
			if (policy == null || !yes(map(policy.get("body")).get("formal_template_confirmed")))
				conflict("负责人已暂停正式模板导出，草稿和历史下载仍可使用");
			var resources = repo.catalogs();
			if (productRulesChanged(map(q.get("body")), result, resources))
				conflict("产品别名或组合规则已变更、停用或过期，请重新试算后生成正式报价");
			boolean hasEquipment = list(result.get("rows")).stream()
				.anyMatch(r -> s(r, "status").equals("priced") && s(r, "pricing_mode").equals("equipment_v1"));
			boolean hasDuct = list(result.get("rows")).stream()
				.anyMatch(r -> s(r, "status").equals("priced")
						&& !Set.of("equipment_v1", "manual_v1").contains(s(r, "pricing_mode")));
			if (hasDuct) {
				var active = DuctCatalog.active(resources, LocalDate.now());
				var duct = s(map(q.get("body")), "pricing_mode").equals("mixed_v1") ? map(result.get("duct_result"))
						: result;
				if (active.size() != 1 || !s(active.get(0), "id").equals(s(map(duct.get("duct_catalog")), "id")))
					conflict("生效指标已变更或停用，请重新试算后生成正式报价");
			}
			if (hasEquipment) {
				var active = EquipmentCatalog.active(resources, LocalDate.now());
				if (active.size() != 1 || list(result.get("rows")).stream()
					.filter(r -> s(r, "status").equals("priced") && s(r, "pricing_mode").equals("equipment_v1"))
					.anyMatch(r -> !s(map(r.get("rule")), "id").equals(s(active.get(0), "id"))))
					conflict("设备指标已变更或停用，请重新试算后生成正式报价");
			}
			if (InstallationPricingEngine.enabled(map(q.get("body")))) {
				var active = InstallationCatalog.active(resources, LocalDate.now());
				var used = map(map(map(result.get("fees")).get("installation")).get("catalog"));
				if (active.size() != 1 || !s(active.get(0), "id").equals(s(used, "id")))
					conflict("安装指标已变更或停用，请重新试算后生成正式报价");
			}
			if (!s(result, "template_sha256").equals(DuctExportService.templateHash()))
				conflict("正式模板已更新，请重新试算");
		}
		byte[] content = exporter.write(map(q.get("body")), result,
				s(q, "family").substring(0, 8) + "-V" + q.get("version"), kind.equals("formal"),
				obj("quote_id", id, "revision", q.get("revision"), "family", q.get("family"), "version",
						q.get("version")));
		String exportId = uuid(), filename = (kind.equals("formal") ? "正式报价" : "报价校对版") + "_" + id.substring(0, 8) + "_V"
				+ q.get("version") + "_" + exportId.substring(0, 6) + ".xlsx";
		repo.update(
				"INSERT INTO quote_export(id,quote_id,kind,filename,content,template_version,template_sha256,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
				exportId, id, kind, filename, content, DuctExportService.TEMPLATE_VERSION,
				DuctExportService.templateHash());
		if (kind.equals("formal"))
			repo.update(
					"UPDATE quote_order SET state='final',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
					id);
		repo.audit(actor, "export_quote", id, obj("kind", kind, "export_id", exportId));
		return obj("id", exportId, "filename", filename);
	}

	public Map<String, Object> download(Actor actor, String id) {
		Map<String, Object> item = repo.find("quote_export", id, false);
		if (item == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
		quote(s(item, "quote_id"), actor, false, false);
		return item;
	}

	public List<Map<String, Object>> audit(Actor actor) {
		leader(actor);
		return repo
			.query("SELECT id,actor_name,action,target,created_at FROM quote_audit ORDER BY created_at DESC LIMIT 200");
	}

}
