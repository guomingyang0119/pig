package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
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

	public QuoteRepository(@Qualifier("quotationJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
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

	/**
	 * 统一JDBC列名为小写，并将正文、结果及解析快照还原为对象。
	 * 导出文件的二进制内容不参与JSON转换。
	 */
	Map<String, Object> decode(Map<String, Object> value) {
		Map<String, Object> result = new LinkedHashMap<>();
		value.forEach((k, v) -> result.put(k.toLowerCase(Locale.ROOT), v));
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
		jdbc.update(
				"INSERT INTO quote_audit(id,actor_id,actor_name,action,target,detail,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
				uuid(), actor.id(), actor.name(), action, target, json(detail == null ? obj() : detail));
	}

}
