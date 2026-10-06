package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoImportTests {

	@Test
	void combinedBuildingHeadingsAreContextRatherThanProducts() {
		var importer = new DuctImportService();
		var headings = List.of("3#、4#楼地下室", "3#、4#楼", "3号、4号楼地下室", "3#,4#楼地下室");
		List<Map<String, Object>> source = new ArrayList<>();
		source.add(obj("number", 1, "cells", List.of("项次", "产品名称", "规格型号", "单位", "数量"), "issues", List.of()));
		for (int i = 0; i < headings.size(); i++)
			source.add(obj("number", i + 2, "cells", List.of(String.valueOf(i + 1), headings.get(i), "", "", ""), "issues", List.of()));
		source.add(obj("number", 6, "cells", List.of("5", "防火阀", "500*500", "个", "2"), "issues", List.of()));
		var sheet = obj("name", "设备", "rows", source);
		var built = importer.buildWorkbook(List.of(sheet), null);
		assertEquals(List.of("防火阀"), list(built.get("rows")).stream().map(r -> s(r, "name")).toList());
		assertEquals(headings.size(), list(built.get("source_special_rows")).size());
		assertTrue(list(built.get("source_special_rows")).stream().allMatch(r -> s(r, "kind").equals("section")));
		assertEquals(headings.size(), list(importer.suggestAuto(sheet).get("auto_context_rows")).size());
	}

	@Test
	void deliverySignatureFooterDoesNotCreateUnnamedProducts() {
		var importer = new DuctImportService();
		var sheet = obj("name", "设备", "rows", List.of(
				obj("number", 1, "cells", List.of("项次", "产品名称", "规格型号", "单位", "数量", "备注"), "issues", List.of()),
				obj("number", 2, "cells", List.of("1", "防火阀", "500*500", "个", "2", ""), "issues", List.of()),
				obj("number", 3, "cells", List.of("送货人签字：", "", "", "", "", "收货人签字："), "issues", List.of()),
				obj("number", 4, "cells", List.of("联系方式：", "", "", "", "", "联系方式："), "issues", List.of()),
				obj("number", 5, "cells", List.of("车 牌：", "", "", "", "", "日期："), "issues", List.of()),
				obj("number", 6, "cells", List.of("日 期：", "", "", "", "", ""), "issues", List.of())));
		var built = importer.buildWorkbook(List.of(sheet), null);
		assertEquals(1, list(built.get("rows")).size());
		assertEquals(List.of(3, 4, 5, 6), list(built.get("source_special_rows")).stream().map(r -> r.get("source_row")).toList());
		assertTrue(list(built.get("source_special_rows")).stream().allMatch(r -> s(r, "kind").equals("note_or_total")));
		assertEquals(4, list(importer.suggestAuto(sheet).get("auto_context_rows")).size());
	}

	@Test
	void documentFilteringPreservesIncompleteAndHeadingNamedProducts() {
		var importer = new DuctImportService();
		var sheet = obj("name", "设备", "rows", List.of(
				obj("number", 1, "cells", List.of("项次", "产品名称", "规格型号", "单位", "数量", "备注"), "issues", List.of()),
				obj("number", 2, "cells", List.of("1", "风机", "", "台", "", ""), "issues", List.of()),
				obj("number", 3, "cells", List.of("2", "", "500*500", "个", "2", ""), "issues", List.of()),
				obj("number", 4, "cells", List.of("3", "3#、4#楼地下室", "500*500", "个", "2", ""), "issues", List.of()),
				obj("number", 5, "cells", List.of("日期：", "", "500*500", "个", "2", ""), "issues", List.of())));
		var built = importer.buildWorkbook(List.of(sheet), null);
		assertEquals(List.of(2, 3, 4, 5), list(built.get("rows")).stream().map(r -> r.get("source_row")).toList());
		assertTrue(list(built.get("source_special_rows")).isEmpty());
	}

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
