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

	// 纯设备及混合报价分别记录引擎版本，供正式导出检查试算是否仍适用。
	public static final String VERSION = "equipment-v9", MIXED_VERSION = "mixed-v9";

	// 内置设备计算类型；组合和全新公式产品另通过产品规则进入 custom 成本分支。
	public static final Set<String> KINDS = Set.of("fan", "fire", "electric_fire", "electric_control", "manual",
			"check", "pressure_relief", "multi_leaf", "plate_smoke", "louver", "silencer", "plenum", "elbow",
			"purchased");

	// 尺寸与工况提取使用的非负十进制文本格式，数值范围由后续业务校验决定。
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

	// 中间十进制除法使用统一精度；最终设备售价的毛利和税费阶段改用精确分数。
	private static BigDecimal div(BigDecimal a, Object b) {
		return a.divide(n(b), MC);
	}

	// 设备单价及行金额均向上取整到整数元，不采用风管的 1 位小数规则。
	private static BigDecimal ceil(BigDecimal a) {
		return a.setScale(0, RoundingMode.CEILING);
	}

	// 将十进制值无损转成分数，保留毛利/税费计算的精确边界。
	private static EquipmentFraction fraction(Object v) {
		return EquipmentFraction.of(n(v));
	}

	// 分数仅在内部明细输出时转为十进制文字，计算继续保留精确值。
	private static String ftext(EquipmentFraction f) {
		return text(f.decimal());
	}

	// 兼容中文或文本形式的防爆、远控等行属性开关；整单税费开关另要求布尔值。
	private static boolean enabled(Object v) {
		return Set.of("true", "1", "是", "有").contains(str(v).toLowerCase(Locale.ROOT));
	}

	// 数量、尺寸等必填参数要求正数，缺失或非正值会作为本行待确认原因。
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
		String modelText = name + " " + s(row, "spec") + " " + s(row, "remark") + " " + s(row, "source_text");
		if (name.contains("风机")
				|| has("(?<![A-Z0-9])(?:HTFC?|SWF|GXF|HL3|DFBZ)(?:[-－（(]|\\b)", modelText))
			return "fan";
		if (contains(name, "通风器", "换气扇", "排气扇"))
			return "purchased";
		String[][] rules = { { "elbow", "消声弯头", "消音弯头" }, { "plenum", "接管箱", "静压箱" },
				{ "silencer", "消声器", "消音器", "消声静压箱" }, { "plate_smoke", "板式排烟" },
				{ "multi_leaf", "多叶排烟", "多叶送风口", "多叶加压送风口", "正压送风口", "加压送风口", "正压送风阀", "组合送风口" },
				{ "pressure_relief", "余压", "泄压阀" },
				{ "check", "止回" }, { "electric_fire", "电动防火" },
				{ "fire", "防火调节阀", "防火阀", "防烟防火阀", "排烟阀", "防烟阀" },
				{ "electric_control", "电动调节", "电动风量", "电动阀", "电动蝶阀" },
				{ "manual", "调节阀", "调节风阀", "蝶阀" }, { "fan", "风机", "通风机" },
				{ "louver", "百叶", "散流器", "检修口", "检修门", "条缝风口", "线形风口" } };
		for (String[] rule : rules)
			for (int i = 1; i < rule.length; i++)
				if (name.contains(rule[i]))
					return rule[0];
		return s(row, "category").equals("外购设备") ? "purchased" : "";
	}

	/**
	 * 从名称、规格和备注中提取尺寸、型号、风量、风压、功率等参数。已有属性优先，复合尺寸则按明确表达式拆分阀体与面板段。
	 */
	public static Map<String, Object> parseAttributes(Map<String, Object> row) {
		Map<String, Object> a = obj();
		map(row.get("attributes")).forEach((k, v) -> {
			if (v != null && !"".equals(v))
				a.put(k, v);
		});
		String all = s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark") + " " + s(row, "source_text");
		// 复合尺寸明确写阀体高与面板段，计算宽高始终取阀体；AI 的合计高度不能覆盖该口径。
		String dimensionsText = has(DECIMAL + "\\s*[×xX*＊]", s(row, "spec")) ? s(row, "spec") : s(row, "name");
		if (dimensionsText.isBlank()) dimensionsText = s(a, "dimension_expression");
		Matcher panel = match(DECIMAL + "\\s*[×xX*＊]\\s*[（(]\\s*" + DECIMAL + "\\s*\\+\\s*" + DECIMAL + "\\s*[）)]", dimensionsText);
		if (panel.find() && Set.of("multi_leaf", "plate_smoke").contains(classify(row))) {
			a.putAll(obj("width", panel.group(1), "height", panel.group(2), "body_width", panel.group(1),
					"body_height", panel.group(2), "panel_height", panel.group(3), "dimension_expression", panel.group(),
					"overall_height", text(add(n(panel.group(2)), n(panel.group(3))))));
		}
		Matcher dims = match(DECIMAL + "\\s*[×xX*＊]\\s*" + DECIMAL + "(?:\\s*[×xX*＊]\\s*" + DECIMAL + ")?", dimensionsText + " " + all);
		if (dims.find()) {
			String[] keys = { "width", "height", "length" };
			for (int i = 0; i < 3; i++)
				if (dims.group(i + 1) != null)
					a.putIfAbsent(keys[i], dims.group(i + 1));
		}
		Matcher diameter = match("(?:[ΦφØø]|DN|直径\\s*[:：=]?)\\s*" + DECIMAL, all);
		if (diameter.find())
			a.putIfAbsent("diameter", diameter.group(1));
		// 圆形尺寸统一用直径作为宽高，并记录 round；不能同时把直径和矩形尺寸重复计入。
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
		patterns.put("pressure", "(?:全压|静压|风压|CMH\\s*P|(?<![A-Z0-9.])(?:P|H))\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("speed", "(?:转速\\s*[:：=＝]?\\s*|(?<![A-Z])N\\s*[:：=＝]\\s*)?" + DECIMAL + "\\s*(?:r/min|rpm|转/分)");
		patterns.put("thickness", "(?:厚度|板厚|厚)\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("power", "(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?)?)\\s*(?:kW|千瓦)");
		patterns.forEach((key, pattern) -> {
			Matcher m = match(pattern, all);
			if (m.find())
				a.putIfAbsent(key, m.group(1).replace(" ", ""));
		});
		// 斜线高低档风量、风压分别记录，双速选型时低档条件也要满足。
		for (String key : List.of("airflow", "pressure")) {
			Matcher pair = match(patterns.get(key) + "\\s*/\\s*" + DECIMAL, all);
			if (pair.find())
				a.putIfAbsent(key + "_low", pair.group(2));
		}
		String modelPattern = classify(row).equals("purchased") ? "\\b[A-Z]{2,}[A-Z\\d]*[-－][A-Z\\d.－-]+"
				: "(?<![A-Z0-9])(?:HTFC|HTF(?:\\([A-Z]\\))?|SWF|GXF|HL3|DFBZ)[-－A-ZIⅠⅡ\\d.()]*";
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
		// 阀门宽高和板厚用 mm，材料吨价由镀锌基础价或不锈钢板厚价提供。
		Map<String, Object> rates = map(catalog.get("rates"));
		// 余压阀按对应尺寸防火阀成本计算，并在明细中保留代价依据。
		boolean proxy = originalKind.equals("pressure_relief");
		String kind = proxy ? "fire" : originalKind;
		Map<String, Object> cfg = map(map(catalog.get("valves")).get(kind));
		String label = EquipmentReview.KIND_LABELS.getOrDefault(kind, kind);
		if (cfg.isEmpty()) throw new RuleException(label + "成本规则未发布，请补齐该类型的机构、叶片及人工成本资料");
		if (List.of("blade_thickness", "mechanism", "labor_base").stream().anyMatch(k -> blank(cfg.get(k))))
			throw new RuleException(label + "成本规则缺少叶片板厚、机构费或基础人工费，请补齐成本资料");
		BigDecimal w = required(a, "width", "宽度（mm）"), h = required(a, "height", "高度（mm）");
		// 宽或高超过分组阈值时按两组；叶片以每 200mm 向上取整，多叶排烟按宽、其他阀按高。
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
		// 按阀门结构选择组成公式，随后应用板式、止回及多叶分组的已确认差异。
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
		// 板式排烟机构按近控/远控/自动取价；人工机构成本优先，远控额外费另叠加。
		BigDecimal mechanism = n(cfg.get("mechanism"));
		String control = str(a.getOrDefault("control", "near"));
		if (!Set.of("near", "remote", "auto").contains(control))
			throw new RuleException("机构方式未识别");
		if (kind.equals("plate_smoke"))
			mechanism = rate(rates, control + "_control");
		if (!blank(a.get("mechanism_cost")))
			mechanism = n(a.get("mechanism_cost"));
		BigDecimal mechanismBase = mechanism, remoteExtra = ZERO;
		if (enabled(a.get("remote")) || control.equals("remote")) {
			remoteExtra = rate(rates, "remote_extra");
			mechanism = add(mechanism, remoteExtra);
		}
		parts.put("mechanism", mechanism);
		// 仅适用类型才计拼接；宽高同时超阈值必须人工确认费用，单边超阈值按指标取费，止回阀不加拼接。
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
		// 组成项金额及实际公式同时保存，明细反映零项、倍率、机构和面板的真实计算口径。
		Map<String, Object> components = obj();
		parts.forEach((k, v) -> components.put(k, text(v)));
		Map<String, Object> componentFormulas = obj();
		Map<String, Object> expressions = map(catalog.get("valve_expressions"));
		for (String key : parts.keySet())
			componentFormulas.put(key, expressions.getOrDefault(key, text(parts.get(key))));
		if (kind.equals("plate_smoke")) {
			componentFormulas.put("link", "0（板式不计连杆）");
			componentFormulas.put("shaft", "0（板式不计圆钢）");
		}
		if (kind.equals("check")) {
			componentFormulas.put("link", "0（止回阀不计连杆）");
			if (!split)
				componentFormulas.put("blade", "(" + s(expressions, "blade") + ") × 1.2");
		}
		if (kind.equals("multi_leaf") && split)
			componentFormulas.put("shaft", "(" + s(expressions, "shaft") + ") × 2");
		componentFormulas.put("mechanism", text(mechanismBase) + " + " + text(remoteExtra));
		componentFormulas.put("splice", text(splice));
		if (parts.containsKey("panel"))
			componentFormulas.put("panel", "(" + text(w) + " + " + s(rates, "panel_edge") + ") × ("
					+ text(h) + " + " + s(rates, "panel_edge") + ") × " + s(rates, "panel_rate") + " ÷ 1000000");
		return new Cost(parts.values().stream().reduce(ZERO, QuoteData::add), obj("components", components, "split",
				split, "groups", text(groups), "blades", text(blades), "price_proxy", proxy ? "对应规格防火阀" : null,
				"component_inputs", env, "component_formulas", componentFormulas,
				"body_width", text(w), "body_height", text(h), "panel_height", a.get("panel_height"),
				"overall_height", a.get("overall_height"), "dimension_expression", a.get("dimension_expression"),
				"panel_outer_width", parts.containsKey("panel") ? text(add(w, rate(rates, "panel_edge"))) : null,
				"panel_outer_height", parts.containsKey("panel") ? text(add(h, rate(rates, "panel_edge"))) : null,
				"mechanism_base", text(mechanismBase), "remote_extra", text(remoteExtra), "material_prices",
				obj("frame_price", text(n(env.get("frame_price"))), "blade_price", text(n(env.get("blade_price"))))));
	}

	// 风口类型和计价口径共用核验规则，有冲突或未知类型时先待确认。
	private static String louverType(Map<String, Object> row, Map<String, Object> a) {
		var recognition = EquipmentReview.louver(row, a);
		if (!blank(recognition.get("issue"))) throw new RuleException(s(recognition, "issue"));
		return s(recognition, "louver_type");
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
			var recognition = EquipmentReview.louver(row, a);
			String basis = s(recognition, "pricing_louver_type");
			String pricingType = basis.startsWith("gravity") ? "single" + basis.substring("gravity".length()) : basis;
			if (!map(catalog.get("louvers")).containsKey(pricingType))
				throw new RuleException("该风口类型尚无成本依据，请核对类型");
			// 风口配置四项依次为元/㎡成本、最低元/个成本、宽高增加 mm 值和附件倍率。
			List<?> values = (List<?>) map(catalog.get("louvers")).get(pricingType);
			BigDecimal rate = n(values.get(0)), minimum = n(values.get(1)), edge = n(values.get(2)),
					factor = n(values.get(3));
			if (s(a, "dimension_basis").equals("throat") && type.startsWith("diffuser"))
				edge = n(130);
			// 宽高增加边距后除以 1000000 换成㎡；实际成本为面积×成本价×倍率与最低成本的较大值。
			BigDecimal area = div(mul(add(w, edge), add(h, edge)), 1000000);
			return new Cost(mul(area, n(a.getOrDefault("area_cost", rate)), factor).max(minimum),
					obj("width", text(w), "height", text(h), "edge", text(edge), "area", text(area),
						"area_rate", text(n(a.getOrDefault("area_cost", rate))), "area_factor", text(factor),
						"louver_type", type, "pricing_louver_type", pricingType, "minimum_cost", text(minimum),
						"pricing_louver_basis", basis, "recognition_evidence", recognition.get("recognition_evidence")));
		}
		BigDecimal length = required(a, "length", "长度（mm）");
		BigDecimal sourceWidth = w, sourceHeight = h;
		if (kind.equals("silencer")) {
			w = add(w, mul(n(2), rate(rates, "silencer_edge")));
			h = add(h, mul(n(2), rate(rates, "silencer_edge")));
		}
		// 消声器先加两侧边距，箱体按六面展开面积计算：[2×(宽+高)×长+2×宽×高]÷1000000，单位㎡。
		BigDecimal area = div(add(mul(add(w, h), length, n(2)), mul(w, h, n(2))), 1000000);
		String layer = str(a.getOrDefault("layer", "single"));
		if (!Set.of("single", "double").contains(layer))
			throw new RuleException("请明确单层或双层");
		BigDecimal rate = rate(rates, layer.equals("double") ? "double_layer_rate"
				: kind.equals("silencer") ? "silencer_rate" : "plenum_rate");
		BigDecimal cost = mul(area, n(a.getOrDefault("area_cost", rate))),
				thickness = n(a.getOrDefault("thickness", ".75"));
		// 0.75mm 为基础口径，1.0/1.2mm 和角钢分别除以指标系数；其他板厚没有依据就待确认。
		BigDecimal thicknessDivisor = ONE, angleDivisor = ONE;
		if (thickness.compareTo(ONE) == 0) {
			thicknessDivisor = rate(rates, "thickness_1_factor");
			cost = div(cost, thicknessDivisor);
		}
		else if (thickness.compareTo(n("1.2")) == 0) {
			thicknessDivisor = rate(rates, "thickness_1_2_factor");
			cost = div(cost, thicknessDivisor);
		}
		else if (thickness.compareTo(n(".75")) != 0)
			throw new RuleException("该板厚没有已确认的消声设备成本系数");
		if (s(a, "flange").equals("angle")) {
			angleDivisor = rate(rates, "angle_factor");
			cost = div(cost, angleDivisor);
		}
		return new Cost(cost,
				obj("width", text(sourceWidth), "height", text(sourceHeight), "length", text(length),
						"adjusted_width", text(w), "adjusted_height", text(h), "area", text(area),
						"area_rate", text(n(a.getOrDefault("area_cost", rate))), "layer", layer,
						"thickness_divisor", text(thicknessDivisor), "angle_divisor", text(angleDivisor)));
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

	// 记录设备成本、售价及取整步骤，仅供内部核对，不额外生成客户产品行。
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
			if (!blank(row.get("product_rule_issue")))
				throw new RuleException(s(row, "product_rule_issue"));
			if (blank(row.get("name")))
				throw new RuleException("请填写设备名称");
			String sourceReason = EquipmentReview.sourceReason(row);
			if (!sourceReason.isBlank() && !yes(row.get("source_issue_confirmed")))
				throw new RuleException(sourceReason);
			if (catalog == null)
				throw new RuleException("设备指标尚未发布或存在多个有效指标，请先核对指标版本");
			BigDecimal qty = required(row, "quantity", "数量");
			String productRuleType = s(map(map(row.get("_product_rule_resource")).get("body")), "rule_type");
			// 组合和全新公式先返回每 1 报价单位未税成本，再进入同一毛利、税费和数量结算流程。
			boolean customCost = Set.of("combination", "formula").contains(productRuleType);
			String kind = customCost ? "custom" : classify(row);
			var a = parseAttributes(row);
			out.putAll(obj("attributes", a, "equipment_kind", kind));
			if (!customCost && !KINDS.contains(kind))
				throw new RuleException("未识别设备类型，请明确本行产品类型");
			if (!customCost && !kind.equals("elbow") && !Set.of("台", "个", "只", "套", "件", "组", "樘").contains(s(row, "unit")))
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
			if (productRuleType.equals("formula"))
				calculated = ProductRuleCatalog.formulaCost(row, catalog);
			else if (productRuleType.equals("combination"))
				calculated = ProductRuleCatalog.combinationCost(row, catalog);
			else if (kind.equals("fan")) {
				String reviewReason = EquipmentReview.reason(row, a);
				if (!reviewReason.isBlank()) throw new RuleException(reviewReason);
				calculated = EquipmentFanSelector.select(row, catalog, a);
			}
			else if (Set.of("fire", "electric_fire", "electric_control", "manual", "check", "pressure_relief", "multi_leaf", "plate_smoke").contains(kind))
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
				boolean bundle = kind.equals("fan") && EquipmentReview.hasAttachedValve(row, a);
				if (bundle) detail.put("fan_ordinary_cost", text(cost));
				// 普通风机防爆倍率按成本档位取值，上限含本数；已经确认含阀整套成本时不重复增加防爆成本。
				if (kind.equals("fan") && enabled(a.get("explosion")) && !(bundle && Boolean.TRUE.equals(EquipmentReview.included(a)))) {
					BigDecimal ordinary = cost;
					var tier = list(catalog.get("explosion_tiers")).stream()
						.filter(t -> t.get("limit") == null || ordinary.compareTo(n(t.get("limit"))) <= 0)
						.findFirst()
						.orElseThrow();
					detail.putAll(obj("ordinary_cost", text(cost), "explosion_factor", tier.get("factor")));
					cost = mul(cost, n(tier.get("factor")));
				}
				// 附阀数量是每台数量：未含阀时先合并风机与所有附阀成本，已含阀时直接采用核实的整套未税成本。
				if (bundle) {
					var valveAttributes = EquipmentReview.valveAttributes(a);
					String valveKind = EquipmentReview.valveKind(row, a);
					boolean included = Boolean.TRUE.equals(EquipmentReview.included(a));
					var valveDetail = obj("kind", valveKind, "attributes", valveAttributes,
							"count_per_fan", a.get("attached_valve_count"), "included", included);
					if (included) {
						cost = required(a, "fan_set_cost", "已核实整套不含税成本");
						valveDetail.put("confirmed_set_cost", text(cost));
						traces.add(trace("整套成本", "人工核实整套不含税成本已含阀门及适用防爆成本，不再加附阀成本", text(cost)));
					}
					else {
						Cost valve;
						try { valve = valveCost(valveKind, valveAttributes, catalog); }
						catch (RuleException e) { throw new RuleException("附带阀门：" + e.getMessage()); }
						BigDecimal round = s(valveAttributes, "shape").equals("round") ? rate(rates, "round_factor") : ONE;
						BigDecimal valvePerUnit = mul(valve.amount(), round);
						BigDecimal valveTotal = mul(valvePerUnit, required(a, "attached_valve_count", "每台风机附阀数量"));
						detail.put("fan_adjusted_cost", text(cost));
						valveDetail.putAll(obj("detail", valve.detail(), "ordinary_cost", text(valve.amount()),
								"round_factor", text(round), "unit_cost", text(valvePerUnit), "cost_per_set", text(valveTotal)));
						traces.add(trace("附阀成本/套", text(valve.amount()) + " × " + text(round) + "（阀门外形系数） × "
								+ s(a, "attached_valve_count") + "（每台数量）", text(valveTotal)));
						traces.add(trace("风机与附阀合计成本", text(cost) + " + " + text(valveTotal), text(add(cost, valveTotal))));
						cost = add(cost, valveTotal);
					}
					detail.put("attached_valve", valveDetail);
				}
				out.put("unit_cost", text(cost));
				// 未税供货价 = 成本÷(1−毛利率)，毛利、税费均在整套成本合并后只应用一次。
				base = fraction(cost).divide(fraction(ONE.subtract(rate(rates, "gross_margin"), MC)));
				detail.putAll(obj("gross_margin", text(rate(rates, "gross_margin")), "price_before_tax", ftext(base),
						"tax_factor", text(rate(rates, "tax_factor"))));
				if (productRuleType.equals("formula"))
					for (var item : list(detail.get("formula_costs")))
						traces.add(trace(s(item, "name"), s(item, "expression"), s(item, "amount")));
				traces.add(trace("不含税成本", productRuleType.equals("formula") ? "自定义成本项目合计（每1报价单位）" : "本体、机构、电机、面板等按本行适用规则合计", text(cost)));
				traces.add(trace("不含税供货价", "成本 ÷ (1 − 毛利率)", ftext(base)));
				if (yes(options.get("tax_included")))
					base = base.multiply(fraction(rates.get("tax_factor")));
				detail.put("price_after_tax", ftext(base));
			}
			else {
				if (!kind.equals("elbow")) throw new RuleException("该产品尚无已确认的成本计算规则，请维护成本资料后重新试算");
				// 消声弯头采用人工确认面积（或清单㎡数量）乘固定含税单价，避免从不完整尺寸推算错误面积。
				BigDecimal area;
				if (Set.of("㎡", "m2", "m²", "平方米").contains(s(row, "unit")))
					area = ONE;
				else if (Set.of("台", "个", "只", "件", "组", "套").contains(s(row, "unit")))
					area = required(a, "area_per_unit", "每个消声弯头人工计算面积/方量");
				else
					throw new RuleException("消声弯头请明确按个或㎡计价，避免面积重复乘数量");
				base = fraction(mul(area, rate(rates, "elbow_taxed_rate")));
				detail.putAll(obj("area_per_unit", text(area), "fixed_taxed_base", ftext(base),
						"fixed_taxed_rate", text(rate(rates, "elbow_taxed_rate")),
						"tax_factor", text(rate(rates, "tax_factor"))));
				if (!yes(options.get("tax_included")))
					base = base.divide(fraction(rates.get("tax_factor")));
				traces.add(trace("消声弯头固定供货价", "人工面积 × 200（含税）；不含税报价除税系数", ftext(base)));
			}
			// 独立圆形阀门/面积设备对售价应用外形系数；风机附圆阀只在附阀成本部分应用，整台风机不再乘。
			if (s(a, "shape").equals("round") && (map(catalog.get("valves")).containsKey(kind)
					|| Set.of("pressure_relief", "louver", "silencer", "plenum").contains(kind))) {
				base = base.multiply(fraction(rates.get("round_factor")));
				detail.put("round_factor", text(rate(rates, "round_factor")));
			}
			var supplyAmount = base.multiply(fraction(qty));
			out.putAll(obj("supply_unit_unrounded", ftext(base), "supply_amount_unrounded", ftext(supplyAmount)));
			// 先将精确供货单价向上取整，再用整数单价×数量并向上取整行金额，不能把未取整单价直接乘数量。
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
			// 仅保留本次设备指标的版本和来源概要，便于历史明细追溯计算依据。
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
		// 运费基于已经取整的设备原单价；地区倍率形成新单价，再乘数量形成新金额，与原金额差即试算运费。
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

	/**
	 * 未达到门槛或存在待确认项时删除暂存行调整，原设备单价保留；不能把试算运费提前写回。
	 *
	 */
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
		// 整单必须只有一个有效设备指标，多个系列同时生效或指标不合法都不能任选其一。
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
		// 整组均有理由排除时不需要设备运费；正常计价行或未明确排除原因的行仍参与条件核验。
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
		// 同单材料税口径和付款方式需一致，两个指标中的同付款方案系数也必须一致，防止合并结果采用不同条件。
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
