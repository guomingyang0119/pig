package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import com.pig4cloud.pig.admin.quotation.EquipmentFanSelector.Cost;

/**
 * 设备供货和复合订单报价引擎。
 * <p>
 * Python 版本只作为迁移核对基线，运行时由本 Java 实现独立完成产品分类、参数提取、选型、成本和售价计算。
 * 输入不能唯一匹配产品、型号或成本规则时保留候选项并标记待确认，不会用相似名称直接替代。
 */
public final class EquipmentPricingEngine {

	public static final String VERSION = "equipment-v6", MIXED_VERSION = "mixed-v6";

	public static final Set<String> KINDS = Set.of("fan", "fire", "electric_fire", "electric_control", "manual",
			"check", "pressure_relief", "multi_leaf", "plate_smoke", "louver", "silencer", "plenum", "elbow",
			"purchased");

	private static final String DECIMAL = "(\\d+(?:\\.\\d+)?)";

	private static Matcher match(String pattern, String text) {
		return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
	}

	private static boolean has(String pattern, String text) {
		return match(pattern, text).find();
	}

	private static boolean contains(String text, String... words) {
		return Arrays.stream(words).anyMatch(text::contains);
	}

	private static BigDecimal div(BigDecimal a, Object b) {
		return a.divide(n(b), MC);
	}

	private static BigDecimal ceil(BigDecimal a) {
		return a.setScale(0, RoundingMode.CEILING);
	}

	private static EquipmentFraction fraction(Object v) {
		return EquipmentFraction.of(n(v));
	}

	private static String ftext(EquipmentFraction f) {
		return text(f.decimal());
	}

	private static boolean enabled(Object v) {
		return Set.of("true", "1", "是", "有").contains(str(v).toLowerCase(Locale.ROOT));
	}

	private static BigDecimal required(Map<String, Object> a, String key, String label) {
		if (blank(a.get(key)))
			throw new RuleException("请补充" + label);
		return positive(a.get(key), label);
	}

	/**
	 * 识别设备类别。报价人员明确选择的 {@code equipment_kind} 优先；文字规则从更具体的组合产品向普通类别匹配，避免被宽泛关键词截走。
	 */
	public static String classify(Map<String, Object> row) {
		var attrs = map(row.get("attributes"));
		if (!blank(attrs.get("equipment_kind")))
			return s(attrs, "equipment_kind");
		String name = s(row, "name");
		if (name.contains("风机") || has("^\\s*(?:HTF|HTFC|SWF|GXF|HL3)[-（(]", name))
			return "fan";
		if (contains(name, "通风器", "换气扇", "排气扇"))
			return "purchased";
		String[][] rules = { { "elbow", "消声弯头", "消音弯头" }, { "plenum", "接管箱", "静压箱" },
				{ "silencer", "消声器", "消音器", "消声静压箱" }, { "plate_smoke", "板式排烟" },
				{ "multi_leaf", "多叶排烟", "多叶送风口", "正压送风口", "正压送风阀", "组合送风口" }, { "pressure_relief", "余压" },
				{ "check", "止回" }, { "electric_fire", "电动防火" }, { "fire", "防火阀", "排烟阀", "防烟阀" },
				{ "electric_control", "电动调节", "电动风量" }, { "manual", "调节阀", "调节风阀" }, { "fan", "风机", "通风机" },
				{ "louver", "百叶", "散流器", "检修口", "检修门", "条缝风口", "线形风口" } };
		for (String[] rule : rules)
			for (int i = 1; i < rule.length; i++)
				if (name.contains(rule[i]))
					return rule[0];
		return s(row, "category").equals("外购设备") ? "purchased" : "";
	}

	/**
	 * 从名称、规格和备注中提取尺寸、型号、风量、风压、功率等参数。已有人工属性不会被文字解析结果覆盖。
	 */
	public static Map<String, Object> parseAttributes(Map<String, Object> row) {
		Map<String, Object> a = obj();
		map(row.get("attributes")).forEach((k, v) -> {
			if (v != null && !"".equals(v))
				a.put(k, v);
		});
		String all = s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark");
		Matcher panel = match(DECIMAL + "\\s*[×xX*＊]\\s*[（(]\\s*" + DECIMAL + "\\s*\\+\\s*250\\s*[）)]", all);
		if (panel.find() && Set.of("multi_leaf", "plate_smoke").contains(classify(row))) {
			a.putIfAbsent("width", panel.group(1));
			a.putIfAbsent("height", panel.group(2));
		}
		Matcher dims = match(DECIMAL + "\\s*[×xX*＊]\\s*" + DECIMAL + "(?:\\s*[×xX*＊]\\s*" + DECIMAL + ")?", all);
		if (dims.find()) {
			String[] keys = { "width", "height", "length" };
			for (int i = 0; i < 3; i++)
				if (dims.group(i + 1) != null)
					a.putIfAbsent(keys[i], dims.group(i + 1));
		}
		Matcher diameter = match("(?:[ΦφØø]|DN|直径\\s*[:：=]?)\\s*" + DECIMAL, all);
		if (diameter.find())
			a.putIfAbsent("diameter", diameter.group(1));
		if (!blank(a.get("diameter"))) {
			a.put("width", a.get("diameter"));
			a.put("height", a.get("diameter"));
			a.putIfAbsent("shape", "round");
		}
		if (contains(all, "圆形", "圆型"))
			a.putIfAbsent("shape", "round");
		for (String grade : List.of("304", "201"))
			if (all.contains(grade) && (all.contains("不锈") || has(grade + "\\s*(?:钢|SS)", all)))
				a.putIfAbsent("material", "stainless_" + grade);
		if (all.contains("不锈钢"))
			a.putIfAbsent("material", "不锈钢");
		for (String[] rule : new String[][] { { "防爆", "explosion", "true" }, { "远控", "remote", "true" },
				{ "双层", "layer", "double" }, { "角钢", "flange", "angle" }, { "喉部", "dimension_basis", "throat" } })
			if (all.contains(rule[0]))
				a.putIfAbsent(rule[1], rule[2]);
		Map<String, String> patterns = new LinkedHashMap<>();
		patterns.put("airflow", "(?:风量|(?<![A-Z])(?:Q|L)\\s*[:：=＝])\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("pressure", "(?:全压|静压|风压|(?<![A-Z])(?:P|H)\\s*[:：=＝])\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("speed", "(?:转速\\s*[:：=＝]?\\s*|(?<![A-Z])N\\s*[:：=＝]\\s*)" + DECIMAL + "\\s*(?:r/min|rpm|转/分)");
		patterns.put("thickness", "(?:厚度|板厚|厚)\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("power", "(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?)?)\\s*(?:kW|千瓦)");
		patterns.forEach((key, pattern) -> {
			Matcher m = match(pattern, all);
			if (m.find())
				a.putIfAbsent(key, m.group(1).replace(" ", ""));
		});
		for (String key : List.of("airflow", "pressure")) {
			Matcher pair = match(patterns.get(key) + "\\s*/\\s*" + DECIMAL, all);
			if (pair.find())
				a.putIfAbsent(key + "_low", pair.group(2));
		}
		String modelPattern = classify(row).equals("purchased") ? "\\b[A-Z]{2,}[A-Z\\d]*[-－][A-Z\\d.－-]+"
				: "\\b(?:HTFC|HTF(?:\\([A-Z]\\))?|SWF|GXF|HL3)[-－A-ZIⅠⅡ\\d.()]*";
		Matcher model = match(modelPattern, all);
		if (!a.containsKey("power")) {
			Matcher watts = match(DECIMAL + "\\s*W\\b", all);
			if (watts.find())
				a.put("power", text(div(n(watts.group(1)), 1000)));
		}
		if (model.find())
			a.putIfAbsent("model", model.group());
		if (!blank(a.get("model")))
			a.put("raw_model", a.get("model"));
		return a;
	}

	/** 按阀体尺寸、叶片数量、材料、机构、拼接和面板等组成项计算阀门基础成本。 */
	public static Cost valveCost(String originalKind, Map<String, Object> a, Map<String, Object> catalog) {
		Map<String, Object> rates = map(catalog.get("rates"));
		boolean proxy = originalKind.equals("pressure_relief");
		String kind = proxy ? "fire" : originalKind;
		Map<String, Object> cfg = map(map(catalog.get("valves")).get(kind));
		BigDecimal w = required(a, "width", "宽度（mm）"), h = required(a, "height", "高度（mm）");
		boolean split = w.compareTo(rate(rates, "split_width")) > 0 || h.compareTo(rate(rates, "split_height")) > 0;
		BigDecimal groups = n(split ? 2 : 1), blades = mul(ceil(div(kind.equals("multi_leaf") ? w : h, 200)), groups);
		BigDecimal ft = positive(a.getOrDefault("thickness", "1.2"), "阀体板厚");
		BigDecimal bt = positive(a.getOrDefault("blade_thickness", cfg.get("blade_thickness")), "叶片板厚"),
				steel = rate(rates, "valve_steel");
		var env = obj("width", w, "height", h, "groups", groups, "blades", blades, "density", rate(rates, "density"),
				"frame_thickness", ft, "blade_thickness", bt, "labor_base", n(cfg.get("labor_base")), "brace_count",
				n(!split || kind.equals("manual") ? 2 : 4), "frame_price",
				EquipmentFanSelector.materialPrice(catalog, a.get("material"), ft, steel), "blade_price",
				EquipmentFanSelector.materialPrice(catalog, a.get("material"), bt, steel), "back_price",
				kind.equals("multi_leaf") ? EquipmentFanSelector.materialPrice(catalog, a.get("material"), ".5", steel)
						: steel);
		List<String> keys = List.of("frame", "blade", "labor", "link", "corners", "shaft", "consumables", "brace");
		if (kind.equals("multi_leaf"))
			keys = List.of("multi_frame", "multi_blade", "labor", "link", "shaft", "consumables", "multi_brace",
					"multi_back");
		if (kind.equals("plate_smoke")) {
			keys = List.of("plate_frame", "plate_blade", "labor", "link", "corners", "shaft", "plate_consumables",
					"plate_brace");
			blades = ceil(div(h, 200));
			env.put("blades", blades);
		}
		Map<String, BigDecimal> parts = new LinkedHashMap<>();
		for (String key : keys)
			parts.put(key, DecimalFormula.evaluate(s(map(catalog.get("valve_expressions")), key), env));
		if (kind.equals("plate_smoke")) {
			parts.put("link", ZERO);
			parts.put("shaft", ZERO);
		}
		if (kind.equals("check")) {
			parts.put("link", ZERO);
			if (!split)
				parts.put("blade", mul(parts.get("blade"), n("1.2")));
		}
		if (kind.equals("multi_leaf") && split)
			parts.put("shaft", mul(parts.get("shaft"), n(2)));
		BigDecimal mechanism = n(cfg.get("mechanism"));
		String control = str(a.getOrDefault("control", "near"));
		if (!Set.of("near", "remote", "auto").contains(control))
			throw new RuleException("机构方式未识别");
		if (kind.equals("plate_smoke"))
			mechanism = rate(rates, control + "_control");
		if (!blank(a.get("mechanism_cost")))
			mechanism = n(a.get("mechanism_cost"));
		if (enabled(a.get("remote")) || control.equals("remote"))
			mechanism = add(mechanism, rate(rates, "remote_extra"));
		parts.put("mechanism", mechanism);
		BigDecimal splice = ZERO;
		if (Set.of("fire", "electric_fire", "electric_control", "manual").contains(kind)) {
			boolean overW = w.compareTo(rate(rates, "splice_width")) > 0,
					overH = h.compareTo(rate(rates, "splice_height")) > 0;
			if (overW && overH) {
				if (blank(a.get("splice_cost")))
					throw new RuleException("宽超过2000且高超过1000，请报价人员确认拼接费用");
				splice = n(a.get("splice_cost"));
			}
			else if (overW || overH)
				splice = rate(rates, kind.equals("manual") ? "manual_splice" : "fire_splice");
		}
		parts.put("splice", splice);
		if (Set.of("multi_leaf", "plate_smoke").contains(kind))
			parts.put("panel", div(mul(add(w, rate(rates, "panel_edge")), add(h, rate(rates, "panel_edge")),
					rate(rates, "panel_rate")), 1000000));
		if (parts.values().stream().anyMatch(v -> v.signum() < 0))
			throw new RuleException("尺寸或成本参数使阀体成本为负，请核对");
		Map<String, Object> components = obj();
		parts.forEach((k, v) -> components.put(k, text(v)));
		return new Cost(parts.values().stream().reduce(ZERO, QuoteData::add), obj("components", components, "split",
				split, "groups", text(groups), "blades", text(blades), "price_proxy", proxy ? "对应规格防火阀" : null,
				"material_prices",
				obj("frame_price", text(n(env.get("frame_price"))), "blade_price", text(n(env.get("blade_price"))))));
	}

	private static String louverType(Map<String, Object> row, Map<String, Object> a) {
		if (!blank(a.get("louver_type")))
			return s(a, "louver_type");
		String all = s(row, "name") + " " + s(row, "spec"), base = "";
		for (String[] pair : new String[][] { { "散流", "diffuser" }, { "防雨", "rain" }, { "自垂", "gravity" },
				{ "门铰", "hinged" }, { "检修", "access" }, { "条缝", "linear" }, { "线形", "linear" }, { "双层", "double" },
				{ "单层", "single" } })
			if (all.contains(pair[0])) {
				base = pair[1];
				break;
			}
		if (base.isEmpty())
			throw new RuleException("请明确风口类型（单层、双层、散流器等）");
		if (all.toUpperCase(Locale.ROOT).contains("ABS"))
			return "abs_" + base + (base.equals("hinged") && all.contains("网") ? "_mesh" : "");
		if (all.contains("阀") && all.contains("网"))
			throw new RuleException("同时带阀和网的风口需明确组合成本，不能只套其中一种");
		return base + (all.contains("阀") ? "_valve" : all.contains("网") ? "_mesh" : "");
	}

	/**
	 * 计算风口、消声器和静压箱的面积成本。缺少尺寸、特殊材质成本或板厚系数时直接待确认，避免套用普通镀锌产品价格。
	 */
	public static Cost areaCost(String kind, Map<String, Object> row, Map<String, Object> a,
			Map<String, Object> catalog) {
		BigDecimal w = required(a, "width", "宽度（mm）"), h = required(a, "height", "高度（mm）");
		Map<String, Object> rates = map(catalog.get("rates"));
		String material = s(a, "material");
		if ((contains(material, "不锈", "stainless") || Set.of("201", "304").contains(material))
				&& blank(a.get("area_cost")))
			throw new RuleException("该产品采用综合面积成本，源表未分解不锈钢用量；请提供已核实的每㎡成本");
		if (kind.equals("louver")) {
			String type = louverType(row, a);
			String pricingType = type.startsWith("gravity") ? "single" + type.substring("gravity".length()) : type;
			if (!map(catalog.get("louvers")).containsKey(pricingType))
				throw new RuleException("该风口类型尚无成本依据，请核对类型");
			List<?> values = (List<?>) map(catalog.get("louvers")).get(pricingType);
			BigDecimal rate = n(values.get(0)), minimum = n(values.get(1)), edge = n(values.get(2)),
					factor = n(values.get(3));
			if (s(a, "dimension_basis").equals("throat") && type.startsWith("diffuser"))
				edge = n(130);
			BigDecimal area = div(mul(add(w, edge), add(h, edge)), 1000000);
			return new Cost(mul(area, n(a.getOrDefault("area_cost", rate)), factor).max(minimum),
					obj("area", text(area), "louver_type", type, "pricing_louver_type", pricingType, "minimum_cost",
							text(minimum)));
		}
		BigDecimal length = required(a, "length", "长度（mm）");
		if (kind.equals("silencer")) {
			w = add(w, mul(n(2), rate(rates, "silencer_edge")));
			h = add(h, mul(n(2), rate(rates, "silencer_edge")));
		}
		BigDecimal area = div(add(mul(add(w, h), length, n(2)), mul(w, h, n(2))), 1000000);
		String layer = str(a.getOrDefault("layer", "single"));
		if (!Set.of("single", "double").contains(layer))
			throw new RuleException("请明确单层或双层");
		BigDecimal rate = rate(rates, layer.equals("double") ? "double_layer_rate"
				: kind.equals("silencer") ? "silencer_rate" : "plenum_rate");
		BigDecimal cost = mul(area, n(a.getOrDefault("area_cost", rate))),
				thickness = n(a.getOrDefault("thickness", ".75"));
		if (thickness.compareTo(ONE) == 0)
			cost = div(cost, rate(rates, "thickness_1_factor"));
		else if (thickness.compareTo(n("1.2")) == 0)
			cost = div(cost, rate(rates, "thickness_1_2_factor"));
		else if (thickness.compareTo(n(".75")) != 0)
			throw new RuleException("该板厚没有已确认的消声设备成本系数");
		if (s(a, "flange").equals("angle"))
			cost = div(cost, rate(rates, "angle_factor"));
		return new Cost(cost,
				obj("area", text(area), "area_rate", text(n(a.getOrDefault("area_cost", rate))), "layer", layer));
	}

	/** 外购设备必须按标准化型号唯一命中基础价格，同时校验客户单位与价格表单位一致。 */
	private static Cost purchased(Map<String, Object> row, Map<String, Object> a, Map<String, Object> catalog) {
		String model = EquipmentFanSelector.normalize(blank(a.get("model")) ? row.get("spec") : a.get("model"));
		var matches = list(catalog.get("purchased")).stream()
			.filter(p -> EquipmentFanSelector.normalize(p.get("model")).equals(model)
					&& (blank(row.get("candidate_id")) || s(p, "id").equals(s(row, "candidate_id"))))
			.toList();
		if (matches.size() != 1)
			throw new RuleException("外购设备型号缺少唯一基础成本，请在指标中补齐或明确型号");
		var p = matches.get(0);
		if (!blank(p.get("unit")) && !s(p, "unit").equals(s(row, "unit")))
			throw new RuleException("外购设备计价单位与基础价格不一致");
		return new Cost(n(p.get("cost")), obj("purchased", p));
	}

	private static Map<String, Object> trace(String step, String expression, String value) {
		return obj("step", step, "expression", expression, "value", value);
	}

	/**
	 * 计算一条设备产品：识别类别和参数，选择对应成本模型，再依次应用毛利、税口径和向上取整规则。
	 * 无法唯一选型时保留候选产品；业务参数不完整时仅将本行标为待确认。
	 */
	public Map<String, Object> calculateRow(Map<String, Object> row, Map<String, Object> catalog,
			Map<String, Object> options, Map<String, Object> resource) {
		List<Map<String, Object>> traces = new ArrayList<>();
		Map<String, Object> out = obj("status", "pending", "reason", "", "unit_price", null, "amount", null, "trace",
				traces, "candidates", new ArrayList<>(), "pricing_mode", "equipment_v1");
		if (yes(row.get("excluded"))) {
			if (!blank(row.get("exclude_reason")))
				out.putAll(obj("status", "excluded", "reason", row.get("exclude_reason")));
			else
				out.put("reason", "排除计价需要填写原因");
			return out;
		}
		try {
			if (blank(row.get("name")))
				throw new RuleException("请填写设备名称");
			if (yes(row.get("source_issue")) && !yes(row.get("source_issue_confirmed")))
				throw new RuleException("请核对原文件未缓存公式或错误值");
			if (catalog == null)
				throw new RuleException("设备指标尚未发布或存在多个有效指标，请先核对指标版本");
			BigDecimal qty = required(row, "quantity", "数量");
			String kind = classify(row);
			var a = parseAttributes(row);
			if (!KINDS.contains(kind))
				throw new RuleException("未识别设备类型，请明确本行产品类型");
			if (!kind.equals("elbow") && !Set.of("台", "个", "只", "套", "件", "组", "樘").contains(s(row, "unit")))
				throw new RuleException("设备数量单位应为台、个、只、套、件、组或樘");
			var rates = map(catalog.get("rates"));
			var term = list(catalog.get("payment_terms")).stream()
				.filter(t -> s(t, "code").equals(s(options, "payment_option")) && s(t, "mode")
					.equals(yes(options.get("include_installation")) ? "with_install" : "no_install"))
				.findFirst()
				.orElse(null);
			if (term == null)
				throw new RuleException("请选择与安装状态对应的整单付款方式");
			if (!bool(options.get("tax_included")))
				throw new RuleException("请明确设备报价是否含税");
			if (!bool(options.get("include_freight")))
				throw new RuleException("请明确设备是否包含运费");
			// 风机、阀门、面积类产品和外购设备分别使用各自的成本模型。
			Cost calculated = null;
			if (kind.equals("fan")) {
				if (has("(?:带|附)[^；;\\n]*(?:防火阀|止回阀)", s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark")))
					throw new RuleException("风机含附带阀门，请明确整套供货配置及成本；不能只报价普通风机");
				calculated = EquipmentFanSelector.select(row, catalog, a);
			}
			else if (map(catalog.get("valves")).containsKey(kind) || kind.equals("pressure_relief"))
				calculated = valveCost(kind, a, catalog);
			else if (Set.of("louver", "silencer", "plenum").contains(kind))
				calculated = areaCost(kind, row, a, catalog);
			else if (kind.equals("purchased"))
				calculated = purchased(row, a, catalog);
			Map<String, Object> detail = calculated == null ? obj() : calculated.detail();
			EquipmentFraction base;
			if (calculated != null) {
				BigDecimal cost = calculated.amount();
				if (cost.signum() < 0)
					throw new RuleException("设备成本不能为负");
				if (kind.equals("fan") && enabled(a.get("explosion"))) {
					BigDecimal ordinary = cost;
					var tier = list(catalog.get("explosion_tiers")).stream()
						.filter(t -> t.get("limit") == null || ordinary.compareTo(n(t.get("limit"))) <= 0)
						.findFirst()
						.orElseThrow();
					detail.putAll(obj("ordinary_cost", text(cost), "explosion_factor", tier.get("factor")));
					cost = mul(cost, n(tier.get("factor")));
				}
				out.put("unit_cost", text(cost));
				base = fraction(cost).divide(fraction(ONE.subtract(rate(rates, "gross_margin"), MC)));
				traces.add(trace("不含税成本", "本体、机构、电机、面板等按本行适用规则合计", text(cost)));
				traces.add(trace("不含税供货价", "成本 ÷ (1 − 毛利率)", ftext(base)));
				if (yes(options.get("tax_included")))
					base = base.multiply(fraction(rates.get("tax_factor")));
			}
			else {
				// 消声弯头采用人工确认面积（或清单㎡数量）乘固定含税单价，避免从不完整尺寸推算错误面积。
				BigDecimal area;
				if (Set.of("㎡", "m2", "m²", "平方米").contains(s(row, "unit")))
					area = ONE;
				else if (Set.of("台", "个", "只", "件", "组", "套").contains(s(row, "unit")))
					area = required(a, "area_per_unit", "每个消声弯头人工计算面积/方量");
				else
					throw new RuleException("消声弯头请明确按个或㎡计价，避免面积重复乘数量");
				base = fraction(mul(area, rate(rates, "elbow_taxed_rate")));
				detail.putAll(obj("area_per_unit", text(area), "fixed_taxed_base", ftext(base)));
				if (!yes(options.get("tax_included")))
					base = base.divide(fraction(rates.get("tax_factor")));
				traces.add(trace("消声弯头固定供货价", "人工面积 × 200（含税）；不含税报价除税系数", ftext(base)));
			}
			if (s(a, "shape").equals("round") && (map(catalog.get("valves")).containsKey(kind)
					|| Set.of("pressure_relief", "louver", "silencer", "plenum").contains(kind))) {
				base = base.multiply(fraction(rates.get("round_factor")));
				detail.put("round_factor", text(rate(rates, "round_factor")));
			}
			var supplyAmount = base.multiply(fraction(qty));
			out.putAll(obj("supply_unit_unrounded", ftext(base), "supply_amount_unrounded", ftext(supplyAmount)));
			BigDecimal unit = base.ceil(), amount = ceil(mul(unit, qty));
			traces.add(trace("付款前供货价", "供货价已按本单材料税口径；付款系数在整单末尾应用", ftext(base)));
			if (yes(options.get("include_freight"))) {
				traces.add(trace("设备运费", "先按每台售价和地区系数试算，整单运费门槛判断后再决定计入单价或另列", "0"));
				traces.add(trace("设备原单价", "CEILING(供货单价)，设备运费在整单设备行计算完成后判断", text(unit)));
				traces.add(trace("设备原行金额", "CEILING(设备原单价 × 数量)", text(amount)));
			}
			else {
				traces.add(trace("设备运费", "本单选择不含运费", "0"));
				traces.add(trace("设备单价", "CEILING(供货单价)", text(unit)));
				traces.add(trace("设备行金额", "CEILING(设备单价 × 数量)", text(amount)));
			}
			var rule = obj();
			for (String key : List.of("id", "kind", "family", "version"))
				rule.put(key, resource.get(key));
			var rb = obj();
			for (String key : List.of("name", "source", "effective_from"))
				rb.put(key, map(resource.get("body")).get(key));
			rule.put("body", rb);
			out.putAll(obj("status", "priced", "unit_price", text(unit), "amount", text(amount), "body_amount",
					text(amount), "billing_quantity", text(qty), "billing_unit", row.get("unit"), "detail", detail,
					"attributes", a, "equipment_kind", kind, "payment", term, "freight_per_unit", "0",
					"coverage", obj("installation", false, "freight", false), "tax_basis",
					yes(options.get("tax_included")) ? "included" : "excluded", "rule", rule));
		}
		catch (EquipmentFanSelector.SelectionException e) {
			out.put("reason", e.getMessage());
			out.put("candidates",
					e.candidates.stream()
						.map(p -> obj("id", p.get("id"), "body", obj("name", p.get("model"), "attributes", p)))
						.toList());
		}
		catch (RuleException e) {
			out.put("reason", e.getMessage());
		}
		catch (NullPointerException | ClassCastException | ArithmeticException | NoSuchElementException e) {
			out.put("reason", "设备规则或数据结构不完整，请核对指标");
		}
		return out;
	}

	/**
	 * 逐行生成按地区系数计算的设备运费试算方案。是否采用该方案，必须等风管、安装和付款系数都汇总后，按整单试算销售额判断。
	 */
	static Map<String, Object> equipmentFreight(Map<String, Object> options, Map<String, Object> catalog,
			List<Map<String, Object>> rows, int pendingRows, boolean hasEquipmentRows) {
		BigDecimal equipmentSubtotal = rows.stream()
			.filter(r -> s(r, "status").equals("priced"))
			.map(r -> n(r.get("amount")))
			.reduce(ZERO, QuoteData::add);
		if (!hasEquipmentRows)
			return obj("status", "not_applicable", "amount", "0", "reason", "本单没有设备产品", "basis", "none",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		if (!yes(options.get("include_freight")))
			return obj("status", "not_applicable", "amount", "0", "reason", "本单不含运费", "basis", "none",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		if (catalog == null)
			return obj("status", "pending", "amount", null, "reason", "设备指标无效，无法计算整单设备运费", "basis", "pending",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		String region = s(options, "region").replaceFirst("省$", "").replaceFirst("壮族自治区$", "");
		var regions = map(catalog.get("regions"));
		if (!regions.containsKey(region))
			return obj("status", "pending", "amount", null,
					"reason", "含运费报价仅适用贵州、四川、广西、云南、陕西、海南，请明确目的省区", "basis", "pending",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		BigDecimal threshold = rate(map(catalog.get("rates")), "freight_threshold");
		if (pendingRows > 0)
			return obj("status", "pending", "amount", null, "reason", "设备材料尚有待确认项，无法计算整单设备运费",
					"basis", "pending", "included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal), "threshold", text(threshold));
		BigDecimal factor = n(regions.get(region)), calculatedFreight = ZERO;
		List<Map<String, Object>> adjusted = new ArrayList<>();
		for (var row : rows) {
			if (!s(row, "status").equals("priced"))
				continue;
			BigDecimal baseUnit = n(row.get("unit_price")), quantity = n(row.get("billing_quantity")),
					baseAmount = n(row.get("amount")), finalUnit = ceil(mul(baseUnit, factor)),
					finalAmount = ceil(mul(finalUnit, quantity)), rowFreight = finalAmount.subtract(baseAmount, MC);
			calculatedFreight = add(calculatedFreight, rowFreight);
			adjusted.add(obj("row", row, "base_unit", baseUnit, "base_amount", baseAmount, "final_unit", finalUnit,
					"final_amount", finalAmount, "freight_per_unit", finalUnit.subtract(baseUnit, MC), "freight_amount", rowFreight));
		}
		return obj("status", "trial", "amount", text(calculatedFreight), "reason", "设备运费已按地区系数试算，待整单销售额判断",
				"basis", "regional_per_unit_trial", "included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal),
				"calculated_amount", text(calculatedFreight), "threshold", text(threshold), "region", region, "factor", text(factor),
				"_adjustments", adjusted);
	}

	/** 整单试算销售额达到门槛后，才把试算运费写入设备行。 */
	static void applyEquipmentFreightPlan(Map<String, Object> freight) {
		for (var values : list(freight.remove("_adjustments"))) {
			var row = map(values.get("row"));
			row.putAll(obj("base_unit_price", text(n(values.get("base_unit"))), "base_amount", text(n(values.get("base_amount"))),
					"unit_price", text(n(values.get("final_unit"))), "amount", text(n(values.get("final_amount"))), "body_amount",
					text(n(values.get("final_amount"))), "freight_per_unit", text(n(values.get("freight_per_unit"))), "freight_amount",
					text(n(values.get("freight_amount")))));
			map(row.get("coverage")).put("freight", true);
			map(row.get("detail")).putAll(obj("freight_basis", "regional_per_unit", "freight_factor", freight.get("factor")));
			list(row.get("trace")).add(trace("含运费单价", "CEILING(设备原单价 × 送货省区运费系数)", text(n(values.get("final_unit")))));
			list(row.get("trace")).add(trace("含运费行金额", "CEILING(含运费单价 × 数量)", text(n(values.get("final_amount")))));
		}
	}

	static void discardEquipmentFreightPlan(Map<String, Object> freight) {
		freight.remove("_adjustments");
	}

	/** 计算纯设备订单并汇总设备材料和整单设备运费，随后交给统一安装与付款边界处理。 */
	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		return calculate(quote, resources, policy, false);
	}

	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy, boolean supplyOnly) {
		List<String> issues = new ArrayList<>();
		var active = EquipmentCatalog.active(resources, LocalDate.now());
		Map<String, Object> catalog = null, resource = null;
		if (active.size() == 1) {
			resource = active.get(0);
			try {
				EquipmentCatalog.validate(map(resource.get("body")));
				catalog = map(resource.get("body"));
			}
			catch (RuleException e) {
				issues.add("设备指标无效：" + e.getMessage());
			}
		}
		else
			issues.add("需要一个已发布且生效的设备指标版本");
		var options = new LinkedHashMap<>(map(quote.get("equipment_options")));
		options.put("include_installation", InstallationPricingEngine.enabled(quote));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (var row : list(quote.get("rows")))
			rows.add(calculateRow(row, catalog, options, resource));
		BigDecimal equipmentMaterialSubtotal = rows.stream()
			.filter(r -> s(r, "status").equals("priced"))
			.map(r -> n(r.get("amount")))
			.reduce(ZERO, QuoteData::add);
		int pending = (int) rows.stream().filter(r -> s(r, "status").equals("pending")).count();
		if (pending > 0)
			issues.add("有" + pending + "项设备待确认，未计入小计");
		if (rows.stream().noneMatch(r -> s(r, "status").equals("priced")))
			issues.add("没有已计算的设备项目");
		Object term = catalog == null ? null
				: list(catalog.get("payment_terms")).stream()
					.filter(t -> s(t, "code").equals(s(options, "payment_option")))
					.findFirst()
					.orElse(null);
		boolean hasEquipmentRows = list(quote.get("rows")).stream().anyMatch(r -> !yes(r.get("excluded")));
		var freight = equipmentFreight(options, catalog, rows, pending, hasEquipmentRows);
		if (s(freight, "status").equals("pending"))
			issues.add("设备运费：" + s(freight, "reason"));
		BigDecimal subtotal = rows.stream()
			.filter(r -> s(r, "status").equals("priced"))
			.map(r -> n(r.get("amount")))
			.reduce(ZERO, QuoteData::add);
		BigDecimal extraFreight = yes(freight.get("included_in_rows")) || blank(freight.get("amount")) ? ZERO
				: n(freight.get("amount"));
		String total = issues.isEmpty() ? text(add(subtotal, extraFreight)) : null;
		if (!yes(policy.get("formal_template_confirmed")))
			issues.add("正式客户模板尚未确认");
		var result = obj("rows", rows, "subtotal", text(subtotal), "material_total", text(equipmentMaterialSubtotal),
				"equipment_material_total", text(equipmentMaterialSubtotal), "equipment_freight_total", freight.get("amount"), "total", total,
				"pending_count", pending, "issues", issues, "formal_ready", issues.isEmpty(), "fees",
				obj("equipment_freight", freight), "policy", policy, "payment", term,
				"accessory_items", new ArrayList<>(), "rounding_difference", "0", "engine_version", VERSION);
		return InstallationPricingEngine.finalizeOrder(quote, result, resources, supplyOnly, LocalDate.now());
	}

	/**
	 * 复合报价先按原清单行号拆成风管组和设备组，分别调用各自引擎，再按原顺序合并。
	 * 安装费和付款系数只在合并后的整单结果上处理一次。
	 */
	public Map<String, Object> mixed(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		var input = list(quote.get("rows"));
		List<Map<String, Object>> rows = new ArrayList<>(Collections.nCopies(input.size(), null));
		Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
		for (String label : List.of("duct", "equipment")) {
			List<Integer> indices = new ArrayList<>();
			for (int i = 0; i < input.size(); i++)
				if (s(input.get(i), "category").equals("风管") == label.equals("duct"))
					indices.add(i);
			if (indices.isEmpty())
				continue;
			if (indices.stream()
				.allMatch(i -> yes(input.get(i).get("excluded")) && !blank(input.get(i).get("exclude_reason")))) {
				for (int i : indices)
					rows.set(i, obj("status", "excluded", "reason", input.get(i).get("exclude_reason"), "unit_price",
							null, "amount", null, "trace", new ArrayList<>(), "candidates", new ArrayList<>()));
				continue;
			}
			Map<String, Object> sub = new LinkedHashMap<>(quote);
			sub.put("rows", indices.stream().map(input::get).toList());
			var group = label.equals("duct")
					? new DuctPricingEngine().calculate(sub, resources, policy, LocalDate.now(), true)
					: calculate(sub, resources, policy, true);
			for (int j = 0; j < indices.size(); j++)
				rows.set(indices.get(j), list(group.get("rows")).get(j));
			groups.put(label, group);
		}
		List<String> issues = new ArrayList<>();
		groups.values().forEach(g -> ((List<?>) g.get("issues")).forEach(i -> issues.add(str(i))));
		if (groups.isEmpty())
			issues.add("清单没有需要计价的产品行");
		var dopt = map(quote.get("duct_options"));
		var eopt = map(quote.get("equipment_options"));
		if (groups.containsKey("duct") && groups.containsKey("equipment")) {
			if (!Objects.equals(dopt.get("tax_included"), eopt.get("tax_included")))
				issues.add("复合报价的风管和设备含税口径需保持一致");
			if (!Objects.equals(dopt.get("payment_option"), eopt.get("payment_option")))
				issues.add("复合报价的风管和设备付款方式需保持一致");
			var dterm = map(groups.get("duct").get("payment"));
			var eterm = map(groups.get("equipment").get("payment"));
			if (!dterm.isEmpty() && !eterm.isEmpty()
					&& n(dterm.get("coefficient")).compareTo(n(eterm.get("coefficient"))) != 0)
				issues.add("风管与设备指标的整单付款系数不一致，请领导核对");
		}
		BigDecimal subtotal = groups.values().stream().map(g -> n(g.get("subtotal"))).reduce(ZERO, QuoteData::add);
		String total = issues.isEmpty() ? text(subtotal) : null;
		var duct = groups.getOrDefault("duct", obj());
		var equipment = groups.getOrDefault("equipment", obj());
		var fees = new LinkedHashMap<>(map(duct.get("fees")));
		if (!equipment.isEmpty() && map(equipment.get("fees")).containsKey("equipment_freight"))
			fees.put("equipment_freight", map(equipment.get("fees")).get("equipment_freight"));
		BigDecimal materialTotal = add(n(duct.getOrDefault("subtotal", "0")),
				n(equipment.getOrDefault("equipment_material_total", "0")));
		var result = obj("rows", rows, "subtotal", text(subtotal), "material_total", text(materialTotal), "total", total, "pending_count",
				groups.values().stream().mapToInt(g -> n(g.get("pending_count")).intValue()).sum(), "issues", issues,
				"formal_ready", issues.isEmpty(), "fees", fees, "policy", policy, "payment",
				groups.getOrDefault("equipment", duct).get("payment"), "accessory_items",
				duct.getOrDefault("accessory_items", new ArrayList<>()), "aggregate_flanges",
				duct.getOrDefault("aggregate_flanges", new ArrayList<>()), "equipment_material_total",
				equipment.getOrDefault("subtotal", "0"), "equipment_freight_total",
				map(equipment.get("fees")).containsKey("equipment_freight")
						? map(map(equipment.get("fees")).get("equipment_freight")).get("amount") : "0",
				"duct_result", duct, "engine_version",
				MIXED_VERSION, "duct_engine_version", DuctPricingEngine.VERSION);
		return InstallationPricingEngine.finalizeOrder(quote, result, resources, false, LocalDate.now());
	}

}
