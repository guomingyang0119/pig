package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * Faithful selection/cost counterpart of equipment_fans.py; ambiguous points stay
 * pending.
 */
public final class EquipmentFanSelector {

	private EquipmentFanSelector() {
	}

	public record Cost(BigDecimal amount, Map<String, Object> detail) {
	}

	private record Choice(Map<String, Object> point, Cost cost) {
	}

	public static class SelectionException extends RuleException {

		final List<Map<String, Object>> candidates;

		SelectionException(String message, List<Map<String, Object>> candidates) {
			super(message);
			this.candidates = candidates.stream().limit(50).toList();
		}

	}

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

	public static List<BigDecimal> numbers(Object value) {
		if (!(value instanceof List<?> values))
			throw new RuleException("设备规则或数据结构不完整，请核对指标");
		return values.stream().map(QuoteData::n).sorted().toList();
	}

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

	private static List<BigDecimal> powers(String value) {
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

	public static Cost cost(Map<String, Object> point, Map<String, Object> catalog, Map<String, Object> attrs) {
		var bodies = list(catalog.get("fan_bodies")).stream()
			.filter(x -> s(x, "family").equals(s(point, "family"))
					&& n(x.get("size")).compareTo(n(point.get("size"))) == 0)
			.toList();
		if (bodies.size() != 1)
			throw new RuleException("选定机号的本体成本公式缺失或重复");
		var body = bodies.get(0);
		var motors = list(catalog.get("motors")).stream()
			.filter(m -> sameNumbers(m.get("powers"), point.get("powers"))
					&& sameNumbers(m.get("poles"), point.get("poles"))
					&& (blank(attrs.get("motor_model"))
							|| normalize(m.get("model")).equals(normalize(attrs.get("motor_model")))))
			.toList();
		if (motors.isEmpty())
			throw new RuleException("所选功率及极数组合缺少电机价");
		if (motors.stream().map(m -> List.of(s(m, "model"), s(m, "cost"))).distinct().count() != 1)
			throw new RuleException("同功率极数对应多个电机，请明确电机型号");
		var motor = motors.get(0);
		Object thickness = blank(attrs.get("thickness")) ? body.get("thickness") : attrs.get("thickness");
		var env = obj("size", n(body.get("size")), "thickness", blank(thickness) ? ONE : n(thickness), "steel_price",
				materialPrice(catalog, attrs.get("material"), thickness, body.get("steel_price")));
		var metal = DecimalFormula.evaluate(s(body, "material_expression"), env);
		var other = DecimalFormula.evaluate(s(body, "other_expression"), env);
		if (metal.signum() < 0 || other.signum() < 0)
			throw new RuleException("风机本体成本公式结果不能为负");
		return new Cost(add(metal, other, n(motor.get("cost"))),
				obj("body", body, "motor", motor, "material_cost", text(metal), "body_other_cost", text(other),
						"motor_cost", motor.get("cost"), "steel_price", text(n(env.get("steel_price")))));
	}

	private static SelectionException failure(String message, List<Choice> choices) {
		return new SelectionException(message, choices.stream().map(Choice::point).toList());
	}

	private static BigDecimal firstPower(Map<String, Object> p) {
		return n(((List<?>) p.get("powers")).get(0));
	}

	private static int score(Choice a, Choice b) {
		int c = firstPower(a.point).compareTo(firstPower(b.point));
		for (String k : List.of("airflow", "pressure", "speed")) {
			if (c != 0)
				return c;
			c = n(a.point.get(k)).compareTo(n(b.point.get(k)));
		}
		return c;
	}

	public static Cost select(Map<String, Object> row, Map<String, Object> catalog, Map<String, Object> attrs) {
		String requested = normalize(attrs.get("model"));
		String family = List.of("HTFC", "HTF(A)", "SWF").stream().filter(requested::startsWith).findFirst().orElse("");
		List<BigDecimal> requestedPowers = blank(attrs.get("power")) ? List.of() : powers(s(attrs, "power"));
		if (requestedPowers.stream().anyMatch(p -> p.signum() <= 0))
			throw new RuleException("需求功率必须大于0");
		Matcher override = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:KW|千瓦)\\s*单价")
			.matcher(s(row, "remark").toUpperCase(Locale.ROOT));
		String overridePower = override.find() ? override.group(1) : null;
		if (overridePower != null)
			requestedPowers = powers(overridePower);
		String full = s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark") + " " + requested;
		String raw = str(attrs.getOrDefault("raw_model", attrs.get("model"))).toUpperCase(Locale.ROOT)
			.replace("Ⅱ", "II");
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
		List<Map<String, Object>> candidates = new ArrayList<>();
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
			if (!blank(attrs.get("speed")) && n(p.get("speed")).compareTo(n(attrs.get("speed"))) != 0)
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
			candidates.add(p);
		}
		if (candidates.isEmpty())
			throw new SelectionException(lows.isEmpty() ? "没有满足风量、风压、功率及单双速要求的科庆参数，请核实需求" : "需核实双速高低档风量/风压配对数据",
					List.of());
		List<Choice> eligible = new ArrayList<>();
		for (var p : candidates) {
			try {
				eligible.add(new Choice(p, cost(p, catalog, attrs)));
			}
			catch (RuleException e) {
				/* Python also skips points without complete body/motor cost. */ }
		}
		if (eligible.isEmpty())
			throw new SelectionException("符合性能的候选均缺少有效本体或电机成本，需补齐价格", candidates);
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
		if (substituted && (flow == null || pressure == null))
			throw failure("原型号缺价或性能不满足；重选需要风量、风压", eligible);
		if (!requestedPowers.isEmpty()) {
			var lowest = eligible.stream().map(x -> firstPower(x.point)).min(BigDecimal::compareTo).orElseThrow();
			eligible.removeIf(x -> firstPower(x.point).compareTo(lowest) != 0);
		}
		Choice selected;
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
				if (eligible.size() > 1)
					throw failure("主路径仍有多个合格工况点，请明确候选型号/功率/转速配置", eligible);
			}
			if (backup || reselect) {
				var smallest = eligible.stream()
					.map(x -> n(x.point.get("size")))
					.min(BigDecimal::compareTo)
					.orElseThrow();
				eligible.removeIf(x -> n(x.point.get("size")).compareTo(smallest) != 0);
			}
			eligible.sort(EquipmentFanSelector::score);
			Choice first = eligible.get(0);
			var best = eligible.stream().filter(x -> score(x, first) == 0).toList();
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
						"requested_power", attrs.getOrDefault("power", ""), "power_override", overridePower,
						"substituted", substituted, "selection_path",
						!requested.isEmpty() || !requestedPowers.isEmpty() ? "main" : "performance"));
	}

}
