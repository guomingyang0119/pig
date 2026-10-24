package com.pig4cloud.pig.admin.quotation;

import java.time.LocalDate;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 已核对安装销售价指标，不经过设备毛利率换算。
 * 项目按地区、安装类别、单位和适用说明维护；风机可配置三档功率价，风管辅材另有地区口径。
 */
public final class InstallationCatalog {

	// 贵州、四川、云南、广西共用四地区项目；陕西项目和辅材价格独立匹配。
	public static final Set<String> FOUR_REGIONS = Set.of("贵州", "四川", "云南", "广西");

	/** 新版本把原设备指标中的全国包裹安装价迁入普通安装项目，保留已修改的价格。 */
	static void supplementFireWrap(Map<String, Object> body, Map<String, Object> equipment) {
		var source = new LinkedHashMap<>(equipment);
		FireWrapSales.supplement(source);
		var data = map(source.get("fire_wrap_sales"));
		var projects = list(body.get("projects"));
		for (String group : List.of("glass", "board")) {
			String id = "national_wrap_" + group;
			if (projects.stream().anyMatch(p -> s(p, "id").equals(id))) continue;
			projects.add(obj("id", id, "name", group.equals("glass") ? "玻璃棉、岩棉包裹安装（全国统一）" : "硅酸盐板、硅酸钙板包裹安装（全国统一）",
				"regions", new ArrayList<>(QuoteRegion.PROVINCES), "kind", "wrap", "unit", "㎡",
				"price", map(data.get("installation_prices")).get(group), "auxiliary_type", "",
				"source", s(data, "source") + " · " + s(map(data.get("installation_sources")), group),
				"note", "外购防火包裹自动取价；组合逐项相加，立方按各材料自身厚度系数换算，不计本表软包、硬包辅材"));
		}
		body.put("projects", projects);
	}

	static Map<String, Object> fireWrapPrices(Map<String, Object> body) {
		var prices = obj();
		for (String group : List.of("glass", "board"))
			list(body.get("projects")).stream().filter(p -> s(p, "id").equals("national_wrap_" + group)).findFirst()
				.ifPresent(p -> prices.put(group, p.get("price")));
		return prices;
	}

	/**
	 * 发布前核验项目编号、来源、单位、地区及未税销售价。
	 * 含税安装系数固定 1.01；风机三档价与固定单价二选一，陕西三种辅材必须各有唯一项目。
	 *
	 */
	public static void validate(Map<String, Object> body) {
		if (blank(body.get("name")) || blank(body.get("source")))
			throw new RuleException("请填写指标名称及正式依据");
		// 安装含税系数独立于材料税系数，当前确认口径为未税安装销售价乘 1.01。
		if (n(body.get("tax_factor")).compareTo(n("1.01")) != 0)
			throw new RuleException("含税订单的安装系数必须为1.01");
		if (n(body.get("four_auxiliary_price")).signum() < 0)
			throw new RuleException("安装辅材单价不能为负");
		var projects = list(body.get("projects"));
		if (projects.isEmpty())
			throw new RuleException("请配置安装项目");
		var wrapPrices = fireWrapPrices(body);
		if (!wrapPrices.isEmpty() && (wrapPrices.size() != 2 || wrapPrices.values().stream().anyMatch(QuoteData::blank)))
			throw new RuleException("全国防火包裹安装销售价必须同时配置玻璃棉/岩棉及板材两项");
		Set<String> ids = new HashSet<>();
		for (var p : projects) {
			if (Set.of("national_wrap_glass", "national_wrap_board").contains(s(p, "id"))
					&& (!s(p, "kind").equals("wrap") || !s(p, "unit").equals("㎡") || p.get("power_prices") != null
						|| !(p.get("regions") instanceof List<?> regions) || !new HashSet<>(regions).equals(new HashSet<>(QuoteRegion.PROVINCES))))
				throw new RuleException("全国防火包裹安装项目必须按平方米配置并适用于全国");
			if (blank(p.get("id")) || !ids.add(s(p, "id")))
				throw new RuleException("安装项目编号缺失或重复");
			if (blank(p.get("name")) || blank(p.get("source")) || !Set.of("㎡", "台", "个", "套").contains(s(p, "unit")))
				throw new RuleException("安装项目名称、单位或依据不完整");
			if (!(p.get("regions") instanceof List<?> regions) || regions.isEmpty()
					|| regions.stream().anyMatch(r -> !(r instanceof String) || blank(r)))
				throw new RuleException("请配置安装项目适用地区");
			if (!Set.of("duct", "wrap", "equipment", "auxiliary").contains(s(p, "kind")))
				throw new RuleException("安装项目类型未识别");
			// 固定价按元/㎡、元/台、元/个或元/套；分档价的功率边界在安装引擎中判断。
			List<?> prices;
			if (p.get("power_prices") != null) {
				if (!(p.get("power_prices") instanceof List<?> bands) || bands.size() != 3 || p.get("price") != null)
					throw new RuleException("风机安装功率档位必须为三档且不能同时配置固定价格");
				prices = (List<?>) p.get("power_prices");
			}
			else
				prices = Collections.singletonList(p.get("price"));
			for (Object price : prices)
				if (n(price).signum() < 0)
					throw new RuleException("安装销售单价不能为负");
		}
		for (String kind : List.of("common", "angle", "composite"))
			if (projects.stream()
				.filter(p -> s(p, "kind").equals("auxiliary") && ((List<?>) p.get("regions")).contains("陕西")
						&& s(p, "auxiliary_type").equals(kind))
				.count() != 1)
				throw new RuleException("陕西共板、角铁、复合安装辅材必须各有一个明确项目");
	}

	/**
	 * 每个安装指标系列只使用已到生效日的最新正式版本；停用或过期时不回退旧价。
	 * 失效日包含当天，未来版本及草稿不提前影响当前报价。
	 *
	 */
	public static List<Map<String, Object>> active(List<Map<String, Object>> resources, LocalDate day) {
		Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
		for (var r : resources) {
			var b = map(r.get("body"));
			if (!s(r, "kind").equals("installation_catalog")
					|| !"guizhou".equals(b.getOrDefault("company_id", "guizhou"))
					|| !Set.of("published", "retired").contains(s(r, "status"))
					|| s(b, "effective_from").compareTo(day.toString()) > 0)
				continue;
			String family = s(r, "family");
			if (!latest.containsKey(family) || n(r.get("version")).compareTo(n(latest.get(family).get("version"))) > 0)
				latest.put(family, r);
		}
		return latest.values()
			.stream()
			.filter(r -> s(r, "status").equals("published") && !blank(map(r.get("body")).get("effective_from"))
					&& (blank(map(r.get("body")).get("effective_to"))
							|| s(map(r.get("body")), "effective_to").compareTo(day.toString()) >= 0))
			.toList();
	}

}
