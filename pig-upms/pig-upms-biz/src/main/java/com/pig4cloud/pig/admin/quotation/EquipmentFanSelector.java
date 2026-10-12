package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 风机工况筛选与本体、电机成本计算，沿用已核对源表的选型顺序。
 * 风量按 m³/h、风压按 Pa、功率按 kW、转速按 r/min 参与筛选；机号和极数分别匹配本体与电机。
 * 不满足性能、被隔离或缺少完整成本的工况不参与最终选型；无法唯一确定时保留候选供报价员确认。
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

	// 把满足需求的工况与已经算出的完整成本绑定，避免选中了没有价格依据的型号。
	private record Choice(Map<String, Object> point, Cost cost) {
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
			return motors.computeIfAbsent(new MotorKey(point.get("powers"), point.get("poles"),
					blank(attrs.get("motor_model")) ? "" : normalize(attrs.get("motor_model"))), ignored ->
				list(catalog.get("motors")).stream()
					.filter(m -> sameNumbers(m.get("powers"), point.get("powers"))
							&& sameNumbers(m.get("poles"), point.get("poles"))
							&& (blank(attrs.get("motor_model"))
									|| normalize(m.get("model")).equals(normalize(attrs.get("motor_model"))))).toList());
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

	}

	/**
	 * 统一型号大小写、括号、横线和罗马数字，并将 HTF-I/II、SWF-I 的已知写法规整为源表系列。
	 *
	 */
	public static String normalize(Object value) {
		return str(value).toUpperCase(Locale.ROOT)
			.replace('（', '(')
			.replace('）', ')')
			.replace('一', '-')
			.replace('－', '-')
			.replaceAll("\\s+", "")
			.replace("Ⅰ", "I")
			.replace("Ⅱ", "II")
			.replace("Ⅲ", "III")
			.replaceFirst("^HTF-(?:II|I)(?=-|$)", "HTF(A)")
			.replaceFirst("^SWF-I(?=-|$)", "SWF");
	}

	// 把功率或极数列表升序标准化；比较时忽略原列表顺序，但不忽略项目个数。
	public static List<BigDecimal> numbers(Object value) {
		if (!(value instanceof List<?> values))
			throw new RuleException("设备规则或数据结构不完整，请核对指标");
		return values.stream().map(QuoteData::n).sorted().toList();
	}

	// 电机功率和极数组合必须逐项数值一致，不能按相邻功率或单个档位借价。
	private static boolean sameNumbers(Object a, Object b) {
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
			throw new RuleException("请明确材料为镀锌、201或304不锈钢");
		if (blank(thickness))
			throw new RuleException("不锈钢成本查价需要对应板厚");
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

	private static Cost cost(Map<String, Object> point, Map<String, Object> catalog, Map<String, Object> attrs,
			Map<String, Object> price, CostLookup lookup) {
		boolean supplement = FanPriceSupplement.applies(price, point, catalog, attrs);
		if (supplement && !blank(price.get("unit_cost")))
			return new Cost(n(price.get("unit_cost")), obj("price_supplement", price, "confirmed_unit_cost", price.get("unit_cost"),
					"explosion_included", true));
		boolean bodySupplement = supplement && !blank(price.get("body_cost"));
		boolean motorSupplement = supplement && !blank(price.get("motor_cost"));
		var bodies = lookup.bodies(point);
		if (!bodySupplement && bodies.size() != 1)
			throw new RuleException("选定机号的本体成本公式缺失或重复");
		var body = bodies.size() == 1 ? bodies.get(0) : obj("size", point.get("size"));
		// 只有完全相同的功率与极数组合才可取电机价，人工指定的电机型号进一步限制候选。
		var motors = lookup.motors(point, attrs);
		if (!motorSupplement && motors.isEmpty())
			throw new RuleException("所选功率及极数组合缺少电机价");
		if (!motorSupplement && motors.stream().map(m -> List.of(s(m, "model"), s(m, "cost"))).distinct().count() != 1)
			throw new RuleException("同功率极数对应多个电机，请明确电机型号");
		var motor = motorSupplement ? obj("model", price.get("motor_model"), "cost", price.get("motor_cost"),
				"powers", point.get("powers"), "poles", point.get("poles")) : motors.get(0);
		// 行内板厚优先于本体默认；没有板厚时公式用 1 占位，不锈钢查价仍要求真实板厚。
		Object thickness = blank(attrs.get("thickness")) ? body.get("thickness") : attrs.get("thickness");
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

	// 把已算出成本的候选还原为工况记录，交给工作台解释待确认原因。
	private static SelectionException failure(String message, List<Choice> choices) {
		return new SelectionException(message, choices.stream().map(Choice::point).toList());
	}

	// 以源工况功率列表首项作为主功率，供选型优先级比较。
	private static BigDecimal firstPower(Map<String, Object> p) {
		return n(((List<?>) p.get("powers")).get(0));
	}

	/**
	 * 最终依次比较主功率、机号、风量和风压，优先选较小的合格配置；并列时再检查型号与极数。
	 *
	 */
	private static int selectionOrder(Choice a, Choice b) {
		int c = firstPower(a.point).compareTo(firstPower(b.point));
		for (String k : List.of("size", "airflow", "pressure")) {
			if (c != 0)
				return c;
			c = n(a.point.get(k)).compareTo(n(b.point.get(k)));
		}
		return c;
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
		String requested = normalize(attrs.get("model"));
		String family = List.of("HTFC", "HTF(A)", "SWF").stream().filter(requested::startsWith).findFirst().orElse("");
		List<BigDecimal> requestedPowers = blank(attrs.get("power")) ? List.of() : powers(s(attrs, "power"));
		if (requestedPowers.stream().anyMatch(p -> p.signum() <= 0))
			throw new RuleException("需求功率必须大于0");
		// 备注明确写某 kW 单价时覆盖功率需求，并记录原文识别结果供内部核对。
		Matcher override = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:KW|千瓦)\\s*单价")
			.matcher(s(row, "remark").toUpperCase(Locale.ROOT));
		String overridePower = override.find() ? override.group(1) : null;
		if (overridePower != null)
			requestedPowers = powers(overridePower);
		String full = s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark") + " " + requested;
		String raw = str(attrs.getOrDefault("raw_model", attrs.get("model"))).toUpperCase(Locale.ROOT)
			.replace("Ⅱ", "II");
		// 双速由多档功率、文字或原型号识别；人工 dual_speed 可以明确覆盖，HTFC 的特定尾号仍表示双速组别。
		boolean dual = requestedPowers.size() > 1 || full.contains("双速")
				|| Pattern.compile("(?:HTF|SWF)-II(?:-|$)").matcher(raw).find();
		if (!blank(attrs.get("dual_speed")))
			dual = Set.of("true", "1", "是", "双速").contains(s(attrs, "dual_speed").toLowerCase(Locale.ROOT));
		if (requested.startsWith("HTFC-") && requested.matches(".*-[12]$"))
			dual = true;
		BigDecimal flow = blank(attrs.get("airflow")) ? null : n(attrs.get("airflow"));
		BigDecimal pressure = blank(attrs.get("pressure")) ? null : n(attrs.get("pressure"));
		Map<String, Object> lows = obj();
		for (String k : List.of("airflow_low", "pressure_low"))
			if (!blank(attrs.get(k)))
				lows.put(k, n(attrs.get(k)));
		if ((flow != null && flow.signum() <= 0) || (pressure != null && pressure.signum() <= 0))
			throw new RuleException("风量、风压必须大于0");
		if (requested.isEmpty() && requestedPowers.isEmpty() && (flow == null || pressure == null))
			throw new SelectionException("无型号、功率时需同时提供风量和风压", List.of());
		if (requested.isEmpty() && !requestedPowers.isEmpty() && (flow == null || pressure == null)
				&& blank(attrs.get("size")))
			throw new SelectionException("仅功率不能确定风机，请补型号或风量、风压", List.of());
		if (!requested.isEmpty() && family.isEmpty() && (flow == null || pressure == null))
			throw new SelectionException("其他系列重选科庆型号需要风量和风压", List.of());
		String group = dual && requested.startsWith("HTFC-") && requested.matches(".*-[12]$")
				? requested.substring(requested.lastIndexOf('-') + 1) : "";
		// 先筛性能：同系列、单双速、分组、风量/风压下限、低档工况、转速以及各档功率要求。
		List<Map<String, Object>> candidates = new ArrayList<>();
		List<Map<String, Object>> speedReferences = new ArrayList<>();
		for (var p : list(catalog.get("fan_points"))) {
			if (yes(p.get("quarantined")) || (!family.isEmpty() && !s(p, "family").equals(family)))
				continue;
			if ((((List<?>) p.get("powers")).size() > 1) != dual || (!group.isEmpty() && !s(p, "group").equals(group)))
				continue;
			if (flow != null && n(p.get("airflow")).compareTo(flow) < 0
					|| pressure != null && n(p.get("pressure")).compareTo(pressure) < 0)
				continue;
			if (lows.entrySet()
				.stream()
				.anyMatch(e -> blank(p.get(e.getKey())) || n(p.get(e.getKey())).compareTo(n(e.getValue())) < 0))
				continue;
			List<BigDecimal> pp = new ArrayList<>(numbers(p.get("powers")));
			Collections.reverse(pp);
			if (!requestedPowers.isEmpty() && pp.get(0).compareTo(requestedPowers.get(0)) < 0)
				continue;
			if (requestedPowers.size() > 1) {
				if (pp.size() != requestedPowers.size())
					continue;
				boolean ok = true;
				for (int i = 0; i < pp.size(); i++)
					if (pp.get(i).compareTo(requestedPowers.get(i)) < 0)
						ok = false;
				if (!ok)
					continue;
			}
			if (!blank(attrs.get("speed")) && (blank(p.get("speed")) || n(p.get("speed")).compareTo(n(attrs.get("speed"))) != 0)) {
				var reference = new LinkedHashMap<>(p);
				reference.put("requirement_mismatches", "转速：客户要求" + s(attrs, "speed") + "r/min，参考候选"
						+ (blank(p.get("speed")) ? "未提供转速" : s(p, "speed") + "r/min"));
				speedReferences.add(reference);
				continue;
			}
			candidates.add(p);
		}
		if (candidates.isEmpty() && !speedReferences.isEmpty())
			throw new SelectionException("没有满足客户转速" + s(attrs, "speed")
					+ "r/min的科庆参数；以下仅为其余性能条件满足的参考候选，转速不符，须报价人员核实客户要求或批准替代后再试算", speedReferences);
		if (candidates.isEmpty())
			throw new SelectionException(lows.isEmpty() ? "没有满足风量、风压、功率及单双速要求的科庆参数，请核实需求" : "需核实双速高低档风量/风压配对数据",
					List.of());
		// 补价仅从已校验规则快照读取，并严格限定有效配置，保留性能筛选和人工候选核对。
		var price = map(map(map(row.get("_product_rule_resource")).get("body")).get("fan_price"));
		if (!price.isEmpty()) {
			candidates.removeIf(p -> !FanPriceSupplement.applies(price, p, catalog, attrs));
			if (candidates.isEmpty()) throw new SelectionException("已核实补价配置不满足本次风机需求，请重新核对型号、功率和工况", List.of());
		}
		// 性能通过后再算完整成本；缺本体公式或电机价格的工况不能成为可售候选。
		List<Choice> eligible = new ArrayList<>();
		Set<String> costIssues = new LinkedHashSet<>();
		for (var p : candidates) {
			try {
				eligible.add(new Choice(p, cost(p, catalog, attrs, price, lookup)));
			}
			catch (RuleException e) {
				costIssues.add(e.getMessage()); }
		}
		if (eligible.isEmpty())
			throw new SelectionException("符合性能的候选均缺少有效本体或电机成本，需补齐价格：" + String.join("；", costIssues), candidates);
		// 优先保留与原型号一致的合格工况；无原型号时，人工机号可缩小候选范围。
		var preferred = eligible.stream().filter(x -> normalize(x.point.get("model")).equals(requested)).toList();
		boolean substituted = !requested.isEmpty() && preferred.isEmpty();
		if (!preferred.isEmpty())
			eligible = new ArrayList<>(preferred);
		else if (!blank(attrs.get("size")) && requested.isEmpty()) {
			var sized = eligible.stream()
				.filter(x -> n(x.point.get("size")).compareTo(n(attrs.get("size"))) == 0)
				.toList();
			if (!sized.isEmpty())
				eligible = new ArrayList<>(sized);
		}
		// 原型号无有效候选时属于重选，必须有风量和风压作为替代选型依据。
		if (substituted && (flow == null || pressure == null))
			throw failure("原型号缺价或性能不满足；重选需要风量、风压", eligible);
		// 填写功率时在合格候选中先取最低主功率，后续再比较机号和性能。
		if (!requestedPowers.isEmpty()) {
			var lowest = eligible.stream().map(x -> firstPower(x.point)).min(BigDecimal::compareTo).orElseThrow();
			eligible.removeIf(x -> firstPower(x.point).compareTo(lowest) != 0);
		}
		Choice selected;
		// 用户指定候选编号也要落在当前有效候选中，避免参数或成本改动后沿用旧选择。
		if (!blank(row.get("candidate_id"))) {
			var chosen = eligible.stream().filter(x -> s(x.point, "id").equals(s(row, "candidate_id"))).toList();
			if (chosen.isEmpty())
				throw failure("指定候选已不满足当前条件或成本资料，请重新选择", eligible);
			selected = chosen.get(0);
		}
		else {
			if (flow == null && pressure == null && eligible.size() > 1)
				throw failure("仍有多个工况点，请补风量/风压或明确选择候选", eligible);
			boolean backup = requested.isEmpty() && requestedPowers.isEmpty();
			boolean reselect = substituted || (preferred.isEmpty() && flow != null && pressure != null);
			if (!backup && !reselect && eligible.size() > 1) {
				var exact = eligible.stream()
					.filter(x -> (flow == null || n(x.point.get("airflow")).compareTo(flow) == 0)
							&& (pressure == null || n(x.point.get("pressure")).compareTo(pressure) == 0))
					.toList();
				if (!exact.isEmpty())
					eligible = new ArrayList<>(exact);
			}
			// 仅性能选型或替代重选时先取最小合格机号，再按功率、风量、风压排序。
			if (backup || reselect) {
				var smallest = eligible.stream()
					.map(x -> n(x.point.get("size")))
					.min(BigDecimal::compareTo)
					.orElseThrow();
				eligible.removeIf(x -> n(x.point.get("size")).compareTo(smallest) != 0);
			}
			eligible.sort(EquipmentFanSelector::selectionOrder);
			Choice first = eligible.get(0);
			var best = eligible.stream().filter(x -> selectionOrder(x, first) == 0).toList();
			// 排序条件相同而型号或极数仍不同，属于业务歧义，必须人工明确。
			if (best.stream().map(x -> List.of(x.point.get("model"), x.point.get("poles"))).distinct().count() > 1)
				throw failure("多个系列满足相同工况，请明确候选型号", best);
			selected = first;
		}
		String warning = !blank(attrs.get("size")) && !preferred.isEmpty()
				&& n(attrs.get("size")).compareTo(n(selected.point.get("size"))) != 0 ? "需求机号与型号不一致，按型号选型" : "";
		return new Cost(selected.cost.amount,
				obj("selected_fan", selected.point, "fan_cost", selected.cost.detail, "selection_note",
						!warning.isEmpty() ? warning : substituted || requested.isEmpty() ? "按风量、风压选择有完整成本的最小合格规格" : "",
						"requested_model", attrs.getOrDefault("raw_model", attrs.getOrDefault("model", "")),
						"requested_power", attrs.getOrDefault("power", ""), "requested_airflow", attrs.getOrDefault("airflow", ""),
						"requested_pressure", attrs.getOrDefault("pressure", ""), "requested_speed", attrs.getOrDefault("speed", ""),
						"power_override", overridePower,
						"substituted", substituted, "selection_path",
						!requested.isEmpty() || !requestedPowers.isEmpty() ? "main" : "performance"));
	}

}
