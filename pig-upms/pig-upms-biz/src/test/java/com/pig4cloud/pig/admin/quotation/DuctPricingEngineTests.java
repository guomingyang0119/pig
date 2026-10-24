package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 风管报价回归验证：覆盖迁移基准、混合材质的净面积分摊及成本算式的限制。
 *
 */
class DuctPricingEngineTests {

	/**
	 * 用冻结基准逐项核对风管、安装和整单输出，固定试算日期防止生效版本随执行日漂移。
	 *
	 */
	@TestFactory
	Stream<DynamicTest> matchesIndependentCurrentPolicyGoldenCases() throws IOException {
		Map<String, Object> fixture;
		try (InputStream stream = new GZIPInputStream(Objects
			.requireNonNull(getClass().getResourceAsStream("/quotation/duct-current-policy-20261022-golden.json.gz")))) {
			fixture = new ObjectMapper().readValue(stream, Map.class);
		}
		return list(fixture.get("cases")).stream().map(c -> DynamicTest.dynamicTest(s(c, "name"), () -> {
			Map<String, Object> actual = new DuctPricingEngine().calculate(map(c.get("quote")),
					list(c.getOrDefault("resources", fixture.get("resources"))),
					map(c.getOrDefault("policy", fixture.get("policy"))), LocalDate.of(2026, 9, 23));
			// 全部不报价现在直接跳过供货规则，保留排除行但禁止空正式单；其余冻结金额继续逐项核对。
			var sources = list(map(c.get("quote")).get("rows"));
			if (!sources.isEmpty() && sources.stream().allMatch(r -> yes(r.get("excluded")))) {
				assertFalse(yes(actual.get("formal_ready")));
				assertNull(actual.get("total"));
				assertEquals(0, n(actual.get("subtotal")).signum());
				var rows = list(actual.get("rows"));
				assertEquals(sources.size(), rows.size());
				for (int i = 0; i < sources.size(); i++) {
					boolean missingReason = blank(sources.get(i).get("exclude_reason"));
					assertEquals(missingReason ? "pending" : "excluded", rows.get(i).get("status"));
					assertEquals(missingReason ? "排除计价需要填写原因" : sources.get(i).get("exclude_reason"), rows.get(i).get("reason"));
				}
				return;
			}
			compare(c.get("expected"), actual, "result");
		}));
	}

	// 递归核对基准字段与列表顺序，数值按大小比较而不要求字符串小数位一致。
	private static void compare(Object expected, Object actual, String path) {
		if ((path.endsWith(".installation_engine_version") || path.endsWith(".engine_version"))
				&& str(expected).startsWith("installation-v")) {
			assertEquals(InstallationPricingEngine.VERSION, actual, path);
			return;
		}
		if (expected instanceof Map<?, ?> m) {
			assertInstanceOf(Map.class, actual, path);
			m.forEach((k, v) -> compare(v, ((Map<?, ?>) actual).get(k), path + "." + k));
			return;
		}
		if (expected instanceof List<?> items) {
			assertInstanceOf(List.class, actual, path);
			List<?> values = (List<?>) actual;
			assertEquals(items.size(), values.size(), path);
			for (int i = 0; i < items.size(); i++)
				compare(items.get(i), values.get(i), path + "[" + i + "]");
			return;
		}
		if (expected != null && actual != null && str(expected).matches("-?\\d+(\\.\\d+)?")
				&& str(actual).matches("-?\\d+(\\.\\d+)?")) {
			assertEquals(0, new BigDecimal(str(expected)).compareTo(new BigDecimal(str(actual))),
					path + " expected=" + expected + " actual=" + actual);
			return;
		}
		assertEquals(expected, actual, path);
	}

	// 读取源指标并构造发布态资源，固定到一个系列以避免版本歧义。
	private Map<String, Object> catalogResource() throws IOException {
		Map<String, Object> catalog = new ObjectMapper().readValue(DuctExportService.asset("duct-catalog-seed.json"),
				Map.class);
		return obj("id", "mixed-test", "family", "mixed-test", "version", 1, "kind", "duct_catalog", "status",
				"published", "body", catalog);
	}

	// 用相同尺寸和面积构造镀锌/不锈钢两行，只改变待验证的面积及法兰口径。
	private Map<String, Object> mixedQuote(String areaBasis, String flange) {
		Map<String, Object> options = obj("area_basis", areaBasis, "product_state", "semi", "shape", "semi_unspecified",
				"zinc_layer", "40", "flange", flange, "include_common_punch", false, "tax_included", false,
				"corner_count", "", "clip_meters", "", "flange_30_meters", "", "flange_40_meters", "", "payment_option",
				"no_install_cash", "include_installation", false, "installation_amount", "", "include_freight", false);
		List<Map<String, Object>> rows = List.of(
				obj("name", "镀锌风管", "spec", "800×320", "category", "风管", "engineering_quantity", "10", "unit", "㎡",
						"attributes", obj("material", "galvanized_40", "thickness", "0.75")),
				obj("name", "304不锈钢风管", "spec", "800×320", "category", "风管", "engineering_quantity", "10", "unit", "㎡",
						"attributes", obj("material", "stainless_304", "thickness", "0.75")));
		return obj("duct_options", options, "rows", rows);
	}

	// 固定日期和模板确认状态，聚焦风管规则本身的金额与待确认结果。
	private Map<String, Object> calculateMixed(Map<String, Object> quote) throws IOException {
		return new DuctPricingEngine().calculate(quote, List.of(catalogResource()),
				obj("formal_template_confirmed", true), LocalDate.of(2026, 9, 23));
	}

	@Test
	void ductSourceIssuesShowActualReviewReasonWithoutRelabelingItAsFormulaError() throws IOException {
		String reason = "AI建议类型缺少有效的原文依据或不受系统支持，请核对产品类别";
		for (boolean explicitReason : List.of(true, false)) {
			var quote = mixedQuote("open", "common");
			var source = list(quote.get("rows")).get(0);
			source.put("source_issue", true);
			if (explicitReason) source.put("source_issue_reason", reason);
			else map(source.get("attributes")).put("recognition_issue", reason);
			var result = calculateMixed(quote);
			var rows = list(result.get("rows"));
			assertEquals("pending", rows.get(0).get("status"));
			assertEquals(reason, rows.get(0).get("reason"));
			assertNull(rows.get(0).get("unit_price"));
			assertNull(rows.get(0).get("amount"));
			assertEquals("priced", rows.get(1).get("status"));
			assertEquals(1, n(result.get("pending_count")).intValue());
			assertNull(result.get("total"));
			assertEquals(true, source.get("source_issue"));
			assertEquals(reason, explicitReason ? source.get("source_issue_reason")
					: map(source.get("attributes")).get("recognition_issue"));
		}
	}

	@Test
	void ductFormulaSourceIssuesRemainPendingUntilConfirmed() throws IOException {
		var quote = mixedQuote("open", "common");
		var source = list(quote.get("rows")).get(0);
		source.put("source_issue", true);
		var result = calculateMixed(quote);
		var row = list(result.get("rows")).get(0);
		assertEquals("pending", row.get("status"));
		assertTrue(s(row, "reason").contains("公式"));
		assertTrue(s(row, "reason").contains("错误值"));
		assertNull(row.get("amount"));
		assertEquals("priced", list(result.get("rows")).get(1).get("status"));
		source.put("source_issue_confirmed", true);
		var confirmed = calculateMixed(quote);
		assertEquals(List.of("priced", "priced"), list(confirmed.get("rows")).stream().map(r -> s(r, "status")).toList());
		assertEquals(0, n(confirmed.get("pending_count")).intValue());
		assertNotNull(confirmed.get("total"));
	}

	@Test
	void ductConfirmedSourceReviewIssuesDoNotBlockPricing() throws IOException {
		var quote = mixedQuote("open", "common");
		var baseline = calculateMixed(quote);
		var source = list(quote.get("rows")).get(0);
		String reason = "原始资料工程量须人工核对";
		source.putAll(obj("source_issue", true, "source_issue_reason", reason, "source_issue_confirmed", true));
		var result = calculateMixed(quote);
		var row = list(result.get("rows")).get(0);
		assertEquals("priced", row.get("status"));
		assertEquals("", row.get("reason"));
		assertEquals(list(baseline.get("rows")).get(0).get("unit_price"), row.get("unit_price"));
		assertEquals(list(baseline.get("rows")).get(0).get("amount"), row.get("amount"));
		assertEquals(baseline.get("total"), result.get("total"));
		assertEquals(reason, source.get("source_issue_reason"));
		assertEquals(true, source.get("source_issue_confirmed"));
	}

	@Test
	void compositeDuctWithoutCompleteRuleStaysPendingEvenAfterSourceConfirmation() throws IOException {
		for (String thickness : List.of("0.5", "0.6", "0.75", "1.0", "1.2", "1.5")) {
			for (boolean confirmed : List.of(false, true)) {
				var quote = mixedQuote("open", "common");
				var source = list(quote.get("rows")).get(0);
				source.putAll(obj("name", "0.2mm彩钢板+14mm漂珠石墨硅+" + thickness + "mm镀锌钢板风管",
						"spec", "", "engineering_quantity", "14.67", "source_issue", false, "source_issue_confirmed", confirmed));
				map(source.get("attributes")).put("thickness", thickness);
				var result = calculateMixed(quote);
				var rows = list(result.get("rows"));
				assertEquals("pending", rows.get(0).get("status"), thickness + " confirmed=" + confirmed);
				assertTrue(s(rows.get(0), "reason").contains("复合风管"));
				assertTrue(s(rows.get(0), "reason").contains("完整"));
				assertTrue(s(rows.get(0), "reason").contains("计价规则"));
				assertFalse(s(rows.get(0), "reason").contains("公式"));
				assertNull(rows.get(0).get("unit_price"));
				assertNull(rows.get(0).get("amount"));
				assertEquals("priced", rows.get(1).get("status"));
				assertEquals(1, n(result.get("pending_count")).intValue());
				assertNull(result.get("total"));
			}
		}
		var ordinary = calculateMixed(mixedQuote("open", "common"));
		assertEquals(List.of("priced", "priced"), list(ordinary.get("rows")).stream().map(r -> s(r, "status")).toList());
	}

	@Test
	void compositeDuctWithConfirmedCompleteCostRuleUsesThatRule() throws IOException {
		var quote = mixedQuote("open", "common");
		var source = list(quote.get("rows")).get(0);
		String name = "0.2mm彩钢板+14mm漂珠石墨硅+0.5mm镀锌钢板风管";
		source.putAll(obj("name", name, "spec", "", "quantity", "14.67", "engineering_quantity", "14.67",
				"source_issue", true, "source_issue_confirmed", true));
		map(source.get("attributes")).put("complete_cost", "123");
		source.put("product_rule", obj("name", name, "rule_type", "formula", "unit", "㎡", "aliases", List.of(name),
				"attributes", obj(), "conditions", obj(), "custom_cost", obj(
						"parameters", List.of(obj("key", "complete_cost", "label", "已确认完整复合风管未税成本", "unit", "元/㎡", "source", "input")),
						"costs", List.of(obj("name", "面层、芯材、内层及加工完整成本", "expression", "complete_cost")))));
		var prepared = ProductRuleCatalog.prepareRow(source, List.of(), LocalDate.of(2026, 10, 8));
		assertTrue(prepared.containsKey("_product_rule_resource"));
		assertEquals("自定义产品", prepared.get("category"));
		assertEquals(name, source.get("name"));
		quote.put("rows", List.of(prepared));
		quote.put("equipment_options", obj("tax_included", false, "include_installation", false,
				"payment_option", "no_install_cash", "include_freight", false));
		Map<String, Object> equipmentCatalog = new ObjectMapper().readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var equipment = obj("id", "composite-cost-test", "family", "composite-cost-test", "version", 1,
				"kind", "equipment_catalog", "status", "published", "body", equipmentCatalog);
		var result = new EquipmentPricingEngine().mixed(quote, List.of(catalogResource(), equipment), obj("formal_template_confirmed", true));
		var row = list(result.get("rows")).get(0);
		assertEquals("priced", row.get("status"), s(row, "reason"));
		assertEquals("custom", row.get("equipment_kind"));
		assertEquals("14.67", row.get("billing_quantity"));
		assertEquals("㎡", row.get("billing_unit"));
		assertEquals("123", list(map(row.get("detail")).get("formula_costs")).get(0).get("amount"));
		assertEquals(0, n(result.get("pending_count")).intValue());
		assertNotNull(row.get("amount"));
		assertNotNull(result.get("total"));
	}

	/**
	 * 确认镀锌与 304 不锈钢各自查价，行单价不同且汇总只加各行金额，不错误合并材料口径。
	 *
	 */
	@Test
	void mixedGalvanizedAndStainlessRowsArePricedIndependentlyAndSummed() throws IOException {
		Map<String, Object> result = calculateMixed(mixedQuote("open", "common"));
		List<Map<String, Object>> rows = list(result.get("rows"));
		assertTrue(yes(result.get("formal_ready")), str(result.get("issues")));
		assertEquals(0, n(result.get("pending_count")).intValue());
		assertNotNull(result.get("total"));
		assertEquals(List.of("priced", "priced"), rows.stream().map(r -> s(r, "status")).toList());
		assertEquals("galvanized_40", s(map(rows.get(0).get("selected_material")), "material"));
		assertEquals("stainless_304", s(map(rows.get(1).get("selected_material")), "material"));
		assertNotEquals(rows.get(0).get("unit_price"), rows.get(1).get("unit_price"));
		BigDecimal rowSum = rows.stream().map(r -> n(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
		assertEquals(0, rowSum.compareTo(n(result.get("subtotal"))));
		assertEquals(0, rowSum.compareTo(n(result.get("total"))));
	}

	/**
	 * 净面积混合材质时整单实际角码无法确定分摊系数，附件待确认，但两条风管仍各自完成试算。
	 *
	 */
	@Test
	void mixedNetAreaActualAccessoriesOnlyRaiseAllocationIssueWithoutBlockingRows() throws IOException {
		Map<String, Object> quote = mixedQuote("net", "common");
		map(quote.get("duct_options")).put("corner_count", "100");
		Map<String, Object> result = calculateMixed(quote);
		assertEquals(List.of("priced", "priced"), list(result.get("rows")).stream().map(r -> s(r, "status")).toList());
		assertEquals("pending", s(map(map(result.get("fees")).get("accessories")), "status"));
		assertTrue(listOfStrings(result.get("issues")).stream().anyMatch(v -> v.contains("整单角码、卡扣实际数量同时对应")));
		assertNull(result.get("total"));
	}

	/**
	 * 净面积混合材质的整单法兰米数无法分配系数时仅保留整单待确认，已明确本体行仍可查看金额。
	 *
	 */
	@Test
	void mixedNetAreaAggregateFlangeMetersOnlyRaiseAllocationIssueWithoutBlockingRows() throws IOException {
		Map<String, Object> quote = mixedQuote("net", "angle");
		map(quote.get("duct_options")).put("flange_30_meters", "100");
		Map<String, Object> result = calculateMixed(quote);
		assertEquals(List.of("priced", "priced"), list(result.get("rows")).stream().map(r -> s(r, "status")).toList());
		assertEquals("pending", s(map(map(result.get("fees")).get("aggregate_flanges")), "status"));
		assertTrue(listOfStrings(result.get("issues")).stream().anyMatch(v -> v.contains("整单角钢法兰实际米数同时对应")));
		assertNull(result.get("total"));
	}

	// 把整单问题列表转成字符串，核对具体的混合材质分摊提示。
	private static List<String> listOfStrings(Object value) {
		return ((List<?>) value).stream().map(Objects::toString).toList();
	}

	/**
	 * 拒绝脚本式调用、成员访问、非法运算、零除数及超限数值/嵌套，同时核验允许函数和四舍五入结果。
	 *
	 */
	@Test
	void formulaIsBoundedAndDoesNotExecuteCode() {
		for (String expression : List.of("Runtime.getRuntime()", "__import__('os')", "1/0", "2**3", "x.class",
				"round(1.2,9)", "1e19", "(".repeat(45) + "1" + ")".repeat(45)))
			assertThrows(RuleException.class, () -> DecimalFormula.evaluate(expression, Map.of()), expression);
		assertEquals("2.4", DecimalFormula.evaluate("round(2.35,1)", Map.of()).toPlainString());
		assertEquals(0, n("10").compareTo(DecimalFormula.evaluate("max(abs(-5),ceil(9.1))", Map.of())));
	}

	/**
	 * 同材质同板厚重复配置会使查价不唯一，发布指标前必须拒绝。
	 *
	 */
	@Test
	void duplicateCatalogPriceIsRejected() throws IOException {
		Map<String, Object> b;
		try (InputStream in = getClass().getResourceAsStream("/quotation/duct-catalog-seed.json")) {
			b = new ObjectMapper().readValue(in, Map.class);
		}
		DuctCatalog.validate(b);
		list(b.get("materials")).add(list(b.get("materials")).get(0));
		assertThrows(RuleException.class, () -> DuctCatalog.validate(b));
	}

}
