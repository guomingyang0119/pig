package com.pig4cloud.pig.admin.quotation;

import java.time.LocalDate;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/** Published installation sales prices, migrated from the reviewed Python catalog. */
public final class InstallationCatalog {

	public static final Set<String> FOUR_REGIONS = Set.of("贵州", "四川", "云南", "广西");

	public static void validate(Map<String, Object> body) {
		if (blank(body.get("name")) || blank(body.get("source")))
			throw new RuleException("请填写指标名称及正式依据");
		if (n(body.get("tax_factor")).compareTo(n("1.01")) != 0)
			throw new RuleException("含税订单的安装系数必须为1.01");
		if (n(body.get("four_auxiliary_price")).signum() < 0)
			throw new RuleException("安装辅材单价不能为负");
		var projects = list(body.get("projects"));
		if (projects.isEmpty())
			throw new RuleException("请配置安装项目");
		Set<String> ids = new HashSet<>();
		for (var p : projects) {
			if (blank(p.get("id")) || !ids.add(s(p, "id")))
				throw new RuleException("安装项目编号缺失或重复");
			if (blank(p.get("name")) || blank(p.get("source")) || !Set.of("㎡", "台", "个", "套").contains(s(p, "unit")))
				throw new RuleException("安装项目名称、单位或依据不完整");
			if (!(p.get("regions") instanceof List<?> regions) || regions.isEmpty()
					|| regions.stream().anyMatch(r -> !(r instanceof String) || blank(r)))
				throw new RuleException("请配置安装项目适用地区");
			if (!Set.of("duct", "wrap", "equipment", "auxiliary").contains(s(p, "kind")))
				throw new RuleException("安装项目类型未识别");
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
