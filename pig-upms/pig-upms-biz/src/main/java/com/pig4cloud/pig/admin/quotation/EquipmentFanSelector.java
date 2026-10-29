package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 风机工况筛选与本体、电机成本计算，沿用已核对源表的选型顺序。
 * 新版目录以风量（m³/h）和全压（Pa）闭区间选型，转速只供查阅；机号和电机配置分别匹配本体与电机。
 * 历史单点资料仅供追溯，不再参与自动计算；先确定范围配置再核对成本。
 */
public final class EquipmentFanSelector {

	private EquipmentFanSelector() {
	}

	/**
	 * 返回未税单位成本和计算明细；后续由设备引擎统一处理毛利、税费、数量及取整。
	 * @param amount 本体与电机等适用成本之和，单位元/台
	 * @param detail 原始依据与成本分解，供内部明细展示
	 *
	 */
	public record Cost(BigDecimal amount, Map<String, Object> detail) {
	}

	/** 只在一次整单计算内复用查表结果；每个候选仍独立算成本并保留原有缺价/重复判断。 */
	static final class CostLookup {
		private record MotorKey(Object powers, Object poles, String model) { }
		private final Map<String, Object> catalog;
		private final Map<List<String>, List<Map<String, Object>>> bodies = new HashMap<>();
		private final Map<MotorKey, List<Map<String, Object>>> motors = new HashMap<>();

		CostLookup(Map<String, Object> catalog) { this.catalog = catalog; }

		private List<Map<String, Object>> bodies(Map<String, Object> point) {
			return bodies.computeIfAbsent(List.of(s(point, "family"), s(point, "size")), ignored ->
				list(catalog.get("fan_bodies")).stream()
					.filter(x -> s(x, "family").equals(s(point, "family"))
							&& n(x.get("size")).compareTo(n(point.get("size"))) == 0).toList());
		}

		private List<Map<String, Object>> motors(Map<String, Object> point, Map<String, Object> attrs) {
			String model = blank(point.get("motor_model")) ? s(attrs, "motor_model") : s(point, "motor_model");
			return motors.computeIfAbsent(new MotorKey(point.get("powers"), point.get("poles"),
					motorKey(model) + ":" + motorKey(attrs.get("motor_model"))), ignored ->
				list(catalog.get("motors")).stream()
					.filter(m -> sameNumbers(m.get("powers"), point.get("powers"))
							&& sameNumbers(m.get("poles"), point.get("poles"))
							&& (!point.containsKey("airflow_min") || motorPairs(m).equals(motorPairs(point)))
							&& (blank(model) || motorKey(m.get("model")).equals(motorKey(model)))
							&& (blank(attrs.get("motor_model")) || motorKey(m.get("model")).equals(motorKey(attrs.get("motor_model"))))).toList());
		}
	}

	/**
	 * 选型待确认异常，携带可供人工选择的工况；最多保留 50 项以限制返回数据量。
	 *
	 */
	public static class SelectionException extends RuleException {

		// 仍需确认的工况记录，设备引擎会转换成候选列表供工作台选择。
		final List<Map<String, Object>> candidates;

		SelectionException(String message, List<Map<String, Object>> candidates) {
			super(message);
			this.candidates = candidates.stream().limit(50).toList();
		}

		SelectionException(QuoteProblem problem, List<Map<String, Object>> candidates) {
			super(problem);
			this.candidates = candidates.stream().limit(50).toList();
		}

		SelectionException(String message, QuoteProblem problem, List<Map<String, Object>> candidates) {
			super(message, problem);
			this.candidates = candidates.stream().limit(50).toList();
		}

	}

	/**
	 * 统一型号格式，并将主营资料确认的系列别名转换为现有指标的查表键；原型号另行保留。
	 *
	 */
	public static String normalize(Object value) {
		return FanModelNames.normalize(value);
	}

	/** 源图 Y/YDT 与价格表 YE2/YD 的前缀映射；不修正机座号或功率冲突。 */
	static String motorKey(Object value) {
		String model = normalize(value).replaceFirst("^YE2-", "Y").replaceFirst("^YDT", "YD");
		int dash = model.lastIndexOf('-');
		if (dash >= 0 && model.substring(dash + 1).matches("\\d+/\\d+"))
			model = model.substring(0, dash + 1) + String.join("/", Arrays.stream(model.substring(dash + 1).split("/"))
					.sorted(Comparator.comparingInt(Integer::parseInt)).toList());
		return model;
	}

	static List<String> motorPairs(Map<String, Object> motor) {
		var powers = (List<?>) motor.get("powers");
		var poles = (List<?>) motor.get("poles");
		if (powers == null || poles == null || powers.size() != poles.size())
			throw new RuleException("电机各档功率和极数必须一一对应");
		var pairs = new ArrayList<String>();
		for (int i = 0; i < powers.size(); i++) pairs.add(n(powers.get(i)).stripTrailingZeros().toPlainString()
				+ ":" + n(poles.get(i)).stripTrailingZeros().toPlainString());
		return pairs.stream().sorted().toList();
	}

	// 把功率或极数列表升序标准化；比较时忽略原列表顺序，但不忽略项目个数。
	public static List<BigDecimal> numbers(Object value) {
		if (!(value instanceof List<?> values))
			throw new RuleException("设备规则或数据结构不完整，请核对指标");
		return values.stream().map(QuoteData::n).sorted().toList();
	}

	// 电机功率和极数组合必须逐项数值一致，不能按相邻功率或单个档位借价。
	static boolean sameNumbers(Object a, Object b) {
		var aa = numbers(a);
		var bb = numbers(b);
		if (aa.size() != bb.size())
			return false;
		for (int i = 0; i < aa.size(); i++)
			if (aa.get(i).compareTo(bb.get(i)) != 0)
				return false;
		return true;
	}

	/**
	 * 读取单档或以斜线分隔的多档功率，W 除以 1000 转成 kW；按高功率到低功率排序。
	 *
	 */
	static List<BigDecimal> powers(String value) {
		String raw = value.trim().toUpperCase(Locale.ROOT).replace("KW", "").replace("千瓦", "");
		BigDecimal scale = raw.endsWith("W") ? n("1000") : ONE;
		raw = raw.replaceFirst("W$", "").replace('／', '/');
		if (!raw.matches("\\d+(?:\\.\\d+)?(?:/\\d+(?:\\.\\d+)?)*"))
			throw new RuleException("功率格式待确认：" + value);
		return Arrays.stream(raw.split("/"))
			.map(x -> n(x).divide(scale, MC))
			.sorted(Comparator.reverseOrder())
			.toList();
	}

	/**
	 * 镀锌材料沿用调用方提供的钢材吨价；不锈钢必须按 201/304 和实际板厚唯一命中材料价格表。
	 * @return 元/吨，不是每台风机成本
	 *
	 */
	public static BigDecimal materialPrice(Map<String, Object> catalog, Object material, Object thickness,
			Object galvanized) {
		String m = str(material).toLowerCase(Locale.ROOT);
		if (m.isEmpty() || Set.of("galvanized", "galvanized_40", "galvanized_80", "镀锌", "镀锌板", "钢板", "普通").contains(m))
			return n(galvanized);
		String grade = m.contains("304") ? "304" : m.contains("201") ? "201" : "";
		if (grade.isEmpty())
			throw new RuleException(new QuoteProblem("fan_material_required", "parameters", "请明确材料为镀锌、201或304不锈钢", "", List.of("material")));
		if (blank(thickness))
			throw new RuleException(new QuoteProblem("fan_thickness_required", "cost", "不锈钢成本查价需要对应板厚", "", List.of("thickness")));
		var matches = list(catalog.get("materials")).stream()
			.filter(x -> s(x, "material").equals(grade) && n(x.get("thickness")).compareTo(n(thickness)) == 0)
			.toList();
		if (matches.size() != 1)
			throw new RuleException(grade + "不锈钢" + str(thickness) + "mm吨价缺失或重复");
		return n(matches.get(0).get("ton_price"));
	}

	/**
	 * 按工况系列和机号唯一匹配本体公式，再按全部功率及极数组合匹配电机成本。
	 * 同组合存在不同型号或成本时需明确电机型号；本体材料和其他成本由受限公式计算。
	 *
	 */
	public static Cost cost(Map<String, Object> point, Map<String, Object> catalog, Map<String, Object> attrs) {
		return cost(point, catalog, attrs, obj());
	}

	static Cost cost(Map<String, Object> point, Map<String, Object> catalog, Map<String, Object> attrs, Map<String, Object> price) {
		return cost(point, catalog, attrs, price, new CostLookup(catalog));
	}

	static Cost cost(Map<String, Object> point, Map<String, Object> catalog, Map<String, Object> attrs,
			Map<String, Object> price, CostLookup lookup) {
		validateThickness(attrs.get("thickness"));
		boolean supplement = FanPriceSupplement.applies(price, point, catalog, attrs);
		if (supplement && !blank(price.get("unit_cost")))
			return new Cost(n(price.get("unit_cost")), obj("price_supplement", price, "confirmed_unit_cost", price.get("unit_cost"),
					"explosion_included", true));
		boolean bodySupplement = supplement && !blank(price.get("body_cost"));
		boolean motorSupplement = supplement && !blank(price.get("motor_cost"));
		var bodies = lookup.bodies(point);
		if (!bodySupplement && bodies.isEmpty())
			throw new RuleException(new QuoteProblem("builtin_product_cost_missing", "cost", "选定机号的本体成本公式缺失", "请补充该机号已核实成本。", List.of()));
		if (!bodySupplement && bodies.size() != 1)
			throw new RuleException("选定机号的本体成本公式缺失或重复");
		var body = bodies.size() == 1 ? bodies.get(0) : obj("size", point.get("size"));
		// 只有完全相同的功率与极数组合才可取电机价，人工指定的电机型号进一步限制候选。
		var motors = lookup.motors(point, attrs);
		if (!motorSupplement && motors.isEmpty())
			throw new RuleException(new QuoteProblem("builtin_product_cost_missing", "cost", "所选功率及极数组合缺少电机价", "请补充该配置已核实电机成本。", List.of()));
		if (!motorSupplement && motors.stream().anyMatch(m -> n(m.get("cost")).signum() <= 0))
			throw new RuleException("电机成本必须大于0，请核对电机价格");
		if (!motorSupplement && motors.stream().map(m -> List.of(motorKey(m.get("model")), n(m.get("cost")).stripTrailingZeros().toPlainString())).distinct().count() != 1)
			throw new RuleException(new QuoteProblem("fan_motor_required", "cost", "同功率极数对应多个电机，请明确电机型号", "", List.of("motor_model")));
		var motor = motorSupplement ? obj("model", price.get("motor_model"), "cost", price.get("motor_cost"),
				"powers", point.get("powers"), "poles", point.get("poles")) : motors.get(0);
		// 行内板厚优先于本体默认；没有板厚时公式用 1 占位，不锈钢查价仍要求真实板厚。
		Object thickness = blank(attrs.get("thickness")) ? body.get("thickness") : attrs.get("thickness");
		validateThickness(thickness);
		var env = obj("size", n(body.get("size")), "thickness", blank(thickness) ? ONE : n(thickness), "steel_price",
				bodySupplement ? ZERO : materialPrice(catalog, attrs.get("material"), thickness, body.get("steel_price")));
		var metal = bodySupplement ? ZERO : DecimalFormula.evaluate(s(body, "material_expression"), env);
		var other = bodySupplement ? n(price.get("body_cost")) : DecimalFormula.evaluate(s(body, "other_expression"), env);
		if (metal.signum() < 0 || other.signum() < 0)
			throw new RuleException("风机本体成本公式结果不能为负");
		var detail = obj("body", body, "motor", motor, "material_cost", text(metal), "body_other_cost", text(other),
				"motor_cost", motor.get("cost"), "steel_price", text(n(env.get("steel_price"))));
		if (supplement) detail.put("price_supplement", price);
		if (bodySupplement) detail.put("confirmed_body_cost", price.get("body_cost"));
		return new Cost(add(metal, other, n(motor.get("cost"))), detail);
	}

	static void validateThickness(Object thickness) {
		if (!blank(thickness) && n(thickness).signum() <= 0)
			throw new RuleException(new QuoteProblem("fan_thickness_invalid", "parameters", "风机板厚必须大于0",
					"请填写有效板厚。", List.of("thickness")));
	}

	/**
	 * 从客户型号、功率和工况需求选出具有完整本体、电机价的风机。
	 * 无型号时需完整性能条件；指定候选必须继续满足当前需求，失效选择不能沿用。
	 *
	 */
	public static Cost select(Map<String, Object> row, Map<String, Object> catalog, Map<String, Object> attrs) {
		return select(row, catalog, attrs, new CostLookup(catalog));
	}

	static Cost select(Map<String, Object> row, Map<String, Object> catalog, Map<String, Object> attrs, CostLookup lookup) {
		FanRangeSelector.requireRangeCatalog(catalog);
		var cost = FanRangeSelector.select(row, catalog, attrs, lookup);
		var selected = map(cost.detail().get("selected_fan"));
		if (!blank(attrs.get("power")) && !sameNumbers(selected.get("powers"), powers(s(attrs, "power"))))
			throw new SelectionException(new QuoteProblem("fan_power_conflict", "conflict",
					"选中电机功率与客户要求不一致，不能形成报价",
					"请核对客户功率及选中配置，不能通过更换机号加大功率。", List.of("power")), List.of(selected));
		return cost;
	}

}
