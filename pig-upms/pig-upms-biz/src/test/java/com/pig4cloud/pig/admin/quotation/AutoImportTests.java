package com.pig4cloud.pig.admin.quotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证逐行自动导入对清单类别、表头映射和文档结构的处理。
 * 楼层、签收说明保留为来源上下文，真实但不完整的产品继续进入待确认清单。
 */
class AutoImportTests {

	@Test
	void automaticStandardWorkbookRoundtripPreservesRecognitionExplanationAndConflict() throws Exception {
		var source = obj("name", "防火阀", "spec", "500×400", "unit", "个", "quantity", "2", "equipment_kind", "fire",
				"main_product_name", "防火阀", "control_description", "联锁关闭排烟风机", "attached_components_description", "远控机构",
				"equipment_kind_source", "rule", "recognition_issue", "请核验主产品识别");
		var importer = new DuctImportService();
		var workbook = importer.read(AiQuoteImportService.standardWorkbook(List.of(source)), "AI核对清单.xlsx");
		var row = list(importer.buildWorkbook(workbook, null).get("rows")).get(0);
		for (String field : List.of("main_product_name", "control_description", "attached_components_description", "equipment_kind_source", "recognition_issue"))
			assertEquals(source.get(field), attr(row, field), field);
		assertTrue(yes(row.get("source_issue")));
		assertTrue(s(row, "source_issue_reason").contains("请核验主产品识别"));
	}

	@Test
	void normalizedRecognitionFieldsRemainSeparateFromPriceAndAttachedValveInputs() {
		var source = obj("name", "规范清单", "rows", List.of(obj("number", 1,
				"cells", List.of("防火阀", "500×400", "个", "2", "防火阀", "联锁关闭排烟风机", "远控机构", "ai", "类型待核验"), "issues", List.of())));
		var mapping = obj("name", 0, "spec", 1, "unit", 2, "quantity", 3, "main_product_name", 4,
				"control_description", 5, "attached_components_description", 6, "equipment_kind_source", 7, "recognition_issue", 8);
		var row = list(new DuctImportService().build(source, 1, mapping, "mixed_v1").get("rows")).get(0);
		var attributes = map(row.get("attributes"));
		assertEquals("防火阀", attributes.get("main_product_name"));
		assertEquals("联锁关闭排烟风机", attributes.get("control_description"));
		assertEquals("远控机构", attributes.get("attached_components_description"));
		assertEquals("ai", attributes.get("equipment_kind_source"));
		assertEquals("类型待核验", attributes.get("recognition_issue"));
		assertFalse(attributes.containsKey("fan_set_cost"));
		assertFalse(attributes.containsKey("attached_valve_kind"));
		assertTrue(s(row, "source_issue_reason").contains("类型待核验"));
	}

	@Test
	void refreshingAutomaticTypeRepairsClearSourceConflictButPreservesManualAndLegacyRows() {
		String source = "280℃排烟防火阀常开\n远距离手动复位;熔断关闭后输出电信号联锁关闭排烟风机(及补风机)";
		for (String origin : List.of("ai", "rule", "manual", "")) {
			var row = obj("name", source, "spec", "800×400", "category", "风机", "candidate_id", "",
					"attributes", obj("equipment_kind", "fan", "equipment_kind_source", origin));
			DuctImportService.refreshAutomaticClassification(row);
			boolean automatic = Set.of("ai", "rule").contains(origin);
			assertEquals(automatic ? "fire" : "fan", attr(row, "equipment_kind"), origin);
			assertEquals(automatic ? "风阀" : "风机", row.get("category"), origin);
			assertEquals(automatic, !blank(attr(row, "recognition_issue")), origin);
		}
	}

	@Test
	void refreshingAutomaticTypePreservesConfirmedCandidateCostAndProductRules() {
		for (String manual : List.of("candidate", "set_cost", "row_rule", "rule_family", "manual_price")) {
			var row = obj("name", "防火阀联锁关闭排烟风机", "spec", "800×400", "category", "风机",
					"attributes", obj("equipment_kind", "fan", "equipment_kind_source", "ai"));
			if (manual.equals("candidate")) row.put("candidate_id", "selected-fan");
			if (manual.equals("set_cost")) map(row.get("attributes")).put("fan_set_cost", "500");
			if (manual.equals("row_rule")) row.put("product_rule", obj("canonical_name", "人工规则"));
			if (manual.equals("rule_family")) row.put("product_rule_family", "custom-family");
			if (manual.equals("manual_price")) row.put("pricing_mode", "manual_v1");
			DuctImportService.refreshAutomaticClassification(row);
			assertEquals("fan", attr(row, "equipment_kind"), manual);
			assertEquals("风机", row.get("category"), manual);
		}
	}

	/** 客户项目特征按字段拆分，厚度和法兰不堆进规格，原文继续可追溯。 */
	@Test
	void projectDescriptionSeparatesThicknessFlangeAndNotes() {
		String description = "1.名称：镀锌薄钢板\n2.厚度：δ=0.75mm\n3.连接形式：角钢法兰连接\n4.支架：支吊架制作、安装\n5.其他：满足设计及规范要求";
		var sheet = obj("name", "材料询价单", "rows", List.of(
				obj("number", 1, "cells", List.of("序号", "名称", "项目特征描述", "单位", "工程量"), "issues", List.of()),
				obj("number", 2, "cells", List.of("1", "镀锌薄钢板风管", description, "㎡", "490"), "issues", List.of())));
		var row = list(new DuctImportService().buildWorkbook(List.of(sheet), null).get("rows")).get(0);
		assertEquals("镀锌薄钢板风管", row.get("name"));
		assertEquals("", row.get("spec"));
		assertEquals("0.75", attr(row, "thickness"));
		assertEquals("angle", attr(row, "flange"));
		assertTrue(s(row, "remark").contains("支吊架制作、安装"));
		assertTrue(s(row, "remark").contains("满足设计及规范要求"));
		assertEquals(description, ((List<?>) map(row.get("original")).get("cells")).get(2));
	}

	/** 风机规格只显示型号，实际工况移入独立参数且原始要求不丢失。 */
	@Test
	void fanDescriptionSeparatesModelPerformanceAndBusinessRequirements() {
		String description = "1.名称：轴流风机\n2.规格：SWF-I-7 风量27500m³/h、功率11Kw、转速2900r/min\n3.支架材质、规格：型钢（综合）\n4.风机软接口风管：满足设计及规范要求";
		var sheet = obj("name", "材料询价单", "rows", List.of(
				obj("number", 1, "cells", List.of("序号", "名称", "项目特征描述", "单位", "数量"), "issues", List.of()),
				obj("number", 2, "cells", List.of("1", "轴流风机", description, "台", "4"), "issues", List.of())));
		var row = list(new DuctImportService().buildWorkbook(List.of(sheet), null).get("rows")).get(0);
		assertEquals("SWF-I-7 风量27500m³/h、功率11Kw、转速2900r/min", row.get("spec"));
		assertEquals("SWF-I-7", attr(row, "model"));
		assertEquals("27500", attr(row, "airflow"));
		assertEquals("11", attr(row, "power"));
		assertEquals("2900", attr(row, "speed"));
		assertTrue(s(row, "remark").contains("型钢（综合）"));
		assertTrue(s(row, "remark").contains("风机软接口风管"));
		assertEquals("4", row.get("quantity"));
	}

	/** 验证“3#、4#楼地下室”等联合楼栋标题归为 section，不与防火阀一起生成报价产品。 */
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

	/** 验证送货/收货签字、联系方式、车牌和日期页脚作为来源说明保存，不形成无名产品。 */
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

	/** 验证缺数量风机、缺名称材料，以及名称像楼层/日期但带规格数量的行仍作为产品保留。 */
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

	/** 采购金额、附注、审核签章留作原始来源；真实产品备注里的“合计”不导致漏行。 */
	@Test
	void purchaseTotalsAndSigningNotesStayOutsideProductsInEveryImportMode() {
		var importer = new DuctImportService();
		var source = obj("name", "采购单", "rows", List.of(
				obj("number", 8, "cells", List.of("序号", "产品名称", "规格型号", "单位", "数量", "备注"), "issues", List.of()),
				obj("number", 9, "cells", List.of("1", "未识别新材料", "500*500", "个", "2", "按最终合计数量供货"), "issues", List.of()),
				obj("number", 30, "cells", List.of("", "", "", "", "采购总金额（人民币元）：", "0"), "issues", List.of()),
				obj("number", 31, "cells", List.of("附注：本价格含13%增值税，运输费由供方承担", "", "", "", "", ""), "issues", List.of()),
				obj("number", 32, "cells", List.of("制单员：梁小姐 核价员： 工程部： 采购员： 核准人：", "", "", "", "", ""), "issues", List.of()),
				obj("number", 33, "cells", List.of("供方签章：2026年9月18日 需方签章：", "", "", "", "", ""), "issues", List.of()),
				obj("number", 34, "cells", List.of("(请确认后签章)", "", "", "", "", ""), "issues", List.of()),
				obj("number", 35, "cells", List.of("2", "合计显示风阀", "400*400", "个", "3", ""), "issues", List.of()),
				obj("number", 36, "cells", List.of("", "材料小计", "", "", "5", ""), "issues", List.of())));
		var mapping = map(importer.suggestAuto(source).get("auto_mapping"));
		for (String mode : List.of("mixed_v1", "equipment_v1", "duct_v1")) {
			var built = importer.build(source, 9, mapping, mode);
			assertEquals(List.of(9, 35), list(built.get("rows")).stream().map(r -> r.get("source_row")).toList(), mode);
			var contexts = list(built.get("source_special_rows"));
			assertEquals(List.of(30, 31, 32, 33, 34, 36), contexts.stream().map(r -> r.get("source_row")).toList(), mode);
			assertEquals("采购单", contexts.get(0).get("source_sheet"));
			assertEquals("采购总金额（人民币元）：", ((List<?>) map(contexts.get(0).get("original")).get("cells")).get(4));
			assertEquals("2", list(built.get("rows")).get(0).get("quantity"));
		}
		assertEquals(6, list(importer.suggestAuto(source).get("auto_context_rows")).size());
	}

	/** 龙源表的工程章节带分级编号，部分规格重复标题；缺数量的真实设备仍保留待确认。 */
	@Test
	void numberedEngineeringSectionTitlesDoNotBecomeProducts() {
		String[][] headings = {
				{"一","氢气单元"},{"16","采暖通风"},{"16.1","制氢厂房暖通设备"},{"16.1.1","空调类"},{"16.1.2","风机类"},
				{"二","中试验证单元"},{"4","自控仪表"},{"3","采暖通风"},{"3.1","除盐水站暖通设备"},{"3.1.1","风机类"},
				{"六","给水及消防泵站"},{"3","工艺管道"},{"3.1","碳钢管"},{"6","采暖通风"},{"6.1","给水及消防泵站暖通设备"},
				{"6.1.1","风机类"},{"7.2","地管阀门"},{"7.2.1","生产给水系统（IW）"},{"七","氮气及压缩空气单元"},
				{"2","工艺管道"},{"2","采暖通风"},{"2.1","危废暂存间暖通设备"},{"2.1.1","风机类"}
		};
		List<Map<String,Object>> records = new ArrayList<>();
		records.add(obj("number",1,"cells",List.of("序号","名称","规格型号","单位","数量"),"issues",List.of()));
		for (int i=0;i<headings.length;i++) records.add(obj("number",i+2,"cells",List.of(headings[i][0],headings[i][1],
				i>=2 && i<=4 ? headings[i][1] : "","",""),"issues",List.of()));
		records.add(obj("number",25,"cells",List.of("16.1.2.1","轴流通风机","SWF-7 11kW","台",""),"issues",List.of()));
		records.add(obj("number",26,"cells",List.of("16.1.2.2","风机类","500×500","个","2"),"issues",List.of()));
		records.add(obj("number",27,"cells",List.of("","碳钢管","","",""),"issues",List.of()));
		records.add(obj("number",28,"cells",List.of("3.1","待确定新设备","","",""),"issues",List.of()));
		var sheet=obj("name","风机","rows",records);
		var importer=new DuctImportService();
		var built=importer.buildWorkbook(List.of(sheet),null);
		assertEquals(List.of(25,26,27,28),list(built.get("rows")).stream().map(r->r.get("source_row")).toList());
		assertEquals(23,list(built.get("source_special_rows")).size());
		assertTrue(list(built.get("source_special_rows")).stream().allMatch(r->s(r,"kind").equals("section")));
	}

	/** 汇总、编号商务条款及错落在规格/单位列的签字行不计产品，具备真实参数的同名行仍保留。 */
	@Test
	void verifiedTotalsTermsAndMisalignedSignaturesKeepSourceContent() {
		var importer=new DuctImportService();
		List<Map<String,Object>> records=new ArrayList<>();
		records.add(obj("number",1,"cells",List.of("序号","名称","规格型号","单位","数量"),"issues",List.of()));
		String[][] contextual={
				{"181","分部小计","","",""},{"116","工程小计","","",""},
				{"","含税含运总价","","",""},{"","含税合价","","",""},
				{"185","报价要求","","",""},{"186","1、质量技术：","","",""},
				{"187","2、交货地点：","","",""},{"188","3、运输费用：","","",""},
				{"189","4、供货周期：","","",""},{"190","5、报价时效：","","",""},
				{"191","6、付款方式：","","",""},{"192","7、其他要求：","","",""},
				{"申请人：","","材料员：","",""},
				{"申请人签字：周伦","","审核人签字：代兵","收货人签字（数量）",""},
				{"","","通风","",""},{"","","卫生间","",""},{"","","风管","",""},{"","","总保温","",""},
				{"7.2.2","生活水管线（PTW）","","",""},{"5.8.5","阻尼器","","",""},
				{"","不含税合计","","",""},{"","不含税含运合计","","",""},
				{"合计金额（大写）","","3743","",""},{"合计金额（大写）","","3343","",""},
				{"合计金额（大写）","","10445","",""},{"1","通风防排烟系统安装工程","","",""}
		};
		for (int i=0;i<contextual.length;i++) records.add(obj("number",i+2,"cells",Arrays.asList(contextual[i]),"issues",List.of()));
		String[][] realProducts={
				{"1","柜式离心风机","风量2000 风压300 功率0.75kW","台",""},
				{"2","未知新产品","","",""},{"3","","500×500","个","2"},
				{"7.2.2","生活水管线（PTW）","DN100","米",""},
				{"5.8.5","阻尼器","","个",""},{"4","风管","500×320","㎡",""},
				{"日期：","","500×500","个","2"},{"7","付款方式：","500×500","个","1"}
		};
		int firstProduct=contextual.length+2;
		for (int i=0;i<realProducts.length;i++) records.add(obj("number",i+firstProduct,"cells",Arrays.asList(realProducts[i]),"issues",List.of()));
		var built=importer.buildWorkbook(List.of(obj("name","客户表","rows",records)),null);
		assertEquals(8,list(built.get("rows")).size());
		assertEquals(26,list(built.get("source_special_rows")).size());
		assertEquals(List.of(28,29,30,31,32,33,34,35),list(built.get("rows")).stream().map(r->r.get("source_row")).toList());
		assertEquals("客户表",list(built.get("source_special_rows")).get(12).get("source_sheet"));
		assertEquals("申请人：",((List<?>)map(list(built.get("source_special_rows")).get(12).get("original")).get("cells")).get(0));
	}

	/** 按已保存的 Python 参照数据动态核对逐行类别与表头建议，保持 Java 导入行为和迁移基准一致。 */
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
