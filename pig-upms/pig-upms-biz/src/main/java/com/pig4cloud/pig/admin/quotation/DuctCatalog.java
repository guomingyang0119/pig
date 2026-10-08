package com.pig4cloud.pig.admin.quotation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 风管报价指标的结构校验及生效版本选择。
 * 材料按材质与板厚查吨价，角钢按材质、规格和计算档位查价；配置必须能够唯一取价。
 *
 */
public final class DuctCatalog {

	private DuctCatalog() {
	}

	// 首期支持 40g/80g 镀锌板和 201/304 不锈钢，代号与行参数及材料价表保持一致。
	public static final Set<String> MATERIALS = Set.of("galvanized_40", "galvanized_80", "stainless_201",
			"stainless_304");

	// 四项可配置公式：板材每㎡成本、风管本体每㎡价、法兰每米价及法兰每㎡折算价。
	public static final Set<String> EXPRESSIONS = Set.of("material_price", "body_sale", "flange_per_meter",
			"flange_per_area");

	// 发布指标必须提供的密度、加工费、附件费、面积及税费系数；尺寸按 mm、吨价按元/吨传入公式。
	public static final Set<String> REQUIRED_RATES = Set.of("density_galvanized", "density_stainless",
			"galvanized_semi", "galvanized_straight", "galvanized_irregular", "galvanized_1_5_straight",
			"galvanized_1_5_irregular", "galvanized_narrow_extra", "stainless_semi", "stainless_straight",
			"stainless_irregular", "closing_common", "closing_angle", "closing_0_5_extra", "accessory_per_sqm",
			"corner_unit_price", "clip_per_meter_price", "common_punch", "net_galvanized_common",
			"net_galvanized_angle", "net_stainless", "material_tax_factor", "installation_tax_factor",
			"flange_area_factor");

	// 历史指标兼容项；未配置时业务引擎会对运费、圆法兰等项目使用各自明确的回退值。
	public static final Set<String> OPTIONAL_RATES = Set.of("freight_per_sqm", "round_flange_pi",
			"round_flange_untaxed", "round_flange_taxed", "net_round", "closing_round", "galvanized_1_5_unspecified",
			"common_punch_small");

	/**
	 * 已确认的八种整单付款方案：不含安装五种、含安装三种。付款系数由整单汇总阶段应用。
	 *
	 */
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

	// 付款编码用于计算匹配，适用模式决定能否用于当前整单安装状态。
	private static Map<String, Object> term(String code, String mode, String label, String coefficient) {
		return obj("code", code, "mode", mode, "label", label, "coefficient", coefficient);
	}

	/**
	 * 读取完整的八项付款配置；历史指标缺项或编码集合不一致时使用已确认的默认方案。
	 *
	 */
	public static List<Map<String, Object>> paymentTerms(Map<String, Object> catalog) {
		List<Map<String, Object>> terms = list(catalog.get("payment_terms"));
		Set<String> expected = new HashSet<>();
		defaultPaymentTerms().forEach(t -> expected.add(s(t, "code")));
		Set<String> actual = new HashSet<>();
		terms.forEach(t -> actual.add(s(t, "code")));
		return terms.size() == 8 && expected.equals(actual) ? terms : defaultPaymentTerms();
	}

	/**
	 * 发布前校验依据、日期、贵州范围、公式变量、材料/法兰唯一性及付款方案。
	 * 校验确认结构和数值合法，具体价格仍需负责人按来源核对。
	 *
	 */
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
		// 公式可以引用已配置费率及本行计算变量，不能读取其他对象、任意方法或未定义参数。
		Set<String> allowed = new HashSet<>(rates.keySet());
		allowed.addAll(Set.of("ton_price", "density", "thickness", "material_price", "processing", "closing",
				"accessory_per_sqm", "area_factor", "flange_grade", "flange_processing", "flange_per_meter"));
		expressions.values().forEach(v -> {
			if (!allowed.containsAll(DecimalFormula.variables(str(v))))
				throw new RuleException("风管运算规则引用了未知变量");
		});
		// 同材质同板厚不得有多个价格；去除无意义尾零后，1 和 1.0 属于同一板厚。
		Set<String> seen = new HashSet<>();
		for (Map<String, Object> item : list(body.get("materials"))) {
			if (!MATERIALS.contains(s(item, "material")))
				throw new RuleException("材料类型未识别");
			String key = s(item, "material") + ":" + positive(item.get("thickness"), "材料厚度").stripTrailingZeros();
			positive(item.get("ton_price"), "吨价");
			if (!seen.add(key))
				throw new RuleException("材料规格重复，无法唯一取价");
		}
		// 角钢档位兼容旧字段 kg_per_bar，唯一键由材质、30/40/50 规格和档位共同组成。
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
		// 自定义付款系数可以调价，已确认编码、名称和含安装适用场景保持固定。
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

	/**
	 * 每个指标系列先取已经到生效日的最新正式版本，再检查是否发布且未过期。
	 * 最新版本停用或过期时该系列无有效价，不回退到旧版本，避免继续采用已撤销价格。
	 */
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
		// 失效日包含当天；未来生效版本暂不参与，草稿不影响当前正式版本。
		return latest.values().stream().filter(r -> {
			Map<String, Object> b = map(r.get("body"));
			return s(r, "status").equals("published") && !blank(b.get("effective_from"))
					&& (blank(b.get("effective_to")) || s(b, "effective_to").compareTo(day.toString()) >= 0);
		}).toList();
	}

}
