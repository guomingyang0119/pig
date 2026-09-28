package com.pig4cloud.pig.admin.quotation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

class DuctWorkbookImportTests {

	final DuctImportService importer = new DuctImportService();

	Map<String, Object> sheet(String name, List<List<String>> values) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < values.size(); i++)
			rows.add(obj("number", i + 1, "cells", values.get(i), "issues", List.of()));
		return obj("name", name, "rows", rows);
	}

	@Test
	void sameAccessorySummaryInTwoWorksheetsMustNotBeOverwrittenOrDoubleCharged() {
		var values = List.of(List.of("产品名称", "工程量", "单位"), List.of("角码", "20", "个"));
		var first = sheet("清单一", values);
		var second = sheet("清单二", values);
		String error = assertThrows(RuleException.class, () -> importer.buildWorkbook(List.of(first, second), null))
			.getMessage();
		assertTrue(error.contains("清单一") && error.contains("清单二") && error.contains("相同配件汇总"));
	}

	@Test
	void perSheetFlangeHintsAndNotesKeepTheirSourceWhileSharingOrderOptions() {
		var first = sheet("角钢", List.of(List.of("产品名称", "工程量", "单位", "法兰方式"), List.of("风管", "10", "㎡", "角钢法兰")));
		var second = sheet("共板", List.of(List.of("产品名称", "工程量", "单位", "法兰方式"), List.of("风管", "15", "㎡", "共板法兰")));
		var notes = sheet("汇总", List.of(List.of("产品名称"), List.of("合计")));
		var built = importer.buildWorkbook(List.of(first, second, notes), null);
		assertEquals("mixed", map(built.get("duct_options")).get("flange"));
		assertEquals(2, list(built.get("rows")).size());
		assertEquals("汇总", list(built.get("source_special_rows")).get(0).get("source_sheet"));
		assertEquals(0, list(built.get("source_sheets")).get(2).get("row_count"));
		assertThrows(RuleException.class,
				() -> importer.buildWorkbook(List.of(first, second), List.of(obj("name", "角钢", "start_row", 100))));
		assertThrows(RuleException.class,
				() -> importer.buildWorkbook(List.of(first, second), List.of(obj("name", "角钢", "excluded", "true"))));
	}

	@Test
	void rowLimitAppliesToTheEntireWorkbook() {
		List<List<String>> values = new ArrayList<>();
		values.add(List.of("产品名称"));
		for (int i = 0; i < 5001; i++)
			values.add(List.of("防火阀"));
		var first = sheet("一", values);
		var second = sheet("二", values);
		assertTrue(assertThrows(RuleException.class, () -> importer.buildWorkbook(List.of(first, second), null))
			.getMessage()
			.contains("10000"));
	}

}
