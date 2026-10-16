package com.pig4cloud.pig.admin.quotation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证客户清单常见中文表头的唯一列映射及规格传递。
 * 含歧义的列留待人工确认，楼层说明独立保存，不完整产品和未命名参数列中的原文继续保留。
 */
class ImportMappingTests {

	final DuctImportService importer = new DuctImportService();

	/** 把二维中文清单转换为含原行号、单元格和问题列表的模拟工作表。 */
	Map<String, Object> sheet(List<List<String>> values) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < values.size(); i++)
			rows.add(obj("number", i + 1, "cells", values.get(i), "issues", List.of()));
		return obj("name", "清单", "rows", rows);
	}

	/** 验证暖通询价单的通用“名称”表头可与规格、单位、数量组合识别，客户旧单价列不进入报价参数映射。 */
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
		assertEquals("单层百叶风口", rows.get(0).get("name"));
		assertEquals("风口", s(EngineeringImportReview.source(rows.get(0)), "name"));
		assertEquals("34", rows.get(0).get("quantity"));
		assertEquals("铝合金", map(rows.get(0).get("attributes")).get("material"));
		assertEquals("客户原备注", rows.get(0).get("remark"));
		assertEquals("12", rows.get(1).get("engineering_quantity"));
	}

	/** 验证单独“名称”标签不是产品表头，不妨碍后续完整表头确定真正数据起始行。 */
	@Test
	void isolatedGenericNameLabelDoesNotCompeteWithActualTableHeader() {
		var sheet = sheet(List.of(List.of("名称", "", "", ""),
				List.of("产品名称", "规格型号", "单位", "数量"), List.of("防火阀", "500×500", "个", "2")));
		var suggested = importer.suggestAuto(sheet);
		assertEquals(3, suggested.get("auto_start_row"));
		assertEquals(0, map(suggested.get("auto_mapping")).get("name"));
	}

	/** 验证重复“名称”列无法唯一映射，起始行和名称建议保持待人工确认。 */
	@Test
	void duplicateGenericNameColumnsRequireManualConfirmation() {
		var sheet = sheet(List.of(List.of("名称", "名称", "规格型号", "单位", "数量")));
		var suggested = importer.suggestAuto(sheet);
		assertNull(suggested.get("auto_start_row"));
		assertFalse(map(suggested.get("auto_mapping")).containsKey("name"));
	}

	/** 验证带换行的“规格参数”表头直接映射规格，设备工况能够解析，风管平方米工程量正确传递。 */
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

	/** 验证材质、板厚、宽高、风量、功率、转速及法兰的独立列支持全角括号和字母单位。 */
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

	/** 验证多个规格或数量表头不自动择列，唯一名称列仍可给出建议。 */
	@Test
	void ambiguousColumnsStayUnmapped() {
		var sheet = sheet(List.of(List.of("产品名称", "规格参数", "规格描述", "数量", "数量", "单位")));
		var mapping = map(importer.suggestAuto(sheet).get("auto_mapping"));
		assertFalse(mapping.containsKey("spec"));
		assertFalse(mapping.containsKey("quantity"));
		assertEquals(0, mapping.get("name"));
	}

	/** 真实客户的四个明确表头别名可自动映射，名称/规格/数量仍只接受唯一列。 */
	@Test
	void customerItemQuantityAndFeatureHeadersAreMappedPrecisely() {
		for (String specification : List.of("规格及型号", "项目特征描述", "规 格 型 号")) {
			var source = sheet(List.of(List.of("序号", "物品名称", specification, "单位", "材料数量"),
					List.of("1", "防火阀", "70℃ 500×500", "个", "2")));
			var mapping = map(importer.suggestAuto(source).get("auto_mapping"));
			assertEquals(1, mapping.get("name"), specification);
			boolean feature = specification.equals("项目特征描述");
			assertEquals(2, mapping.get(feature ? "feature_description" : "spec"), specification);
			assertEquals(4, mapping.get("quantity"), specification);
			var row = list(importer.buildWorkbook(List.of(source), null).get("rows")).get(0);
			assertEquals(feature ? "" : "70℃ 500×500", row.get("spec"));
			assertEquals("2", row.get("quantity"));
		}
		var ambiguous = sheet(List.of(List.of("物品名称", "材料名称", "规格参数", "项目特征描述", "材料数量", "数量")));
		var mapping = map(importer.suggestAuto(ambiguous).get("auto_mapping"));
		assertFalse(mapping.containsKey("name"));
		assertFalse(mapping.containsKey("spec"));
		assertFalse(mapping.containsKey("quantity"));
		var unrelated = sheet(List.of(List.of("负责人名称", "数量说明", "规格备注")));
		assertTrue(map(importer.suggestAuto(unrelated).get("auto_mapping")).isEmpty());
	}

	/** 标准清单新增来源尾列能回导，空格工作表名不被 trim，来源冲突继续要求人工确认。 */
	@Test
	void standardSourceColumnsAndOriginalParameterExpressionsSurviveImport() {
		var source = sheet(List.of(List.of("产品名称", "规格及型号", "单位", "数量", "原位号", "原尺寸算式", "面板附加高度（mm）",
				"来源文件", "来源工作表原名", "来源工作表序号", "来源行号", "来源定位", "来源原文", "原资料冲突", "原资料冲突原因"),
				List.of("多叶送风口", "500×600", "个", "2", "B4a", "500×(600+250)", "250", "客户原表.xlsx", "     ", "1", "99",
						"sheet-index:1/row:99", "客户原文500×(600+250)", "true", "阀体与面板待核实")));
		var row = list(importer.buildWorkbook(List.of(source), null).get("rows")).get(0);
		assertEquals("客户原表.xlsx", row.get("source_file"));
		assertEquals("清单", row.get("source_sheet"));
		assertEquals("1", row.get("source_sheet_index"));
		assertEquals(2, row.get("source_row"));
		assertEquals("sheet-index:1/row:99", row.get("source_locator"));
		assertEquals("客户原文500×(600+250)", row.get("source_excerpt"));
		assertEquals(true, row.get("source_issue"));
		assertEquals(false, row.get("source_issue_confirmed"));
		assertEquals("阀体与面板待核实", row.get("source_issue_reason"));
		assertEquals("     ", map(row.get("original")).get("source_sheet"));
		assertEquals("99", map(row.get("original")).get("source_row"));
		assertEquals("B4a", map(row.get("attributes")).get("position_tag"));
		assertEquals("500×(600+250)", map(row.get("attributes")).get("dimension_expression"));
		assertEquals("250", map(row.get("attributes")).get("panel_height"));
	}

	/** 验证楼层、区域和说明保存为特殊来源行，风机及未知真实产品继续保留在报价清单。 */
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

	/** 验证无表头说明列原文仍保存进 source_text，“平方”数量成为工程量并可提取实际板厚和半成品状态。 */
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
