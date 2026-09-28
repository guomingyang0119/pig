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

class EquipmentPricingEngineTests {

	@Test
	void exhaustFansArePurchasedProducts() {
		assertEquals("purchased", EquipmentPricingEngine.classify(obj("name", "吸顶式排气扇", "attributes", obj())));
	}

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

	private static Map<String, Object> freightRow(String unitPrice, String quantity) {
		return obj("status", "priced", "unit_price", unitPrice, "amount", text(mul(n(unitPrice), n(quantity))),
				"body_amount", text(mul(n(unitPrice), n(quantity))), "billing_quantity", quantity, "trace",
				new ArrayList<Map<String, Object>>(), "coverage", obj("installation", false, "freight", false), "detail", obj());
	}

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
			if (s(q, "pricing_mode").equals("mixed_v1"))
				compareMixed(map(c.get("expected")), result, q);
			else {
				var expected = new LinkedHashMap<>(map(c.get("expected")));
				expected.put("engine_version", EquipmentPricingEngine.VERSION);
				compare(expected, result, "result");
			}
		}));
	}

	private static void compareMixed(Map<String, Object> expected, Map<String, Object> actual,
			Map<String, Object> quote) {
		// Existing duct Java traces have Chinese UI wording; compare its business
		// outputs,
		// while equipment rows retain the full source-result comparison above and here.
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
