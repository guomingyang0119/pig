package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

class InstallationPricingEngineTests {

	private Map<String, Object> installationResource() throws IOException {
		Map<String, Object> catalog = new ObjectMapper().readValue(DuctExportService.asset("installation-catalog-seed.json"),
				Map.class);
		catalog.put("effective_from", "2020-01-01");
		return obj("id", "installation", "family", "installation", "version", 1, "kind", "installation_catalog",
				"status", "published", "body", catalog);
	}

	private Map<String, Object> lineSettlementQuote(boolean taxIncluded) {
		return obj("pricing_mode", "duct_v1", "duct_options", obj("tax_included", taxIncluded),
				"installation_options", obj("included", true, "region", "贵州", "include_auxiliaries", false,
						"include_site_fee", true, "height", "0", "cross_work", false, "site_amount", "0.03", "site_note",
						"高空施工附加费"),
				"rows", List.of(
						obj("name", "第一项", "category", "风管", "engineering_quantity", "1", "unit", "㎡",
								"installation", obj("unit_price", "1.01")),
						obj("name", "第二项", "category", "风管", "engineering_quantity", "1", "unit", "㎡",
								"installation", obj("unit_price", "1.99"))));
	}

	@Test
	void taxesAndRoundsEachInstallationLineAndKeepsSiteChargeSeparate() throws IOException {
		var result = InstallationPricingEngine.calculate(lineSettlementQuote(true), List.of(installationResource()),
				List.of(obj(), obj()), LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var fee = result.fee();
		var lines = list(fee.get("lines"));
		assertEquals("2", lines.get(0).get("quoted_amount"));
		assertEquals("3", lines.get(1).get("quoted_amount"));
		assertEquals("1", fee.get("site_quoted_amount"));
		assertEquals("5", fee.get("line_amount"));
		assertEquals("6", fee.get("amount"));
		assertEquals("高空施工附加费", fee.get("site_note"));
	}

	@Test
	void untaxedOrderRoundsLinesButUsesEnteredSiteChargeDirectly() throws IOException {
		var result = InstallationPricingEngine.calculate(lineSettlementQuote(false), List.of(installationResource()),
				List.of(obj(), obj()), LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var fee = result.fee();
		var lines = list(fee.get("lines"));
		assertEquals("2", lines.get(0).get("quoted_amount"));
		assertEquals("2", lines.get(1).get("quoted_amount"));
		assertEquals("0.03", fee.get("site_quoted_amount"));
		assertEquals("4.03", fee.get("amount"));
		assertEquals("0.00", fee.get("tax_amount"));
	}

	@Test
	void confirmedLineKeepsItsOwnAmountWhenAnotherInstallationLineIsPending() throws IOException {
		var quote = lineSettlementQuote(true);
		map(list(quote.get("rows")).get(1).get("installation")).clear();
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertFalse(result.issues().isEmpty());
		assertEquals("pending", result.fee().get("status"));
		var lines = list(result.fee().get("lines"));
		assertEquals("2", lines.get(0).get("quoted_amount"));
		assertEquals("pending", lines.get(1).get("status"));
	}

	@Test
	void positiveSiteChargeRequiresAReasonForTheQuotationRemark() throws IOException {
		var quote = lineSettlementQuote(true);
		map(quote.get("installation_options")).put("site_note", "");
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertEquals("pending", result.fee().get("status"));
		assertTrue(result.issues().contains("安装费：现场附加费大于0时请填写附加原因"));
	}

	@Test
	void latestCatalogUsesReviewedFourRegionPricesAndRainLouverBands() throws IOException {
		var catalog = map(installationResource().get("body"));
		var projects = list(catalog.get("projects"));
		assertEquals("50", project(projects, "four_16").get("price"));
		assertEquals("20", project(projects, "four_18").get("price"));
		assertEquals("50", project(projects, "four_23").get("price"));
		assertEquals("100", project(projects, "four_24").get("price"));
		assertEquals("35", project(projects, "four_25").get("price"));
		assertEquals("65", project(projects, "four_26").get("price"));

		var quote = obj("duct_options", obj());
		assertEquals("four_18", InstallationPricingEngine.autoProject(equipment("双层百叶风口", ""), quote, "贵州"));
		assertEquals("four_23",
				InstallationPricingEngine.autoProject(equipment("防雨百叶风口", "1200×200"), quote, "贵州"));
		assertEquals("four_24",
				InstallationPricingEngine.autoProject(equipment("防雨百叶风口", "1200×300"), quote, "贵州"));
		assertNull(InstallationPricingEngine.autoProject(equipment("防雨百叶风口", "尺寸待定"), quote, "贵州"));
		assertEquals("sx_33", InstallationPricingEngine.autoProject(equipment("防雨百叶风口", "尺寸待定"), quote, "陕西"));
	}

	@Test
	void latestCatalogResolvesOnlyUnambiguousPreviouslyPendingProducts() {
		var quote = obj("duct_options", obj());
		for (String name : List.of("正压送风口", "多叶排烟口", "多叶送风口", "加压送风口")) {
			assertEquals("four_20", InstallationPricingEngine.autoProject(equipment(name, ""), quote, "贵州"), name);
			assertEquals("sx_31", InstallationPricingEngine.autoProject(equipment(name, ""), quote, "陕西"), name);
		}
		assertEquals("four_16", InstallationPricingEngine.autoProject(equipment("电动阀", ""), quote, "贵州"));
		assertNull(InstallationPricingEngine.autoProject(equipment("电动阀", ""), quote, "陕西"));
		assertEquals("four_16", InstallationPricingEngine.autoProject(equipment("蝶阀", ""), quote, "贵州"));
		assertEquals("sx_23", InstallationPricingEngine.autoProject(equipment("蝶阀", ""), quote, "陕西"));
		assertEquals("four_16", InstallationPricingEngine.autoProject(equipment("280℃排烟阀", ""), quote, "四川"));
		assertEquals("sx_23", InstallationPricingEngine.autoProject(equipment("手动对开多叶调节阀", ""), quote, "陕西"));
		assertEquals("four_16", InstallationPricingEngine.autoProject(equipment("电动对开多叶调节阀", ""), quote, "四川"));
		assertEquals("sx_24",
				InstallationPricingEngine.autoProject(equipment("280℃全自动排烟防火阀", ""), quote, "陕西"));
		assertEquals("four_16",
				InstallationPricingEngine.autoProject(equipment("280℃全自动排烟防火阀", ""), quote, "四川"));
		assertEquals("four_20", InstallationPricingEngine.autoProject(equipment("280℃板式排烟口", ""), quote, "贵州"));
		assertEquals("sx_33", InstallationPricingEngine.autoProject(equipment("280℃板式排烟口", ""), quote, "陕西"));
		assertEquals("sx_17", InstallationPricingEngine.autoProject(equipment("消声弯头", ""), quote, "陕西"));
		assertEquals("four_26", InstallationPricingEngine.autoProject(equipment("消声弯头", ""), quote, "贵州"));
		assertEquals("four_26", InstallationPricingEngine.autoProject(equipment("防雨弯头", ""), quote, "广西"));
		assertEquals("four_25", InstallationPricingEngine.autoProject(equipment("散流器", ""), quote, "四川"));
		assertEquals("four_25", InstallationPricingEngine.autoProject(equipment("带阀散流器", ""), quote, "云南"));
		assertEquals("four_18", InstallationPricingEngine.autoProject(equipment("单层百叶带阀风口", ""), quote, "贵州"));
		assertEquals("four_13", InstallationPricingEngine.autoProject(equipment("轴流风机带防雨百叶", ""), quote, "贵州"));
		assertEquals("four_20",
				InstallationPricingEngine.autoProject(equipment("电动多叶加压送风口", "600*（800+250）"), quote, "贵州"));
		assertEquals("four_18",
				InstallationPricingEngine.autoProject(equipment("单层防火百叶风口", "800*400"), quote, "贵州"));
		assertEquals("four_18",
				InstallationPricingEngine.autoProject(equipment("单层百叶防火送风口", "1800*1000"), quote, "贵州"));
		assertEquals("four_13", InstallationPricingEngine.autoProject(
				equipment("PF-3-2#WD-1", "L=950CMHP=41PaN=40W-220V平时贴梁安装DFBZ-2.8"), quote, "贵州"));
		assertEquals("sx_19", InstallationPricingEngine.autoProject(
				equipment("JY-3-1#WM-01", "L=33210CMHP=1186PaN=15KW消防SWF-I-8落地安装"), quote, "陕西"));
		assertEquals("four_16",
				InstallationPricingEngine.autoProject(equipment("电动泄压阀", "500*1000"), quote, "贵州"));
		assertEquals("sx_23",
				InstallationPricingEngine.autoProject(equipment("电动泄压阀", "500*1000"), quote, "陕西"));
	}

	@Test
	void louverSetUsesTheReviewedPerItemInstallationPrice() throws IOException {
		var row = equipment("单层防火百叶风口", "800*400");
		row.put("unit", "套");
		row.put("quantity", "2");
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(row));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_18", line.get("project_id"));
		assertEquals("个", line.get("unit"));
		assertEquals("2", line.get("quantity"));
		assertEquals("40", line.get("quoted_amount"));
	}

	@Test
	void multiLeafSupplyAndFireOutletSetsCountAsInstallationItems() throws IOException {
		var multiLeaf = equipment("电动多叶加压送风口", "600*（800+250）");
		multiLeaf.put("unit", "套");
		multiLeaf.put("quantity", "3");
		var fireOutlet = equipment("防火风口", "800*400");
		fireOutlet.put("unit", "套");
		fireOutlet.put("quantity", "2");
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(multiLeaf, fireOutlet));

		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()),
				List.of(obj(), obj()), LocalDate.now());

		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var lines = list(result.fee().get("lines"));
		assertEquals("four_20", lines.get(0).get("project_id"));
		assertEquals("个", lines.get(0).get("unit"));
		assertEquals("3", lines.get(0).get("quantity"));
		assertEquals("195", lines.get(0).get("quoted_amount"));
		assertEquals("four_22", lines.get(1).get("project_id"));
		assertEquals("个", lines.get(1).get("unit"));
		assertEquals("2", lines.get(1).get("quantity"));
		assertEquals("120", lines.get(1).get("quoted_amount"));
	}

	@Test
	void electricPressureReliefValveUsesOrdinaryValveInstallationPrice() throws IOException {
		var row = equipment("电动泄压阀", "500*1000");
		row.put("quantity", "3");
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(row));

		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());

		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_16", line.get("project_id"));
		assertEquals("风阀", line.get("project_name"));
		assertEquals("个", line.get("unit"));
		assertEquals("50", line.get("unit_price"));
		assertEquals("150", line.get("quoted_amount"));

		map(quote.get("installation_options")).put("region", "陕西");
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());

		assertTrue(result.issues().isEmpty(), result.issues().toString());
		line = list(result.fee().get("lines")).get(0);
		assertEquals("sx_23", line.get("project_id"));
		assertEquals("套", line.get("unit"));
		assertEquals("3", line.get("quantity"));
		assertEquals("55", line.get("unit_price"));
		assertEquals("165", line.get("quoted_amount"));
	}

	@Test
	void stainlessDuctDefaultsToFullWeldAndUsesHigherRateOnlyWhenInstallationIncludesFlange() throws IOException {
		var row = obj("name", "304不锈钢板风管", "spec", "304不锈钢板0.5mm", "remark", "", "category", "风管",
				"unit", "㎡", "engineering_quantity", "3", "attributes",
				obj("material", "stainless_304", "flange", "angle"));
		var quote = obj("pricing_mode", "duct_v1", "duct_options", obj("tax_included", false),
				"installation_options", obj("included", true, "region", "广西", "include_auxiliaries", false,
						"include_site_fee", false),
				"rows", List.of(row));

		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_6", line.get("project_id"));
		assertEquals("100", line.get("unit_price"));
		assertEquals("300", line.get("quoted_amount"));

		map(row.get("attributes")).put("flange", "安装包含法兰");
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		line = list(result.fee().get("lines")).get(0);
		assertEquals("four_6_flange", line.get("project_id"));
		assertEquals("110", line.get("unit_price"));
		assertEquals("330", line.get("quoted_amount"));
	}

	@Test
	void plateSmokeOutletNameWithWindCharacterUsesReviewedInstallationPrice() throws IOException {
		var row = equipment("280℃板式排烟风口（常闭）", "1250*630");
		row.put("quantity", "2");
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false,
						"height", "", "cross_work", false, "site_amount", "", "site_note", ""),
				"rows", List.of(row));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_20", line.get("project_id"));
		assertEquals("65", line.get("unit_price"));
		assertEquals("130", line.get("quoted_amount"));
	}

	@Test
	void fourRegionStaticBoxUsesSourceQuantityWhenCatalogCountsByItem() throws IOException {
		var row = obj("name", "静压箱", "spec", "1600×800×800", "remark", "", "category", "设备", "unit", "台",
				"quantity", "3", "attributes", obj());
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(row));

		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());

		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_21", line.get("project_id"));
		assertEquals("个", line.get("unit"));
		assertEquals("3", line.get("quantity"));
		assertEquals("300", line.get("unit_price"));
		assertEquals("900", line.get("quoted_amount"));
	}

	@Test
	void gravityLouverWithValveUsesGravityLouverInstallationPrice() throws IOException {
		var row = equipment("自垂百叶（带阀）", "800×500");
		row.put("quantity", "3");
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(row));

		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());

		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_19", line.get("project_id"));
		assertEquals("50", line.get("unit_price"));
		assertEquals("150", line.get("quoted_amount"));
	}

	@Test
	void elbowWithoutItemCountUsesCommonFlangeAreaPrice() throws IOException {
		var row = obj("name", "消声弯头", "spec", "", "remark", "", "category", "消声设备", "unit", "㎡",
				"quantity", "", "engineering_quantity", "10", "attributes", obj("flange", "common"));
		var quote = obj("pricing_mode", "mixed_v1", "duct_options", obj("tax_included", false), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(row));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_3", line.get("project_id"));
		assertEquals("㎡", line.get("unit"));
		assertEquals("10", line.get("quantity"));
		assertEquals("30", line.get("unit_price"));
		assertEquals("300", line.get("quoted_amount"));
	}

	@Test
	void elbowWithoutItemCountUsesAnglePriceWhenOrderHasBothFlanges() throws IOException {
		var elbow = obj("name", "消声弯头", "spec", "", "remark", "", "category", "消声设备", "unit", "㎡",
				"quantity", "", "engineering_quantity", "10", "attributes", obj("flange", "common"));
		var angleDuct = obj("name", "镀锌钢板角钢法兰风管", "spec", "", "remark", "", "category", "风管", "unit", "㎡",
				"engineering_quantity", "1", "attributes", obj("flange", "angle"));
		var quote = obj("pricing_mode", "mixed_v1", "duct_options", obj("tax_included", false), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(elbow, angleDuct));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_4", line.get("project_id"));
		assertEquals("35", line.get("unit_price"));
		assertEquals("350", line.get("quoted_amount"));
	}

	@Test
	void elbowWithItemCountKeepsPerItemPriceWhenOrderHasBothFlanges() throws IOException {
		var elbow = equipment("消声弯头", "");
		elbow.put("quantity", "2");
		var commonDuct = obj("name", "镀锌钢板共板法兰风管", "category", "风管", "unit", "㎡",
				"engineering_quantity", "1", "attributes", obj("flange", "common"));
		var angleDuct = obj("name", "镀锌钢板角钢法兰风管", "category", "风管", "unit", "㎡",
				"engineering_quantity", "1", "attributes", obj("flange", "angle"));
		var quote = obj("pricing_mode", "mixed_v1", "duct_options", obj("tax_included", false), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false),
				"rows", List.of(elbow, commonDuct, angleDuct));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()),
				List.of(obj(), obj(), obj()), LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("four_26", line.get("project_id"));
		assertEquals("个", line.get("unit"));
		assertEquals("65", line.get("unit_price"));
		assertEquals("130", line.get("quoted_amount"));
	}

	@Test
	void explicitRemoteControlAddsTheReviewedSurchargeOnlyInFourRegions() throws IOException {
		var quote = obj("pricing_mode", "equipment_v1", "duct_options", obj(), "equipment_options",
				obj("tax_included", false), "installation_options",
				obj("included", true, "region", "贵州", "include_auxiliaries", false, "include_site_fee", false,
						"height", "", "cross_work", false, "site_amount", "", "site_note", ""),
				"rows", List.of(equipment("远控防火阀", "500×500")));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		var line = list(result.fee().get("lines")).get(0);
		assertEquals("50", line.get("base_unit_price"));
		assertEquals("65", line.get("remote_control_unit_price"));
		assertEquals("115", line.get("unit_price"));
		assertEquals("风阀＋远程控制机构", line.get("project_name"));

		list(quote.get("rows")).get(0).put("name", "电动防火阀");
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj()),
				LocalDate.now());
		line = list(result.fee().get("lines")).get(0);
		assertEquals("50", line.get("unit_price"));
		assertEquals("0", line.get("remote_control_unit_price"));
	}

	@Test
	void crossWorkRequiresAnExplicitSiteChargeDecision() throws IOException {
		var quote = lineSettlementQuote(false);
		var options = map(quote.get("installation_options"));
		options.putAll(obj("include_site_fee", false, "height", "8", "cross_work", true,
				"cross_work_fee_decision", "", "site_amount", "", "site_note", "", "site_waive_reason", ""));
		var result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertTrue(result.issues().contains("安装费：交叉施工请确认是否计取现场附加费"));
		assertEquals("0", result.fee().get("site_amount"));

		options.put("cross_work_fee_decision", "waive");
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertTrue(result.issues().contains("安装费：交叉施工不计取现场附加费时请填写原因"));

		options.put("site_waive_reason", "合同约定本次不另行计取");
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		assertEquals("0", result.fee().get("site_amount"));

		options.putAll(obj("cross_work_fee_decision", "charge", "include_site_fee", true, "site_amount", "",
				"site_note", "", "site_waive_reason", ""));
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertTrue(result.issues().contains("安装费：请填写未税现场附加费"));

		options.putAll(obj("site_amount", "300", "site_note", "交叉施工协调及降效费"));
		result = InstallationPricingEngine.calculate(quote, List.of(installationResource()), List.of(obj(), obj()),
				LocalDate.now());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		assertEquals("300", result.fee().get("site_amount"));
	}

	private static Map<String, Object> project(List<Map<String, Object>> projects, String id) {
		return projects.stream().filter(p -> s(p, "id").equals(id)).findFirst().orElseThrow();
	}

	private static Map<String, Object> equipment(String name, String spec) {
		return obj("name", name, "spec", spec, "remark", "", "category", "设备", "unit", "个", "quantity", "1",
				"attributes", obj());
	}

	@TestFactory
	Stream<DynamicTest> matchesFrozenPythonInstallation() throws IOException {
		Map<String, Object> fixture;
		try (var in = new GZIPInputStream(
				Objects.requireNonNull(getClass().getResourceAsStream("/quotation/installation-golden.json.gz")))) {
			fixture = new ObjectMapper().readValue(in, Map.class);
		}
		return list(fixture.get("cases")).stream().map(c -> DynamicTest.dynamicTest(s(c, "name"), () -> {
			var quote = map(c.get("quote"));
			var resources = list(c.get("resources"));
			Map<String, Object> actual;
			if (s(c, "mode").equals("fee")) {
				var result = InstallationPricingEngine.calculate(quote, resources, list(c.get("calculated_rows")),
						LocalDate.now());
				actual = obj("fee", result.fee(), "issues", result.issues());
			}
			else {
				var policy = obj("formal_template_confirmed", true);
				actual = s(quote, "pricing_mode").equals("duct_v1")
						? new DuctPricingEngine().calculate(quote, resources, policy)
						: s(quote, "pricing_mode").equals("mixed_v1")
								? new EquipmentPricingEngine().mixed(quote, resources, policy)
								: new EquipmentPricingEngine().calculate(quote, resources, policy);
				actual.put("installation", map(actual.get("fees")).get("installation"));
			}
			EquipmentPricingEngineTests.compare(c.get("expected"), actual, "result");
		}));
	}

}
