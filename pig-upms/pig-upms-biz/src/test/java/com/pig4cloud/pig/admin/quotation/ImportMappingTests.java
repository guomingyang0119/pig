package com.pig4cloud.pig.admin.quotation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportMappingTests {

	final DuctImportService importer = new DuctImportService();

	Map<String, Object> sheet(List<List<String>> values) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < values.size(); i++)
			rows.add(obj("number", i + 1, "cells", values.get(i), "issues", List.of()));
		return obj("name", "清单", "rows", rows);
	}

	@Test
	void customerSpecificationHeaderReachesPricingWithoutManualMapping() {
		var sheet = sheet(List.of(List.of("序号", "设备名称", "规格\n参数", "单位", "数量", "备注"),
				List.of("1", "柜式离心风机", "风量：21290m³/h，风压：383Pa，功率：7.5KW", "台", "2", ""),
				List.of("2", "镀锌共板风管", "1.2mm", "㎡", "855", "")));
		var mapping = map(importer.suggestAuto(sheet).get("auto_mapping"));
		assertEquals(2, mapping.get("spec"));
		var rows = list(importer.buildWorkbook(List.of(sheet), null).get("rows"));
		var attributes = EquipmentPricingEngine.parseAttributes(rows.get(0));
		assertEquals("21290", attributes.get("airflow"));
		assertEquals("383", attributes.get("pressure"));
		assertEquals("7.5", attributes.get("power"));
		assertEquals("1.2mm", rows.get(1).get("spec"));
		assertEquals("855", rows.get(1).get("engineering_quantity"));
	}

	@Test
	void separateParametersAndFullWidthUnitsAreRecognized() {
		var sheet = sheet(List
			.of(List.of("产品名称", "材质", "板厚（mm）", "宽度（mm）", "高度（mm）", "风量（m³/h）", "功率（ＫＷ）", "转速（r/min）", "法兰形式", "数量")));
		var mapping = map(importer.suggestAuto(sheet).get("auto_mapping"));
		assertEquals(1, mapping.get("material"));
		assertEquals(2, mapping.get("thickness"));
		assertEquals(3, mapping.get("width"));
		assertEquals(4, mapping.get("height"));
		assertEquals(5, mapping.get("airflow"));
		assertEquals(6, mapping.get("power"));
		assertEquals(7, mapping.get("speed"));
		assertEquals(8, mapping.get("flange"));
	}

	@Test
	void ambiguousColumnsStayUnmapped() {
		var sheet = sheet(List.of(List.of("产品名称", "规格参数", "规格描述", "数量", "数量", "单位")));
		var mapping = map(importer.suggestAuto(sheet).get("auto_mapping"));
		assertFalse(mapping.containsKey("spec"));
		assertFalse(mapping.containsKey("quantity"));
		assertEquals(0, mapping.get("name"));
	}

	@Test
	void sectionLabelsArePreservedSeparatelyButUnknownProductsAreNotDiscarded() {
		var sheet = sheet(List.of(List.of("序号", "设备名称", "规格参数", "单位", "数量"), List.of("", "配电装置楼", "", "", ""),
				List.of("1", "3.5米层", "", "", ""), List.of("2", "风机", "7.5kW", "台", "2"),
				List.of("3", "待确认新产品", "", "", ""), List.of("", "人工+材料（含税金）：", "", "", ""),
				List.of("说明", "本报价不含风机控制箱", "", "", "")));
		var built = importer.buildWorkbook(List.of(sheet), null);
		var rows = list(built.get("rows"));
		assertEquals(List.of("风机", "待确认新产品"), rows.stream().map(r -> s(r, "name")).toList());
		assertEquals(4, list(built.get("source_special_rows")).size());
		assertEquals(4, list(importer.suggestAuto(sheet).get("auto_context_rows")).size());
	}

}
