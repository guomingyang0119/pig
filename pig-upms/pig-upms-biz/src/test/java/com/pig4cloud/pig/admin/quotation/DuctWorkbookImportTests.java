package com.pig4cloud.pig.admin.quotation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证整份客户工作簿的合并导入、来源记录和整单辅材汇总。
 * 重点防止跨表重复汇总、错误起始行以及整份产品数量超限造成遗漏或重复计费。
 */
class DuctWorkbookImportTests {

	final DuctImportService importer = new DuctImportService();

	/** 按真实导入器的行结构创建模拟工作表，保留一基源行号和原单元格用于多表验证。 */
	Map<String, Object> sheet(String name, List<List<String>> values) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < values.size(); i++)
			rows.add(obj("number", i + 1, "cells", values.get(i), "issues", List.of()));
		return obj("name", name, "rows", rows);
	}

	/** 跨表同类配件保留原位置、进入待确认，不能静默覆盖或累计。 */
	@Test
	void sameAccessorySummaryInTwoWorksheetsMustNotBeOverwrittenOrDoubleCharged() {
		var values = List.of(List.of("产品名称", "工程量", "单位"), List.of("角码", "20", "个"), List.of("风管", "10", "㎡"));
		var first = sheet("清单一", values);
		var second = sheet("清单二", values);
		var built = importer.buildWorkbook(List.of(first, second), null);
		var options = map(built.get("duct_options"));
		assertEquals("", options.get("corner_count"));
		var group = list(options.get("accessory_review")).get(0);
		assertEquals(List.of("清单一", "清单二"), list(group.get("rows")).stream().map(r -> r.get("source_sheet")).toList());
		assertEquals("pending", group.get("mode"));
	}

	/** 验证只有数量列的角码、卡条和角钢汇总可提取为整单辅材数量/米数。 */
	@Test
	void accessorySummaryAcceptsQuantityColumnWithoutEngineeringQuantity() {
		var values = List.of(List.of("产品名称", "数量", "单位"), List.of("角码", "8000", "个"),
				List.of("卡条", "2628", "米"), List.of("30角钢", "120", "米"), List.of("防火阀", "1", "个"));
		var built = importer.buildWorkbook(List.of(sheet("Sheet1", values)), null);
		var options = map(built.get("duct_options"));
		assertEquals("8000", options.get("corner_count"));
		assertEquals("2628", options.get("clip_meters"));
		assertEquals("120", options.get("flange_30_meters"));
	}

	/** 验证辅材有明确数量时优先数量，只有数量空缺时才读取工程量列。 */
	@Test
	void accessorySummaryPrefersQuantityAndFallsBackToEngineeringQuantity() {
		var values = List.of(List.of("产品名称", "数量", "工程量", "单位"), List.of("角码", "20", "99", "个"),
				List.of("卡条", "", "88", "米"), List.of("防火阀", "1", "1", "个"));
		var built = importer.buildWorkbook(List.of(sheet("Sheet1", values)), null);
		var options = map(built.get("duct_options"));
		assertEquals("20", options.get("corner_count"));
		assertEquals("88", options.get("clip_meters"));
	}

	/** 验证多表法兰提示合并为 mixed，合计说明保留所属表；错误起始行及无原因排除不能静默漏表。 */
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

	/** 验证产品行上限对整份文件累计检查，两张各 5001 条产品的表不能绕过 10000 行限制。 */
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
