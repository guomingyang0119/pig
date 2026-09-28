package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

@Repository
public class QuoteRepository {

	private static final Set<String> TABLES = Set.of("quote_order", "quote_catalog", "quote_import", "quote_export",
			"quote_setting");

	private final JdbcTemplate jdbc;

	private final ObjectMapper json;

	public QuoteRepository(@Qualifier("quotationJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public String json(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (JsonProcessingException e) {
			throw new RuleException("数据不能序列化");
		}
	}

	public Map<String, Object> object(String value) {
		try {
			return json.readValue(value, Map.class);
		}
		catch (JsonProcessingException e) {
			throw new RuleException("报价数据格式错误");
		}
	}

	public Map<String, Object> find(String table, String id, boolean lock) {
		if (!TABLES.contains(table))
			throw new IllegalArgumentException("Invalid table");
		List<Map<String, Object>> rows = jdbc
			.queryForList("SELECT * FROM " + table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
		return rows.isEmpty() ? null : decode(rows.get(0));
	}

	Map<String, Object> decode(Map<String, Object> value) {
		Map<String, Object> result = new LinkedHashMap<>();
		value.forEach((k, v) -> result.put(k.toLowerCase(Locale.ROOT), v));
		for (String key : List.of("body", "result", "parsed"))
			if (result.get(key) instanceof String s)
				result.put(key, object(s));
		return result;
	}

	public List<Map<String, Object>> catalogs() {
		return jdbc.queryForList("SELECT * FROM quote_catalog ORDER BY created_at DESC,version DESC")
			.stream()
			.map(this::decode)
			.toList();
	}

	/**
	 * Single lock for changes to the rule family/version graph; works across application
	 * instances.
	 */
	public void catalogLock() {
		if (find("quote_setting", "catalog-lock", true) == null)
			throw new RuleException("请先执行报价模块初始化SQL");
	}

	public int update(String sql, Object... args) {
		return jdbc.update(sql, args);
	}

	public List<Map<String, Object>> query(String sql, Object... args) {
		return jdbc.queryForList(sql, args);
	}

	public <T> T scalar(String sql, Class<T> type, Object... args) {
		return jdbc.queryForObject(sql, type, args);
	}

	public void audit(QuoteService.Actor actor, String action, String target, Object detail) {
		jdbc.update(
				"INSERT INTO quote_audit(id,actor_id,actor_name,action,target,detail,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
				uuid(), actor.id(), actor.name(), action, target, json(detail == null ? obj() : detail));
	}

}
