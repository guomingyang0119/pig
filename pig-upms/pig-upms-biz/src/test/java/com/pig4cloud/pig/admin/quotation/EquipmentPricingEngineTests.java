package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
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
	void smokeOutletPanelExpressionUsesTheSameOverallHeightAsPressureSupply() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
				.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var parsed = EquipmentPricingEngine.parseAttributes(obj("name", "多叶排烟口", "spec", "800×(1000+250)",
				"attributes", obj("width", "800", "height", "1250")));
		assertEquals("1250", parsed.get("height"));
		assertEquals("250", parsed.get("panel_height"));
		assertEquals("1250", parsed.get("overall_height"));
		var actual = EquipmentPricingEngine.valveCost("multi_leaf", parsed, catalog);
		var expected = EquipmentPricingEngine.valveCost("multi_leaf", EquipmentPricingEngine.parseAttributes(
				obj("name", "正压送风口", "spec", "800×1250", "attributes", obj())), catalog);
		assertEquals(0, expected.amount().compareTo(actual.amount()));
		assertEquals(false, actual.detail().get("split"));
		assertEquals("4", actual.detail().get("blades"));
		assertEquals("800", actual.detail().get("body_width"));
		assertEquals("1250", actual.detail().get("body_height"));
		assertEquals("1310", actual.detail().get("panel_outer_height"));
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

	/** 阀门的联锁控制说明不能把本行主产品改成被控制的风机。 */
	@Test
	void valveControlDescriptionsDoNotBecomeFanProducts() {
		String valve = "280℃排烟防火阀常开\n远距离手动复位;熔断关闭后输出电信号联锁关闭排烟风机(及补风机);消控室显示启闭状态";
		assertEquals("fire", EquipmentPricingEngine.classify(obj("name", valve, "spec", "1250×630", "attributes", obj())));
		assertEquals("fire", EquipmentPricingEngine.classify(obj("name", "280℃排烟防火阀常开\n手动复位;消控室显示启闭状态", "attributes", obj())));
		assertEquals("fire", EquipmentPricingEngine.classify(obj("name", "防火阀", "spec", "800×500，联锁关闭SWF-I-7排烟风机", "attributes", obj())));
	}

	/** 风机控制柜是独立外购产品，真正的含阀风机仍使用风机本体分类。 */
	@Test
	void fanControlCabinetsAndFansWithAttachedValvesHaveDifferentMainProducts() {
		assertEquals("purchased", EquipmentPricingEngine.classify(obj("name", "排风风机控制柜", "attributes", obj())));
		assertEquals("purchased", EquipmentPricingEngine.classify(obj("name", "排烟风机控制箱", "attributes", obj())));
		assertEquals("fan", EquipmentPricingEngine.classify(obj("name", "轴流风机（带70°防火阀）", "attributes", obj())));
		assertEquals("fan", EquipmentPricingEngine.classify(obj("name", "防爆轴流风机（带防火阀）", "attributes", obj())));
		assertEquals("plenum", EquipmentPricingEngine.classify(obj("name", "消声静压箱", "attributes", obj())));
	}

	/** 自动原文识别忽略 AI 类别和解释字段，人工明确选择仍由正式分类入口优先采用。 */
	@Test
	void sourceClassificationDoesNotTrustAiSuggestionsOrExplanatoryFields() {
		var row = obj("name", "280℃排烟防火阀常开\n熔断关闭后联锁关闭排烟风机", "spec", "1250×630",
				"main_product_name", "排烟风机", "control_description", "风机", "attached_components_description", "风机",
				"equipment_kind", "fan", "equipment_kind_source", "ai", "attributes", obj("equipment_kind", "fan"));
		assertEquals("fire", EquipmentPricingEngine.classifySource(row));
		assertEquals("fan", EquipmentPricingEngine.classify(row));
		assertEquals("", EquipmentPricingEngine.classifySource(obj("name", "待识别材料", "main_product_name", "防火阀",
				"control_description", "关闭排烟风机", "attached_components_description", "轴流风机")));
	}

	/** 未知行业主产品不能借用括号内或分段后的附件类型。 */
	@Test
	void sourceClassificationDoesNotUseAttachmentsForUnknownMainProducts() {
		for (String name : List.of("稳压箱（配百叶）", "稳压箱(带防火阀)", "稳压箱；附带轴流风机"))
			assertEquals("", EquipmentPricingEngine.classifySource(obj("name", name)), name);
	}

	/** 前置的明确带阀描述属于附件，后面的风机仍是本行主产品。 */
	@Test
	void leadingValveAttachmentDescriptionsKeepTheFanMainProduct() {
		for (String name : List.of("带70℃防火阀轴流风机", "（带防火阀）轴流风机"))
			assertEquals("fan", EquipmentPricingEngine.classifySource(obj("name", name)), name);
	}

	/** 前置带控制开关是百叶本体的修饰，剩余文本不构成另一主产品时不能剥离。 */
	@Test
	void leadingAttachmentPrefixDoesNotRemoveTheEntireLouverProduct() {
		assertEquals("louver", EquipmentPricingEngine.classifySource(obj("name", "带控制开关百叶风口")));
	}

	/** 仅写带防火阀仍保留阀门主语，不能把整个名称视为另一设备的附件删除。 */
	@Test
	void leadingAttachmentPrefixDoesNotRemoveTheEntireValveProduct() {
		assertEquals("fire", EquipmentPricingEngine.classifySource(obj("name", "带70℃防火阀")));
		assertEquals("fire", EquipmentPricingEngine.classifySource(obj("name", "带70℃防火阀800×500")));
	}

	/** 型号后备只用本体型号资料，不能从联锁对象或附件说明中拾取风机型号。 */
	@Test
	void modelFallbackDoesNotUseControlOrAttachmentDescriptions() {
		assertEquals("", EquipmentPricingEngine.classifySource(obj("name", "稳压箱", "remark", "联锁关闭SWF-I-7排烟风机")));
		assertEquals("", EquipmentPricingEngine.classifySource(obj("name", "稳压箱", "spec", "800×500；联锁关闭SWF-I-7排烟风机")));
		assertEquals("", EquipmentPricingEngine.classifySource(obj("name", "稳压箱", "spec", "800×500（配SWF-I-7风机）")));
		assertEquals("fan", EquipmentPricingEngine.classifySource(obj("name", "PF-3-2", "spec", "SWF-I-7 风量27500 风压700")));
	}

	/**
	 * 自垂百叶沿用普通百叶的面积公式，读取独立成本指标。
	 *
	 */
	@Test
	void selfClosingLouverUsesItsOwnRateInThePlainLouverMaterialFormula() throws IOException {
		Map<String, Object> catalog = new ObjectMapper()
			.readValue(DuctExportService.asset("equipment-catalog-seed.json"), Map.class);
		var dimensions = obj("width", "400", "height", "400");
		var plain = EquipmentPricingEngine.areaCost("louver", obj("name", "单层百叶风口", "spec", "400×400"),
				dimensions, catalog);
		var gravity = EquipmentPricingEngine.areaCost("louver", obj("name", "自垂百叶风口", "spec", "400×400"),
				dimensions, catalog);
		assertEquals(0, plain.amount().compareTo(n("14.812")));
		assertEquals(0, gravity.amount().compareTo(n("16.928")));
		assertEquals("gravity", gravity.detail().get("louver_type"));
		assertEquals("gravity", gravity.detail().get("pricing_louver_type"));
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
		assertEquals(s(detail, "mechanism_base") + " + " + s(detail, "remote_extra") + " × 1",
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
		var cases = list(fixture.get("cases"));
		Map<String, Object> dimensions = new ObjectMapper().readValue(getClass().getResourceAsStream("/quotation/dimension-order-golden.json"), Map.class);
		return cases.stream()
			.map(c -> DynamicTest.dynamicTest(s(c, "name"), () -> {
			var engine = new EquipmentPricingEngine();
			var q = map(c.get("quote"));
			var resources = list(map(fixture.get("catalogs")).get(s(c, "resources_key")));
			var expected = frozenExpectedWithConfirmedSplice(c, cases);
			for (int i = 0; i < list(expected.get("rows")).size(); i++) {
				var correction = map(dimensions.get(s(c, "name") + ":" + i));
				if (correction.isEmpty()) continue;
				var corrected = list(expected.get("rows")).get(i);
				corrected.put("attributes", new LinkedHashMap<>(map(correction.get("attributes"))));
				if (correction.containsKey("detail")) {
					corrected.put("detail", new ObjectMapper().convertValue(correction.get("detail"), Map.class));
					var rates = map(map(resources.stream().filter(r -> s(r, "kind").equals("equipment_catalog")).findFirst().orElseThrow().get("body")).get("rates"));
					updateFrozenValvePrice(q, rates, corrected);
					updateFrozenEquipmentTotal(expected);
				}
			}
			applyConfirmedValveMechanisms(q, resources, expected);
			applyConfirmedValveSplices(q, resources, expected);
			applyConfirmedRoundValveCost(q, resources, expected);
			applyIndependentGravityIndicator(q, resources, expected);
			applyConfirmedFanAliases(s(c, "name"), q, resources, expected);
			var result = s(q, "pricing_mode").equals("mixed_v1") ? engine.mixed(q, resources, map(c.get("policy")))
					: engine.calculate(q, resources, map(c.get("policy")));
			// 新确认的系列锁定约束覆盖旧基准中的未知系列自动替代行为。
			if (Set.of("fan-edge-2747", "fan-edge-2748", "fan-edge-2749").contains(s(c, "name"))) {
				var row = list(result.get("rows")).get(0);
				assertEquals("pending", row.get("status"));
				assertEquals("客户指定的风机系列没有匹配资料，不能自动更换系列", row.get("reason"));
				assertNull(row.get("unit_price"));
				assertNull(row.get("amount"));
				assertTrue(map(map(row.get("detail")).get("selected_fan")).isEmpty());
				assertFalse(yes(result.get("formal_ready")));
				return;
			}
			if (s(c, "name").equals("parse-正压送风口"))
				applyConfirmedPressureSupplyCost(expected);
			allowDetailedFrozenCostReason(list(expected.get("rows")), list(result.get("rows")));
			// 该旧用例缺的是目录低档资料；只更新本次明确调整的提示，金额、状态和其余字段继续冻结比较。
			if (s(c, "name").equals("fan-edge-2753")) {
				var expectedRow = list(expected.get("rows")).get(0);
				assertEquals("需核实双速高低档风量/风压配对数据", expectedRow.get("reason"));
				expectedRow.put("reason", "系统风机资料缺少低速档风量、低速档风压，暂不能验证客户要求；请补齐系统资料。参数无误时，可使用已有成本模板或补充已核实的整台采购成本。");
			}
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

	/**
	 * 2026-10-09 已确认机构规则对旧 Python 基准的独立转换。结构成本仍逐项使用冻结值：
	 * 防火阀及板式口 N=ceil(长边/2000)+ceil(短边/1000)-1，远控每机构加 40；
	 * 板式口仍用原结构公式，不再将旧分组标记解释成结构拆分。本基准没有全自动用例。
	 * 只在预期侧计算，既不调用生产成本/分数工具，也不读取本次实际结果。
	 */
	private static void applyConfirmedValveMechanisms(Map<String, Object> quote,
			List<Map<String, Object>> resources, Map<String, Object> expected) {
		var inputs = list(quote.get("rows"));
		var rows = list(expected.get("rows"));
		boolean converted = false;
		for (int i = 0; i < inputs.size(); i++) {
			var input = inputs.get(i);
			String kind = s(map(input.get("attributes")), "equipment_kind");
			if (kind.isEmpty() && s(input, "name").contains("板式排烟口")) kind = "plate_smoke";
			if (kind.isEmpty() && s(input, "name").equals("防火阀")) kind = "fire";
			if (!Set.of("fire", "electric_fire", "plate_smoke").contains(kind)) continue;
			var row = rows.get(i);
			if (!s(row, "status").equals("priced")) continue;
			// 明确限制在当前冻结矩阵；若以后加入运费/安装/混合用例，应先独立扩展预期算法。
			assertEquals("equipment_v1", quote.get("pricing_mode"));
			assertEquals(1, inputs.size());
			var options = map(quote.get("equipment_options"));
			assertEquals(false, options.get("include_freight"));
			assertEquals("not_applicable", map(map(expected.get("fees")).get("installation")).get("status"));
			var catalog = resources.stream().filter(r -> s(r, "kind").equals("equipment_catalog"))
				.map(r -> map(r.get("body"))).findFirst().orElseThrow();
			var rates = map(catalog.get("rates"));
			var attributes = map(row.get("attributes"));
			BigDecimal width = frozenDecimal(attributes.get("width")), height = frozenDecimal(attributes.get("height"));
			BigDecimal count = width.max(height).divide(new BigDecimal("2000"), 0, RoundingMode.CEILING)
				.add(width.min(height).divide(new BigDecimal("1000"), 0, RoundingMode.CEILING)).subtract(BigDecimal.ONE);
			String control = s(map(input.get("attributes")), "control");
			if (control.isEmpty()) control = s(input, "name").contains("远控") ? "remote" : "near";
			assertTrue(Set.of("near", "remote").contains(control), "frozen control needs an independent expectation");
			BigDecimal base = frozenDecimal(kind.equals("plate_smoke") ? rates.get("near_control")
					: map(map(catalog.get("valves")).get(kind)).get("mechanism"));
			assertFalse(map(input.get("attributes")).containsKey("mechanism_cost"));
			BigDecimal mechanism = base.multiply(kind.equals("plate_smoke") ? count : BigDecimal.ONE)
					.add((control.equals("remote") ? new BigDecimal("40") : BigDecimal.ZERO).multiply(count));
			var detail = map(row.get("detail"));
			var components = map(detail.get("components"));
			components.put("mechanism", mechanism.toPlainString());
			if (kind.equals("plate_smoke")) detail.putAll(obj("split", false, "groups", "1"));
			if (s(input, "name").equals("远控板式排烟口"))
				attributes.putAll(obj("remote", true, "control", "remote"));
			updateFrozenValvePrice(quote, rates, row);
			converted = true;
		}
		if (converted) updateFrozenEquipmentTotal(expected);
	}

	/**
	 * 旧双边超限 pending 用例以冻结文件中唯一同源、仅多填 66 元拼接费的 priced 用例
	 * 补回结构成本。逐项核验输入、资源和政策相同，随后独立应用已确认的新机构及拼接规则。
	 * 深拷贝保证不会把前一个用例转换后的预期再次当作冻结结构依据。
	 */
	private static Map<String, Object> frozenExpectedWithConfirmedSplice(Map<String, Object> testCase,
			List<Map<String, Object>> cases) {
		var mapper = new ObjectMapper();
		Map<String, Object> expected = mapper.convertValue(testCase.get("expected"), Map.class);
		var rows = list(expected.get("rows"));
		if (rows.size() != 1 || !s(rows.get(0), "reason")
			.equals("宽超过2000且高超过1000，请报价人员确认拼接费用")) return expected;
		var quote = map(testCase.get("quote"));
		assertEquals("equipment_v1", quote.get("pricing_mode"));
		assertEquals(1, list(quote.get("rows")).size());
		assertFalse(map(list(quote.get("rows")).get(0).get("attributes")).containsKey("splice_cost"));
		var siblings = cases.stream().filter(c -> s(c, "name").startsWith("splice-confirmed-"))
			.filter(c -> Objects.equals(testCase.get("resources_key"), c.get("resources_key"))
				&& Objects.equals(testCase.get("policy"), c.get("policy")))
			.filter(c -> {
				Map<String, Object> siblingQuote = mapper.convertValue(c.get("quote"), Map.class);
				var inputs = list(siblingQuote.get("rows"));
				if (inputs.size() != 1) return false;
				var attrs = map(inputs.get(0).get("attributes"));
				if (!"66".equals(attrs.remove("splice_cost"))) return false;
				return quote.equals(siblingQuote);
			}).toList();
		assertEquals(1, siblings.size(), "pending splice case requires one independent frozen priced sibling");
		expected = mapper.convertValue(siblings.get(0).get("expected"), Map.class);
		assertEquals("priced", list(expected.get("rows")).get(0).get("status"));
		map(list(expected.get("rows")).get(0).get("attributes")).remove("splice_cost");
		return expected;
	}

	/**
	 * 2026-10-12 已确认拼接规则的独立预期：宽、高分别按各自目录阈值分段，
	 * 每边次数为 ceil(尺寸/阈值)-1，两边相加乘原产品拼接费率；旧人工总额不再参与。
	 * 只替换拼接组成项，其余结构、机构金额沿用冻结依据及上面的独立机构转换。
	 */
	private static void applyConfirmedValveSplices(Map<String, Object> quote,
			List<Map<String, Object>> resources, Map<String, Object> expected) {
		var catalog = resources.stream().filter(r -> s(r, "kind").equals("equipment_catalog"))
			.map(r -> map(r.get("body"))).findFirst().orElse(null);
		if (catalog == null) return;
		var rates = map(catalog.get("rates"));
		boolean converted = false;
		for (var row : list(expected.get("rows"))) {
			String kind = s(row, "equipment_kind");
			if (!s(row, "status").equals("priced")
				|| !Set.of("fire", "electric_fire", "electric_control", "manual", "pressure_relief").contains(kind)) continue;
			var attrs = map(row.get("attributes"));
			BigDecimal widthCount = frozenDecimal(attrs.get("width"))
				.divide(frozenDecimal(rates.get("splice_width")), 0, RoundingMode.CEILING)
				.subtract(BigDecimal.ONE).max(BigDecimal.ZERO);
			BigDecimal heightCount = frozenDecimal(attrs.get("height"))
				.divide(frozenDecimal(rates.get("splice_height")), 0, RoundingMode.CEILING)
				.subtract(BigDecimal.ONE).max(BigDecimal.ZERO);
			BigDecimal splice = widthCount.add(heightCount)
				.multiply(frozenDecimal(rates.get(kind.equals("manual") ? "manual_splice" : "fire_splice")));
			var components = map(map(row.get("detail")).get("components"));
			if (splice.compareTo(frozenDecimal(components.get("splice"))) == 0) continue;
			components.put("splice", splice.toPlainString());
			updateFrozenValvePrice(quote, rates, row);
			converted = true;
		}
		if (converted) updateFrozenEquipmentTotal(expected);
	}

	/** 圆阀新口径独立作用于冻结成本，不改写 Python 原始样本，也不读取生产计算结果。 */
	private static void applyConfirmedRoundValveCost(Map<String, Object> quote,
			List<Map<String, Object>> resources, Map<String, Object> expected) {
		boolean converted = false;
		for (var row : list(expected.get("rows"))) {
			if (!s(row, "status").equals("priced") || !s(map(row.get("attributes")), "shape").equals("round")
					|| !Set.of("fire", "electric_fire", "electric_control", "manual", "check", "pressure_relief", "multi_leaf", "plate_smoke")
							.contains(s(row, "equipment_kind"))) continue;
			var catalog = resources.stream().filter(r -> s(r, "kind").equals("equipment_catalog"))
					.map(r -> map(r.get("body"))).findFirst().orElseThrow();
			updateFrozenValvePrice(quote, map(catalog.get("rates")), row);
			converted = true;
		}
		if (converted) updateFrozenEquipmentTotal(expected);
	}

	private static void updateFrozenValvePrice(Map<String, Object> quote, Map<String, Object> rates,
			Map<String, Object> row) {
		assertEquals("equipment_v1", quote.get("pricing_mode"));
		assertEquals(1, list(quote.get("rows")).size());
		var input = list(quote.get("rows")).get(0);
		var options = map(quote.get("equipment_options"));
		assertEquals(false, options.get("include_freight"));
		var attributes = map(row.get("attributes"));
		var components = map(map(row.get("detail")).get("components"));
		components.remove("round_processing");
		BigDecimal cost = components.values().stream().map(EquipmentPricingEngineTests::frozenDecimal)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (s(attributes, "shape").equals("round")) {
			components.put("round_processing", cost.multiply(new BigDecimal("0.3")).toPlainString());
			cost = cost.multiply(new BigDecimal("1.3"));
			map(row.get("detail")).remove("round_factor");
		}
		BigDecimal denominator = BigDecimal.ONE.subtract(frozenDecimal(rates.get("gross_margin")));
		BigDecimal numerator = cost;
		if (Boolean.TRUE.equals(options.get("tax_included"))) numerator = numerator.multiply(frozenDecimal(rates.get("tax_factor")));
		BigDecimal quantity = frozenDecimal(input.get("quantity"));
		BigDecimal unit = numerator.divide(denominator, 0, RoundingMode.CEILING);
		BigDecimal amount = unit.multiply(quantity).setScale(0, RoundingMode.CEILING);
		// 乘税/外形/数量先在分子精确完成，最后才除分母展示，避免循环小数连乘误差。
		String supply = frozenQuotient(numerator, denominator);
		row.putAll(obj("unit_cost", cost.toPlainString(), "supply_unit_unrounded", supply,
				"supply_amount_unrounded", frozenQuotient(numerator.multiply(quantity), denominator),
				"unit_price", unit.toPlainString(), "amount", amount.toPlainString(), "body_amount", amount.toPlainString()));
		var traceValues = obj("不含税成本", cost.toPlainString(), "不含税供货价", frozenQuotient(cost, denominator),
				"付款前供货价", supply, "设备运费", "0", "设备单价", unit.toPlainString(), "设备行金额", amount.toPlainString());
		assertEquals(traceValues.size(), list(row.get("trace")).size());
		for (var trace : list(row.get("trace"))) {
			assertTrue(traceValues.containsKey(s(trace, "step")), "unexpected frozen trace step");
			trace.put("value", traceValues.get(s(trace, "step")));
		}
	}

	private static void updateFrozenEquipmentTotal(Map<String, Object> expected) {
		var rows = list(expected.get("rows"));
		BigDecimal subtotal = rows.stream().filter(r -> s(r, "status").equals("priced"))
			.map(r -> frozenDecimal(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
		for (String key : List.of("subtotal", "material_total", "equipment_material_total", "before_payment_total"))
			expected.put(key, subtotal.toPlainString());
		map(map(expected.get("fees")).get("equipment_freight")).put("equipment_subtotal", subtotal.toPlainString());
		BigDecimal paymentTotal = subtotal.multiply(frozenDecimal(map(expected.get("payment")).get("coefficient")));
		BigDecimal total = paymentTotal.setScale(0, RoundingMode.CEILING);
		expected.putAll(obj("total", total.toPlainString(), "payment_amount", paymentTotal.subtract(subtotal).toPlainString(),
				"rounding_difference", total.subtract(paymentTotal).toPlainString()));
	}

	/** 自垂由借用单层指标改为独立取价，只转换冻结矩阵中的四个普通自垂用例。 */
	private static void applyIndependentGravityIndicator(Map<String, Object> quote,
			List<Map<String, Object>> resources, Map<String, Object> expected) {
		var rows = list(expected.get("rows"));
		if (rows.size() != 1 || !s(map(rows.get(0).get("detail")), "louver_type").equals("gravity")) return;
		assertEquals("equipment_v1", quote.get("pricing_mode"));
		var row = rows.get(0);
		var detail = map(row.get("detail"));
		var catalog = resources.stream().filter(r -> s(r, "kind").equals("equipment_catalog"))
			.map(r -> map(r.get("body"))).findFirst().orElseThrow();
		var values = (List<?>) map(catalog.get("louvers")).get("gravity");
		var rates = map(catalog.get("rates"));
		var options = map(quote.get("equipment_options"));
		assertEquals(false, options.get("include_freight"));
		assertEquals("not_applicable", map(map(expected.get("fees")).get("installation")).get("status"));
		BigDecimal cost = frozenDecimal(detail.get("area")).multiply(frozenDecimal(values.get(0)))
			.multiply(frozenDecimal(values.get(3))).max(frozenDecimal(values.get(1)));
		BigDecimal denominator = BigDecimal.ONE.subtract(frozenDecimal(rates.get("gross_margin")));
		BigDecimal numerator = Boolean.TRUE.equals(options.get("tax_included")) ? cost.multiply(frozenDecimal(rates.get("tax_factor"))) : cost;
		BigDecimal quantity = frozenDecimal(list(quote.get("rows")).get(0).get("quantity"));
		BigDecimal unit = numerator.divide(denominator, 0, RoundingMode.CEILING);
		BigDecimal amount = unit.multiply(quantity).setScale(0, RoundingMode.CEILING);
		String supply = frozenQuotient(numerator, denominator);
		detail.put("pricing_louver_type", "gravity");
		row.putAll(obj("unit_cost", cost.toPlainString(), "supply_unit_unrounded", supply,
			"supply_amount_unrounded", frozenQuotient(numerator.multiply(quantity), denominator),
			"unit_price", unit.toPlainString(), "amount", amount.toPlainString(), "body_amount", amount.toPlainString()));
		var traceValues = List.of(cost.toPlainString(), frozenQuotient(cost, denominator), supply, "0", unit.toPlainString(), amount.toPlainString());
		var trace = list(row.get("trace"));
		assertEquals(traceValues.size(), trace.size());
		for (int i = 0; i < trace.size(); i++) trace.get(i).put("value", traceValues.get(i));
		for (String key : List.of("subtotal", "material_total", "equipment_material_total", "before_payment_total")) expected.put(key, amount.toPlainString());
		map(map(expected.get("fees")).get("equipment_freight")).put("equipment_subtotal", amount.toPlainString());
		BigDecimal paymentTotal = amount.multiply(frozenDecimal(map(expected.get("payment")).get("coefficient")));
		BigDecimal total = paymentTotal.setScale(0, RoundingMode.CEILING);
		expected.putAll(obj("total", total.toPlainString(), "payment_amount", paymentTotal.subtract(amount).toPlainString(),
			"rounding_difference", total.subtract(paymentTotal).toPlainString()));
	}

	private static BigDecimal frozenDecimal(Object value) {
		return new BigDecimal(Objects.requireNonNull(value).toString());
	}

	/** 两个旧别名曾跨系列选 HTF；新口径限定 SWF。用源表指定工况及逐项独立核算值更新预期，不读取实际结果。 */
	private static void applyConfirmedFanAliases(String name, Map<String, Object> quote,
			List<Map<String, Object>> resources, Map<String, Object> expected) {
		boolean gxf = name.equals("parse-斜流式风机 GXF-9");
		if (!gxf && !name.equals("parse-风机 HL3-2A-NO6A")) return;
		var catalog = resources.stream().filter(r -> s(r, "kind").equals("equipment_catalog"))
				.map(r -> map(r.get("body"))).findFirst().orElseThrow();
		var point = list(catalog.get("fan_points")).stream().filter(p -> s(p, "id").equals(gxf ? "swf:56" : "swf:26")).findFirst().orElseThrow();
		var body = list(catalog.get("fan_bodies")).stream().filter(p -> s(p, "id").equals(gxf ? "body:SWF:9" : "body:SWF:6")).findFirst().orElseThrow();
		var motor = list(catalog.get("motors")).stream().filter(p -> s(p, "id").equals(gxf ? "motor:32:4" : "motor:28:1")).findFirst().orElseThrow();
		String metal = gxf ? "361.5160500" : "165.8720700", other = gxf ? "325.5850" : "194.20200";
		BigDecimal cost = frozenDecimal(metal).add(frozenDecimal(other)).add(frozenDecimal(motor.get("cost")));
		var row = list(expected.get("rows")).get(0);
		map(row.get("detail")).putAll(obj("selected_fan", point, "fan_cost", obj("body", body, "motor", motor,
				"material_cost", metal, "body_other_cost", other, "motor_cost", motor.get("cost"), "steel_price", "4300"),
				"substituted", !gxf, "selection_note", gxf ? "" : "按风量、风压选择有完整成本的最小合格规格"));
		assertEquals(false, map(quote.get("equipment_options")).get("include_freight"));
		assertEquals(false, map(quote.get("equipment_options")).get("tax_included"));
		String supply = frozenQuotient(cost, new BigDecimal("0.75"));
		BigDecimal unit = cost.divide(new BigDecimal("0.75"), 0, RoundingMode.CEILING);
		BigDecimal quantity = frozenDecimal(list(quote.get("rows")).get(0).get("quantity"));
		BigDecimal amount = unit.multiply(quantity);
		row.putAll(obj("unit_cost", cost.toPlainString(), "supply_unit_unrounded", supply,
				"supply_amount_unrounded", frozenQuotient(cost.multiply(quantity), new BigDecimal("0.75")),
				"unit_price", unit.toPlainString(), "amount", amount.toPlainString(), "body_amount", amount.toPlainString()));
		var values = List.of(cost.toPlainString(), supply, supply, "0", unit.toPlainString(), amount.toPlainString());
		var trace = list(row.get("trace"));
		assertEquals(values.size(), trace.size());
		for (int i = 0; i < trace.size(); i++) trace.get(i).put("value", values.get(i));
		updateFrozenEquipmentTotal(expected);
	}

	private static String frozenQuotient(BigDecimal numerator, BigDecimal denominator) {
		return numerator.divide(denominator, new MathContext(48, RoundingMode.HALF_EVEN)).toPlainString();
	}

	/**
	 * 仅此正压口用例改为已确认规则：整体高2050、大尺寸成本、70元/㎡面板，长边超2000增加一个20元机构。
	 * 数值由源表逐项独立核算，冻结 Python 文件及其余用例保持原样，不从实际结果生成预期。
	 */
	private static void applyConfirmedPressureSupplyCost(Map<String, Object> expected) {
		var row = list(expected.get("rows")).get(0);
		String unit = text(n("240.440906083").divide(n("0.75"), MC));
		String supplyAmount = text(n("240.440906083").multiply(n(2)).divide(n("0.75"), MC));
		row.putAll(obj("unit_cost", "240.440906083", "supply_unit_unrounded", unit,
				"supply_amount_unrounded", supplyAmount, "unit_price", "321", "amount", "642", "body_amount", "642"));
		map(row.get("attributes")).put("height", "2050");
		map(map(row.get("detail")).get("components")).putAll(obj("multi_frame", "54.5771250",
				"multi_blade", "14.838394833", "labor", "31", "link", "1.0975475", "shaft", "6.6",
				"consumables", "4.5875", "multi_brace", "5.63787", "multi_back", "1.54546875",
				"mechanism", "20", "splice", "40", "panel", "60.557"));
		var trace = list(row.get("trace"));
		var values = List.of("240.440906083", unit, unit, "0", "321", "642");
		assertEquals(values.size(), trace.size());
		for (int i = 0; i < values.size(); i++)
			trace.get(i).put("value", values.get(i));
		for (String field : List.of("subtotal", "material_total", "equipment_material_total", "total", "before_payment_total"))
			expected.put(field, "642");
		map(map(expected.get("fees")).get("equipment_freight")).put("equipment_subtotal", "642");
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
				"installation_engine_version", "pending_count", "formal_ready",
				"accessory_items", "aggregate_flanges"))
			compare(expected.get(key), actual.get(key), "result." + key);
		assertEquals(EquipmentPricingEngine.MIXED_VERSION, actual.get("engine_version"));
		assertEquals(DuctPricingEngine.VERSION, actual.get("duct_engine_version"));
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
		if ((path.endsWith(".installation_engine_version") || path.endsWith(".engine_version"))
				&& str(expected).startsWith("installation-v")) {
			assertEquals(InstallationPricingEngine.VERSION, actual, path);
			return;
		}
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
