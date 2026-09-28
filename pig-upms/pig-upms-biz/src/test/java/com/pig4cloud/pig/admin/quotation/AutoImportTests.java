package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoImportTests {

	@TestFactory
	List<DynamicTest> matchesPythonAutomaticImportReference() throws Exception {
		var fixture = new ObjectMapper().readValue(
				Files.readString(Path.of("..", "..", "quotation-verification", "auto-import-golden.json")), Map.class);
		List<DynamicTest> tests = new ArrayList<>();
		int index = 0;
		for (var sample : list(fixture.get("rows"))) {
			var row = map(sample.get("input"));
			tests.add(DynamicTest.dynamicTest("classify-" + index++ + "-" + s(row, "name"), () -> {
				DuctImportService.autoClassifyRow(row);
				assertEquals(sample.get("expected"), row);
			}));
		}
		for (var sample : list(fixture.get("sheets"))) {
			tests.add(DynamicTest.dynamicTest("mapping-" + index++, () -> {
				var actual = new DuctImportService().suggestAuto(map(sample.get("input")));
				assertEquals(map(sample.get("expected")).get("mapping"), actual.get("auto_mapping"));
				assertEquals(map(sample.get("expected")).get("start_row"), actual.get("auto_start_row"));
			}));
		}
		return tests;
	}

}
