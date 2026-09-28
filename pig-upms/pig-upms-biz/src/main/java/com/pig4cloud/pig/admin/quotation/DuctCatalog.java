package com.pig4cloud.pig.admin.quotation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

public final class DuctCatalog {

	private DuctCatalog() {
	}

	public static final Set<String> MATERIALS = Set.of("galvanized_40", "galvanized_80", "stainless_201",
			"stainless_304");

	public static final Set<String> EXPRESSIONS = Set.of("material_price", "body_sale", "flange_per_meter",
			"flange_per_area");

	public static final Set<String> REQUIRED_RATES = Set.of("density_galvanized", "density_stainless",
			"galvanized_semi", "galvanized_straight", "galvanized_irregular", "galvanized_1_5_straight",
			"galvanized_1_5_irregular", "galvanized_narrow_extra", "stainless_semi", "stainless_straight",
			"stainless_irregular", "closing_common", "closing_angle", "closing_0_5_extra", "accessory_per_sqm",
			"corner_unit_price", "clip_per_meter_price", "common_punch", "net_galvanized_common",
			"net_galvanized_angle", "net_stainless", "material_tax_factor", "installation_tax_factor",
			"flange_area_factor");

	public static final Set<String> OPTIONAL_RATES = Set.of("freight_per_sqm", "round_flange_pi",
			"round_flange_untaxed", "round_flange_taxed", "net_round", "closing_round", "galvanized_1_5_unspecified",
			"common_punch_small");

	public static List<Map<String, Object>> defaultPaymentTerms() {
		return List.of(term("no_install_cash", "no_install", "现金", "1.00"),
				term("no_install_days_7", "no_install", "付款周期≤7天", "1.04"),
				term("no_install_days_15", "no_install", "付款周期7—15天（不含7天，含15天）", "1.05"),
				term("no_install_days_30", "no_install", "付款周期15—30天（不含15天，含30天）", "1.06"),
				term("no_install_days_45", "no_install", "付款周期30—45天（不含30天，含45天）", "1.07"),
				term("with_install_cash", "with_install", "现金支付（一次性全部付清）", "1.00"),
				term("with_install_progress_70", "with_install", "付款进度70%", "1.20"),
				term("with_install_progress_80", "with_install", "付款进度80%", "1.10"));
	}

	private static Map<String, Object> term(String code, String mode, String label, String coefficient) {
		return obj("code", code, "mode", mode, "label", label, "coefficient", coefficient);
	}

	public static List<Map<String, Object>> paymentTerms(Map<String, Object> catalog) {
		List<Map<String, Object>> terms = list(catalog.get("payment_terms"));
		Set<String> expected = new HashSet<>();
		defaultPaymentTerms().forEach(t -> expected.add(s(t, "code")));
		Set<String> actual = new HashSet<>();
		terms.forEach(t -> actual.add(s(t, "code")));
		return terms.size() == 8 && expected.equals(actual) ? terms : defaultPaymentTerms();
	}

	public static void validate(Map<String, Object> body) {
		if (blank(body.get("name")) || blank(body.get("source")))
			throw new RuleException("请填写风管指标名称与依据");
		try {
			LocalDate start = LocalDate.parse(s(body, "effective_from"));
			if (!blank(body.get("effective_to")) && LocalDate.parse(s(body, "effective_to")).isBefore(start))
				throw new RuleException("失效日不能早于生效日");
		}
		catch (DateTimeParseException e) {
			throw new RuleException("请填写有效的生效/失效日期");
		}
		if (!"guizhou".equals(body.getOrDefault("company_id", "guizhou")))
			throw new RuleException("首期仅支持贵州报价");
		Map<String, Object> rates = map(body.get("rates")), expressions = map(body.get("expressions"));
		Set<String> allowedRates = new HashSet<>(REQUIRED_RATES);
		allowedRates.addAll(OPTIONAL_RATES);
		if (!rates.keySet().containsAll(REQUIRED_RATES) || !allowedRates.containsAll(rates.keySet())
				|| !expressions.keySet().equals(EXPRESSIONS))
			throw new RuleException("风管成本参数或运算规则缺失、重复或未识别");
		rates.values().forEach(v -> positive(v, "风管价格、费用和系数"));
		Set<String> allowed = new HashSet<>(rates.keySet());
		allowed.addAll(Set.of("ton_price", "density", "thickness", "material_price", "processing", "closing",
				"accessory_per_sqm", "area_factor", "flange_grade", "flange_processing", "flange_per_meter"));
		expressions.values().forEach(v -> {
			if (!allowed.containsAll(DecimalFormula.variables(str(v))))
				throw new RuleException("风管运算规则引用了未知变量");
		});
		Set<String> seen = new HashSet<>();
		for (Map<String, Object> item : list(body.get("materials"))) {
			if (!MATERIALS.contains(s(item, "material")))
				throw new RuleException("材料类型未识别");
			String key = s(item, "material") + ":" + positive(item.get("thickness"), "材料厚度").stripTrailingZeros();
			positive(item.get("ton_price"), "吨价");
			if (!seen.add(key))
				throw new RuleException("材料规格重复，无法唯一取价");
		}
		seen.clear();
		for (Map<String, Object> item : list(body.get("flanges"))) {
			if (!Set.of("black", "hot_galvanized").contains(s(item, "material"))
					|| !Set.of("30", "40", "50").contains(s(item, "size")))
				throw new RuleException("角钢法兰材质或规格未识别");
			String key = s(item, "material") + ":" + s(item, "size") + ":"
					+ positive(item.getOrDefault("grade", item.get("kg_per_bar")), "计算档位").stripTrailingZeros();
			positive(item.get("ton_price"), "吨价");
			positive(item.get("processing_per_m"), "加工费");
			if (!seen.add(key))
				throw new RuleException("角钢法兰规格重复，无法唯一取价");
		}
		if (body.containsKey("payment_terms")) {
			List<Map<String, Object>> terms = list(body.get("payment_terms"));
			if (terms.size() != 8)
				throw new RuleException("付款系数规则缺失或重复");
			Set<String> codes = new HashSet<>();
			for (Map<String, Object> term : terms) {
				Map<String, Object> expected = defaultPaymentTerms().stream()
					.filter(t -> s(t, "code").equals(s(term, "code")))
					.findFirst()
					.orElseThrow(() -> new RuleException("付款方案未识别"));
				if (!codes.add(s(term, "code")) || !s(expected, "mode").equals(s(term, "mode"))
						|| !s(expected, "label").equals(s(term, "label")))
					throw new RuleException("付款方案名称或适用场景不可擅自修改");
				positive(term.get("coefficient"), "付款系数");
			}
		}
	}

	/** Latest effective formal version wins, including a retired/expired tombstone. */
	public static List<Map<String, Object>> active(List<Map<String, Object>> resources, LocalDate day) {
		Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
		for (Map<String, Object> resource : resources) {
			Map<String, Object> b = map(resource.get("body"));
			if (!"guizhou".equals(b.getOrDefault("company_id", "guizhou"))
					|| !"duct_catalog".equals(s(resource, "kind"))
					|| !Set.of("published", "retired").contains(s(resource, "status"))
					|| s(b, "effective_from").compareTo(day.toString()) > 0)
				continue;
			String family = s(resource, "family");
			if (!latest.containsKey(family)
					|| n(resource.get("version")).compareTo(n(latest.get(family).get("version"))) > 0)
				latest.put(family, resource);
		}
		return latest.values().stream().filter(r -> {
			Map<String, Object> b = map(r.get("body"));
			return s(r, "status").equals("published") && !blank(b.get("effective_from"))
					&& (blank(b.get("effective_to")) || s(b, "effective_to").compareTo(day.toString()) >= 0);
		}).toList();
	}

}
