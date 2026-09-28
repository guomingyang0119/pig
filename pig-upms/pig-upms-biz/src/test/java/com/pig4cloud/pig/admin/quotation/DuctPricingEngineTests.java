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

class DuctPricingEngineTests {

	@TestFactory
	Stream<DynamicTest> matchesPythonV16GoldenCases() throws IOException {
		Map<String, Object> fixture;
		try (InputStream stream = new GZIPInputStream(Objects
			.requireNonNull(getClass().getResourceAsStream("/quotation/duct-installation-v1-golden.json.gz")))) {
			fixture = new ObjectMapper().readValue(stream, Map.class);
		}
		return list(fixture.get("cases")).stream().map(c -> DynamicTest.dynamicTest(s(c, "name"), () -> {
			Map<String, Object> actual = new DuctPricingEngine().calculate(map(c.get("quote")),
					list(c.getOrDefault("resources", fixture.get("resources"))),
					map(c.getOrDefault("policy", fixture.get("policy"))), LocalDate.of(2026, 9, 23));
			compare(c.get("expected"), actual, "result");
		}));
	}

	private static void compare(Object expected, Object actual, String path) {
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

	private Map<String, Object> catalogResource() throws IOException {
		Map<String, Object> catalog = new ObjectMapper().readValue(DuctExportService.asset("duct-catalog-seed.json"),
				Map.class);
		return obj("id", "mixed-test", "family", "mixed-test", "version", 1, "kind", "duct_catalog", "status",
				"published", "body", catalog);
	}

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

	private Map<String, Object> calculateMixed(Map<String, Object> quote) throws IOException {
		return new DuctPricingEngine().calculate(quote, List.of(catalogResource()),
				obj("formal_template_confirmed", true), LocalDate.of(2026, 9, 23));
	}

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

	private static List<String> listOfStrings(Object value) {
		return ((List<?>) value).stream().map(Objects::toString).toList();
	}

	@Test
	void formulaIsBoundedAndDoesNotExecuteCode() {
		for (String expression : List.of("Runtime.getRuntime()", "__import__('os')", "1/0", "2**3", "x.class",
				"round(1.2,9)", "1e19", "(".repeat(45) + "1" + ")".repeat(45)))
			assertThrows(RuleException.class, () -> DecimalFormula.evaluate(expression, Map.of()), expression);
		assertEquals("2.4", DecimalFormula.evaluate("round(2.35,1)", Map.of()).toPlainString());
		assertEquals(0, n("10").compareTo(DecimalFormula.evaluate("max(abs(-5),ceil(9.1))", Map.of())));
	}

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
