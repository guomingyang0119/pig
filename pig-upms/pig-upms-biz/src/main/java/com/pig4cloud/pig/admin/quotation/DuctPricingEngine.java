package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 风管材料报价引擎。
 * <p>
 * 按“行参数识别 → 材料与加工成本 → 法兰及附件 → 税费和面积系数 → 行金额 → 整单汇总”的顺序计算。
 * 规则和单价来自已发布的风管指标，无法唯一确定的材质、厚度、法兰或计价口径会保留为待确认，不会自行猜测。
 * 付款系数不在单行内重复计算，而是在材料、设备运费和安装费汇总后由安装引擎统一应用一次。
 */
public final class DuctPricingEngine {

	public static final String VERSION = "duct-v16";

	public static final List<String> INSTALLATION_NOTES = List.of("不含风机控制箱及控制箱到风机的电线、电器。", "不含风机基础预制的制作；不含抗震支架。",
			"不含设备及管道预留洞孔的开启与修复。");

	private static final Set<String> SHAPES = Set.of("straight", "irregular", "semi_unspecified");

	private static String description(Map<String, Object> row) {
		return s(row, "name") + " " + s(row, "spec");
	}

	private static String alias(Object value, Map<String, String> aliases) {
		return aliases.getOrDefault(str(value), str(value));
	}

	private static Matcher match(String pattern, String text) {
		return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
	}

	private static BigDecimal eval(Map<String, Object> expressions, String key, Map<String, Object> env) {
		return DecimalFormula.evaluate(s(expressions, key), env);
	}

	private static Map<String, Object> env(Map<String, Object> source, Object... pairs) {
		Map<String, Object> e = new LinkedHashMap<>(source);
		e.putAll(obj(pairs));
		return e;
	}

	/** 行内已确认属性优先，其次解析名称和规格；只有镀锌层级缺失时才使用整单默认值。 */
	static String material(Map<String, Object> row, Map<String, Object> options) {
		String explicit = alias(attr(row, "material"), Map.of("镀锌板", "galvanized", "40g镀锌板", "galvanized_40", "80g镀锌板",
				"galvanized_80", "201不锈钢", "stainless_201", "304不锈钢", "stainless_304"));
		if (DuctCatalog.MATERIALS.contains(explicit))
			return explicit;
		String source = explicit.isEmpty() ? description(row) : explicit;
		if (source.contains("不锈钢")) {
			if (source.contains("201"))
				return "stainless_201";
			if (source.contains("304"))
				return "stainless_304";
			throw new RuleException("待确认：请选择201或304不锈钢");
		}
		if (source.contains("镀锌") || explicit.equals("galvanized")) {
			if (match("80\\s*g", source).find())
				return "galvanized_80";
			if (match("40\\s*g", source).find())
				return "galvanized_40";
			String layer = blank(options.get("zinc_layer")) ? "40" : s(options, "zinc_layer");
			if (!Set.of("40", "80").contains(layer))
				throw new RuleException("待确认：请选择40g或80g锌层");
			return "galvanized_" + layer;
		}
		throw new RuleException("待确认：请明确风管材质");
	}

	/** 厚度必须得到唯一值；规格中出现多个候选厚度时交由报价人员确认。 */
	static BigDecimal thickness(Map<String, Object> row) {
		if (!blank(attr(row, "thickness")))
			return n(attr(row, "thickness"));
		Matcher m = match("(?<!\\d)(0\\.5|0\\.6|0\\.75|1\\.0|1\\.2|1\\.5|2\\.0|1|2)\\s*(?:mm|毫米|厚)?(?!\\d)",
				s(row, "spec") + " " + s(row, "name"));
		Set<BigDecimal> values = new TreeSet<>();
		while (m.find())
			values.add(n(m.group(1)));
		Matcher source = match("(?:厚度|板厚|厚)\\s*[:：=＝]?\\s*(0\\.5|0\\.6|0\\.75|1\\.0|1\\.2|1\\.5|2\\.0|1|2)\\s*(?:mm|毫米)?",
				s(row, "source_text"));
		while (source.find())
			values.add(n(source.group(1)));
		if (values.size() == 1)
			return values.iterator().next();
		throw new RuleException("待确认：请明确风管厚度(mm)");
	}

	/**
	 * 管件类型、法兰形式和成品状态均按“行内确认值 → 行文字 → 整单默认值”的优先级读取。
	 */
	static String shape(Map<String, Object> row, Map<String, Object> options) {
		Map<String, String> aliases = Map.of("直管", "straight", "异形件", "irregular", "不区分", "semi_unspecified", "半成品不区分",
				"semi_unspecified");
		String explicit = alias(attr(row, "shape"), aliases), desc = description(row);
		if (explicit.isEmpty())
			explicit = desc.contains("直管") && !desc.contains("异形") ? "straight"
					: desc.contains("异形") && !desc.contains("直管") ? "irregular" : "";
		if (SHAPES.contains(explicit))
			return explicit;
		String selected = alias(options.get("shape"), aliases);
		return SHAPES.contains(selected) ? selected : "semi_unspecified";
	}

	static String flange(Map<String, Object> row, Map<String, Object> options) {
		String value = alias(attr(row, "flange"), Map.of("共板法兰", "common", "共板", "common", "角钢法兰", "angle", "角钢",
				"angle", "角铁法兰", "angle", "角铁", "angle", "圆法兰", "round", "圆形法兰", "round"));
		String desc = description(row);
		if (value.isEmpty())
			value = desc.contains("圆法兰") ? "round"
					: desc.contains("角钢法兰") ? "angle" : desc.contains("共板法兰") ? "common" : s(options, "flange");
		if (value.equals("mixed"))
			throw new RuleException("待确认：混合法兰模式下请明确本行法兰形式");
		if (!Set.of("common", "angle", "round").contains(value))
			throw new RuleException("待确认：请选择共板、角钢或圆法兰");
		return value;
	}

	static String state(Map<String, Object> row, Map<String, Object> options) {
		String value = alias(attr(row, "product_state"), Map.of("半成品", "semi", "成品", "finished"));
		String desc = description(row) + " " + s(row, "remark") + " " + s(row, "source_text");
		if (value.isEmpty())
			value = desc.contains("半成品") ? "semi" : desc.contains("成品") ? "finished" : s(options, "product_state");
		if (!Set.of("semi", "finished").contains(value))
			throw new RuleException("待确认：请选择半成品或成品");
		return value;
	}

	private static BigDecimal[] dimensions(Map<String, Object> row) {
		if (!blank(attr(row, "width")) && !blank(attr(row, "height")))
			return new BigDecimal[] { positive(attr(row, "width"), "宽"), positive(attr(row, "height"), "高") };
		Matcher m = match("(?<!\\d)(\\d{2,4})\\s*[x×*]\\s*(\\d{2,4})(?!\\d)",
				s(row, "spec") + " " + str(attr(row, "model")));
		return m.find() ? new BigDecimal[] { n(m.group(1)), n(m.group(2)) } : null;
	}

	private static String flangeMaterial(Map<String, Object> row) {
		String explicit = alias(attr(row, "flange_material"),
				Map.of("黑角铁", "black", "黑角钢", "black", "热镀锌角铁", "hot_galvanized", "热镀锌角钢", "hot_galvanized"));
		if (Set.of("black", "hot_galvanized").contains(explicit))
			return explicit;
		return description(row).contains("热镀锌角") ? "hot_galvanized" : "black";
	}

	private static int flangeSize(Map<String, Object> row, BigDecimal thickness) {
		if (!blank(attr(row, "flange_size"))) {
			String value = str(attr(row, "flange_size")).replace("角钢", "").trim();
			if (!Set.of("30", "40", "50").contains(value))
				throw new RuleException("待确认：角钢规格必须是30、40或50");
			return Integer.parseInt(value);
		}
		Matcher m = match("(?<!\\d)(30|40|50)\\s*(?:角钢|角铁|法兰)", description(row));
		if (m.find())
			return Integer.parseInt(m.group(1));
		BigDecimal[] size = dimensions(row);
		if (size != null && size[0].max(size[1]).compareTo(n("1600")) >= 0)
			return 40;
		if (Set.of("0.5", "0.6", "0.75", "1", "1.2").contains(thickness.stripTrailingZeros().toPlainString()))
			return 30;
		if (Set.of("1.5", "2").contains(thickness.stripTrailingZeros().toPlainString()))
			return 40;
		throw new RuleException("待确认：此厚度没有默认角钢规格");
	}

	private static BigDecimal grade(Map<String, Object> row, int size) {
		Object explicit = blank(attr(row, "flange_grade")) ? attr(row, "flange_weight") : attr(row, "flange_grade");
		if (!blank(explicit))
			return positive(explicit, "角钢法兰计算档位");
		Matcher m = match("(?<!\\d)(\\d+(?:\\.\\d+)?)\\s*档", description(row));
		if (m.find())
			return n(m.group(1));
		if (size == 30)
			return n("7.5");
		if (size == 40)
			return n("12.5");
		throw new RuleException("待确认：50角钢必须明确计算档位");
	}

	private static Map<String, Object> selectFlange(Map<String, Object> catalog, String material, int size,
			BigDecimal grade) {
		List<Map<String, Object>> matches = list(catalog.get("flanges")).stream()
			.filter(f -> s(f, "material").equals(material) && n(f.get("size")).compareTo(n(size)) == 0
					&& n(f.getOrDefault("grade", f.get("kg_per_bar"))).compareTo(grade) == 0)
			.toList();
		if (matches.size() != 1)
			throw new RuleException("角钢法兰价格缺失或档位冲突，请核对材质、规格和计算档位");
		return matches.get(0);
	}

	private static BigDecimal flangePrice(Map<String, Object> f, Map<String, Object> catalog) {
		return eval(map(catalog.get("expressions")), "flange_per_meter",
				env(map(catalog.get("rates")), "flange_grade", f.getOrDefault("grade", f.get("kg_per_bar")),
						"ton_price", f.get("ton_price"), "flange_processing", f.get("processing_per_m")));
	}

	private static Map<String, Object> payment(Map<String, Object> options, Map<String, Object> catalog) {
		String code = s(options, "payment_option");
		if (code.isEmpty())
			throw new RuleException("待确认：请选择整单付款方式");
		Map<String, Object> selected = DuctCatalog.paymentTerms(catalog)
			.stream()
			.filter(t -> s(t, "code").equals(code))
			.findFirst()
			.orElseThrow(() -> new RuleException("待确认：付款方案不存在或配置冲突"));
		if (!s(selected, "mode").equals(yes(options.get("include_installation")) ? "with_install" : "no_install"))
			throw new RuleException("待确认：安装状态已变化，请重新选择对应的整单付款方式");
		return env(selected, "coefficient", positive(selected.get("coefficient"), "付款系数"));
	}

	private static Map<String, Object> pricedItem(BigDecimal base, BigDecimal quantity, BigDecimal areaFactor,
			BigDecimal taxFactor) {
		BigDecimal untaxedUnit = round(mul(base, areaFactor)), unit = round(mul(base, areaFactor, taxFactor));
		BigDecimal untaxedAmount = round(mul(untaxedUnit, quantity)), amount = round(mul(unit, quantity));
		return obj("base_unit_price", text(base), "untaxed_unit_price", text(untaxedUnit), "unit_price", text(unit),
				"area_factor", text(areaFactor), "tax_factor", text(taxFactor),
				"untaxed_amount", text(untaxedAmount), "amount", text(amount), "tax_amount",
				text(amount.subtract(untaxedAmount, MC)));
	}

	private static Map<String, Object> fee(BigDecimal amount, String status, String reason) {
		return obj("amount", text(amount == null ? null : round(amount)), "status", status, "reason", reason);
	}

	private static Map<String, Object> trace(String step, String expression, BigDecimal value) {
		return obj("step", step, "expression", expression, "value", text(value));
	}

	private static String materialFamily(String material) {
		return material.startsWith("stainless") ? "stainless" : "galvanized";
	}

	/**
	 * 把整单录入的法兰米数或附件数量换算到当前面积口径。净面积报价同时包含镀锌和不锈钢时系数不唯一，必须拆分确认。
	 */
	private static BigDecimal wholeQuoteFactor(Map<String, Object> rates, String areaBasis, Set<String> families,
			String galvanizedRate, String label) {
		if (families.isEmpty())
			throw new RuleException("待确认：" + label + "没有对应到已识别的风管材质");
		if (!areaBasis.equals("net"))
			return ONE;
		if (families.size() > 1)
			throw new RuleException("待确认：" + label + "同时对应镀锌和不锈钢风管，无法确定净面积系数，请按材质分开填写");
		return rate(rates, families.contains("stainless") ? "net_stainless" : galvanizedRate);
	}

	/**
	 * 计算一条风管产品。所有校验失败都会转成该行的待确认原因，使其他已明确行仍可继续试算。
	 */
	private Map<String, Object> row(Map<String, Object> source, Map<String, Object> options,
			Map<String, Object> resource, Map<String, Object> payment, BigDecimal accessory, boolean aggregate) {
		Map<String, Object> out = obj("status", "pending", "reason", "", "unit_price", null, "amount", null, "trace",
				new ArrayList<>(), "billing_unit", "㎡");
		if (yes(source.get("excluded"))) {
			out.put("reason", blank(source.get("exclude_reason")) ? "排除计价需要填写原因" : s(source, "exclude_reason"));
			if (!blank(source.get("exclude_reason")))
				out.put("status", "excluded");
			return out;
		}
		try {
			if (!blank(source.get("product_rule_issue")))
				throw new RuleException(s(source, "product_rule_issue"));
			if (yes(source.get("source_issue")) && !yes(source.get("source_issue_confirmed")))
				throw new RuleException("原文件存在未缓存公式或错误值，请先核对");
			if ((!s(source, "category").isEmpty() && !s(source, "category").equals("风管"))
					|| (s(source, "category").isEmpty() && !s(source, "name").contains("风管")))
				throw new RuleException("首版仅对风管类自动报价");
			if (blank(source.get("name")))
				throw new RuleException("缺少产品名称");
			if (resource == null)
				throw new RuleException("新版风管指标尚未发布，不能使用旧规则报价");
			if (payment == null)
				throw new RuleException("待确认：请选择与安装状态对应的整单付款方式");
			if (!bool(options.get("tax_included")))
				throw new RuleException("待确认：请选择整单含税或不含税");
			String state = state(source, options), flange = flange(source, options),
					areaBasis = s(options, "area_basis");
			if (!Set.of("open", "net").contains(areaBasis))
				throw new RuleException("待确认：请选择开板面积或净面积");
			if (flange.equals("common") && !bool(options.get("include_common_punch")))
				throw new RuleException("待确认：请选择共板法兰是否需要打孔");
			// 先固定材质、厚度、管件类型和面积，这些参数共同决定材料档位及加工费。
			String material = material(source, options), shape = shape(source, options);
			BigDecimal thickness = thickness(source), area = positive(source.get("engineering_quantity"), "工程量总面积");
			Map<String, Object> catalog = map(resource.get("body")), rates = map(catalog.get("rates")),
					expressions = map(catalog.get("expressions"));
			List<Map<String, Object>> materials = list(catalog.get("materials")).stream()
				.filter(m -> s(m, "material").equals(material) && n(m.get("thickness")).compareTo(thickness) == 0)
				.toList();
			if (materials.size() != 1)
				throw new RuleException("材料价格缺失或冲突：" + material + " " + text(thickness) + "mm");
			BigDecimal coefficient = n(payment.get("coefficient")),
					tax = yes(options.get("tax_included")) ? rate(rates, "material_tax_factor") : ONE;
			BigDecimal freight = yes(options.get("include_freight")) ? rate(rates, "freight_per_sqm", "1") : ZERO;
			BigDecimal processing;
			if (material.startsWith("stainless"))
				processing = rate(rates, shape.equals("semi_unspecified") ? "stainless_semi" : "stainless_" + shape);
			else if (shape.equals("semi_unspecified"))
				processing = thickness.compareTo(n("1.5")) == 0 ? rate(rates, "galvanized_1_5_unspecified", "5.5")
						: rate(rates, "galvanized_semi");
			else
				processing = rate(rates,
						(thickness.compareTo(n("1.5")) == 0 ? "galvanized_1_5_" : "galvanized_") + shape);
			BigDecimal[] dimensions = dimensions(source);
			BigDecimal extra = dimensions != null && add(dimensions).compareTo(n("520")) <= 0
					? rate(rates, "galvanized_narrow_extra") : ZERO;
			processing = add(processing, extra);
			BigDecimal closing = state.equals("finished")
					? (flange.equals("round") ? rate(rates, "closing_round", "20") : rate(rates, "closing_" + flange))
					: ZERO;
			if (state.equals("finished") && thickness.compareTo(n("0.5")) == 0)
				closing = add(closing, rate(rates, "closing_0_5_extra"));
			BigDecimal factor = areaBasis.equals("open") ? ONE
					: flange.equals("round") ? rate(rates, "net_round", s(rates, "net_galvanized_common"))
							: rate(rates, material.startsWith("stainless") ? "net_stainless"
									: flange.equals("angle") ? "net_galvanized_angle" : "net_galvanized_common");
			BigDecimal rowAccessory = flange.equals("common") ? accessory : ZERO;
			Map<String, Object> env = env(rates, "ton_price", materials.get(0).get("ton_price"), "density",
					rate(rates, material.startsWith("stainless") ? "density_stainless" : "density_galvanized"),
					"thickness", thickness, "processing", processing, "closing", closing, "accessory_per_sqm",
					rowAccessory, "area_factor", ONE);
			BigDecimal materialPrice = eval(expressions, "material_price", env);
			env.put("material_price", materialPrice);
			BigDecimal body = eval(expressions, "body_sale", env), combined = body, punch = ZERO, flangePerMeter = ZERO,
					flangePerArea = ZERO;
			BigDecimal flangeMeters = null, roundCount = null, diameter = null, roundRate = null;
			BigDecimal roundUntaxedArea = ZERO, roundArea = ZERO;
			Map<String, Object> selectedFlange = null, actualFlange = null, roundItem = null;
			String basis;
			// 三类法兰采用不同计价基础：共板按㎡，角钢可按㎡折算或实际米数，圆法兰可按㎡折算或实际数量。
			if (flange.equals("common")) {
				punch = yes(options.get("include_common_punch")) ? rate(rates, "common_punch") : ZERO;
				combined = add(body, punch);
				basis = yes(options.get("include_common_punch")) ? "common_punch" : "common_no_punch";
			}
			else if (flange.equals("angle")) {
				Object raw = attr(source, "flange_meters");
				if (aggregate) {
					if (!blank(raw))
						throw new RuleException("已使用整单30#/40#法兰米数，本行不能再填写法兰米数");
					basis = "aggregate_meters";
				}
				else {
					int size = flangeSize(source, thickness);
					selectedFlange = selectFlange(catalog, flangeMaterial(source), size, grade(source, size));
					flangePerMeter = flangePrice(selectedFlange, catalog);
					if (!blank(raw)) {
						flangeMeters = positive(raw, "角钢法兰实际米数");
						actualFlange = pricedItem(flangePerMeter, flangeMeters, factor, tax);
						basis = "actual_meters";
					}
					else {
						flangePerArea = eval(expressions, "flange_per_area",
								env(rates, "flange_per_meter", flangePerMeter, "flange_grade",
										selectedFlange.getOrDefault("grade", selectedFlange.get("kg_per_bar")),
										"ton_price", selectedFlange.get("ton_price"), "flange_processing",
										selectedFlange.get("processing_per_m")));
						combined = add(body, flangePerArea);
						basis = "per_area";
					}
				}
			}
			else {
				if (!blank(attr(source, "diameter")))
					diameter = positive(attr(source, "diameter"), "圆风管直径");
				else {
					Matcher m = match("(?:Φ|φ|Ø|直径)\\s*(\\d+(?:\\.\\d+)?)",
							s(source, "spec") + " " + str(attr(source, "model")) + " " + s(source, "name"));
					if (!m.find())
						throw new RuleException("客户清单未提供圆风管直径，请报价人员填写");
					diameter = positive(m.group(1), "圆风管直径");
				}
				BigDecimal circumference = mul(rate(rates, "round_flange_pi", "3.14"), diameter).divide(n("1000"), MC);
				BigDecimal untaxedRate = rate(rates, "round_flange_untaxed", "10");
				roundRate = yes(options.get("tax_included")) ? rate(rates, "round_flange_taxed", "12") : untaxedRate;
				if (blank(attr(source, "round_flange_count"))) {
					roundUntaxedArea = mul(circumference, untaxedRate, rate(rates, "flange_area_factor"));
					roundArea = mul(circumference, roundRate, rate(rates, "flange_area_factor"));
					basis = "round_area";
				}
				else {
					roundCount = positive(attr(source, "round_flange_count"), "圆法兰数量");
					BigDecimal untaxedUnit = round(mul(circumference, untaxedRate, factor)),
							unit = round(mul(circumference, roundRate, factor));
					roundItem = obj("untaxed_unit_price", text(untaxedUnit), "unit_price", text(unit), "untaxed_amount",
							text(round(mul(untaxedUnit, roundCount))), "amount", text(round(mul(unit, roundCount))));
					basis = "round_count";
				}
			}
			combined = add(combined, freight);
			BigDecimal untaxedUnit = round(mul(add(combined, roundUntaxedArea), factor));
			BigDecimal unit = round(mul(add(mul(combined, tax), roundArea), factor));
			BigDecimal bodyAmount = round(mul(unit, area)), untaxedAmount = round(mul(untaxedUnit, area)),
					amount = bodyAmount;
			if (actualFlange != null) {
				amount = add(amount, n(actualFlange.get("amount")));
				untaxedAmount = add(untaxedAmount, n(actualFlange.get("untaxed_amount")));
			}
			if (roundItem != null) {
				amount = add(amount, n(roundItem.get("amount")));
				untaxedAmount = add(untaxedAmount, n(roundItem.get("untaxed_amount")));
			}
			out.putAll(obj("status", "priced", "unit_price", text(unit), "amount", text(amount), "billing_quantity",
					text(area), "body_amount", text(bodyAmount), "untaxed_unit_price", text(untaxedUnit),
					"untaxed_amount", text(untaxedAmount), "tax_amount", text(amount.subtract(untaxedAmount, MC)),
					"body_unit_price", text(round(mul(body, factor, tax))), "body_base_unit_price",
					text(mul(body, factor)), "flange_unit_price",
					actualFlange == null ? null : actualFlange.get("unit_price"), "flange_untaxed_unit_price",
					actualFlange == null ? null : actualFlange.get("untaxed_unit_price"), "flange_amount",
					actualFlange == null ? null : actualFlange.get("amount"), "flange_untaxed_amount",
					actualFlange == null ? null : actualFlange.get("untaxed_amount"), "flange_meters",
					text(flangeMeters), "flange_per_area",
					basis.equals("per_area") ? text(round(mul(flangePerArea, factor, tax))) : null,
					"flange_per_area_base", basis.equals("per_area") ? text(flangePerArea) : null,
					"round_flange_diameter", text(diameter), "round_flange_count", text(roundCount),
					"round_flange_rate", text(roundRate), "round_flange_unit_price",
					roundItem == null ? null : roundItem.get("unit_price"), "round_flange_untaxed_unit_price",
					roundItem == null ? null : roundItem.get("untaxed_unit_price"), "round_flange_amount",
					roundItem == null ? null : roundItem.get("amount"), "round_flange_untaxed_amount",
					roundItem == null ? null : roundItem.get("untaxed_amount"), "round_flange_area_unit_price",
					basis.equals("round_area") ? text(round(mul(roundArea, factor))) : null,
					"round_flange_untaxed_area_unit_price",
					basis.equals("round_area") ? text(round(mul(roundUntaxedArea, factor))) : null, "flange_basis",
					basis, "selected_material", materials.get(0), "selected_flange", selectedFlange, "selected_shape",
					shape, "effective_flange", flange, "effective_product_state", state, "area_factor", text(factor),
					"rule", resource, "payment_coefficient", text(coefficient), "payment_applied", false, "coverage",
					obj("installation", false, "freight", yes(options.get("include_freight"))), "tax_basis",
					yes(options.get("tax_included")) ? "included" : "excluded"));
			out.put("calculation_inputs", obj("material_formula", s(expressions, "material_price"), "body_formula",
					s(expressions, "body_sale"), "ton_price", materials.get(0).get("ton_price"), "density",
					text(n(env.get("density"))), "thickness", text(thickness), "material_price", text(materialPrice),
					"processing", text(processing), "closing", text(closing), "accessory_per_sqm", text(rowAccessory),
					"body", text(body), "punch", text(punch), "flange_per_area", text(flangePerArea),
					"round_area", text(roundArea), "combined_with_freight", text(combined), "freight", text(freight),
					"tax_factor", text(tax), "area_factor", text(factor), "flange_per_meter", text(flangePerMeter),
					"flange_per_meter_formula", s(expressions, "flange_per_meter"), "flange_per_area_formula",
					s(expressions, "flange_per_area"), "flange_area_factor", s(rates, "flange_area_factor"),
					"flange_grade", selectedFlange == null ? null
							: selectedFlange.getOrDefault("grade", selectedFlange.get("kg_per_bar")),
					"flange_ton_price", selectedFlange == null ? null : selectedFlange.get("ton_price"),
					"flange_processing", selectedFlange == null ? null : selectedFlange.get("processing_per_m"),
					"round_pi", roundRate == null ? null : text(rate(rates, "round_flange_pi", "3.14")),
					"round_rate", roundRate == null ? null : text(roundRate)));
			List<Map<String, Object>> traces = new ArrayList<>(
					List.of(trace("铁皮材料价/㎡", s(expressions, "material_price"), materialPrice),
							trace("风管基础加工费/㎡", "按材质、厚度、管件类型取费", processing.subtract(extra, MC)),
							trace("口型风管A+B≤520附加加工费/㎡", "宽+高≤520mm", extra),
							trace("成品合管费/㎡", "半成品为0；成品按法兰形式和厚度", closing), trace("按㎡角码卡扣/㎡", "仅共板法兰适用", rowAccessory),
							trace("净面积系数后风管本体/㎡", s(expressions, "body_sale"), mul(body, factor)),
							trace("运费/㎡", "已进入材料综合单价，不重复加总", freight), trace("面积结算系数", "全部供货材料统一应用", factor),
							trace("整单付款条件", s(payment, "label") + "；材料与安装汇总后统一应用一次，本行金额未乘付款系数", coefficient),
							trace("材料含税系数", "圆法兰独立使用含税或未税材料价", tax)));
			if (flange.equals("common"))
				traces.add(trace("共板打孔费/㎡", "按整单明确选择", punch));
			if (flange.equals("angle") && !aggregate)
				traces.add(trace("角钢法兰基础综合价/米", s(expressions, "flange_per_meter"), flangePerMeter));
			if (basis.equals("per_area"))
				traces.add(trace("角钢法兰基础折算价/㎡", s(expressions, "flange_per_area"), flangePerArea));
			if (roundItem != null)
				traces.add(trace("圆法兰每个销售价", "圆周长×材料价×面积结算系数（付款前）", n(roundItem.get("unit_price"))));
			if (basis.equals("round_area"))
				traces.add(trace("圆法兰折算价/㎡", "圆周长×材料价×1.72×面积结算系数（付款前）", n(out.get("round_flange_area_unit_price"))));
			traces.add(trace("风管综合销售单价/㎡", "各项合计后四舍五入保留一位", unit));
			out.put("trace", traces);
		}
		catch (RuleException e) {
			out.put("reason", e.getMessage());
		}
		catch (ArithmeticException | NullPointerException | ClassCastException e) {
			out.put("reason", "风管输入或指标不完整，请核对");
		}
		return out;
	}

	/**
	 * 计算风管整单：锁定唯一生效指标与付款方案，处理整单法兰/附件，再逐行计价并汇总待确认项。
	 * {@code supplyOnly} 用于复合报价的分组计算，避免在风管子结果中提前叠加安装费和付款系数。
	 */
	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		return calculate(quote, resources, policy, LocalDate.now());
	}

	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy, LocalDate day) {
		return calculate(quote, resources, policy, day, false);
	}

	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy, LocalDate day, boolean supplyOnly) {
		Map<String, Object> options = new LinkedHashMap<>(map(quote.get("duct_options")));
		options.put("include_installation", InstallationPricingEngine.enabled(quote));
		List<Map<String, Object>> catalogs = DuctCatalog.active(resources, day);
		Map<String, Object> resource = catalogs.size() == 1 ? catalogs.get(0) : null,
				catalog = resource == null ? null : map(resource.get("body"));
		List<String> issues = new ArrayList<>();
		if (resource == null)
			issues.add("新版风管指标未发布或版本冲突");
		Map<String, Object> rates = catalog == null ? null : map(catalog.get("rates")), payment = null;
		if (catalog != null) {
			try {
				payment = payment(options, catalog);
			}
			catch (RuleException e) {
				issues.add(e.getMessage());
			}
		}
		BigDecimal tax = rates != null && yes(options.get("tax_included")) ? rate(rates, "material_tax_factor") : ONE;
		List<Map<String, Object>> sources = list(quote.get("rows"));
		// 预扫描用于判断整单附件是否适用，以及整单实际法兰米数应采用哪一种净面积系数。
		boolean common = false, semiCommon = false;
		Set<String> angleFamilies = new HashSet<>(), semiCommonFamilies = new HashSet<>();
		for (Map<String, Object> source : sources) {
			if (yes(source.get("excluded")) || (!s(source, "category").isEmpty() && !s(source, "category").equals("风管"))
					|| (s(source, "category").isEmpty() && !s(source, "name").contains("风管")))
				continue;
			try {
				String f = flange(source, options), st = state(source, options),
						family = materialFamily(material(source, options));
				common |= f.equals("common");
				semiCommon |= f.equals("common") && st.equals("semi");
				if (f.equals("angle"))
					angleFamilies.add(family);
				if (f.equals("common") && st.equals("semi"))
					semiCommonFamilies.add(family);
			}
			catch (RuleException ignored) {
				/* Reported per row. */ }
		}
		// 一旦填写整单30#/40#法兰米数，就按整单另列，相关行不再重复按㎡折算法兰。
		boolean aggregate = !blank(options.get("flange_30_meters")) || !blank(options.get("flange_40_meters"));
		List<Map<String, Object>> aggregateItems = new ArrayList<>(), accessoryItems = new ArrayList<>();
		BigDecimal accessory = ZERO;
		String accessoryMode = "pending";
		if (aggregate && catalog != null && payment != null) {
			try {
				BigDecimal factor = wholeQuoteFactor(rates, s(options, "area_basis"), angleFamilies,
						"net_galvanized_angle", "整单角钢法兰实际米数");
				List<Map<String, Object>> staged = new ArrayList<>();
				for (int size : List.of(30, 40)) {
					if (blank(options.get("flange_" + size + "_meters")))
						continue;
					BigDecimal meters = positive(options.get("flange_" + size + "_meters"), "整单" + size + "#角钢法兰米数");
					Map<String, Object> selected = selectFlange(catalog, "black", size, n(size == 30 ? "7.5" : "12.5"));
					Map<String, Object> item = pricedItem(flangePrice(selected, catalog), meters, factor, tax);
					item.putAll(obj("size", size, "name",
							blank(selected.get("name")) ? size + "#黑角钢法兰" : s(selected, "name"), "meters", text(meters),
							"source", "客户清单整单实际米数"));
					staged.add(item);
				}
				aggregateItems = staged;
			}
			catch (RuleException e) {
				issues.add(e.getMessage());
			}
		}
		if (rates != null && payment != null && bool(options.get("tax_included"))) {
			try {
				if (!common)
					accessoryMode = "not_applicable";
				else if ((blank(options.get("corner_count")) && blank(options.get("clip_meters"))) || !semiCommon) {
					accessoryMode = "per_sqm";
					accessory = rate(rates, "accessory_per_sqm");
				}
				else {
					BigDecimal factor = wholeQuoteFactor(rates, s(options, "area_basis"), semiCommonFamilies,
							"net_galvanized_common", "整单角码、卡扣实际数量");
					List<Map<String, Object>> staged = new ArrayList<>();
					for (String key : List.of("corner_count", "clip_meters")) {
						if (blank(options.get(key)))
							continue;
						boolean corner = key.equals("corner_count");
						BigDecimal quantity = nonnegative(options.get(key), corner ? "角码数量" : "卡扣长度");
						Map<String, Object> item = pricedItem(
								rate(rates, corner ? "corner_unit_price" : "clip_per_meter_price"), quantity, factor,
								tax);
						item.putAll(obj("code", corner ? "corner" : "clip", "name", corner ? "角码" : "卡扣", "unit",
								corner ? "个" : "米", "quantity", text(quantity)));
						staged.add(item);
					}
					accessoryItems = staged;
					accessoryMode = "actual";
				}
			}
			catch (RuleException e) {
				issues.add(e.getMessage());
			}
		}
		// 单行待确认不会阻断其他行计算，但正式报价必须等所有待确认项处理完成。
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> source : sources)
			rows.add(row(source, options, resource, payment, accessory, aggregate));
		List<Map<String, Object>> priced = rows.stream().filter(r -> s(r, "status").equals("priced")).toList();
		int pending = (int) rows.stream().filter(r -> s(r, "status").equals("pending")).count();
		BigDecimal subtotal = add(sum(priced, "amount"), sum(aggregateItems, "amount"), sum(accessoryItems, "amount"));
		BigDecimal untaxedSubtotal = add(sum(priced, "untaxed_amount"), sum(aggregateItems, "untaxed_amount"),
				sum(accessoryItems, "untaxed_amount"));
		if (rows.isEmpty())
			issues.add("清单没有产品行");
		if (priced.isEmpty() && !rows.isEmpty())
			issues.add("没有已计算的风管项目");
		if (pending > 0)
			issues.add("有" + pending + "项待确认，未计入小计");
		if (!bool(options.get("tax_included")))
			issues.add("请选择整单含税或不含税");
		if (common && !bool(options.get("include_common_punch")))
			issues.add("请选择共板法兰是否需要打孔");
		Map<String, Object> fees = obj("accessories", fee(sum(accessoryItems, "amount"), switch (accessoryMode) {
			case "actual" -> "ready";
			case "per_sqm" -> "included";
			case "not_applicable" -> "not_applicable";
			default -> "pending";
		}, accessoryMode.equals("actual") ? "按实际数量另列，未填写项不计费"
				: accessoryMode.equals("per_sqm") ? "已计入共板风管综合单价，不重复加总"
						: accessoryMode.equals("not_applicable") ? "本单没有共板法兰，不计角码卡扣" : "整单实际角码、卡扣尚未明确对应材质，未计入小计"),
				"aggregate_flanges",
				fee(sum(aggregateItems, "amount"),
						!aggregateItems.isEmpty() ? "ready" : aggregate ? "pending" : "not_applicable",
						!aggregateItems.isEmpty() ? "整单实际米数另列，不再按1.72折算"
								: aggregate ? "整单实际法兰米数尚未明确对应材质，未计入小计" : "未使用整单实际米数"));
		if (!yes(options.get("include_freight")))
			fees.put("freight", fee(ZERO, "not_applicable", "本单不需要"));
		else if (rates == null || priced.isEmpty() || pending > 0) {
			fees.put("freight", fee(null, "pending", "风管指标或工程量未全部确认"));
			issues.add("运费：风管工程量总面积待确认");
		}
		else {
			BigDecimal base = rate(rates, "freight_per_sqm", "1"), freight = ZERO;
			Set<BigDecimal> factors = new TreeSet<>();
			for (Map<String, Object> r : priced) {
				freight = add(freight, mul(n(r.get("billing_quantity")), base, n(r.get("area_factor"))));
				factors.add(n(r.get("area_factor")));
			}
			Map<String, Object> f = fee(mul(freight, tax), "included", "运费已进入材料单价，本项仅展示，不重复加总");
			f.put("untaxed_amount", text(round(freight)));
			f.put("unit_price", factors.size() == 1 ? text(round(mul(base, factors.iterator().next(), tax))) : null);
			fees.put("freight", f);
		}
		BigDecimal taxAmount = yes(options.get("tax_included")) ? subtotal.subtract(untaxedSubtotal, MC) : ZERO;
		fees.put("tax",
				fee(taxAmount, yes(options.get("tax_included")) ? "ready" : "not_applicable", "含税差额已计入各项，不重复加总"));
		String total = issues.isEmpty() ? text(subtotal) : null;
		if (!yes(policy.get("formal_template_confirmed")))
			issues.add("正式客户模板尚未确认，当前仅可导出通用草稿");
		Map<String, Object> result = obj("rows", rows, "subtotal", text(subtotal), "pending_count", pending, "fees",
				fees, "taxable_base", text(round(untaxedSubtotal)), "total", total, "rounding_difference",
				total == null ? null : "0.0", "issues", issues, "formal_ready", issues.isEmpty(), "policy", policy,
				"engine_version", VERSION, "implementation_version", "java-1", "installation_notes",
				yes(options.get("include_installation")) ? INSTALLATION_NOTES : List.of(), "accessory_mode",
				accessoryMode, "accessory_items", accessoryItems, "aggregate_flanges", aggregateItems, "payment",
				payment == null ? null : env(payment, "coefficient", text(n(payment.get("coefficient")))),
				"duct_catalog",
				resource == null ? null : obj("id", resource.get("id"), "version", resource.get("version")));
		return InstallationPricingEngine.finalizeOrder(quote, result, resources, supplyOnly, day);
	}

}
