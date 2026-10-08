package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 设备报价回归验证：覆盖识别、风机选型、成本明细、设备运费试算及混合整单结果。
 *
 */
class EquipmentPricingEngineTests {

	@Test
	void fireControlValveUsesTheExistingFireValveCost() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
				.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var input = obj("name", "70℃防火调节阀", "spec", "400×250", "attributes", obj());
		assertEquals("fire", EquipmentPricingEngine.classify(input));
		assertEquals("electric_fire", EquipmentPricingEngine.classify(obj("name", "电动防火调节阀", "attributes", obj())));
		assertEquals("manual", EquipmentPricingEngine.classify(obj("name", "手动调节阀", "attributes", obj())));
		var actual = EquipmentPricingEngine.valveCost(EquipmentPricingEngine.classify(input),
				EquipmentPricingEngine.parseAttributes(input), catalog);
		var expected = EquipmentPricingEngine.valveCost("fire", obj("width", "400", "height", "250"), catalog);
		assertEquals(0, expected.amount().compareTo(actual.amount()));
		assertEquals("25", map(actual.detail().get("components")).get("mechanism"));
		assertEquals("17", map(actual.detail().get("components")).get("labor"));
	}

	@Test
	void panelExpressionKeepsValveBodyDimensionsEvenWhenAiSuppliesTheTotalHeight() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
				.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var parsed = EquipmentPricingEngine.parseAttributes(obj("name", "多叶排烟口", "spec", "800×(1000+250)",
				"attributes", obj("width", "800", "height", "1250")));
		assertEquals("1000", parsed.get("height"));
		assertEquals("250", parsed.get("panel_height"));
		assertEquals("1250", parsed.get("overall_height"));
		var actual = EquipmentPricingEngine.valveCost("multi_leaf", parsed, catalog);
		var expected = EquipmentPricingEngine.valveCost("multi_leaf", obj("width", "800", "height", "1000"), catalog);
		assertEquals(0, expected.amount().compareTo(actual.amount()));
		assertEquals(false, actual.detail().get("split"));
		assertEquals("4", actual.detail().get("blades"));
		assertEquals("800", actual.detail().get("body_width"));
		assertEquals("1000", actual.detail().get("body_height"));
		assertEquals("1060", actual.detail().get("panel_outer_height"));
	}

	@Test
	void explicitSpeedWithoutAPrefixIsPreservedAndUnmatchedSpeedKeepsReferenceCandidates() throws IOException {
		var parsed = EquipmentPricingEngine.parseAttributes(obj("name", "风机", "spec", "SWF-I-7 风量27500 风压700 11kW 2900r/min", "attributes", obj()));
		assertEquals("2900", parsed.get("speed"));
		Map<String, Object> catalog = new ObjectMapper()
				.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var error = assertThrows(EquipmentFanSelector.SelectionException.class,
				() -> EquipmentFanSelector.select(obj(), catalog, parsed));
		assertTrue(error.getMessage().contains("转速2900"), error.getMessage());
		assertFalse(error.candidates.isEmpty());
		assertTrue(error.candidates.stream().allMatch(p -> n(p.get("speed")).compareTo(n("2900")) != 0));
		assertTrue(error.candidates.stream().allMatch(p -> s(p, "requirement_mismatches").contains("客户要求2900")));
		assertThrows(EquipmentFanSelector.SelectionException.class,
				() -> EquipmentFanSelector.select(obj("candidate_id", error.candidates.get(0).get("id")), catalog, parsed));
	}

	/**
	 * 客户编号式名称也应依据规格内型号识别风机，正确提取双速高低工况并把 W 换算为 kW。
	 *
	 */
	@Test
	void codedFanNamesUseTheModelAndPerformanceWrittenInTheSpecification() {
		var dual = obj("name", "PYF-B2-4-1", "spec",
				"L=35000/24019CMHP=770/338PaN=12/4Kw消防/平时HTF-II-10贴梁安装", "remark", "", "attributes", obj());
		assertEquals("fan", EquipmentPricingEngine.classify(dual));
		var dualAttributes = EquipmentPricingEngine.parseAttributes(dual);
		assertEquals("35000", dualAttributes.get("airflow"));
		assertEquals("24019", dualAttributes.get("airflow_low"));
		assertEquals("770", dualAttributes.get("pressure"));
		assertEquals("338", dualAttributes.get("pressure_low"));
		assertEquals("12/4", dualAttributes.get("power"));
		assertEquals("HTF-II-10", dualAttributes.get("model"));

		// 壁式风机的瓦数需转换为 kW，不能把 40W 误识别成 40kW。
		var wall = obj("name", "PF-3-2#WD-1", "spec", "L=950CMHP=41PaN=40W-220V平时贴梁安装DFBZ-2.8", "remark", "",
				"attributes", obj());
		assertEquals("fan", EquipmentPricingEngine.classify(wall));
		var wallAttributes = EquipmentPricingEngine.parseAttributes(wall);
		assertEquals("950", wallAttributes.get("airflow"));
		assertEquals("41", wallAttributes.get("pressure"));
		assertEquals("0.04", wallAttributes.get("power"));
		assertEquals("DFBZ-2.8", wallAttributes.get("model"));
	}

	/**
	 * 核验已给型号/性能需求时选择合格工况，不能把需求功率直接当作实际选型电机功率。
	 *
	 */
	@Test
	void mainFanPathChoosesTheSmallestQualifiedOperatingPoint() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
			.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var selected = EquipmentFanSelector.select(obj(), catalog,
				obj("model", "SWF-I-5", "airflow", "4896", "pressure", "382", "power", "1.1"));
		var fan = map(selected.detail().get("selected_fan"));
		assertEquals("SWF-5", fan.get("model"));
		assertEquals(0, n(((List<?>) fan.get("powers")).get(0)).compareTo(n("5.5")));
		assertEquals(0, n(fan.get("size")).compareTo(n("5")));
		assertEquals(0, n(fan.get("airflow")).compareTo(n("11342")));
		assertEquals(0, n(fan.get("pressure")).compareTo(n("960")));
	}

	/**
	 * 核对正压、多叶、蝶阀、泄压及电动防火等业务叫法落到正确的内置计算类型。
	 *
	 */
	@Test
	void productAliasesMapToTheirExistingPricingTypes() {
		for (String name : List.of("正压送风口", "多叶排烟口", "多叶送风口", "加压送风口"))
			assertEquals("multi_leaf", EquipmentPricingEngine.classify(obj("name", name, "attributes", obj())), name);
		assertEquals("electric_control",
				EquipmentPricingEngine.classify(obj("name", "电动阀", "attributes", obj())));
		assertEquals("electric_control",
				EquipmentPricingEngine.classify(obj("name", "电动蝶阀", "attributes", obj())));
		assertEquals("manual", EquipmentPricingEngine.classify(obj("name", "蝶阀", "attributes", obj())));
		assertEquals("pressure_relief",
				EquipmentPricingEngine.classify(obj("name", "电动泄压阀", "attributes", obj())));
		assertEquals("electric_fire",
				EquipmentPricingEngine.classify(obj("name", "电动防火阀", "attributes", obj())));
	}

	/**
	 * 排气扇走外购型号成本，不被普通风机选型算法截走。
	 *
	 */
	@Test
	void exhaustFansArePurchasedProducts() {
		assertEquals("purchased", EquipmentPricingEngine.classify(obj("name", "吸顶式排气扇", "attributes", obj())));
	}

	/**
	 * 自垂百叶保留实际类型，但采用已确认的单层百叶材料成本口径。
	 *
	 */
	@Test
	void selfClosingLouverUsesThePlainLouverMaterialFormula() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
			.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var dimensions = obj("width", "400", "height", "400");
		var plain = EquipmentPricingEngine.areaCost("louver", obj("name", "单层百叶风口", "spec", "400×400"),
				dimensions, catalog);
		var gravity = EquipmentPricingEngine.areaCost("louver", obj("name", "自垂百叶风口", "spec", "400×400"),
				dimensions, catalog);
		assertEquals(0, plain.amount().compareTo(gravity.amount()));
		assertEquals("gravity", gravity.detail().get("louver_type"));
		assertEquals("single", gravity.detail().get("pricing_louver_type"));
	}

	/**
	 * 远控机构的明细金额与真实成本一致，并展示基础机构费加远控额外费的算式。
	 *
	 */
	@Test
	void remoteValveRecordsTheSameMechanismBreakdownUsedInCost() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
				.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var detail = EquipmentPricingEngine.valveCost("fire",
				obj("width", "500", "height", "500", "control", "remote"), catalog).detail();
		assertTrue(n(detail.get("remote_extra")).signum() > 0);
		assertEquals(0, n(map(detail.get("components")).get("mechanism"))
				.compareTo(add(n(detail.get("mechanism_base")), n(detail.get("remote_extra")))));
		assertEquals(s(detail, "mechanism_base") + " + " + s(detail, "remote_extra"),
				s(map(detail.get("component_formulas")), "mechanism"));
	}

	/**
	 * 地区系数先产生设备运费试算方案；整单门槛未决前，原设备单价不应被提前修改。
	 *
	 */
	@Test
	void equipmentFreightCreatesPerUnitTrialWithoutChangingRowsEarly() {
		var catalog = obj("rates", obj("freight_threshold", "8000"), "regions", obj("贵州", "1.1"));
		var rows = new ArrayList<Map<String, Object>>();
		rows.add(freightRow("50000", "1"));
		rows.add(freightRow("30000", "1"));
		var trial = EquipmentPricingEngine.equipmentFreight(
				obj("include_freight", true, "region", "贵州"), catalog, rows, 0, true);
		assertEquals("trial", trial.get("status"));
		assertEquals("8000", trial.get("calculated_amount"));
		assertEquals("regional_per_unit_trial", trial.get("basis"));
		assertEquals(false, trial.get("included_in_rows"));
		assertEquals("50000", rows.get(0).get("unit_price"));
		assertEquals("30000", rows.get(1).get("unit_price"));
	}

	/**
	 * 对比达到门槛与低于门槛的整单：安装和付款系数参与试算，自动运费进设备行，人工运费另列。
	 *
	 */
	@Test
	void equipmentFreightThresholdUsesWholeOrderTrialSalesAmount() {
		var catalog = obj("rates", obj("freight_threshold", "8000"), "regions", obj("贵州", "1.1"));
		var equipment = freightRow("1000", "1");
		var automaticRows = new ArrayList<Map<String, Object>>(List.of(equipment));
		var automaticFreight = EquipmentPricingEngine.equipmentFreight(
				obj("include_freight", true, "region", "贵州"), catalog, automaticRows, 0, true);
		var automaticIssues = new ArrayList<String>();
		var automaticResult = obj("rows", automaticRows, "subtotal", "4000", "fees",
				obj("equipment_freight", automaticFreight), "payment", obj("coefficient", "1.5"));
		InstallationPricingEngine.resolveEquipmentFreight(
				obj("equipment_options", obj("freight_amount", "")), automaticResult, obj("amount", "1400"), automaticIssues);
		assertTrue(automaticIssues.isEmpty());
		assertEquals("8250", automaticFreight.get("trial_order_total"));
		assertEquals("regional_per_unit", automaticFreight.get("basis"));
		assertEquals(true, automaticFreight.get("included_in_rows"));
		assertEquals("1100", equipment.get("unit_price"));
		assertEquals("4100", automaticResult.get("subtotal"));

		// 低于门槛时采用人工整单运费，保留设备原单价且不把人工费用写入设备小计。
		var manualRow = freightRow("1000", "1");
		var manualRows = new ArrayList<Map<String, Object>>(List.of(manualRow));
		var manualFreight = EquipmentPricingEngine.equipmentFreight(
				obj("include_freight", true, "region", "贵州"), catalog, manualRows, 0, true);
		var manualIssues = new ArrayList<String>();
		var manualResult = obj("rows", manualRows, "subtotal", "2000", "fees", obj("equipment_freight", manualFreight),
				"payment", obj("coefficient", "2"));
		InstallationPricingEngine.resolveEquipmentFreight(
				obj("equipment_options", obj("freight_amount", "600")), manualResult, obj("amount", "1000"), manualIssues);
		assertTrue(manualIssues.isEmpty());
		assertEquals("6200", manualFreight.get("trial_order_total"));
		assertEquals("manual_order", manualFreight.get("basis"));
		assertEquals("600", manualFreight.get("amount"));
		assertEquals(false, manualFreight.get("included_in_rows"));
		assertEquals("1000", manualRow.get("unit_price"));
		assertEquals("2000", manualResult.get("subtotal"));
	}

	// 构造付款前、未含运费的已计价设备行，隔离运费方案与整单门槛的验证。
	private static Map<String, Object> freightRow(String unitPrice, String quantity) {
		return obj("status", "priced", "unit_price", unitPrice, "amount", text(mul(n(unitPrice), n(quantity))),
				"body_amount", text(mul(n(unitPrice), n(quantity))), "billing_quantity", quantity, "trace",
				new ArrayList<Map<String, Object>>(), "coverage", obj("installation", false, "freight", false), "detail", obj());
	}

	/**
	 * 使用冻结基准核验设备与混合报价；附阀核验提示按现行可补充字段规则校正后再比较。
	 *
	 */
	@TestFactory
	Stream<DynamicTest> matchesFrozenPythonEquipmentResults() throws IOException {
		Map<String, Object> fixture;
		try (InputStream in = new GZIPInputStream(Objects
			.requireNonNull(getClass().getResourceAsStream("/quotation/equipment-installation-v1-golden.json.gz")))) {
			fixture = new ObjectMapper().readValue(in, Map.class);
		}
		return list(fixture.get("cases")).stream()
			.map(c -> DynamicTest.dynamicTest(s(c, "name"), () -> {
			var engine = new EquipmentPricingEngine();
			var q = map(c.get("quote"));
			var resources = list(map(fixture.get("catalogs")).get(s(c, "resources_key")));
			var result = s(q, "pricing_mode").equals("mixed_v1") ? engine.mixed(q, resources, map(c.get("policy")))
					: engine.calculate(q, resources, map(c.get("policy")));
			var expected = new LinkedHashMap<>(map(c.get("expected")));
			allowDetailedFrozenCostReason(list(expected.get("rows")), list(result.get("rows")));
			if (s(q, "pricing_mode").equals("mixed_v1"))
				compareMixed(expected, result, q);
			else {
				expected.put("engine_version", EquipmentPricingEngine.VERSION);
				// 现行附阀规则逐项给出可补齐的输入原因，冻结旧基准的笼统拒绝提示按此口径转换。
				for (var expectedRow : list(expected.get("rows")))
					if (s(expectedRow, "reason").equals("风机含附带阀门，请明确整套供货配置及成本；不能只报价普通风机"))
						expectedRow.put("reason", "风机含附带阀门，缺少或需核对：阀门宽度和高度（mm），圆阀则填写直径；每台风机附阀数量（正整数）；风机成本是否已含阀（是/否）。补齐后重新导入或保存，再试算。");
				compare(expected, result, "result");
			}
		}));
	}

	// 只兼容这个旧缺成本提示增加的两个已知原因，其余原因、待确认状态及所有金额仍逐项核对。
	private static void allowDetailedFrozenCostReason(List<Map<String, Object>> expectedRows,
			List<Map<String, Object>> actualRows) {
		assertEquals(expectedRows.size(), actualRows.size());
		String legacy = "符合性能的候选均缺少有效本体或电机成本，需补齐价格";
		var allowed = Set.of("选定机号的本体成本公式缺失或重复", "所选功率及极数组合缺少电机价");
		for (int i = 0; i < expectedRows.size(); i++) {
			var expected = expectedRows.get(i);
			var actual = actualRows.get(i);
			String reason = s(actual, "reason");
			if (!s(expected, "reason").equals(legacy) || !reason.startsWith(legacy + "：")) continue;
			var details = Arrays.asList(reason.substring((legacy + "：").length()).split("；", -1));
			assertTrue(allowed.containsAll(details), "unexpected new cost reason: " + reason);
			assertEquals("pending", actual.get("status"));
			assertNull(actual.get("unit_price"));
			assertNull(actual.get("amount"));
			expected.put("reason", reason);
		}
	}

	// 混合报价风管部分只核对业务字段，设备部分继续逐项比较完整源结果。
	private static void compareMixed(Map<String, Object> expected, Map<String, Object> actual,
			Map<String, Object> quote) {
		// 风管 Java 明细已采用中文界面措辞，这里核对金额等业务输出；设备行继续逐项比较完整结果。
		for (String key : List.of("subtotal", "total", "before_payment_total", "payment_amount", "rounding_difference",
				"installation_engine_version", "pending_count", "formal_ready", "duct_engine_version",
				"accessory_items", "aggregate_flanges"))
			compare(expected.get(key), actual.get(key), "result." + key);
		assertEquals(EquipmentPricingEngine.MIXED_VERSION, actual.get("engine_version"));
		assertEquals(((List<?>) expected.get("issues")).size(), ((List<?>) actual.get("issues")).size(), "issue count");
		List<Map<String, Object>> erows = list(expected.get("rows")), arows = list(actual.get("rows")),
				inputs = list(quote.get("rows"));
		assertEquals(erows.size(), arows.size());
		for (int i = 0; i < erows.size(); i++) {
			if (!s(inputs.get(i), "category").equals("风管"))
				compare(erows.get(i), arows.get(i), "result.rows[" + i + "]");
			else
				for (String key : List.of("status", "unit_price", "amount", "billing_quantity", "body_amount",
						"untaxed_unit_price", "untaxed_amount", "tax_amount", "body_unit_price", "body_base_unit_price",
						"flange_amount", "flange_unit_price", "round_flange_amount", "area_factor",
						"payment_coefficient"))
					compare(erows.get(i).get(key), arows.get(i).get(key), "result.rows[" + i + "]." + key);
		}
		var efees = map(expected.get("fees"));
		var afees = map(actual.get("fees"));
		for (String name : efees.keySet())
			for (String key : List.of("amount", "status", "untaxed_amount", "unit_price"))
				compare(map(efees.get(name)).get(key), map(afees.get(name)).get(key),
						"result.fees." + name + "." + key);
	}

	// 递归比较金额、状态和明细；数值用 BigDecimal 比较，忽略表示上的尾零差异。
	static void compare(Object expected, Object actual, String path) {
		if (expected instanceof Map<?, ?> values) {
			assertInstanceOf(Map.class, actual, path);
			values.forEach((k, v) -> compare(v, ((Map<?, ?>) actual).get(k), path + "." + k));
		}
		else if (expected instanceof List<?> values) {
			assertInstanceOf(List.class, actual, path);
			var results = (List<?>) actual;
			assertEquals(values.size(), results.size(), path);
			for (int i = 0; i < values.size(); i++)
				compare(values.get(i), results.get(i), path + "[" + i + "]");
		}
		else if (expected != null && actual != null && str(expected).matches("-?\\d+(\\.\\d+)?")
				&& str(actual).matches("-?\\d+(\\.\\d+)?"))
			assertEquals(0, new BigDecimal(str(expected)).compareTo(new BigDecimal(str(actual))),
					path + " expected=" + expected + " actual=" + actual);
		else
			assertEquals(expected, actual, path);
	}

}
