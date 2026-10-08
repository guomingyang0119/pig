package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/**
 * 报价模块共用的数据转换与十进制运算工具。
 * 报价输入、指标和计算快照以 Map/List 表示；金额使用 BigDecimal，避免二进制浮点误差。
 */
public final class QuoteData {

	/** 中间计算保留48位有效数字；具体报价取整仍由各业务引擎决定。 */
	public static final MathContext MC = new MathContext(48, RoundingMode.HALF_EVEN);

	public static final BigDecimal ZERO = BigDecimal.ZERO;

	public static final BigDecimal ONE = BigDecimal.ONE;

	private QuoteData() {
	}

	/** 可向报价人员展示的输入或业务规则错误，由接口层转换为失败响应。 */
	public static class RuleException extends IllegalArgumentException {

		public RuleException(String message) {
			super(message);
		}

	}

	/**
	 * 按键值对创建有序对象，用于组装报价字段、计算结果和审计快照。
	 */
	public static Map<String, Object> obj(Object... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put((String) pairs[i], pairs[i + 1]);
		return result;
	}

	/**
	 * 读取 JSON 对象；缺失字段返回空对象，类型错误则终止当前报价操作。
	 * 非空对象直接返回原引用，因此修改后会反映到所属报价快照。
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Object value) {
		if (value == null)
			return new LinkedHashMap<>();
		if (!(value instanceof Map))
			throw new RuleException("数据必须为对象");
		return (Map<String, Object>) value;
	}

	/**
	 * 读取产品行或指标组成项列表，并确认每一项都是对象。
	 * 缺失字段视为空列表，不把错误类型静默当成没有产品。
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> list(Object value) {
		if (value == null)
			return new ArrayList<>();
		if (!(value instanceof List<?> items) || items.stream().anyMatch(v -> !(v instanceof Map)))
			throw new RuleException("数据必须为对象列表");
		return (List<Map<String, Object>>) value;
	}

	/**
	 * 把可显示的字段转为去掉首尾空白的文本；空值显示为空字符串。
	 */
	public static String str(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	/**
	 * 按字段名读取文本，统一处理报价 JSON 中的空值。
	 */
	public static String s(Map<String, Object> map, String key) {
		return str(map.get(key));
	}

	/**
	 * 判断字段是否尚未填写；数值0不属于空值，仍可表示明确的零费用。
	 */
	public static boolean blank(Object value) {
		return value == null || str(value).isEmpty();
	}

	/**
	 * 仅把布尔 true 视为已选择，避免文本或数字被误作报价确认。
	 */
	public static boolean yes(Object value) {
		return Boolean.TRUE.equals(value);
	}

	/**
	 * 检查是否为明确的布尔选择，用于含税、含运费及人工确认等开关校验。
	 */
	public static boolean bool(Object value) {
		return value instanceof Boolean;
	}

	/**
	 * 把数量、单价或系数字段解析为十进制数，拒绝空值、布尔值及过长输入。
	 * 限制绝对值和小数位范围，防止异常指数或超大数字进入计价；账号ID不使用此转换。
	 */
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

	/**
	 * 校验面积、数量等必须大于0的业务字段，并在错误中带上中文字段名。
	 */
	public static BigDecimal positive(Object value, String label) {
		BigDecimal n = n(value);
		if (n.signum() <= 0)
			throw new RuleException(label + "必须大于0");
		return n;
	}

	/**
	 * 校验允许明确填写0的价格或费用字段，拒绝负数。
	 */
	public static BigDecimal nonnegative(Object value, String label) {
		BigDecimal n = n(value);
		if (n.signum() < 0)
			throw new RuleException(label + "不能小于0");
		return n;
	}

	/**
	 * 按统一中间精度连乘；单价、行金额和整单金额何时取整由调用方决定。
	 */
	public static BigDecimal mul(BigDecimal... values) {
		BigDecimal result = ONE;
		for (BigDecimal value : values)
			result = result.multiply(value, MC);
		return result;
	}

	/**
	 * 按统一中间精度汇总分项金额，避免在汇总过程中额外按元取整。
	 */
	public static BigDecimal add(BigDecimal... values) {
		BigDecimal result = ZERO;
		for (BigDecimal value : values)
			result = result.add(value, MC);
		return result;
	}

	/**
	 * 四舍五入到1位小数，用于采用该精度的风管报价步骤。
	 * 设备及整单向上取整数元的规则由各自引擎执行。
	 */
	public static BigDecimal round(BigDecimal value) {
		return value.setScale(1, RoundingMode.HALF_UP);
	}

	/**
	 * 输出不使用科学计数法的金额文本；空金额保留null，供页面表示待确认。
	 */
	public static String text(BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

	/**
	 * 汇总列表中的指定金额字段，跳过尚未计算的空值；待确认状态由上层另行判断。
	 */
	public static BigDecimal sum(List<Map<String, Object>> items, String key) {
		BigDecimal result = ZERO;
		for (Map<String, Object> item : items)
			if (!blank(item.get(key)))
				result = add(result, n(item.get(key)));
		return result;
	}

	/**
	 * 读取必须存在的报价系数；缺失或非数值会触发业务错误。
	 */
	public static BigDecimal rate(Map<String, Object> rates, String key) {
		return n(rates.get(key));
	}

	/**
	 * 读取允许缺省的系数；只在键不存在时使用调用方指定的默认文本。
	 */
	public static BigDecimal rate(Map<String, Object> rates, String key, String fallback) {
		return n(rates.getOrDefault(key, fallback));
	}

	/**
	 * 读取产品行的扩展参数，如尺寸、功率或产品状态。
	 */
	public static Object attr(Map<String, Object> row, String key) {
		return map(row.get("attributes")).get(key);
	}

	/**
	 * 生成32位标识，用于报价、产品行、指标和导入导出记录的稳定关联。
	 */
	public static String uuid() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
