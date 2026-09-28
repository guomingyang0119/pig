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
		return switch (s(body, "pricing_mode")) {
			case "equipment_v1" -> equipment.calculate(body, resources, policy);
			case "mixed_v1" -> equipment.mixed(body, resources, policy);
			default -> engine.calculate(body, resources, policy);
		};
	}

	private final DuctImportService importer = new DuctImportService();

	private final DuctExportService exporter = new DuctExportService();

	private final QuoteReviewImportService reviewImporter = new QuoteReviewImportService();

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
		if (q == null || (!actor.manager() && n(q.get("owner_id")).longValue() != actor.id()))
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
		if (!Set.of("duct_catalog", "equipment_catalog", "installation_catalog").contains(kind))
			throw new RuleException("资源类型不正确");
		repo.catalogLock();
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
		leader(actor);
		repo.catalogLock();
		Map<String, Object> r = catalog(id);
		revision(r, data);
		if (!s(r, "status").equals("draft"))
			conflict("已发布或停用的指标不可修改，请创建新版本");
		Map<String, Object> body = map(data.get("body"));
		checkCatalogDraft(body);
		repo.update("UPDATE quote_catalog SET body=?,revision=revision+1 WHERE id=?", repo.json(body), id);
		repo.audit(actor, "edit_catalog", id, obj("previous_body", r.get("body")));
		return obj("revision", n(r.get("revision")).intValue() + 1);
	}

	@Transactional(transactionManager = "quotationTransactionManager")
	public Map<String, Object> catalogAction(Actor actor, String id, String action, Map<String, Object> data) {
		leader(actor);
		repo.catalogLock();
		Map<String, Object> r = catalog(id);
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
			if (Set.of("equipment_catalog", "installation_catalog").contains(s(r, "kind"))) {
				if (s(r, "kind").equals("installation_catalog"))
					InstallationCatalog.validate(body);
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
				return obj("status", "validated", "message", "指标结构、价格、公式和付款方案校验通过");
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
				String end = blank(body.get("effective_to")) ? "9999-12-31" : s(body, "effective_to"),
						otherEnd = blank(b.get("effective_to")) ? "9999-12-31" : s(b, "effective_to");
				if (s(body, "effective_from").compareTo(otherEnd) <= 0 && s(b, "effective_from").compareTo(end) <= 0)
					conflict("已有其他系列的生效指标，请先停用冲突系列");
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
				"include_site_fee", false, "height", "", "cross_work", false, "site_amount", "", "site_note", ""));
		body.put("installation_items", new ArrayList<>());
		if (equipmentMode(body))
			body.put("equipment_options", equipmentDefaults());
		applyFreight(body, data.getOrDefault("include_freight", false));
		if (!blank(data.get("import_id"))) {
			Map<String, Object> imported = repo.find("quote_import", s(data, "import_id"), false);
			if (imported == null || n(imported.get("owner_id")).longValue() != actor.id())
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
					|| (s(q, "state").equals("draft") && freightIncluded(body) == null);
			q.putAll(obj("result_stale", stale, "total", stale ? null : calculation.get("total"), "pending_count",
					stale ? null : calculation.get("pending_count")));
			result.add(q);
		}
		return result;
	}

	public Map<String, Object> detail(Actor actor, String id) {
		Map<String, Object> q = quote(id, actor, false, false), calculation = map(q.get("result"));
		var body = map(q.get("body"));
		boolean stale = stale(body, calculation) || freightUnconfirmed(q);
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
			for (String key : List.of("region", "height", "site_amount", "site_note"))
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
			if (row.containsKey("installation") || original.containsKey("installation"))
				cleaned.put("installation",
						installationEntry(map(row.getOrDefault("installation", original.get("installation")))));
			if (s(old, "recognition_mode").equals("auto_v1"))
				DuctImportService.autoClassifyRow(cleaned);
			safe.add(cleaned);
		}
		body.put("rows", safe);
		body.put("duct_options", options);
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
				"installation_total", parsed.get("installation_total"),
				"before_payment_total", parsed.get("before_payment_total"), "column_count", parsed.get("column_count"),
				"review_rows", reviewRows);
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
		if (imported == null || n(imported.get("owner_id")).longValue() != actor.id())
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
		if (hasEquipmentFreightLine) {
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
						false, "include_site_fee", false, "height", "", "cross_work", false, "site_amount", "", "site_note",
						""));
				body.putIfAbsent("installation_items", new ArrayList<>());
				body.putAll(obj("pricing_mode", "mixed_v1", "recognition_mode", "auto_v1"));
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
