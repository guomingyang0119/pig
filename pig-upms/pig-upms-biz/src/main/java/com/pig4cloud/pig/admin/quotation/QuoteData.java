package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/** Decimal values and JSON-shaped snapshots shared by the quotation module. */
public final class QuoteData {

	public static final MathContext MC = new MathContext(48, RoundingMode.HALF_EVEN);

	public static final BigDecimal ZERO = BigDecimal.ZERO;

	public static final BigDecimal ONE = BigDecimal.ONE;

	private QuoteData() {
	}

	public static class RuleException extends IllegalArgumentException {

		public RuleException(String message) {
			super(message);
		}

	}

	public static Map<String, Object> obj(Object... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put((String) pairs[i], pairs[i + 1]);
		return result;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Object value) {
		if (value == null)
			return new LinkedHashMap<>();
		if (!(value instanceof Map))
			throw new RuleException("数据必须为对象");
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> list(Object value) {
		if (value == null)
			return new ArrayList<>();
		if (!(value instanceof List<?> items) || items.stream().anyMatch(v -> !(v instanceof Map)))
			throw new RuleException("数据必须为对象列表");
		return (List<Map<String, Object>>) value;
	}

	public static String str(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	public static String s(Map<String, Object> map, String key) {
		return str(map.get(key));
	}

	public static boolean blank(Object value) {
		return value == null || str(value).isEmpty();
	}

	public static boolean yes(Object value) {
		return Boolean.TRUE.equals(value);
	}

	public static boolean bool(Object value) {
		return value instanceof Boolean;
	}

	public static BigDecimal n(Object value) {
		if (blank(value) || value instanceof Boolean || str(value).length() > 60)
			throw new RuleException("数值为空或类型不正确");
		try {
			BigDecimal n = new BigDecimal(str(value));
			if (n.abs().compareTo(new BigDecimal("1e18")) > 0 || n.scale() > 100 || n.scale() < -100)
				throw new RuleException("数值超出支持范围");
			return n;
		}
		catch (NumberFormatException e) {
			throw new RuleException("不是有效数值：" + str(value));
		}
	}

	public static BigDecimal positive(Object value, String label) {
		BigDecimal n = n(value);
		if (n.signum() <= 0)
			throw new RuleException(label + "必须大于0");
		return n;
	}

	public static BigDecimal nonnegative(Object value, String label) {
		BigDecimal n = n(value);
		if (n.signum() < 0)
			throw new RuleException(label + "不能小于0");
		return n;
	}

	public static BigDecimal mul(BigDecimal... values) {
		BigDecimal result = ONE;
		for (BigDecimal value : values)
			result = result.multiply(value, MC);
		return result;
	}

	public static BigDecimal add(BigDecimal... values) {
		BigDecimal result = ZERO;
		for (BigDecimal value : values)
			result = result.add(value, MC);
		return result;
	}

	public static BigDecimal round(BigDecimal value) {
		return value.setScale(1, RoundingMode.HALF_UP);
	}

	public static String text(BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

	public static BigDecimal sum(List<Map<String, Object>> items, String key) {
		BigDecimal result = ZERO;
		for (Map<String, Object> item : items)
			if (!blank(item.get(key)))
				result = add(result, n(item.get(key)));
		return result;
	}

	public static BigDecimal rate(Map<String, Object> rates, String key) {
		return n(rates.get(key));
	}

	public static BigDecimal rate(Map<String, Object> rates, String key, String fallback) {
		return n(rates.getOrDefault(key, fallback));
	}

	public static Object attr(Map<String, Object> row, String key) {
		return map(row.get("attributes")).get(key);
	}

	public static String uuid() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
