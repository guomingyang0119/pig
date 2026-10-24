package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 报价独立数据库的持久化入口，保存业务JSON快照、Excel文件及操作审计。
 * 行锁和事务由服务层配合使用，确保修订号校验与写入属于同一次业务操作。
 */
@Repository
public class QuoteRepository {

	// 动态查询只允许报价表名；记录ID仍通过占位符传入。
	private static final Set<String> TABLES = Set.of("quote_order", "quote_catalog", "quote_import", "quote_export",
			"quote_setting");

	private final JdbcTemplate jdbc;

	private final ObjectMapper json;

	private final TransactionTemplate newTransaction;

	public QuoteRepository(@Qualifier("quotationJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
		this(jdbc, json, new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
	}

	@Autowired
	public QuoteRepository(@Qualifier("quotationJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json,
			@Qualifier("quotationTransactionManager") PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.json = json;
		this.newTransaction = new TransactionTemplate(manager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** 独立短事务；导出快照和过期导入清理均用它限制持锁时间。 */
	public <T> T inNewTransaction(Supplier<T> action) {
		return newTransaction.execute(status -> action.get());
	}

	public <T> T exportTransaction(Supplier<T> action) {
		return inNewTransaction(action);
	}

	/**
	 * 序列化指标、报价正文或审计内容；失败时返回报价业务错误。
	 */
	public String json(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (JsonProcessingException e) {
			throw new RuleException("数据不能序列化");
		}
	}

	/**
	 * 还原数据库中的JSON快照，供规则校验和报价计算读取。
	 */
	public Map<String, Object> object(String value) {
		try {
			return json.readValue(value, Map.class);
		}
		catch (JsonProcessingException e) {
			throw new RuleException("报价数据格式错误");
		}
	}

	/**
	 * 按ID读取允许的报价业务表；需要修改时可附加FOR UPDATE锁定当前记录。
	 * 未找到返回null，由服务层结合归属权限决定响应。
	 */
	public Map<String, Object> find(String table, String id, boolean lock) {
		if (!TABLES.contains(table))
			throw new IllegalArgumentException("Invalid table");
		List<Map<String, Object>> rows = jdbc
			.queryForList("SELECT * FROM " + table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
		return rows.isEmpty() ? null : decode(rows.get(0));
	}

	/** 单行原文读取只需报价归属、修订与输入；不加载计算明细或派生列表摘要。 */
	public Map<String,Object> findOrderInput(String id) {
		var rows=jdbc.queryForList("SELECT id,family,version,owner_id,body,state,revision,created_at,updated_at FROM quote_order WHERE id=?",id);
		return rows.isEmpty() ? null : decode(rows.get(0));
	}

	/**
	 * 统一JDBC列名为小写，并将正文、结果及解析快照还原为对象。
	 * 导出文件的二进制内容不参与JSON转换。
	 */
	Map<String, Object> decode(Map<String, Object> value) {
		Map<String, Object> result = new LinkedHashMap<>();
		value.forEach((k, v) -> result.put(k.toLowerCase(Locale.ROOT), v));
		result.remove("list_body");
		result.remove("list_result");
		for (String key : List.of("body", "result", "parsed"))
			if (result.get(key) instanceof String s)
				result.put(key, object(s));
		return result;
	}

	/**
	 * 读取全部系列与状态的指标，由各引擎按类型、生效日期和版本筛选可用规则。
	 */
	public List<Map<String, Object>> catalogs() {
		return jdbc.queryForList("SELECT * FROM quote_catalog ORDER BY created_at DESC,version DESC")
			.stream()
			.map(this::decode)
			.toList();
	}

	/** 列表只需版本、生效日期和状态，不能读取设备价格或历史指标正文。 */
	public List<Map<String, Object>> catalogHeads() {
		String fields = "id,family,kind,version,status";
		for (String key : List.of("company_id", "effective_from", "effective_to"))
			fields += ",TRIM(BOTH '\"' FROM JSON_EXTRACT(body,'$." + key + "')) AS " + key;
		return jdbc.queryForList("SELECT " + fields + " FROM quote_catalog WHERE status IN ('published','retired') ORDER BY created_at DESC,version DESC")
			.stream().map(this::decode).map(row -> {
				var body = obj();
				for (String key : List.of("company_id", "effective_from", "effective_to")) {
					Object value = row.remove(key);
					if (value != null && !"null".equals(value)) body.put(key, value);
				}
				row.put("body", body);
				return row;
			}).toList();
	}

	/** 仅更新派生列表摘要；并发修改报价后不能写入旧核对结果，也不改变报价修订号或排序时间。 */
	public void cacheRuleReview(String id, Object revision, Map<String, Object> calculation,
			List<String> activeIds, boolean changed) {
		jdbc.update("UPDATE quote_order SET list_result=? WHERE id=? AND revision=? AND state='draft'",
			json(QuoteListSummary.reviewedResult(calculation, revision, activeIds, changed)), id, revision);
	}

	/**
	 * 锁定固定的catalog-lock记录，串行化指标发布和同系列版本号分配。
	 * 锁由数据库事务管理，可协调多个服务实例；初始化SQL必须先创建这条记录。
	 */
	public void catalogLock() {
		if (find("quote_setting", "catalog-lock", true) == null)
			throw new RuleException("请先执行报价模块初始化SQL");
	}

	/**
	 * 执行服务层提供的参数化写入；使用报价数据源参与当前报价事务。
	 */
	public int update(String sql, Object... args) {
		return jdbc.update(sql, args);
	}

	/** 同一条 SQL 写入正文/结果及列表摘要，事务回滚时两者一并回滚。 */
	public int updateOrder(String id, Map<String, Object> changes, boolean revision, boolean updatedAt) {
		return updateOrder(id, changes, revision, updatedAt, null);
	}

	/** serializedBody只由事务内刚完成大小校验且尚未修改的正文提供，客户端不能指定。 */
	int updateOrder(String id, Map<String, Object> changes, boolean revision, boolean updatedAt, String serializedBody) {
		List<String> fields = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		changes.forEach((key, value) -> {
			if (!Set.of("body", "result", "state").contains(key)) throw new IllegalArgumentException("Invalid order field");
			fields.add(key + "=?");
			args.add(key.equals("state") || value == null ? value
				: key.equals("body") && serializedBody != null ? serializedBody : json(value));
			if (!key.equals("state")) {
				fields.add("list_" + key + "=?");
				args.add(value == null ? null : json(key.equals("body") ? QuoteListSummary.body(map(value)) : QuoteListSummary.result(map(value))));
			}
		});
		if (revision) fields.add("revision=revision+1");
		if (updatedAt) fields.add("updated_at=CURRENT_TIMESTAMP");
		args.add(id);
		return jdbc.update("UPDATE quote_order SET " + String.join(",", fields) + " WHERE id=?", args.toArray());
	}

	public void insertOrder(String id, Object family, int version, Object owner, Map<String, Object> body) {
		jdbc.update("INSERT INTO quote_order(id,family,version,owner_id,body,list_body,state,revision,created_at,updated_at) "
				+ "VALUES(?,?,?,?,?,?,'draft',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				id, family, version, owner, json(body), json(QuoteListSummary.body(body)));
	}

	/**
	 * 查询报价列表、导出记录或审计摘要，业务筛选条件由服务层确定。
	 */
	public List<Map<String, Object>> query(String sql, Object... args) {
		return jdbc.queryForList(sql, args);
	}

	/**
	 * 读取计数或最大版本等单值，供状态限制和版本分配使用。
	 */
	public <T> T scalar(String sql, Class<T> type, Object... args) {
		return jdbc.queryForObject(sql, type, args);
	}

	/**
	 * 记录实际操作者、动作、目标和业务快照，便于追溯价格与报价状态变更。
	 */
	public void audit(QuoteService.Actor actor, String action, String target, Object detail) {
		audit(actor, action, target, detail, null);
	}

	/** 编辑事务已持有新正文时直接取差异，避免再次读取并解析完整报价；旧调用仍从数据库取真实提交状态。 */
	public void audit(QuoteService.Actor actor, String action, String target, Object detail, Map<String, Object> currentBody) {
		Map<String, Object> payload = new LinkedHashMap<>(map(detail));
		if (Set.of("edit_quote", "edit_catalog").contains(action) && payload.get("previous_body") instanceof Map) {
			if (currentBody == null) {
				var current = find(action.equals("edit_quote") ? "quote_order" : "quote_catalog", target, false);
				if (current != null) currentBody = map(current.get("body"));
			}
			if (currentBody != null) {
				List<Map<String, Object>> changes = new ArrayList<>();
				auditChanges(payload.get("previous_body"), currentBody, "", changes);
				payload.put("changes", changes);
				payload.put("changes_truncated", false);
			}
		}
		else if (action.equals("apply_review_import") && !payload.containsKey("changes") && !blank(payload.get("import_id"))) {
			var imported = find("quote_import", s(payload, "import_id"), false);
			if (imported != null) {
				var parsed = map(imported.get("parsed"));
				payload.put("changes", parsed.getOrDefault("changes", List.of()));
				payload.put("changes_truncated", parsed.getOrDefault("changes_truncated", false));
			}
		}
		jdbc.update(
				"INSERT INTO quote_audit(id,actor_id,actor_name,action,target,detail,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
				uuid(), actor.id(), actor.name(), action, target, json(payload));
	}

	/** 在提交修改的事务内保存差异，不能在查看日志时把后来状态冒充为当时的新值。 */
	private void auditChanges(Object before, Object after, String path, List<Map<String, Object>> changes) {
		if (Objects.equals(before, after)) return;
		if (before instanceof Map<?, ?> old && after instanceof Map<?, ?> next) {
			Set<Object> keys = new LinkedHashSet<>(old.keySet()); keys.addAll(next.keySet());
			for (Object key : keys) auditChanges(old.get(key), next.get(key), path.isEmpty() ? str(key) : path + "." + key, changes);
		}
		else if (before instanceof List<?> old && after instanceof List<?> next) {
			for (int i = 0; i < Math.max(old.size(), next.size()); i++)
				auditChanges(i < old.size() ? old.get(i) : null, i < next.size() ? next.get(i) : null, path + "[" + i + "]", changes);
		}
		else changes.add(obj("field", path, "label", auditFieldLabel(path), "before", auditValue(before), "after", auditValue(after)));
	}

	private Object auditValue(Object value) {
		if (value == null || value instanceof Number || value instanceof Boolean) return value;
		String text = value instanceof String ? (String) value : json(value);
		return text.length() <= 2000 ? text : text.substring(0, 2000) + "…（内容过长已截取）";
	}

	private static String auditFieldLabel(String path) {
		var louver = java.util.regex.Pattern.compile("^louvers\\.([^\\[.]+)\\[([0-3])]$").matcher(path);
		if (louver.matches()) return EquipmentReview.LOUVER_LABELS.getOrDefault(louver.group(1), louver.group(1)) + " · "
				+ List.of("面积成本（元/㎡）", "最低成本（元）", "宽高增加值（mm）", "附件倍率").get(Integer.parseInt(louver.group(2)));
		String key = path.substring(path.lastIndexOf('.') + 1);
		String label = Map.ofEntries(Map.entry("customer", "客户"), Map.entry("project", "项目"), Map.entry("contact", "联系人"),
				Map.entry("name", "名称"), Map.entry("spec", "规格"), Map.entry("quantity", "数量"), Map.entry("engineering_quantity", "工程量"),
				Map.entry("unit", "单位"), Map.entry("remark", "备注"), Map.entry("note", "说明"), Map.entry("unit_price", "单价"),
				Map.entry("cost", "成本"), Map.entry("material_unit", "材料单价"), Map.entry("material_amount", "材料金额"),
				Map.entry("installation_amount", "安装金额"), Map.entry("excluded", "不参与报价"), Map.entry("installation_only", "仅安装"),
				Map.entry("tax_included", "是否含税"), Map.entry("include_freight", "是否含运费"), Map.entry("payment_option", "付款方式"),
				Map.entry("gross_margin", "毛利率"), Map.entry("tax_factor", "税系数"), Map.entry("effective_from", "生效日期"),
				Map.entry("price", "价格"), Map.entry("steel_price", "材料吨价"), Map.entry("valve_steel", "阀门材料吨价"),
				Map.entry("round_factor", "圆形售价系数"), Map.entry("coefficient", "付款系数"), Map.entry("expression", "成本算式"))
				.getOrDefault(key, EquipmentReview.HEADERS.getOrDefault(key, key));
		for (var rate : ProductDefinitions.catalogRates()) if (s(rate, "key").equals(key)) { label = s(rate, "label"); break; }
		var row = java.util.regex.Pattern.compile("^rows\\[(\\d+)](?:\\.|$)").matcher(path);
		return row.find() ? "第" + (Integer.parseInt(row.group(1)) + 1) + "行 · " + label : label;
	}

}
