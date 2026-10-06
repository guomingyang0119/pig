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
	void genericNameHeaderAutomaticallyImportsCustomerHvacInquiry() {
		var sheet = sheet(List.of(List.of("材料询价", "", "", "", "", "", "", "", ""),
				List.of("工程名称：龙源张掖碳中和产业基地风光氢储一体化项目", "", "", "", "", "", "", "", ""),
				List.of("", "名称", "规格型号", "单位", "数量", "材质", "含税单价/元", "总价/元", "备注"),
				List.of("16.2.1.5", "风口", "单层百叶风口 800X400", "个", "34", "铝合金", "", "", "客户原备注"),
				List.of("16.2.1.6", "镀锌风管", "0.75 / 500×320", "㎡", "12", "镀锌钢板", "", "", "")));
		var suggested = importer.suggestAuto(sheet);
		var mapping = map(suggested.get("auto_mapping"));
		assertEquals(4, suggested.get("auto_start_row"));
		assertEquals(1, mapping.get("name"));
		assertEquals(2, mapping.get("spec"));
		assertEquals(3, mapping.get("unit"));
		assertEquals(4, mapping.get("quantity"));
		assertEquals(5, mapping.get("material"));
		assertEquals(8, mapping.get("remark"));
		var rows = list(importer.buildWorkbook(List.of(sheet), null).get("rows"));
		assertEquals(2, rows.size());
		assertEquals("风口", rows.get(0).get("name"));
		assertEquals("34", rows.get(0).get("quantity"));
		assertEquals("铝合金", map(rows.get(0).get("attributes")).get("material"));
		assertEquals("客户原备注", rows.get(0).get("remark"));
		assertEquals("12", rows.get(1).get("engineering_quantity"));
	}

	@Test
	void isolatedGenericNameLabelDoesNotCompeteWithActualTableHeader() {
		var sheet = sheet(List.of(List.of("名称", "", "", ""),
				List.of("产品名称", "规格型号", "单位", "数量"), List.of("防火阀", "500×500", "个", "2")));
		var suggested = importer.suggestAuto(sheet);
		assertEquals(3, suggested.get("auto_start_row"));
		assertEquals(0, map(suggested.get("auto_mapping")).get("name"));
	}

	@Test
	void duplicateGenericNameColumnsRequireManualConfirmation() {
		var sheet = sheet(List.of(List.of("名称", "名称", "规格型号", "单位", "数量")));
		var suggested = importer.suggestAuto(sheet);
		assertNull(suggested.get("auto_start_row"));
		assertFalse(map(suggested.get("auto_mapping")).containsKey("name"));
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
				List.of("1", "3.5米层", "", "", ""), List.of("", "1#楼地下室", "", "", ""),
				List.of("", "1#楼~屋顶", "", "", ""), List.of("", "3#楼地下室负一层", "", "", ""),
				List.of("", "3#楼一层~楼顶", "", "", ""), List.of("2", "风机", "7.5kW", "台", "2"),
				List.of("3", "待确认新产品", "", "", ""), List.of("", "人工+材料（含税金）：", "", "", ""),
				List.of("说明", "本报价不含风机控制箱", "", "", "")));
		var built = importer.buildWorkbook(List.of(sheet), null);
		var rows = list(built.get("rows"));
		assertEquals(List.of("风机", "待确认新产品"), rows.stream().map(r -> s(r, "name")).toList());
		assertEquals(8, list(built.get("source_special_rows")).size());
		assertEquals(8, list(importer.suggestAuto(sheet).get("auto_context_rows")).size());
	}

	@Test
	void unheadedPricingNoteAndSquareUnitReachDuctPricing() {
		var sheet = sheet(List.of(List.of("产品名称", "规格及型号", "单位", "数量", "备注", ""),
				List.of("镀锌风管", "1600*400", "平方", "383.6", "0.9",
						"厚度1.2mm半成品风管直管单价，异形件+3元/㎡，不含角码卡条")));
		var row = list(importer.buildWorkbook(List.of(sheet), null).get("rows")).get(0);
		assertEquals("383.6", row.get("engineering_quantity"));
		assertTrue(s(row, "source_text").contains("厚度1.2mm半成品"));
		assertEquals(0, n("1.2").compareTo(DuctPricingEngine.thickness(row)));
		assertEquals("semi", DuctPricingEngine.state(row, obj()));
	}

}
