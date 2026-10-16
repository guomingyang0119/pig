package com.pig4cloud.pig.admin.quotation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 报价单业务流程编排服务。
 * <p>
 * 本类负责报价单的保存、试算、校对版回导、状态流转和导出校验；具体金额公式分别由风管、设备和安装运算引擎负责。
 * 所有会改变报价结果的操作都在这里统一做版本校验和审计记录，避免旧页面或旧导出文件覆盖新数据。
 */
@Service
public class QuoteService {

	/** 当前报价操作者：账号ID用于归属，姓名用于审计，manager表示报价负责人权限。 */
	public record Actor(long id, String name, boolean manager) {
	}

	private final QuoteRepository repo;

	private final DuctPricingEngine engine = new DuctPricingEngine();

	private final EquipmentPricingEngine equipment = new EquipmentPricingEngine();

	/**
	 * 判断是否需要设备报价选项；自动识别模式在保存时使用复合报价的内部模式。
	 */
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

	/**
	 * 把唯一整单运费选择同步给各引擎的旧选项；null保留待确认状态。
	 */
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

	/**
	 * 仅草稿需要重新确认运费选择；历史定稿或归档报价保留当时的业务快照。
	 */
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
		QuoteRegion.requireConfirmed(body);
		QuoteRegion.sync(body);
		Boolean freight = freightIncluded(body);
		if (freight == null)
			throw new RuleException("请统一选择整单是否含运费后重新试算");
		// 先解析生效的产品别名/组合规则，再让原生计价引擎处理实际产品和结算。
		applyFreight(body, freight);
		var rulePreparation = ProductRuleCatalog.preparation(resources, LocalDate.now());
		var rows = list(body.get("rows")).stream()
			.map(rulePreparation::prepare)
			.toList();
		enableProductRuleMode(body, rows);
		QuoteRegion.sync(body);
		Map<String, Object> prepared = repo.object(repo.json(body));
		prepared.put("rows", rows);
		Map<String, Object> result = switch (s(body, "pricing_mode")) {
			case "equipment_v1" -> equipment.calculate(prepared, resources, policy);
			case "mixed_v1" -> equipment.mixed(prepared, resources, policy);
			default -> engine.calculate(prepared, resources, policy);
		};
		var resultRows = list(result.get("rows"));
		for (int i = 0; i < rows.size(); i++)
			if (rows.get(i).containsKey("_product_rule_resource"))
				resultRows.get(i).put("product_rule", rows.get(i).get("_product_rule_resource"));
		result.put("product_rule_bindings", productRuleBindings(rows));
		result.put("product_rule_catalog_ids", QuoteListSummary.activeRuleIds(resources));
		var accessoryIssues = AccessoryImportReview.issues(map(body.get("duct_options")));
		if (!accessoryIssues.isEmpty()) {
			var issues = new ArrayList<>((List<String>) result.get("issues"));
			for (String issue : accessoryIssues) if (!issues.contains(issue)) issues.add(issue);
			result.putAll(obj("issues", issues, "formal_ready", false, "total", null));
		}
		result.put("product_rule_engine_version", ProductRuleCatalog.VERSION);
		resultRows.forEach(QuoteProblem::present);
		result.put("catalog_bindings", catalogBindings(prepared, result, resources));
		return result;
	}

	/** 连未匹配成功的行也记录参与查价的目录；补价、发布或停用后必须重算。 */
	private List<Map<String, Object>> catalogBindings(Map<String, Object> body, Map<String, Object> result,
			List<Map<String, Object>> resources) {
		Set<String> kinds = new LinkedHashSet<>();
		var rows = list(result.get("rows"));
		for (int i = 0; i < rows.size(); i++) {
			var row = rows.get(i);
			if (Set.of("excluded", "installation_only").contains(s(row, "status")) || s(row, "pricing_mode").equals("manual_v1")) continue;
			var input = i < list(body.get("rows")).size() ? list(body.get("rows")).get(i) : obj();
			kinds.add(s(row, "pricing_mode").equals("equipment_v1") || !s(body, "pricing_mode").equals("duct_v1") && !s(input, "category").equals("风管") ? "equipment_catalog" : "duct_catalog");
		}
		if (InstallationPricingEngine.enabled(body)) kinds.add("installation_catalog");
		List<Map<String, Object>> bindings = new ArrayList<>();
		for (String kind : kinds) bindings.add(obj("kind", kind, "ids", activeCatalogIds(kind, resources)));
		return bindings;
	}

	private List<String> activeCatalogIds(String kind, List<Map<String, Object>> resources) {
		var active = switch (kind) {
			case "duct_catalog" -> DuctCatalog.active(resources, LocalDate.now());
			case "installation_catalog" -> InstallationCatalog.active(resources, LocalDate.now());
			default -> EquipmentCatalog.active(resources, LocalDate.now());
		};
		return active.stream().map(r -> s(r, "id")).sorted().toList();
	}

	private boolean catalogsChanged(Map<String, Object> result, List<Map<String, Object>> resources) {
		return list(result.get("catalog_bindings")).stream().anyMatch(binding ->
				!binding.get("ids").equals(activeCatalogIds(s(binding, "kind"), resources)));
	}

	/**
	 * 风管草稿中出现非风管产品规则时启用复合计算，并继承税费、付款和运费选择。
	 */
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

	/**
	 * 提取实际绑定的规则版本和问题，保存到试算结果中作为正式导出复核依据。
	 */
	private static List<Map<String, Object>> productRuleBindings(List<Map<String, Object>> rows) {
		return list(ProductRuleCatalog.bindings(rows)).stream()
			.filter(binding -> !yes(rows.get(n(binding.get("row_index")).intValue()).get("excluded")))
			.filter(binding -> !map(binding.get("resource")).isEmpty() || !blank(binding.get("issue")))
			.toList();
	}

	/**
	 * 用今天的生效规则重新匹配清单，与上次试算绑定比较。
	 * 别名、组合规则或生效状态变化时，草稿结果需要重新试算。
	 */
	private boolean productRulesChanged(Map<String, Object> body, Map<String, Object> result,
			List<Map<String, Object>> resources) {
		if (result.isEmpty())
			return false;
		var rulePreparation = ProductRuleCatalog.preparation(resources, LocalDate.now());
		var rows = list(body.get("rows")).stream()
			.map(rulePreparation::prepare)
			.toList();
		return !repo.json(result.getOrDefault("product_rule_bindings", List.of()))
			.equals(repo.json(productRuleBindings(rows)));
	}

	private final DuctImportService importer = new DuctImportService();

	private final DuctExportService exporter = new DuctExportService();

	private final Set<String> generatingExports = ConcurrentHashMap.newKeySet();

	private final QuoteReviewImportService reviewImporter = new QuoteReviewImportService();
	private final AiQuoteReviewImportService aiReviewImporter = new AiQuoteReviewImportService();

	public QuoteService(QuoteRepository repo) {
		this.repo = repo;
	}

	/**
	 * 以409表示记录状态或版本冲突，提示页面刷新或建立修订版。
	 */
	private static void conflict(String message) {
		throw new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

	/**
	 * 限制指标发布、策略修改及审计查看等负责人操作。
	 */
	private static void leader(Actor actor) {
		if (!actor.manager())
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有报价负责人可以执行此操作");
	}

	/**
	 * 只接收长度受限的文本，防止错误JSON类型进入客户信息、条件或产品参数。
	 */
	private static String textValue(Object value, int max) {
		if (value == null)
			return "";
		if (!(value instanceof String) || str(value).length() > max)
			throw new RuleException("文本类型不正确或超过长度限制");
		return str(value);
	}

	/**
	 * 比较客户端修订号与锁定记录，拒绝旧页面和旧文件覆盖新版本。
	 */
	private static void revision(Map<String, Object> row, Map<String, Object> data) {
		if (data.get("revision") == null || n(data.get("revision")).compareTo(n(row.get("revision"))) != 0)
			conflict("记录已在另一页面修改，请刷新后重试");
	}

	/**
	 * 读取并核对报价归属；负责人可查看所有报价，普通报价员仅能访问自己的记录。
	 * 需编辑时要求草稿状态，需写入时由调用方开启事务并请求行锁。
	 */
	private Map<String, Object> quote(String id, Actor actor, boolean lock, boolean editable) {
		Map<String, Object> q = repo.find("quote_order", id, lock);
		if (q == null || (!actor.manager() && Long.parseLong(str(q.get("owner_id"))) != actor.id()))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报价不存在或无权访问");
		if (editable && !s(q, "state").equals("draft"))
			conflict("报价已锁定；归档请先恢复，正式报价请创建修订版");
		var body = map(q.get("body"));
		if (s(q, "state").equals("draft") && s(body, "ai_review_status").equals("pending"))
			list(body.get("rows")).forEach(EquipmentReview::recheckAutomaticElectricClassification);
		if (s(q, "state").equals("draft") && !map(body.get("ai_reused_from")).isEmpty())
			for (var row : list(body.get("rows"))) {
				var attrs = map(row.get("attributes"));
				String previousIssue = s(EquipmentReview.flatAttributes(row), "recognition_issue");
				EquipmentReview.sourceReason(row);
				if (!previousIssue.equals(s(EquipmentReview.flatAttributes(row), "recognition_issue"))) {
					// 只刷新已解决提醒的显示结果；读取不写库，也不改人工业务参数或定稿快照。
					attrs.remove("review_reason");
					String reason = EquipmentReview.reason(row, attrs);
					if (!reason.isBlank()) attrs.put("review_reason", reason);
				}
			}
		return q;
	}

	/**
	 * 锁定当前指标版本记录，供修订号校验和状态变更使用。
	 */
	private Map<String, Object> catalog(String id) {
		Map<String, Object> r = repo.find("quote_catalog", id, true);
		if (r == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在");
		return r;
	}

	/**
	 * 返回全部报价指标快照，计价时再按类型、状态、日期与版本选择。
	 */
	public List<Map<String, Object>> resources() {
		return repo.catalogs();
	}

	/** 对外资源读取遵守产品规则草稿的归属范围；引擎内部仍读取完整指标。 */
	public List<Map<String, Object>> resources(Actor actor) {
		return repo.catalogs().stream().filter(r -> !s(r, "kind").equals("product_rule")
				|| actor.manager() || !s(r, "status").equals("draft")
				|| Long.parseLong(str(r.get("created_by"))) == actor.id()).toList();
	}

	/**
	 * 普通报价员可见已发布/停用规则及自己的草稿，负责人可见全部规则。
	 */
	public List<Map<String, Object>> productRules(Actor actor) {
		return repo.catalogs().stream().filter(r -> s(r, "kind").equals("product_rule"))
			.filter(r -> actor.manager() || !s(r, "status").equals("draft")
					|| Long.parseLong(str(r.get("created_by"))) == actor.id())
			.toList();
	}

	/**
	 * 根据当前产品行推荐规则和编辑模板，并返回操作者是否可发布。
	 */
	public Map<String, Object> productRuleRecommendations(Actor actor, Map<String, Object> data) {
		data = repo.object(repo.json(data));
		QuoteRegion.sync(data);
		var resources = repo.catalogs();
		var row = repo.object(repo.json(map(data.get("row"))));
		var definition = map(data.get("body"));
		if (!definition.isEmpty()) {
			row.put("product_rule", definition);
			row.remove("product_rule_family");
		}
		var prepared = ProductRuleCatalog.prepareRow(row, resources, LocalDate.now());
		if (definition.isEmpty()) definition = map(map(prepared.get("_product_rule_resource")).get("body"));
		var catalogs = EquipmentCatalog.active(resources, LocalDate.now());
		var installationContext = obj("rows", List.of(prepared), "installation_options", map(data.get("installation_options")),
				"equipment_options", map(data.get("equipment_options")), "duct_options", map(data.get("duct_options")));
		installationContext.put("_installation_context_rows", productRuleInstallationContext(data, prepared, resources));
		var templates = ProductDefinitions.templates(resources, LocalDate.now());
		return obj("templates", templates, "recommendations",
				ProductRuleCatalog.recommendations(row, resources, LocalDate.now()),
				"catalog_rates", ProductRuleCatalog.catalogRates(), "can_publish", actor.manager(), "fan_input",
				FanInputGuidance.describe(row, prepared, resources, LocalDate.now(), null), "parameter_input",
				ProductInputGuidance.describe(row, definition, AcousticRegionalCost.withRegion(catalogs.size() == 1 ? map(catalogs.get(0).get("body")) : obj(), map(data.get("equipment_options"))), map(data.get("duct_options"))),
				"installation_input", ProductRuleInstallation.input(row, prepared, installationContext, resources, LocalDate.now(), null));
	}

	/** 产品规则试算不保存指标或报价，金额沿用实际报价的税费、运费、安装和付款结算流程。 */
	public Map<String, Object> trialProductRule(Actor actor, Map<String, Object> data) {
		data = repo.object(repo.json(data));
		QuoteRegion.sync(data);
		Map<String, Object> definition = map(data.get("body"));
		ProductRuleCatalog.validate(definition);
		var row = repo.object(repo.json(map(data.get("row"))));
		row.put("product_rule", definition);
		row.remove("product_rule_family");
		var resources = repo.catalogs();
		var prepared = ProductRuleCatalog.prepareRow(row, resources, LocalDate.now());
		var guidance = FanInputGuidance.describe(row, prepared, resources, LocalDate.now(), null);
		var catalogs = EquipmentCatalog.active(resources, LocalDate.now());
		var inputs = ProductInputGuidance.describe(row, definition, AcousticRegionalCost.withRegion(catalogs.size() == 1 ? map(catalogs.get(0).get("body")) : obj(), map(data.get("equipment_options"))), map(data.get("duct_options")));
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
		var installationOptions = repo.object(repo.json(map(data.get("installation_options"))));
		installationOptions.putAll(obj("included", installationIncluded, "include_site_fee", false, "site_amount", "0", "site_note", ""));
		// 现场附加费属于整单，不分摊到单行试算；其余地区、辅材、标高和施工条件完整保留。
		if (yes(installationOptions.get("cross_work")))
			installationOptions.putAll(obj("cross_work_fee_decision", "waive", "site_waive_reason", "单行试算不计整单现场附加费，整单另行核对"));
		var body = obj("pricing_mode", "mixed_v1", "rows", List.of(row), "equipment_options", options,
				"duct_options", duct, "include_freight", data.getOrDefault("include_freight", false),
				"installation_options", installationOptions, "installation_items", List.of());
		QuoteRegion.readInput(data, body);
		QuoteRegion.requireConfirmed(body);
		QuoteRegion.sync(body);
		body.put("_installation_context_rows", productRuleInstallationContext(data, prepared, resources));
		if (guidance != null && Set.of("unknown", "conflict").contains(s(guidance, "speed_mode"))) {
			var result = obj("index", 1, "status", "pending", "name", row.get("name"), "reason",
					guidance.get("reason"), "problem", guidance.get("problem"), "fan_input", guidance, "parameter_input", inputs, "trace", List.of());
			var installationBody = new LinkedHashMap<>(body);
			installationBody.put("rows", List.of(prepared));
			var installation = InstallationPricingEngine.calculate(installationBody, resources, List.of(result), LocalDate.now());
			var lines = list(installation.fee().get("lines"));
			var line = lines.isEmpty() ? obj("status", installation.fee().get("status"), "reason", installation.fee().get("reason")) : lines.get(0);
			result.putAll(obj("installation_status", line.getOrDefault("status", installation.fee().get("status")),
					"installation_reason", line.getOrDefault("reason", installation.fee().get("reason")),
					"installation_amount", line.get("quoted_amount"), "installation_detail", line,
					"installation_input", ProductRuleInstallation.input(row, prepared, installationBody, resources, LocalDate.now(), line)));
			return obj("result", result, "installation_fee", installation.fee());
		}
		var calculation = compute(body, resources, map(settings(actor).get("policy")));
		var result = list(calculation.get("rows")).get(0);
		if (guidance != null)
			result.put("fan_input", FanInputGuidance.describe(row, prepared, resources, LocalDate.now(), result));
		result.put("parameter_input", inputs);
		var installationFee = map(map(calculation.get("fees")).get("installation"));
		if (installationIncluded && s(installationFee, "status").equals("pending") && blank(result.get("installation_status")))
			result.putAll(obj("installation_status", "pending", "installation_reason", installationFee.get("reason"), "installation_amount", null,
					"installation_detail", obj("status", "pending", "reason", installationFee.get("reason"), "amount", null)));
		result.put("installation_input", ProductRuleInstallation.input(row, prepared, body, resources, LocalDate.now(), map(result.get("installation_detail"))));
		return obj("result", result, "installation_fee", installationFee);
	}

	private List<Map<String, Object>> productRuleInstallationContext(Map<String, Object> data, Map<String, Object> prepared,
			List<Map<String, Object>> resources) {
		var context = new ArrayList<Map<String, Object>>();
		var rulePreparation = ProductRuleCatalog.preparation(resources, LocalDate.now());
		for (var row : list(data.get("context_rows")))
			if (blank(prepared.get("row_id")) || !s(row, "row_id").equals(s(prepared, "row_id")))
				context.add(rulePreparation.prepare(row));
		context.add(prepared);
		return context;
	}

	/**
	 * 校验产品规则并串行化创建草稿；此入口允许报价员建立待负责人发布的规则。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> createProductRule(Actor actor, Map<String, Object> body) {
		ProductRuleCatalog.validate(body);
		repo.catalogLock();
		return insertCatalog(actor, "product_rule", body);
	}

	/**
	 * 先确认目标为产品规则，再沿用指标草稿的归属、状态与版本校验。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> editProductRule(Actor actor, String id, Map<String, Object> data) {
		repo.catalogLock();
		if (!s(catalog(id), "kind").equals("product_rule"))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "产品规则不存在");
		return editCatalog(actor, id, data);
	}

	/**
	 * 将产品规则动作交给统一指标生命周期，同时保留规则专属权限约束。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> productRuleAction(Actor actor, String id, String action, Map<String, Object> data) {
		repo.catalogLock();
		if (!s(catalog(id), "kind").equals("product_rule"))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "产品规则不存在");
		return catalogAction(actor, id, action, data);
	}

	/**
	 * 通用供货/安装指标由负责人维护；报价员仅可维护自己创建的产品规则草稿。
	 * 产品规则的发布、停用与恢复生效仍必须由负责人执行。
	 */
	private static void catalogAuthority(Actor actor, Map<String, Object> resource, String action) {
		if (!s(resource, "kind").equals("product_rule")) {
			leader(actor);
			return;
		}
		if (!actor.manager() && (Long.parseLong(str(resource.get("created_by"))) != actor.id()
				|| Set.of("publish", "retire", "reactivate").contains(action)))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能维护自己的规则草稿；通用规则由报价负责人发布");
	}

	/**
	 * 读取正式模板确认及取整策略，同时返回模板哈希供页面和导出流程核对。
	 */
	public Map<String, Object> settings(Actor actor) {
		Map<String, Object> stored = repo.find("quote_setting", "policy", false);
		if (stored == null)
			throw new RuleException("请先执行报价模块初始化SQL");
		return obj("policy", stored.get("body"), "revision", stored.get("revision"), "can_manage", actor.manager(),
				"parameter_definitions", ProductDefinitions.parameters(),
				"template_version", DuctExportService.TEMPLATE_VERSION, "template_sha256",
				DuctExportService.templateHash(), "engine_version", DuctPricingEngine.VERSION);
	}

	/**
	 * 锁定策略记录并核对修订号，由负责人更改正式模板确认状态和记录审计。
	 */
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

	/**
	 * 生成当前模板版本、哈希及1位小数策略，避免客户端自行指定模板身份。
	 */
	private Map<String, Object> envPolicy(Object confirmed) {
		return obj("formal_template_confirmed", confirmed, "template_version", DuctExportService.TEMPLATE_VERSION,
				"template_sha256", DuctExportService.templateHash(), "unit_round", 1, "line_round", 1);
	}

	/**
	 * 报价负责人首次初始化风管指标草稿；已有任意风管指标时复用已有记录。
	 */
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

	/**
	 * 兼容风管指标创建入口，默认资源类型为duct_catalog。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> createCatalog(Actor actor, Map<String, Object> body) {
		return createCatalog(actor, "duct_catalog", body);
	}

	/**
	 * 读取设备指标种子并把生效日期设为今天，供负责人核对后另行保存发布。
	 */
	public Map<String, Object> equipmentSeed(Actor actor) {
		leader(actor);
		Map<String, Object> body = repo.object(new String(DuctExportService.asset("equipment-catalog-seed.json"),
				java.nio.charset.StandardCharsets.UTF_8));
		PurchasedEquipment.supplement(body, repo.object(new String(DuctExportService.asset("purchased-ventilators-20261010.json"),
				java.nio.charset.StandardCharsets.UTF_8)));
		body.put("acoustic_region_costs", acousticRegionCosts());
		body.putAll(repo.object(new String(DuctExportService.asset("fan-ranges-20261014.json"),
				java.nio.charset.StandardCharsets.UTF_8)));
		body.put("effective_from", LocalDate.now().toString());
		return body;
	}

	/** 返回独立的地区参考价格，不读取或修改报价及指标版本。 */
	public Map<String, Object> acousticRegionCosts() {
		return repo.object(new String(DuctExportService.asset("acoustic-region-costs-20261013.json"),
				java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * 读取安装指标种子并设置初始生效日期，读取操作不写入数据库。
	 */
	public Map<String, Object> installationSeed(Actor actor) {
		leader(actor);
		Map<String, Object> body = repo.object(new String(DuctExportService.asset("installation-catalog-seed.json"),
				java.nio.charset.StandardCharsets.UTF_8));
		body.put("effective_from", LocalDate.now().toString());
		return body;
	}

	/**
	 * 由负责人创建指定类型的指标草稿，类型必须属于报价模块支持范围。
	 */
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

	/**
	 * 建立新指标系列的第1版草稿，初始ID同时作为系列family，后续版本共用family。
	 */
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

	/**
	 * 校验草稿的名称和内容大小，统一公司范围；完整价格校验留给发布流程。
	 */
	private void checkCatalogDraft(Map<String, Object> body) {
		body.put("company_id", "guizhou");
		if (repo.json(body).length() > 2 * 1024 * 1024)
			throw new RuleException("指标内容过长");
		// 指标草稿允许价格等内容暂未补齐；发布或校验动作再检查完整规则。
		if (blank(body.get("name")))
			throw new RuleException("请填写指标名称");
	}

	/**
	 * 仅更新有权限的指标草稿，保存后递增revision并保留修改前正文审计。
	 */
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

	/**
	 * 管理指标的校验、发布、停用、恢复生效、修订和草稿删除。
	 * 同系列新版本复用已有草稿；发布时检查完整规则和跨系列日期冲突。
	 */
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
			// 发布前检查不同系列的生效区间，包括未来日期，避免同类指标或冲突产品规则同时有效。
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

	/**
	 * 解析客户Excel并保存带操作者归属的工作表快照；文件名仅保留末级名称。
	 */
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

	/** 在同一事务中保存AI标准清单、来源资料及未计价草稿，任一步失败则整体回滚。 */
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
			for (String key : List.of("source_file", "source_sheet", "source_sheet_index", "source_row",
					"source_locator", "source_excerpt", "source_issue", "source_issue_reason"))
				if (raw.containsKey(key)) source.put(key, raw.get(key));
			if (!map(raw.get("model_field_issues")).isEmpty())
				source.put("model_field_issues", raw.get("model_field_issues"));
			if (!AiConfidencePolicy.metadata(raw).isEmpty())
				source.put("ai_confidence", AiConfidencePolicy.metadata(raw));
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
		for (Map<String, Object> row : list(body.get("rows"))) {
			map(row.get("attributes")).remove("review_reason");
			int index = n(row.get("source_row")).intValue() - AiQuoteImportService.DETAIL_START_ROW;
			if (index >= 0 && index < normalizedRows.size()) {
				var raw = normalizedRows.get(index);
				if (blank(raw.get("quantity")) && !blank(raw.get("engineering_quantity"))
						&& AiInputReview.validNumber(s(row, "quantity")) && AiInputReview.validNumber(s(raw, "engineering_quantity"))
						&& n(row.get("quantity")).compareTo(n(raw.get("engineering_quantity"))) == 0
						&& (AiConfidencePolicy.proven(raw, "engineering_quantity", "source") || AiConfidencePolicy.proven(raw, "engineering_quantity", "rule")))
					AiConfidencePolicy.prove(row, "quantity", s(row, "quantity"), "rule", obj("rule", "builtin:engineering-quantity-fallback-v1", "source_field", "engineering_quantity"));
				if (s(row, "category").equals("风管") && Set.of("㎡", "m2", "m²", "平方米", "平方").contains(s(row, "unit"))
						&& blank(raw.get("engineering_quantity")) && AiInputReview.validNumber(s(raw, "quantity"))
						&& AiInputReview.validNumber(s(row, "engineering_quantity"))
						&& n(raw.get("quantity")).compareTo(n(row.get("engineering_quantity"))) == 0
						&& (AiConfidencePolicy.proven(raw, "quantity", "source") || AiConfidencePolicy.proven(raw, "quantity", "rule")))
					AiConfidencePolicy.prove(row, "engineering_quantity", s(row, "engineering_quantity"), "rule", obj("rule", "builtin:area-quantity-fallback-v1", "source_field", "quantity"));
			}
		}
		reuseConfirmedAiInputs(actor, quoteId, body, sources);
		body.putAll(obj("ai_review_status", "pending", "ai_source_files", sourceFiles,
				"ai_generated_at", OffsetDateTime.now().toString()));
		repo.updateOrder(quoteId, obj("body", body), false, false);
		repo.audit(actor, "create_ai_quote", quoteId, obj("import_id", importId, "row_count", normalizedRows.size(),
				"source_files", sourceFiles));
		return obj("id", quoteId, "row_count", normalizedRows.size(), "product_row_count", list(body.get("rows")).size(),
				"special_row_count", list(body.get("source_special_rows")).size());
	}

	/** 相同原件只复用本人的已核验输入，新草稿仍需核验并按当前规则重新试算。 */
	private void reuseConfirmedAiInputs(Actor actor, String quoteId, Map<String, Object> body,
			List<Map<String, Object>> sources) {
		String fingerprint = aiSourceFingerprint(sources);
		if (fingerprint.isEmpty()) return;
		body.put("ai_source_fingerprint", fingerprint);
		for (var stored : repo.query("SELECT * FROM quote_order WHERE owner_id=? AND state<>'archived' ORDER BY updated_at DESC,created_at DESC", actor.id())) {
			var previousQuote = repo.decode(stored);
			var previous = map(previousQuote.get("body"));
			if (s(previousQuote, "id").equals(quoteId) || !s(previous, "ai_review_status").equals("approved")) continue;
			var imported = repo.find("quote_import", s(previous, "import_id"), false);
			if (imported == null) continue;
			var previousSources = list(map(imported.get("parsed")).get("ai_sources"));
			if (!fingerprint.equals(aiSourceFingerprint(previousSources))) continue;
			var oldFiles = aiSourceHashes(previousSources);
			var newFiles = aiSourceHashes(sources);
			Map<String, Map<String, Object>> confirmed = new HashMap<>();
			Set<String> ambiguous = new HashSet<>();
			for (var row : list(previous.get("rows"))) {
				String key = aiSourceRowKey(row, oldFiles);
				if (!key.isEmpty() && confirmed.putIfAbsent(key, row) != null) ambiguous.add(key);
			}
			int reused = 0;
			Set<String> newKeys = new HashSet<>();
			for (var row : list(body.get("rows"))) {
				String key = aiSourceRowKey(row, newFiles);
				if (!newKeys.add(key)) ambiguous.add(key);
			}
			for (var row : list(body.get("rows"))) {
				String key = aiSourceRowKey(row, newFiles);
				var old = confirmed.get(key);
				if (old == null || ambiguous.contains(key)) continue;
				var freshRow = new LinkedHashMap<>(row);
				var freshAttributes = new LinkedHashMap<>(map(row.get("attributes")));
				String sourceKind = EquipmentPricingEngine.classifySource(row);
				for (String field : List.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark",
						"category", "candidate_id", "excluded", "installation_only", "exclude_reason", "product_rule", "product_rule_family", "installation"))
					if (old.containsKey(field)) row.put(field, old.get(field));
				// 已核验的空值和删除也是决定，不能被本次AI重新补回。
				var attrs = new LinkedHashMap<>(map(old.get("attributes")));
				attrs.remove("review_reason");
				QuoteRowFields.normalizePersistentAttributes(attrs);
				// 识别警告是本次来源校验结果，不能作为人工决定从旧草稿复用。
				attrs.remove("recognition_issue");
				if (freshAttributes.containsKey("recognition_issue")) attrs.put("recognition_issue", freshAttributes.get("recognition_issue"));
				// 新版解释只补旧版本没有的字段，旧核验的空值仍保留；未标来源且类型不同时不能冒充 AI 结果。
				for (String field : List.of("main_product_name", "control_description", "attached_components_description"))
					if (!attrs.containsKey(field) && freshAttributes.containsKey(field)) attrs.put(field, freshAttributes.get(field));
				if (blank(attrs.get("equipment_kind_source")) && s(attrs, "equipment_kind").equals(s(freshAttributes, "equipment_kind"))
						&& !blank(freshAttributes.get("equipment_kind_source")))
					attrs.put("equipment_kind_source", freshAttributes.get("equipment_kind_source"));
				row.put("attributes", attrs);
				row.put("source_text", s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark"));
				DuctImportService.refreshAutomaticClassification(row);
				boolean recognitionConflict = !sourceKind.isBlank() && !sourceKind.equals(s(attrs, "equipment_kind"));
				if (recognitionConflict) {
					String issue = "本次原文主产品识别为" + EquipmentReview.KIND_LABELS.getOrDefault(sourceKind, sourceKind)
							+ "，此前已核验类型为" + EquipmentReview.KIND_LABELS.getOrDefault(s(attrs, "equipment_kind"), "未明确类型")
							+ "；已保留此前输入，请核对实际产品后确认来源";
					String existing = s(attrs, "recognition_issue");
					if (!existing.contains(issue)) attrs.put("recognition_issue", existing.isBlank() ? issue : existing + "；" + issue);
				}
				String sourceReason = EquipmentReview.sourceReason(row);
				if (!sourceReason.isBlank()) row.putAll(obj("source_issue", true, "source_issue_reason", sourceReason));
				row.put("source_issue_confirmed", yes(old.get("source_issue_confirmed"))
						&& !recognitionConflict && s(old, "source_issue_reason").equals(s(row, "source_issue_reason"))
						&& s(map(old.get("attributes")), "recognition_issue").equals(s(attrs, "recognition_issue")));
				AiConfidencePolicy.manualEdits(freshRow, row);
				reused++;
			}
			if (reused > 0) body.put("ai_reused_from", obj("quote_id", previousQuote.get("id"), "row_count", reused));
			return;
		}
	}

	private static String aiSourceFingerprint(List<Map<String, Object>> sources) {
		if (sources.isEmpty()) return "";
		List<String> hashes = new ArrayList<>();
		for (var source : sources) {
			String hash = aiSourceHash(source);
			if (hash.isEmpty()) return "";
			hashes.add(hash);
		}
		Collections.sort(hashes);
		return String.join(":", hashes);
	}

	private static String aiSourceHash(Map<String, Object> source) {
		if (blank(source.get("content_base64"))) return "";
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(Base64.getDecoder().decode(s(source, "content_base64"))));
		}
		catch (IllegalArgumentException e) { return ""; }
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}

	private static Map<String, String> aiSourceHashes(List<Map<String, Object>> sources) {
		Map<String, String> files = new HashMap<>();
		Set<String> ambiguous = new HashSet<>();
		for (var source : sources) {
			String file = s(source, "filename"), hash = aiSourceHash(source);
			if (files.putIfAbsent(file, hash) != null) ambiguous.add(file);
		}
		ambiguous.forEach(files::remove);
		return files;
	}

	private static String aiSourceRowKey(Map<String, Object> row, Map<String, String> files) {
		var origin = map(row.get("original"));
		String hash = files.get(s(origin, "source_file")), locator = s(origin, "source_locator");
		if (hash == null || hash.isEmpty() || locator.isEmpty()) return "";
		// 原行位置而非AI生成的产品序号用于关联；空白表名保留来源定位。
		return hash + "\n" + Objects.toString(origin.get("source_sheet"), "") + "\n" + locator;
	}

	/**
	 * 按当前草稿重新生成待核验Excel，并写入报价ID与修订号，避免下载旧清单。
	 */
	public byte[] aiStandardWorkbook(Actor actor, String id) {
		aiImport(actor, id);
		var quote = quote(id, actor, false, false);
		var body = map(quote.get("body"));
		var rows = AiQuoteReviewImportService.currentRows(body, map(quote.get("result")));
		if (s(body, "ai_review_status").equals("approved"))
			rows.forEach(row -> row.put("ai_confidence_reviewed", true));
		int downloadRevision = n(quote.get("revision")).intValue();
		String downloadId = uuid();
		byte[] workbook = AiQuoteImportService.standardWorkbook(rows, id, downloadRevision, downloadId);
		var snapshot = obj("type", "ai_input_download_v1", "quote_id", id, "revision", downloadRevision,
				"rows", AiQuoteReviewImportService.downloadRows(workbook));
		String serialized = repo.json(snapshot);
		if (serialized.length() > 16 * 1024 * 1024) throw new RuleException("清单下载基准过大，请拆分清单后重试");
		repo.exportTransaction(() -> {
			var current = quote(id, actor, true, false);
			if (n(current.get("revision")).intValue() != downloadRevision) conflict("下载期间草稿已修改，请重新下载最新清单");
			repo.update("INSERT INTO quote_import(id,owner_id,filename,parsed,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
					downloadId, quote.get("owner_id"), "AI清单下载基准", serialized);
			return null;
		});
		return workbook;
	}

	/**
	 * 校验AI回导清单并存储字段差异快照，保留文件供追溯；当前报价正文仍不变。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> previewAiInputImport(Actor actor, String id, byte[] bytes, String filename,
			int expectedRevision) {
		var quote = quote(id, actor, true, true);
		revision(quote, obj("revision", expectedRevision));
		var body = map(quote.get("body"));
		if (blank(body.get("ai_review_status"))) throw new RuleException("只有AI生成的草稿可以导回已校验清单");
		String clean = cleanFilename(filename);
		var parsed = aiReviewImporter.read(bytes, clean, id, expectedRevision, body, map(quote.get("result")), downloadId -> {
			var download = repo.find("quote_import", downloadId, false);
			if (download == null || !actor.manager() && Long.parseLong(str(download.get("owner_id"))) != actor.id()) return null;
			return map(download.get("parsed"));
		});
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

	/**
	 * 应用已确认的AI清单预览，验证归属、报价ID、解析规则版本和基准修订号。
	 * 替换清单后清除旧结果；明确要求统一试算时记录人工核验并保存各行结果，否则保留待核验状态。
	 */
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
		repo.updateOrder(id, obj("body", body, "result", null), true, true);
		repo.audit(actor, "apply_ai_input_import", id, obj("import_id", importId, "previous_body", old,
				"changes", parsed.get("changes")));
		if (yes(data.get("recalculate"))) {
			int updatedRevision = n(quote.get("revision")).intValue() + 1;
			action(actor, id, "approve-ai", obj("revision", updatedRevision, "review_confirmed", true));
			var calculated = calculate(actor, id, obj("revision", updatedRevision + 1));
			storeExcelParameterReport(id, s(imported, "filename"), list(parsed.get("changes")));
			return calculated;
		}
		return obj("revision", n(quote.get("revision")).intValue() + 1, "line_count", parsed.get("line_count"),
				"product_row_count", parsed.get("product_row_count"), "special_row_count", parsed.get("special_row_count"));
	}

	/**
	 * 根据来源序号返回原文件内容，便于核验AI整理结果；先校验报价访问权限。
	 */
	public Map<String, Object> aiSource(Actor actor, String id, int index) {
		List<Map<String, Object>> sources = list(aiImport(actor, id).get("ai_sources"));
		if (index < 0 || index >= sources.size())
			throw new RuleException("客户原始资料不存在");
		Map<String, Object> source = sources.get(index);
		return obj("filename", s(source, "filename"), "media_type", s(source, "media_type"), "content",
				Base64.getDecoder().decode(s(source, "content_base64")));
	}

	/**
	 * 定位AI草稿关联的标准清单快照，校验类型，供标准Excel和原始资料下载使用。
	 */
	private Map<String, Object> aiImport(Actor actor, String id) {
		Map<String, Object> quote = quote(id, actor, false, false), body = map(quote.get("body"));
		if (blank(body.get("ai_review_status")))
			throw new RuleException("这份报价没有AI生成的标准清单");
		Map<String, Object> imported = repo.find("quote_import", s(body, "import_id"), false);
		if (imported == null || !"ai_normalized_v1".equals(s(map(imported.get("parsed")), "type")))
			throw new RuleException("标准清单不存在");
		return map(imported.get("parsed"));
	}

	/**
	 * 提取客户、项目、联系信息、交货地点、日期和备注；仅接受合法日期与受限文本。
	 */
	private Map<String, Object> metadata(Map<String, Object> input) {
		Map<String, Object> body = obj();
		QuoteRegion.readInput(input, body);
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

	/**
	 * 建立风管初始条件；影响价格的未选项保留空值或null，要求报价人员明确确认。
	 */
	private static Map<String, Object> ductDefaults() {
		return obj("area_basis", "", "product_state", "", "shape", "", "zinc_layer", "40", "flange", "",
				"include_common_punch", null, "tax_included", null, "corner_count", "", "clip_meters", "",
				"flange_30_meters", "", "flange_40_meters", "", "payment_option", "", "include_installation", false,
				"installation_amount", "", "include_freight", false);
	}

	/**
	 * 建立设备初始条件，税口径和付款方案待选，默认不含运费。
	 */
	private static Map<String, Object> equipmentDefaults() {
		return obj("tax_included", null, "payment_option", "", "include_freight", false, "region", "", "freight_amount", "");
	}

	/**
	 * 新建报价系列第1版草稿，支持手工、单表和整份工作簿导入。
	 * 自动识别采用复合引擎，并保留来源表、原始行及稳定row_id用于核验与回导。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> create(Actor actor, Map<String, Object> data) {
		Map<String, Object> body = metadata(data), options = ductDefaults();
		if (blank(body.get("quote_date")))
			body.put("quote_date", LocalDate.now().toString());
		String mode = str(data.getOrDefault("pricing_mode", "duct_v1"));
		boolean wholeWorkbook = !blank(data.get("import_id"))
				&& (data.containsKey("sheet_settings") || (mode.equals("auto_v1") && blank(data.get("sheet"))));
		// 整份客户清单可能同时包含风管与设备，按每行识别，不能沿用单类整单模式。
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
		if (body.containsKey("project_region") && blank(body.get("project_region")) && !yes(body.get("project_region_confirmed"))) {
			var regions = QuoteRegion.suggestions(body);
			if (regions.size() == 1) body.put("project_region", regions.get(0));
		}
		if (repo.json(body).length() > 16 * 1024 * 1024)
			throw new RuleException("报价内容过长，请拆分");
		String id = uuid();
		repo.insertOrder(id, id, 1, actor.id(), body);
		repo.audit(actor, "create_quote", id, null);
		return obj("id", id);
	}

	/**
	 * 生成报价记录摘要并标记试算是否过期，普通报价员按owner_id过滤。
	 * 计价规则或未确认运费导致结果失效时，不向列表返回可用总价。
	 */
	private static String quoteSummarySelect() {
		return "SELECT q.id,q.family,q.version,q.owner_id,q.list_body AS body,q.list_result AS result,"
				+ "q.state,q.revision,q.created_at,q.updated_at,"
				+ "(SELECT COUNT(*) FROM quote_export x WHERE x.quote_id=q.id) export_count FROM quote_order q";
	}

	public List<Map<String, Object>> listQuotes(Actor actor, boolean archived) {
		String sql = quoteSummarySelect() + " WHERE state "
				+ (archived ? "=" : "<>") + " 'archived'" + (actor.manager() ? "" : " AND owner_id=?")
				+ " ORDER BY updated_at DESC";
		return quoteSummaries(repo.query(sql, actor.manager() ? new Object[] {} : new Object[] { actor.id() }));
	}

	/** 先按权限、归档状态和客户/项目关键字筛选，再只读取当前页的报价摘要。 */
	public Map<String, Object> listQuotePage(Actor actor, boolean archived, int page, int size, String keyword) {
		if (page < 1 || size < 1 || size > 100)
			throw new RuleException("页码应大于0，每页条数应为1至100");
		String where = " WHERE q.state " + (archived ? "=" : "<>") + " 'archived'";
		List<Object> args = new ArrayList<>();
		if (!actor.manager()) {
			where += " AND q.owner_id=?";
			args.add(actor.id());
		}
		String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
		if (!search.isEmpty()) {
			where += " AND (LOWER(JSON_UNQUOTE(JSON_EXTRACT(q.list_body,'$.customer'))) LIKE ? ESCAPE '!'"
					+ " OR LOWER(JSON_UNQUOTE(JSON_EXTRACT(q.list_body,'$.project'))) LIKE ? ESCAPE '!')";
			String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
			args.add(pattern);
			args.add(pattern);
		}
		int total = repo.scalar("SELECT COUNT(*) FROM quote_order q" + where, Integer.class, args.toArray());
		int current = Math.min(page, (int) Math.max(1, (total + (long) size - 1) / size));
		args.add(size);
		args.add((current - 1L) * size);
		String sql = quoteSummarySelect() + where + " ORDER BY q.updated_at DESC,q.id DESC LIMIT ? OFFSET ?";
		return obj("records", total == 0 ? List.of() : quoteSummaries(repo.query(sql, args.toArray())),
				"total", total, "page", current, "size", size);
	}

	/** 分页和兼容列表共用摘要转换，保持总价有效性、版本及导出次数的原有口径。 */
	private List<Map<String, Object>> quoteSummaries(List<Map<String, Object>> values) {
		List<Map<String, Object>> result = new ArrayList<>();
		var resources = repo.catalogs();
		var activeRuleIds = QuoteListSummary.activeRuleIds(resources);
		for (Map<String, Object> value : values) {
			Map<String, Object> q = repo.decode(value), body = map(q.remove("body")), calculation = map(q.remove("result"));
			// pig会将Long序列化为字符串，这里把导出次数转成整数，确保页面的0值和计数判断正确。
			q.put("export_count", n(q.get("export_count")).intValueExact());
			q.putAll(obj("customer", body.get("customer"), "project", body.get("project"), "quote_date",
					body.get("quote_date"), "row_count", n(body.get("row_count")).intValueExact()));
			q.put("pricing_mode", body.get("pricing_mode"));
			q.put("recognition_mode", body.get("recognition_mode"));
			boolean stale = s(q, "state").equals("draft") && (stale(body, calculation)
					|| freightIncluded(body) == null || QuoteListSummary.rulesChanged(calculation, activeRuleIds) || catalogsChanged(calculation, resources));
			q.putAll(obj("result_stale", stale, "total", stale ? null : calculation.get("total"), "pending_count",
					stale ? null : calculation.get("pending_count")));
			result.add(q);
		}
		return result;
	}

	/**
	 * 读取报价与历史导出列表；草稿试算失效时隐藏结果，避免按旧价格继续操作。
	 */
	public Map<String, Object> detail(Actor actor, String id) {
		Map<String, Object> q = quote(id, actor, false, false), calculation = map(q.get("result"));
		var body = map(q.get("body"));
		boolean stale = s(q, "state").equals("draft") && (stale(body, calculation) || freightUnconfirmed(q)
				|| productRulesChanged(body, calculation, repo.catalogs()) || catalogsChanged(calculation, repo.catalogs()));
		prepareBodyForDisplay(body);
		q.put("result_stale", stale);
		if (stale && s(q, "state").equals("draft"))
			q.put("result", null);
		else FailedQuoteAssist.annotate(body, calculation);
		q.put("exports", repo.query(
				"SELECT id,kind,filename,created_at,template_version,template_sha256 FROM quote_export WHERE quote_id=? ORDER BY created_at DESC",
				id));
		return q;
	}

	/** 可选AI仅读取本人的草稿中所选歧义行，不发送已识别行，不写回报价。 */
	public List<Map<String, Object>> aiAssistRows(Actor actor, String id, Map<String, Object> data) {
		var q = quote(id, actor, false, true);
		revision(q, data);
		if (!(data.get("row_ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 20)
			throw new RuleException("请选择1到20条存在识别歧义的明细");
		Set<String> selected = new LinkedHashSet<>();
		for (Object value : ids) if (!(value instanceof String) || !selected.add(str(value)))
			throw new RuleException("选择的明细标识重复或无效");
		var rows = list(map(q.get("body")).get("rows")).stream().filter(r -> selected.contains(s(r, "row_id"))).toList();
		if (rows.size() != selected.size()) throw new RuleException("选择的明细不存在，请刷新报价");
		if (rows.stream().anyMatch(r -> !yes(EngineeringImportReview.inspect(r).get("ai_eligible"))))
			throw new RuleException("所选行已明确、已确认或仅缺参数/价格，无需AI解析，请重新选择歧义行");
		return rows;
	}

	/** 必须是当前草稿有效试算中仍失败、且可由识别补救的项目；前端不能自行扩大范围。 */
	public List<Map<String, Object>> failedAiRows(Actor actor, String id, Map<String, Object> data) {
		var quote = quote(id, actor, false, true);
		revision(quote, data);
		var body = map(quote.get("body")); var result = map(quote.get("result"));
		if (!blank(data.get("report_id"))) failedAiReport(body, data, quote.get("revision"));
		if (result.isEmpty() || stale(body, result) || freightUnconfirmed(quote)
				|| productRulesChanged(body, result, repo.catalogs()) || catalogsChanged(result, repo.catalogs()))
			throw new RuleException("请先保存并完成当前版本的试算，再选择AI辅助识别");
		if (!(data.get("row_ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 20)
			throw new RuleException("请选择1到20条报价失败的识别项目");
		Set<String> selected = new LinkedHashSet<>();
		for (Object value : ids) if (!(value instanceof String) || !selected.add(str(value))) throw new RuleException("项目标识无效或重复");
		var rows = list(body.get("rows")); var calculated = list(result.get("rows"));
		var targets = new ArrayList<Map<String, Object>>();
		for (int i = 0; i < rows.size(); i++) if (selected.contains(s(rows.get(i), "row_id"))) {
			if (i >= calculated.size() || !yes(FailedQuoteAssist.inspect(rows.get(i), calculated.get(i)).get("eligible")))
				throw new RuleException("所选项目已完成、已人工确认，或需要补参数/价格，不能使用AI重新识别");
			targets.add(rows.get(i));
		}
		if (targets.size() != selected.size()) throw new RuleException("所选项目不存在，请刷新报价");
		return targets;
	}

	private static Map<String, Object> failedAiReport(Map<String, Object> body, Map<String, Object> data, Object revision) {
		var report = map(body.get("ai_retry_report"));
		if (report.isEmpty() || !s(report, "id").equals(s(data, "report_id")))
			throw new RuleException("辅助识别记录已更新，请重新打开最近一次结果");
		if (!s(report, "quote_revision").equals(str(revision)))
			throw new RuleException("报价在识别后已修改，请重新试算并生成建议；旧结果仍可查看");
		return report;
	}

	/** 建议与失败原因保存在报价正文，保持计价修订不变；不写入任何建议参数。 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> recordFailedAi(Actor actor, String id, Map<String, Object> data,
			List<Map<String, Object>> suggestions, String error) {
		var q = quote(id, actor, true, true); revision(q, data);
		var targets = failedAiRows(actor, id, data);
		var body = map(q.get("body")); var rows = list(body.get("rows")); var results = list(map(q.get("result")).get("rows"));
		var report = blank(data.get("report_id")) ? obj("id", uuid(), "quote_revision", q.get("revision"),
				"created_at", OffsetDateTime.now().toString(), "entries", new ArrayList<Map<String, Object>>())
				: failedAiReport(body, data, q.get("revision"));
		var entries = new ArrayList<>(list(report.get("entries")));
		for (var target : targets) {
			String rowId = s(target, "row_id");
			int index = -1;
			for (int i = 0; i < rows.size(); i++) if (s(rows.get(i), "row_id").equals(rowId)) { index = i; break; }
			var suggestion = suggestions.stream().filter(v -> s(v, "row_id").equals(rowId)).findFirst().orElseGet(() ->
					FailedQuoteAssist.suggestion(target, obj(), yes(data.get("include_features"))));
			entries.removeIf(entry -> s(entry, "row_id").equals(rowId));
			entries.add(obj("row_id", rowId, "position", index + 1, "source_file", blank(target.get("source_file")) ? map(target.get("original")).get("source_file") : target.get("source_file"),
					"source_sheet", target.get("source_sheet"), "source_row", target.get("source_row"),
					"before", FailedQuoteAssist.snapshot(target), "initial_reason", results.get(index).get("reason"),
					"suggestion", suggestion, "status", !error.isBlank() ? "request_failed" : yes(suggestion.get("verified")) ? "suggested" : "no_suggestion",
					"error", error));
		}
		report.put("entries", entries); body.put("ai_retry_report", report);
		String serialized = repo.json(body);
		if (serialized.length() > 16 * 1024 * 1024) throw new RuleException("辅助结果过多，请减少本次处理行数");
		repo.updateOrder(id, obj("body", body), false, false);
		return report;
	}

	/** 整批保存与一次试算共用事务；整单异常时全部回滚，逐行待确认则正常保留其实际原因。 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> applyFailedAi(Actor actor, String id, Map<String, Object> data) {
		var q = quote(id, actor, true, true); revision(q, data);
		var body = map(q.get("body"));
		var report = failedAiReport(body, data, q.get("revision"));
		var entries = list(report.get("entries")); boolean undo = yes(data.get("undo"));
		Set<String> selected = new LinkedHashSet<>();
		if (undo) entries.stream().filter(e -> s(e, "status").equals("applied")).forEach(e -> selected.add(s(e, "row_id")));
		else {
			if (!(data.get("row_ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 10000)
				throw new RuleException("请先勾选要采用的建议");
			for (Object value : ids) if (!(value instanceof String) || !selected.add(str(value))) throw new RuleException("项目标识无效或重复");
		}
		if (selected.isEmpty()) throw new RuleException("没有可处理的项目");
		var rows = list(body.get("rows")); int count = 0;
		for (var entry : entries) if (selected.contains(s(entry, "row_id"))) {
			var row = rows.stream().filter(r -> s(r, "row_id").equals(s(entry, "row_id"))).findFirst()
					.orElseThrow(() -> new RuleException("原清单行不存在，请刷新报价"));
			if (!(undo ? s(entry, "status").equals("applied") : Set.of("suggested", "undone").contains(s(entry, "status"))))
				throw new RuleException("第" + entry.get("position") + "行没有可采用的建议或已经处理，请重新核对选择");
			if (!FailedQuoteAssist.snapshot(row).equals(map(entry.get(undo ? "after" : "before"))))
				throw new RuleException("第" + entry.get("position") + "行已发生变化，本批未修改，请重新识别");
			if (undo) {
				var original = map(entry.get("before"));
				for (String field : FailedQuoteAssist.INPUT_FIELDS) {
					if (original.containsKey(field)) row.put(field, original.get(field)); else row.remove(field);
				}
			} else if (!FailedQuoteAssist.apply(row, map(entry.get("suggestion"))))
				throw new RuleException("第" + entry.get("position") + "行的建议不再适用，本批未修改");
			count++;
		}
		if (count != selected.size()) throw new RuleException("选择的建议不存在，本批未修改");
		body.put("revision", q.get("revision"));
		Map<String, Object> saved;
		try { saved = save(actor, id, body); }
		catch (RuleException ex) { throw new RuleException("批量保存未完成，本批修改未保存：" + ex.getMessage()); }
		Map<String, Object> response;
		try { response = calculate(actor, id, obj("revision", saved.get("revision"), "replace_manual_review", yes(data.get("replace_manual_review")))); }
		catch (RuleException ex) { throw new RuleException("统一试算未完成，本批修改未保存：" + ex.getMessage()); }
		var updatedBody = map(response.get("body")); var updatedRows = list(updatedBody.get("rows"));
		var updatedReport = map(updatedBody.get("ai_retry_report"));
		var results = list(map(response.get("result")).get("rows"));
		for (var entry : list(updatedReport.get("entries"))) if (selected.contains(s(entry, "row_id"))) {
			int index = -1;
			for (int i = 0; i < updatedRows.size(); i++) if (s(updatedRows.get(i), "row_id").equals(s(entry, "row_id"))) { index = i; break; }
			entry.put("status", undo ? "undone" : "applied");
			entry.put("after", FailedQuoteAssist.snapshot(updatedRows.get(index)));
			entry.put("result", obj("status", results.get(index).get("status"), "reason", results.get(index).get("reason"),
					"unit_price", results.get(index).get("unit_price"), "amount", results.get(index).get("amount")));
			entry.put("calculated_at", map(response.get("result")).get("calculated_at"));
		}
		updatedReport.put("quote_revision", response.get("revision"));
		repo.updateOrder(id, obj("body", updatedBody), false, false);
		repo.audit(actor, undo ? "undo_ai_retry" : "apply_ai_retry", id, obj("report_id", report.get("id"), "row_ids", selected));
		return response;
	}

	/** 仅为响应补充展示字段，必须在保存业务正文之后调用。 */
	private static void prepareBodyForDisplay(Map<String, Object> body) {
		body.put("project_region_suggestions", QuoteRegion.suggestions(body));
		body.put("include_freight", freightIncluded(body));
		for (var row : list(body.get("rows"))) {
			if (!EngineeringImportReview.source(row).isEmpty()) row.put("import_review", EngineeringImportReview.inspect(row));
			var confidence = AiConfidencePolicy.summary(row);
			row.putAll(obj("ai_confidence_reason", s(body, "ai_review_status").equals("approved") ? "" : confidence.get("reason"),
					"ai_confidence_score", confidence.get("score"), "ai_confidence_threshold", confidence.get("threshold"),
					"ai_correction_reason", AiConfidencePolicy.correctionReason(row)));
		}
	}

	/** 返回本次事务已经保存的状态，客户端无需再读取整份报价。 */
	private static Map<String, Object> mutationResponse(Map<String, Object> quote, Map<String, Object> body,
			Map<String, Object> result) {
		prepareBodyForDisplay(body);
		if (result != null) FailedQuoteAssist.annotate(body, result);
		return obj("revision", n(quote.get("revision")).intValue() + 1, "state", quote.get("state"), "body", body,
				"result", result, "result_stale", s(quote, "state").equals("draft")
						&& (freightIncluded(body) == null || result != null && stale(body, result)));
	}

	/**
	 * 清洗单行或独立安装项目字段；明确排除安装时必须填写原因。
	 */
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

	/**
	 * 保存草稿的客户信息、整单条件和产品参数，并清除旧试算结果。
	 * 原始行不可静默删除、替换或调序；保留来源证据，新增行生成独立row_id。
	 */
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
		AccessoryImportReview.save(options, incoming);
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
		// 原始客户清单是核价证据：允许标记不计价，但不能通过删除或替换行隐藏来源。
		List<Map<String, Object>> rows = list(data.get("rows")), previous = list(old.get("rows"));
		if (rows.size() < previous.size() || rows.size() > 10000)
			throw new RuleException("产品行不能静默删除，请保留原行并标记不计价及原因");
		List<Map<String, Object>> safe = new ArrayList<>();
		List<Map<String, Object>> preparedRules = new ArrayList<>();
		List<Map<String, Object>> ruleResources = repo.catalogs();
		var rulePreparation = ProductRuleCatalog.preparation(ruleResources, LocalDate.now());
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i), original = i < previous.size() ? previous.get(i) : obj("source_row",
					"手工" + (i + 1), "source_sheet", "手工", "original", null, "source_issue", false, "row_id", uuid());
			if (i < previous.size() && !s(row, "row_id").equals(s(original, "row_id")))
				throw new RuleException("原始清单行顺序不可变更或替换");
			Map<String, Object> cleaned = obj();
			for (String key : List.of("row_id", "source_row", "source_sheet", "source_sheet_index", "original", "source_issue",
					"source_file", "source_locator", "source_excerpt", "source_issue_reason"))
				cleaned.put(key, original.get(key));
			for (String key : List.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark", "category",
					"exclude_reason", "candidate_id"))
				cleaned.put(key, textValue(row.get(key), 1000));
			for (String key : List.of("excluded", "installation_only", "source_issue_confirmed")) {
				if (row.containsKey(key) && !bool(row.get(key)))
					throw new RuleException("行确认状态不正确");
				cleaned.put(key, yes(row.get(key)));
			}
			if (yes(cleaned.get("excluded")) && blank(cleaned.get("exclude_reason")))
				throw new RuleException("不计价行必须说明原因");
			if (yes(cleaned.get("excluded")) && yes(cleaned.get("installation_only")))
				throw new RuleException("本行不能同时选择不报价和仅安装");
			Map<String, Object> attrs = new LinkedHashMap<>(map(row.get("attributes"))), safeAttrs = obj();
			EquipmentReview.FAN_HEADERS.keySet().forEach(field -> {
				if (attrs.containsKey(field)) attrs.put(field, DuctImportService.reviewFanValue(field, s(attrs, field)));
			});
			Set<String> clearedFields = AiInputReview.clearedFields(attrs);
			clearedFields.removeIf(field -> !blank(attrs.get(field)));
			if (clearedFields.isEmpty()) attrs.remove(AiInputReview.CLEARED_FIELDS);
			else attrs.put(AiInputReview.CLEARED_FIELDS, String.join(",", clearedFields));
			QuoteRowFields.normalizePersistentAttributes(attrs);
			if (attrs.size() > 100)
				throw new RuleException("产品参数过多");
			attrs.forEach((k, v) -> {
				if (k.startsWith("ai_confidence") || k.equals("ai_correction_reason")) return;
				if (k.length() > 40)
					throw new RuleException("参数名称过长");
				safeAttrs.put(k, textValue(v, 300));
			});
			var oldAttributes = map(original.get("attributes"));
			if (!blank(safeAttrs.get("equipment_kind"))
					&& (!s(safeAttrs, "equipment_kind").equals(s(oldAttributes, "equipment_kind"))
							|| !s(cleaned, "category").equals(s(original, "category"))))
				safeAttrs.put("equipment_kind_source", "manual");
			else if (s(oldAttributes, "equipment_kind_source").equals("manual") && !blank(safeAttrs.get("equipment_kind")))
				safeAttrs.put("equipment_kind_source", "manual");
			cleaned.put("attributes", safeAttrs);
			AcousticLengthInputs.saved(original, cleaned);
			RoundDimensions.invalidatePreviousChoice(original, cleaned);
			DuctImportService.refreshAutomaticClassification(cleaned);
			String sourceReason = EquipmentReview.sourceReason(cleaned);
			if (!sourceReason.isEmpty()) cleaned.putAll(obj("source_issue", true, "source_issue_reason", sourceReason));
			if (yes(original.get("source_issue_confirmed")) && sourceInputChanged(original, cleaned)
					&& !RoundDimensions.reconfirmed(original, cleaned))
				cleaned.put("source_issue_confirmed", false);
			// AI草稿以人工核验为门槛，保存时仅规范参数，核验问题继续从原始证据追溯。
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
			var preparedRow = rulePreparation.prepare(cleaned);
			preparedRules.add(preparedRow);
			if (preparedRow.containsKey("_product_rule_resource"))
				cleaned.put("category", preparedRow.get("category"));
			AiConfidencePolicy.manualEdits(original, cleaned);
			safe.add(cleaned);
		}
		body.put("rows", safe);
		// 清单被修改后，原人工核验不再有效，必须重新确认后才能试算。
		if (!blank(old.get("ai_review_status")))
			body.put("ai_review_status", "pending");
		body.put("duct_options", options);
		enableProductRuleMode(body, preparedRules);
		applyFreight(body, data.containsKey("include_freight") ? data.get("include_freight") : freightIncluded(body));
		if (body.containsKey("project_region") && QuoteRegion.changed(old, body) && !map(map(q.get("result")).get("manual_review_import")).isEmpty()) {
			var previousResult = map(q.get("result"));
			body.put("region_manual_review_snapshot", obj("project_region", QuoteRegion.project(old), "delivery_region", QuoteRegion.delivery(old),
					"filename", map(previousResult.get("manual_review_import")).get("filename"), "lines", previousResult.get("manual_lines")));
		}
		if (body.containsKey("project_region") && QuoteRegion.changed(old, body) && QuoteRegion.hasManualPrices(body)
				&& !yes(data.get("region_prices_confirmed"))) body.put("region_prices_confirmed", false);
		QuoteRegion.sync(body);
		Map<String, Object> previousResult = map(q.get("result"));
		boolean samePrices = samePricingInputs(old, body);
		if (samePrices && !blank(old.get("ai_review_status"))) body.put("ai_review_status", old.get("ai_review_status"));
		if (!map(previousResult.get("manual_review_import")).isEmpty()) {
			body.put("manual_review_snapshot", obj("review", previousResult.get("manual_review_import"),
					"lines", previousResult.get("manual_lines"), "total", previousResult.get("total"),
					"requires_confirmation", true));
		}
		// 客户、联系人、整单备注等展示信息不改变人工价格；计价输入变化仍使旧试算失效。
		Map<String, Object> retainedResult = samePrices && !previousResult.isEmpty() ? previousResult : null;
		String serializedBody = repo.json(body);
		if (serializedBody.length() > 16 * 1024 * 1024)
			throw new RuleException("报价内容过长，请拆分");
		repo.updateOrder(id, obj("body", body, "result", retainedResult), true, true);
		repo.audit(actor, "edit_quote", id, obj("previous_body", old));
		return mutationResponse(q, body, retainedResult);
	}

	/** 只排除不参与计价的抬头信息；产品名称和行备注仍可能包含计价要求。 */
	private static boolean samePricingInputs(Map<String, Object> before, Map<String, Object> after) {
		for (String key : List.of("pricing_mode", "recognition_mode", "duct_options", "equipment_options",
				"installation_options", "installation_items", "project_region", "project_region_confirmed",
				"different_delivery_region", "delivery_region", "region_prices_confirmed", "include_freight"))
			if (!Objects.equals(before.get(key), after.get(key))) return false;
		var oldRows = list(before.get("rows"));
		var newRows = list(after.get("rows"));
		if (oldRows.size() != newRows.size()) return false;
		for (int i = 0; i < oldRows.size(); i++) {
			var oldRow = oldRows.get(i);
			var newRow = newRows.get(i);
			if (sourceInputChanged(oldRow, newRow)) return false;
			for (String key : List.of("row_id", "candidate_id", "product_rule_family"))
				if (!s(oldRow, key).equals(s(newRow, key))) return false;
			for (String key : List.of("excluded", "installation_only", "source_issue_confirmed", "source_issue"))
				if (yes(oldRow.get(key)) != yes(newRow.get(key))) return false;
			for (String key : List.of("product_rule", "installation"))
				if (!map(oldRow.get(key)).equals(map(newRow.get(key)))) return false;
		}
		return true;
	}

	/** 参数再次变化时，旧的来源异常确认不能沿用。 */
	static boolean sourceInputChanged(Map<String, Object> before, Map<String, Object> after) {
		for (String key : List.of("name", "spec", "quantity", "engineering_quantity", "unit", "remark", "category"))
			if (!s(before, key).equals(s(after, key))) return true;
		var oldAttrs = new LinkedHashMap<>(map(before.get("attributes")));
		var newAttrs = new LinkedHashMap<>(map(after.get("attributes")));
		QuoteRowFields.normalizePersistentAttributes(oldAttrs);
		QuoteRowFields.normalizePersistentAttributes(newAttrs);
		oldAttrs.remove("review_reason");
		newAttrs.remove("review_reason");
		return !oldAttrs.equals(newAttrs);
	}

	/**
	 * 对当前修订版执行试算并原子保存结果。行级待确认项由运算引擎返回，不会用猜测值强行补齐。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> calculate(Actor actor, String id, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, true);
		revision(q, data);
		var body = map(q.get("body"));
		var manual = map(map(q.get("result")).get("manual_review_import"));
		var snapshot = map(body.get("manual_review_snapshot"));
		if ((!manual.isEmpty() || yes(snapshot.get("requires_confirmation"))) && !yes(data.get("replace_manual_review")))
			throw new RuleException("本单有人工校对价格，重新试算将按系统规则替换人工结果，请明确确认；原人工价格会保留供核对");
		if (!manual.isEmpty()) {
			var previousResult = map(q.get("result"));
			snapshot = obj("review", manual, "lines", previousResult.get("manual_lines"), "total", previousResult.get("total"));
			body.put("manual_review_snapshot", snapshot);
		}
		if (!blank(body.get("ai_review_status")) && !s(body, "ai_review_status").equals("approved"))
			throw new RuleException("请先人工核对并确认AI生成的清单，再试算报价");
		Map<String, Object> result = compute(body, repo.catalogs(), map(settings(actor).get("policy")));
		if (!snapshot.isEmpty()) snapshot.put("requires_confirmation", false);
		result.put("calculated_at", OffsetDateTime.now().toString());
		result.put("template_sha256", DuctExportService.templateHash());
		repo.updateOrder(id, obj("body", body, "result", result), true, true);
		repo.audit(actor, "calculate_quote", id, obj("pending_count", result.get("pending_count")));
		return mutationResponse(q, body, result);
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
		Map<String, String> scopes = new HashMap<>();
		for (var row : list(map(q.get("body")).get("rows")))
			scopes.put(s(row, "row_id"), quotationScope(row));
		Map<String, Object> parsed = reviewImporter.read(bytes, clean, id, expectedRevision, scopes);
		boolean priceEdited = list(parsed.get("changes")).stream().anyMatch(change -> s(change, "kind").equals("manual")
				&& Set.of("material_unit", "material_amount", "installation_amount", "line_total").contains(s(change, "field")));
		boolean pendingInputEdited = yes(parsed.get("parameter_input_format")) && !priceEdited
				&& list(parsed.get("lines")).stream().anyMatch(line -> yes(line.get("was_pending"))
						&& list(line.get("changes")).stream().anyMatch(change -> s(change, "kind").equals("manual")
								&& Set.of("name", "spec", "thickness", "process", "unit", "quantity").contains(s(change, "field"))));
		if (pendingInputEdited || list(parsed.get("lines")).stream().anyMatch(line -> !map(line.get("parameter_updates")).isEmpty())) {
			if (!map(result.get("manual_review_import")).isEmpty()) throw new RuleException("当前报价含Excel人工价格，请先在工作台确认替换人工价格并重新试算，再导出参数校对版");
			var nextBody = excelParameterBody(map(q.get("body")), parsed);
			var calculated = compute(nextBody, repo.catalogs(), map(settings(actor).get("policy")));
			parsed.putAll(obj("input_recalculated", true, "calculation_issues", calculated.get("issues"), "pending_count", calculated.get("pending_count"),
					"subtotal", calculated.get("subtotal"), "material_total", calculated.get("material_total"),
					"equipment_freight_total", calculated.get("equipment_freight_total"), "before_payment_total", calculated.get("before_payment_total")));
			attachExcelInputResults(parsed, nextBody, calculated);
		}
		var regionBody = map(q.get("body"));
		if (regionBody.containsKey("project_region")) {
			String expectedLocation = QuoteRegion.exportLocation(regionBody);
			parsed.put("location_changed", !expectedLocation.equals(s(parsed, "location_text")));
			parsed.put("system_location", expectedLocation);
		}
		if (!yes(parsed.get("input_recalculated")) && list(parsed.get("scope_restorations")).isEmpty())
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
		return obj("import_id", importId, "filename", clean, "input_recalculated", parsed.get("input_recalculated"),
				"input_results", parsed.get("input_results"), "calculation_issues", parsed.get("calculation_issues"), "line_count", parsed.get("line_count"), "change_count",
				parsed.get("change_count"), "location_changed", parsed.get("location_changed"), "location_text", parsed.get("location_text"),
				"system_location", parsed.get("system_location"), "changes", changes.stream().limit(200).toList(), "changes_truncated",
				changes.size() > 200, "derived_count", parsed.get("derived_count"), "changed_row_count",
				parsed.get("changed_row_count"), "unchanged_count", parsed.get("unchanged_count"), "pending_count",
				parsed.get("pending_count"), "subtotal", parsed.get("subtotal"), "material_total",
				parsed.get("material_total"), "equipment_freight_total", parsed.get("equipment_freight_total"),
				"equipment_freight_included", parsed.get("equipment_freight_included"),
				"installation_total", parsed.get("installation_total"),
				"before_payment_total", parsed.get("before_payment_total"), "column_count", parsed.get("column_count"),
				"review_rows", reviewRows, "scope_restorations", list(parsed.get("scope_restorations")));
	}

	private static String quotationScope(Map<String, Object> row) {
		return yes(row.get("excluded")) ? "excluded" : yes(row.get("installation_only")) ? "installation_only" : "quoted";
	}

	/** 参数修改走规则重算；人工价格回导沿用原流程，避免把旧金额误认为新参数的报价。 */
	private Map<String, Object> excelParameterBody(Map<String, Object> original, Map<String, Object> parsed) {
		if (!map(original.get("manual_review_import")).isEmpty() || yes(map(original.get("manual_review_snapshot")).get("requires_confirmation")))
			throw new RuleException("当前报价含Excel人工价格，请先在工作台确认替换人工价格并重新试算，再导出参数校对版");
		if (!list(parsed.get("scope_restorations")).isEmpty())
			throw new RuleException("本次修改同时涉及恢复报价范围，请先在工作台确认范围并重新导出，再修改参数");
		if (list(parsed.get("changes")).stream().anyMatch(change -> s(change, "kind").equals("manual")
				&& Set.of("material_unit", "material_amount", "installation_amount", "line_total").contains(s(change, "field"))))
			throw new RuleException("本次同时修改了产品参数和人工价格，请先导回参数并重新试算，再核对人工价格，避免沿用旧参数金额");
		var body = repo.object(repo.json(original));
		Map<String, Map<String, Object>> byId = new HashMap<>();
		for (var row : list(body.get("rows"))) byId.put(s(row, "row_id"), row);
		for (var line : list(parsed.get("lines"))) {
			if (!yes(line.get("changed"))) continue;
			var row = byId.get(s(line, "source_row_id"));
			if (row == null || yes(row.get("excluded")) || yes(row.get("installation_only")))
				throw QuoteExcelParameters.invalid(n(line.get("visible_row")).intValue(), "本行不参与产品供货计价，不能修改产品参数");
			var before = repo.object(repo.json(row));
			var attributes = map(row.get("attributes"));
			for (var change : list(line.get("changes"))) {
				String field = s(change, "field");
				if (!s(change, "kind").equals("manual")) continue;
				if (Set.of("name", "spec", "unit", "remark").contains(field)) row.put(field, change.get("after"));
				if (field.equals("quantity")) row.put(s(row, "category").equals("风管") ? "engineering_quantity" : "quantity", change.get("after"));
				if (field.equals("thickness")) attributes.put("thickness", change.get("after"));
				if (field.equals("process")) attributes.put("flange", change.get("after"));
			}
			var updates = map(line.get("parameter_updates"));
			if (List.of("name", "spec", "remark").stream().anyMatch(key -> !s(before, key).equals(s(row, key)))) {
				var previous = AiQuoteReviewImportService.inferred(before); var current = AiQuoteReviewImportService.inferred(row);
				Set<String> inferredFields = new HashSet<>(previous.keySet()); inferredFields.addAll(current.keySet());
				boolean manualKind = s(attributes, "equipment_kind_source").equals("manual");
				for (String field : inferredFields)
					if (!s(previous, field).equals(s(current, field)) && !updates.containsKey(field)
							&& !(manualKind && field.equals("equipment_kind"))) attributes.remove(field);
				if (!manualKind && !s(previous, "equipment_kind").equals(s(current, "equipment_kind"))) row.put("category", "");
				row.put("source_text", s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark"));
			}
			attributes.putAll(updates);
			if (map(line.get("parameter_updates")).containsKey("equipment_kind")) {
				attributes.put("equipment_kind_source", "manual"); row.put("category", "");
			}
			EquipmentReview.normalizeInputs(attributes); row.put("attributes", attributes);
			row.put("candidate_id", "");
			QuoteRowFields.normalize(row);
			DuctImportService.autoClassifyRow(row);
			AiInputReview.refresh(row, false);
			if (sourceInputChanged(before, row)) row.put("source_issue_confirmed", false);
			AiConfidencePolicy.manualEdits(before, row);
		}
		return body;
	}

	private static void attachExcelInputResults(Map<String, Object> target, Map<String, Object> body, Map<String, Object> result) {
		var products = list(body.get("rows")); var rows = list(result.get("rows"));
		List<Map<String, Object>> outcomes = new ArrayList<>();
		for (int i = 0; i < Math.min(products.size(), rows.size()); i++) {
			var source = products.get(i); var calculated = rows.get(i);
			outcomes.add(obj("row_id", source.get("row_id"), "name", source.get("name"), "source_location", QuoteExcelParameters.location(source),
					"status", calculated.get("status"), "reason", calculated.get("reason"), "unit_price", calculated.get("unit_price"), "amount", calculated.get("amount")));
		}
		target.put("input_results", outcomes);
	}

	private void storeExcelParameterReport(String id, String filename, List<Map<String, Object>> changes) {
		var quote = repo.find("quote_order", id, false); var body = map(quote.get("body"));
		var report = obj("filename", filename, "changes", changes, "calculated_at", OffsetDateTime.now().toString());
		attachExcelInputResults(report, body, map(quote.get("result")));
		body.put("excel_parameter_review", report);
		repo.updateOrder(id, obj("body", body), false, false);
	}

	/** 校对版中的补价只有经明确确认，才可恢复本单产品的供货范围。 */
	private static Set<String> restoreReviewScope(Map<String, Object> body, Map<String, Object> parsed,
			Map<String, Object> data) {
		Object requested = data.getOrDefault("restore_scope_row_ids", List.of());
		if (!(requested instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String)))
			throw new RuleException("报价范围恢复确认格式不正确");
		Set<String> confirmed = new LinkedHashSet<>();
		for (Object value : (List<?>) requested)
			confirmed.add(str(value));
		Map<String, Map<String, Object>> sources = new HashMap<>();
		for (var row : list(body.get("rows")))
			sources.put(s(row, "row_id"), row);
		Set<String> required = new LinkedHashSet<>();
		for (var restoration : list(parsed.get("scope_restorations"))) {
			String rowId = s(restoration, "source_row_id");
			var row = sources.get(rowId);
			if (row == null || !quotationScope(row).equals(s(restoration, "previous_scope"))
					|| quotationScope(row).equals("quoted"))
				throw new RuleException("报价范围已改变，请重新上传校对版");
			required.add(rowId);
		}
		if (!confirmed.equals(required))
			throw new RuleException("校对版给不报价或仅安装产品填写了材料价格，请明确确认恢复供货报价范围后再应用");
		if (required.isEmpty())
			return required;
		QuoteReviewImportService.acceptScopeRestorations(parsed, confirmed);
		for (String rowId : required)
			sources.get(rowId).putAll(obj("excluded", false, "installation_only", false, "exclude_reason", ""));
		return required;
	}

	/** 新恢复的行补记当前规则快照，其余行保留试算时绑定，避免掩盖其他产品规则的变化。 */
	private static void bindRestoredReviewRows(Map<String, Object> body, Map<String, Object> result,
			Set<String> restored, List<Map<String, Object>> resources) {
		if (restored.isEmpty())
			return;
		var rows = list(body.get("rows"));
		Set<Integer> indices = new HashSet<>();
		for (int i = 0; i < rows.size(); i++)
			if (restored.contains(s(rows.get(i), "row_id")))
				indices.add(i);
		var bindings = new ArrayList<>(list(result.get("product_rule_bindings")));
		bindings.removeIf(binding -> indices.contains(n(binding.get("row_index")).intValue()));
		var rulePreparation = ProductRuleCatalog.preparation(resources, LocalDate.now());
		var current = productRuleBindings(rows.stream().map(rulePreparation::prepare).toList());
		current.stream().filter(binding -> indices.contains(n(binding.get("row_index")).intValue())).forEach(bindings::add);
		bindings.sort(Comparator.comparingInt(binding -> n(binding.get("row_index")).intValue()));
		result.put("product_rule_bindings", bindings);
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
			if (source == null || yes(source.get("excluded")) || yes(source.get("installation_only")) || s(source, "category").equals("风管")
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
		// 用补齐后的整单试算销售额判断运费门槛，付款条件和安装金额均按当前实现参与试算。
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

	/**
	 * 按稳定源行ID把回导后补算的地区运费写入结果行，保留未含运费的基础价供追溯。
	 */
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
	 * 保留人工确认的供货基础价，在其上补算地区运费并更新含运费单价、行金额和整单合计；修复步骤本身不递增修订号。
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
	 * 人工修改按来源行ID应用，设备运费补算及整单汇总等派生字段统一更新。
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
		if (yes(parsed.get("location_changed")) && !yes(data.get("keep_system_location")))
			throw new RuleException("校对版地址与系统不一致，请确认保留系统地址；需要换省份请在工作台修改后重新试算");
		if (n(parsed.get("base_revision")).compareTo(n(q.get("revision"))) != 0)
			conflict("报价单在校对版预览后已有修改，请重新上传校对版");
		if (n(parsed.get("change_count")).signum() == 0)
			throw new RuleException("校对版没有检测到任何人工修改");
		Map<String, Object> body = map(q.get("body")), result = map(q.get("result"));
		if (result.isEmpty())
			throw new RuleException("当前报价尚未试算，不能应用校对结果");
		if (yes(parsed.get("input_recalculated"))) {
			var inputBody = excelParameterBody(body, parsed);
			if (!blank(inputBody.get("ai_review_status"))) inputBody.put("ai_review_status", "approved");
			var inputResult = compute(inputBody, repo.catalogs(), map(settings(actor).get("policy")));
			inputResult.putAll(obj("calculated_at", OffsetDateTime.now().toString(), "template_sha256", DuctExportService.templateHash()));
			repo.updateOrder(id, obj("body", inputBody, "result", inputResult), true, true);
			storeExcelParameterReport(id, s(imported, "filename"), list(parsed.get("changes")));
			repo.audit(actor, "apply_excel_parameters", id, obj("import_id", importId, "changes", parsed.get("changes"), "pending_count", inputResult.get("pending_count")));
			return mutationResponse(q, inputBody, inputResult);
		}
		Set<String> restoredScope = restoreReviewScope(body, parsed, data);
		var resources = repo.catalogs();
		bindRestoredReviewRows(body, result, restoredScope, resources);
		if (!restoredScope.isEmpty() && map(result.get("payment")).isEmpty()) {
			// 原单全不报价时没有付款结果；恢复供货后只取得现行付款方案，不覆盖Excel人工价格。
			result.put("payment", compute(body, resources, map(settings(actor).get("policy"))).get("payment"));
		}
		boolean restoredEquipment = list(body.get("rows")).stream()
			.anyMatch(row -> restoredScope.contains(s(row, "row_id")) && !s(row, "category").equals("风管"));
		var originalFreight = map(map(result.get("fees")).get("equipment_freight"));
		if (restoredEquipment && equipmentMode(body) && Boolean.TRUE.equals(freightIncluded(body))
				&& (originalFreight.isEmpty() || s(originalFreight, "status").equals("not_applicable"))) {
			var fees = map(result.get("fees"));
			fees.put("equipment_freight", obj("status", "pending", "amount", null, "basis", "pending",
					"included_in_rows", false, "reason", "恢复设备供货后请核对送货地区、设备指标和整单设备运费"));
			result.put("fees", fees);
		}
		// 兼容旧预览中待确认的设备运费，应用时再次计算这一派生项。
		resolveReviewEquipmentFreight(body, result, parsed, resources);
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
			Map<String, Object> source = bodyRows.get(index);
			if (yes(source.get("excluded")))
				continue;
			if (yes(source.get("installation_only"))) {
				boolean installationPending = yes(line.get("pending")) || !InstallationPricingEngine.enabled(body);
				row.putAll(obj("status", "installation_only", "reason", "客户自备材料，仅报安装", "pricing_mode", "manual_v1",
						"billing_unit", line.get("unit"), "billing_quantity", line.get("quantity"), "unit_price", null,
						"amount", "0", "body_amount", "0", "installation_status", installationPending ? "pending" : "priced",
						"installation_reason", installationPending ? "请开启整单安装并补齐本行安装费用" : "",
						"installation_amount", installationPending ? null : line.get("installation_amount"),
						"line_total", installationPending ? null : line.get("installation_amount")));
			}
			else if (yes(line.get("pending"))) {
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
		if (bodyRows.stream().allMatch(row -> yes(row.get("excluded")))
				&& list(body.get("installation_items")).stream().allMatch(row -> yes(row.get("excluded"))))
			issues.add("清单没有参与报价的产品或安装项目");
		if (!InstallationPricingEngine.enabled(body) && bodyRows.stream()
			.anyMatch(row -> !yes(row.get("excluded")) && yes(row.get("installation_only"))))
			issues.add("存在仅安装产品，请开启整单安装并补齐安装报价");
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
		// 区分设备运费已经计入逐行单价或单列，汇总时防止重复加入。
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
		resultRows.forEach(QuoteProblem::present);
		// 人工核实价格不再依赖被替代的材料目录；其余自动行仍保留原目录绑定，不能借回导确认旧价。
		var remainingKinds = catalogBindings(body, result, repo.catalogs()).stream().map(binding -> s(binding, "kind")).toList();
		result.put("catalog_bindings", list(result.get("catalog_bindings")).stream().filter(binding -> remainingKinds.contains(s(binding, "kind"))).toList());
		repo.updateOrder(id, obj("body", body, "result", result), true, true);
		repo.audit(actor, "apply_review_import", id,
				obj("import_id", importId, "change_count", parsed.get("change_count"), "pending_count", pending, "subtotal",
						parsed.get("subtotal"), "restored_scope_row_ids", new ArrayList<>(restoredScope)));
		return obj("result", result, "revision", n(q.get("revision")).intValue() + 1);
	}

	/**
	 * 校验导入文件名并去掉路径，只保留可显示的附件名称。
	 */
	private static String cleanFilename(String filename) {
		if (filename == null || filename.length() > 255)
			throw new RuleException("文件名无效");
		String clean = filename.replace('\\', '/');
		return clean.substring(clean.lastIndexOf('/') + 1);
	}

	/**
	 * 执行报价状态流转，并统一核对归属与修订号。
	 * 正式或已导出数据通过修订/归档保留历史；AI核验与重新识别只适用于合法草稿状态。
	 */
	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> action(Actor actor, String id, String action, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, false);
		revision(q, data);
		String state = s(q, "state");
		boolean exported = repo.scalar("SELECT COUNT(*) FROM quote_export WHERE quote_id=?", Integer.class, id) > 0;
		Map<String, Object> body = map(q.get("body"));
		switch (action) {
			// 人工确认只解除AI清单核验门槛，缺失的实际计价条件仍由试算返回待确认。
			case "approve-ai" -> {
				if (!state.equals("draft") || !s(body, "ai_review_status").equals("pending"))
					conflict("只有待核对的AI草稿可以确认");
				if (!yes(data.get("review_confirmed")) || list(body.get("rows")).isEmpty())
					throw new RuleException("请先核对所有清单行，再确认AI草稿");
				for (var row : list(body.get("rows"))) {
					if (yes(row.get("excluded"))) continue;
					String invalid = AiInputReview.invalidNumberReason(row);
					if (!invalid.isBlank()) throw new RuleException("第" + row.get("source_row") + "行：" + invalid);
					if (!AiInputReview.clearedFields(map(row.get("attributes"))).isEmpty())
						throw new RuleException("第" + row.get("source_row") + "行参数已人工清空，请补齐后再确认AI草稿");
				}
				for (var special : list(body.get("source_special_rows"))) {
					if (Set.of("corner_count", "clip_meters", "flange_30_meters", "flange_40_meters").contains(s(special, "kind"))) {
						String label = "第" + special.get("source_row") + "行辅材数量/米数";
						boolean repeated = list(body.get("source_special_rows")).stream().filter(item -> s(item, "kind").equals(s(special, "kind"))).count() > 1;
						Object quantity = repeated ? special.get("quantity") : map(body.get("duct_options")).get(s(special, "kind"));
						if (blank(quantity)) throw new RuleException(label + "待核实，请补齐后再确认AI草稿");
						positive(quantity, label);
						positive(map(body.get("duct_options")).get(s(special, "kind")), "辅材汇总数量/米数");
						special.put("auxiliary_review_confirmed", true);
					}
				}
				body.putAll(obj("ai_review_status", "approved", "ai_reviewed_by", actor.id(),
						"ai_reviewed_at", OffsetDateTime.now().toString()));
				repo.updateOrder(id, obj("body", body), true, true);
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
				repo.updateOrder(id, obj("body", body, "result", null), true, true);
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
			// 归档保存原状态，恢复时回到草稿或定稿，不把历史正式报价变成可编辑数据。
			case "archive" -> {
				if (state.equals("archived") || !exported)
					conflict("只有已导出报价可以归档");
				body.put("_archived_from_state", state);
				repo.updateOrder(id, obj("state", "archived", "body", body), true, true);
			}
			case "restore" -> {
				String previous = s(body, "_archived_from_state");
				if (!state.equals("archived") || !Set.of("draft", "final").contains(previous))
					conflict("报价归档状态无效");
				body.remove("_archived_from_state");
				repo.updateOrder(id, obj("state", previous, "body", body), true, true);
			}
			// 新修订沿用系列和来源正文，使用新ID/版本及空结果，历史报价和导出文件继续保留。
			case "revise" -> {
				if (state.equals("archived"))
					conflict("归档报价请先恢复，再创建修订版");
				// 固定锁记录不依赖报价系列的根记录，根草稿被删除后仍能安全分配下一版本号。
				repo.catalogLock();
				int version = repo.scalar("SELECT MAX(version) FROM quote_order WHERE family=?", Integer.class,
						q.get("family")) + 1;
				String next = uuid();
				if (!blank(body.get("ai_review_status")))
					body.put("ai_review_status", "pending");
				repo.insertOrder(next, q.get("family"), version, q.get("owner_id"), body);
				repo.audit(actor, "revise_quote", next, obj("from", id));
				return obj("id", next);
			}
			default -> throw new RuleException("不支持的报价操作");
		}
		repo.audit(actor, action + "_quote", id, null);
		return obj("ok", true);
	}

	/**
	 * 短事务取得快照，事务外生成文件，再复核当前报价与规则后原子保存；重复点击复用同一快照的文件。
	 */
	@Transactional(transactionManager = "quotationTransactionManager", propagation = Propagation.NOT_SUPPORTED)
	public Map<String, Object> export(Actor actor, String id, Map<String, Object> data) {
		if (!generatingExports.add(id))
			conflict("此报价正在生成导出文件，请稍候从历史导出文件下载");
		try {
			Map<String, Object> snapshot = repo.exportTransaction(() -> prepareExport(actor, id, data));
			if (snapshot.containsKey("existing")) return exportResponse(map(snapshot.get("quote")), map(snapshot.get("existing")));
			Map<String, Object> q = map(snapshot.get("quote"));
			String kind = s(data, "kind"), fingerprint = s(snapshot, "fingerprint");
			byte[] content = renderExport(q, kind);
			return repo.exportTransaction(() -> {
				// 生成期间允许编辑；过期文件不能保存或把已变化的报价定稿。
				Map<String, Object> current = prepareExport(actor, id, data);
				if (current.containsKey("existing")) return exportResponse(map(current.get("quote")), map(current.get("existing")));
				if (!fingerprint.equals(s(current, "fingerprint")))
					conflict("报价或模板在导出期间已修改，请刷新后重新导出");
				return saveExport(actor, id, kind, map(current.get("quote")), content, fingerprint);
			});
		}
		finally {
			generatingExports.remove(id);
		}
	}

	/** 两次短事务使用相同的权限、版本、规则和模板检查，正式导出仍以最新有效指标为准。 */
	private Map<String, Object> prepareExport(Actor actor, String id, Map<String, Object> data) {
		Map<String, Object> q = quote(id, actor, true, false);
		revision(q, data);
		if (s(q, "state").equals("archived"))
			conflict("归档报价不可重新导出，请先恢复");
		String kind = s(data, "kind");
		if (s(q, "state").equals("draft")) QuoteRegion.requireConfirmed(map(q.get("body")));
		if (!Set.of("draft", "formal").contains(kind))
			throw new RuleException("请选择校对版或正式导出");
		if (kind.equals("formal") && s(q, "state").equals("final")) {
			var existing = repo.query("SELECT id,filename FROM quote_export WHERE quote_id=? AND kind='formal' ORDER BY created_at", id);
			if (!existing.isEmpty()) return obj("quote", q, "existing", existing.get(0));
			conflict("历史正式文件缺失，请核对备份；重新报价请创建修订版");
		}
		if (kind.equals("formal") && freightUnconfirmed(q))
			conflict("请统一选择整单是否含运费并重新试算后生成正式报价");
		if (kind.equals("formal")) {
			var accessoryIssues = AccessoryImportReview.issues(map(map(q.get("body")).get("duct_options")));
			if (!accessoryIssues.isEmpty()) throw new RuleException(String.join("；", accessoryIssues));
		}
		Map<String, Object> result = map(q.get("result"));
		if (result.isEmpty())
			throw new RuleException("请先保存并试算");
		if (stale(map(q.get("body")), result))
			conflict("运算规则已更新，请重新试算后导出");
		// 校对版也必须使用当前计算依据，不能从未刷新的页面导出旧价或旧提示。
		if (s(q, "state").equals("draft")) {
			repo.catalogLock();
			var currentResources = repo.catalogs();
			if (productRulesChanged(map(q.get("body")), result, currentResources) || catalogsChanged(result, currentResources))
				conflict("产品规则或生效指标已更新，请重新试算后导出校对版或正式报价");
		}
		if (kind.equals("formal") && repairImportedReviewFreight(map(q.get("body")), result, repo.catalogs())) {
			repo.updateOrder(id, obj("result", result), false, true);
			repo.audit(actor, "repair_review_equipment_freight", id,
					obj("amount", result.get("equipment_freight_total"), "pending_count", result.get("pending_count")));
		}
		if (kind.equals("formal") && !yes(result.get("formal_ready")))
			throw new RuleException("正式报价条件未满足："
					+ String.join("；", ((List<?>) result.get("issues")).stream().map(QuoteData::str).toList()));
		if (kind.equals("formal")) {
			List<Map<String, Object>> existing = repo.query(
					"SELECT id,filename FROM quote_export WHERE quote_id=? AND kind='formal' ORDER BY created_at", id);
			// 已有正式文件时返回原快照，实现重复正式导出的幂等性。
			if (!existing.isEmpty())
				return obj("quote", q, "existing", existing.get(0));
			repo.catalogLock();
			Map<String, Object> policy = repo.find("quote_setting", "policy", true);
			if (policy == null || !yes(map(policy.get("body")).get("formal_template_confirmed")))
				conflict("负责人已暂停正式模板导出，草稿和历史下载仍可使用");
			var resources = repo.catalogs();
			if (productRulesChanged(map(q.get("body")), result, resources))
				conflict("产品别名或组合规则已变更、停用或过期，请重新试算后生成正式报价");
			// 原生自动计价行需复核当前生效指标；人工补价行保留manual_v1确认金额。
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
		String fingerprint = exportFingerprint(q, kind);
		if (kind.equals("draft")) {
			Map<String, Map<String, Object>> files = new HashMap<>();
			for (var file : repo.query("SELECT id,filename FROM quote_export WHERE quote_id=? AND kind='draft' AND template_sha256=?",
					id, DuctExportService.templateHash())) files.put(s(file, "id"), file);
			if (!files.isEmpty())
				for (var audit : repo.query("SELECT detail FROM quote_audit WHERE target=? AND action='export_quote' ORDER BY created_at DESC", id)) {
					var detail = repo.object(s(audit, "detail"));
					if (fingerprint.equals(s(detail, "snapshot_sha256")) && files.containsKey(s(detail, "export_id")))
						return obj("quote", q, "existing", files.get(s(detail, "export_id")));
				}
		}
		return obj("quote", q, "fingerprint", fingerprint);
	}

	/** 报价正文、结果、状态和模板共同标识导出快照，不仅依赖页面的修订号。 */
	private String exportFingerprint(Map<String, Object> q, String kind) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(repo.json(
					obj("body", q.get("body"), "result", q.get("result"), "state", q.get("state"),
							"revision", q.get("revision"), "kind", kind, "template", DuctExportService.templateHash()))
					.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}

	/** 文件、审计与正式状态一次提交；数据库只在此阶段和快照检查期间持锁。 */
	private Map<String, Object> saveExport(Actor actor, String id, String kind, Map<String, Object> q,
			byte[] content, String fingerprint) {
		String exportId = uuid(), filename = (kind.equals("formal") ? "正式报价" : "报价校对版") + "_" + id.substring(0, 8) + "_V"
				+ q.get("version") + "_" + exportId.substring(0, 6) + ".xlsx";
		repo.update(
				"INSERT INTO quote_export(id,quote_id,kind,filename,content,template_version,template_sha256,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
				exportId, id, kind, filename, content, DuctExportService.TEMPLATE_VERSION,
				DuctExportService.templateHash());
		// 正式文件与定稿状态在同一事务写入，后续改价必须建立修订版。
		if (kind.equals("formal")) {
			repo.update(
					"UPDATE quote_order SET state='final',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
					id);
			q.put("state", "final");
			q.put("revision", n(q.get("revision")).intValue() + 1);
		}
		repo.audit(actor, "export_quote", id, obj("kind", kind, "export_id", exportId,
				"revision", q.get("revision"), "snapshot_sha256", fingerprint));
		return exportResponse(q, obj("id", exportId, "filename", filename));
	}

	/** 仅返回文件和版本摘要，页面无需为下载重新读取上千行报价明细。 */
	private Map<String, Object> exportResponse(Map<String, Object> q, Map<String, Object> file) {
		var metadata = repo.query("SELECT id,kind,filename,created_at FROM quote_export WHERE id=?", file.get("id")).get(0);
		metadata.putAll(obj("quote_state", q.get("state"), "quote_revision", q.get("revision")));
		return metadata;
	}

	/** 只按已经校验的报价快照生成文件，不读写数据库。 */
	byte[] renderExport(Map<String, Object> q, String kind) {
		return exporter.write(map(q.get("body")), map(q.get("result")),
				s(q, "family").substring(0, 8) + "-V" + q.get("version"), kind.equals("formal"),
				obj("quote_id", q.get("id"), "revision", q.get("revision"), "family", q.get("family"), "version",
						q.get("version")));
	}

	/**
	 * 通过关联报价核对访问权限，返回已经保存的文件快照，避免随当前指标重新生成历史文件。
	 */
	public Map<String, Object> download(Actor actor, String id) {
		Map<String, Object> item = repo.find("quote_export", id, false);
		if (item == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
		quote(s(item, "quote_id"), actor, false, false);
		return item;
	}

	/**
	 * 仅向报价负责人返回最近200条操作摘要，详细快照仍保存在审计表。
	 */
	public List<Map<String, Object>> audit(Actor actor) {
		leader(actor);
		return repo
			.query("SELECT id,actor_name,action,target,created_at FROM quote_audit ORDER BY created_at DESC LIMIT 200");
	}

}
