package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;
import com.pig4cloud.pig.admin.quotation.EquipmentFanSelector.Cost;

/**
 * 设备供货和复合订单报价引擎。
 * <p>
 * Python 版本只作为迁移核对基线，运行时由本 Java 实现独立完成产品分类、参数提取、选型、成本和售价计算。
 * 输入不能唯一匹配产品、型号或成本规则时保留候选项并标记待确认，不会用相似名称直接替代。
 */
public final class EquipmentPricingEngine {

	// 纯设备及混合报价分别记录引擎版本，供正式导出检查试算是否仍适用。
	public static final String VERSION = "equipment-v31", MIXED_VERSION = "mixed-v31";

	// 内置设备计算类型；组合和全新公式产品另通过产品规则进入 custom 成本分支。
	public static final Set<String> KINDS = ProductDefinitions.equipmentKinds();

	// 尺寸与工况提取使用的非负十进制文本格式，数值范围由后续业务校验决定。
	private static final String DECIMAL = "(\\d+(?:\\.\\d+)?)";

	private static Matcher match(String pattern, String text) {
		return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
	}

	private static boolean has(String pattern, String text) {
		return match(pattern, text).find();
	}

	private static boolean contains(String text, String... words) {
		return Arrays.stream(words).anyMatch(text::contains);
	}

	// 中间十进制除法使用统一精度；最终设备售价的毛利和税费阶段改用精确分数。
	private static BigDecimal div(BigDecimal a, Object b) {
		return a.divide(n(b), MC);
	}

	// 设备单价及行金额均向上取整到整数元，不采用风管的 1 位小数规则。
	private static BigDecimal ceil(BigDecimal a) {
		return a.setScale(0, RoundingMode.CEILING);
	}

	/** 按固定宽、高方向分段；整倍数尺寸不增加下一段，N 段只计 N-1 次拼接。 */
	static BigDecimal spliceCount(BigDecimal size, BigDecimal segmentSize) {
		return size.divide(positive(segmentSize, "拼接分段长度"), 0, RoundingMode.CEILING).subtract(ONE).max(ZERO);
	}

	// 将十进制值无损转成分数，保留毛利/税费计算的精确边界。
	private static EquipmentFraction fraction(Object v) {
		return EquipmentFraction.of(n(v));
	}

	// 分数仅在内部明细输出时转为十进制文字，计算继续保留精确值。
	private static String ftext(EquipmentFraction f) {
		return text(f.decimal());
	}

	// 兼容中文或文本形式的防爆、远控等行属性开关；整单税费开关另要求布尔值。
	private static boolean enabled(Object v) {
		return Set.of("true", "1", "是", "有").contains(str(v).toLowerCase(Locale.ROOT));
	}

	/** 行选择和附阀字段共用控制方式编码；全自动仅在防火阀成本分支支持。 */
	static String valveControl(Object value) {
		return switch (str(value).strip()) {
			case "普通", "正常", "近控" -> "near";
			case "远控" -> "remote";
			case "自动", "全自动" -> "auto";
			default -> str(value).strip();
		};
	}

	/** 150°是工作温度；专用材料只按明确钢号选择，未注明钢号的不锈钢默认201。 */
	static String fire150ExplicitMaterial(Object value, String source) {
		String material = str(value).strip();
		boolean unspecified = Set.of("", "不锈钢", "不锈", "stainless", "stainless_steel")
				.contains(material.toLowerCase(Locale.ROOT));
		String evidence = unspecified ? source : "材质：" + material;
		// 304×201、DN304等尺寸不是钢号，避免把尺寸旁的“不锈钢”误读为材质要求。
		evidence = evidence.replaceAll("(?i)" + DECIMAL + "\\s*[×x*＊]\\s*" + DECIMAL + "(?:\\s*[×x*＊]\\s*" + DECIMAL + ")?", " ")
				.replaceAll("(?i)(?:DN|[ΦφØø])\\s*" + DECIMAL, " ")
				.replaceAll("(?i)" + DECIMAL + "\\s*(?:℃|°\\s*C?|度|C\\b)", " ")
				.replaceAll("(?i)(?:不(?:需要|要求|采用|使用|用)|无需|无须|不用|非)\\s*(?:(?:SUS|SS|不锈钢)\\s*)?(?:201|304)\\s*(?:不锈钢|钢)?", " ");
		Matcher grade = match("(?:SUS|SS|stainless[_ -]?|不锈钢\\s*[:：]?|材质\\s*[:：=]?)\\s*(\\d{3}L?)(?![0-9]|\\s*[×x*＊])"
				+ "|(?<![0-9])(\\d{3}L?)\\s*(?:不锈钢|钢|SS)(?![A-Z0-9])", evidence);
		Set<String> grades = new LinkedHashSet<>();
		while (grade.find()) grades.add((grade.group(1) == null ? grade.group(2) : grade.group(1)).toLowerCase(Locale.ROOT));
		if (grades.size() > 1) return "conflict";
		if (!grades.isEmpty()) return "stainless_" + grades.iterator().next();
		if (!unspecified) return material;
		if (contains(source, "镀锌", "碳钢", "铝合金")) return "unsupported";
		return "";
	}

	static String fire150Material(Object value, String source) {
		String material = fire150ExplicitMaterial(value, source);
		return material.isEmpty() ? "stainless_201" : material;
	}

	// 数量、尺寸等必填参数要求正数，缺失或非正值会作为本行待确认原因。
	private static BigDecimal required(Map<String, Object> a, String key, String label) {
		if (blank(a.get(key)))
			throw new RuleException("请补充" + label);
		return positive(a.get(key), label);
	}

	/**
	 * 识别设备类别。报价人员明确选择的 {@code equipment_kind} 优先，未选择时按原文主产品识别。
	 */
	public static String classify(Map<String, Object> row) {
		var attrs = map(row.get("attributes"));
		if (!blank(attrs.get("equipment_kind")))
			return s(attrs, "equipment_kind");
		return classifySource(row);
	}

	// 分类与唯一性核验共用完整电动阀短语，结构词中的“式”不改变计算类型。
	static final Pattern ELECTRIC_CONTROL_NAME = Pattern.compile(
			"电动\\s*(?:(?:对开(?:式)?|多叶(?:式)?|风量)\\s*)*(?:调节(?:风)?阀|蝶阀|风量阀|阀)");

	static String positiveElectricText(String text) {
		return text.replaceAll("(?:非|无须|无需|不用|不需要|不需|不带|不含|不采用|不配|没有|无)\\s*"
				+ "(?:电动(?:执行机构|执行器|机构)?|电控|电机驱动)", " ");
	}

	private static boolean electricControlRequired(String name) {
		return has("电动|电控|电机驱动", positiveElectricText(name));
	}

	private static final Pattern ELECTRIC_CONTROL_DETAIL = Pattern.compile(
			"(?:控制(?:方式|要求)|驱动方式|机构(?:方式|形式))\\s*[:：=]?\\s*(?:采用|为|是)?\\s*(?:电动|电控|电机驱动)"
					+ "|(?:电动|电控)\\s*(?:执行机构|执行器|机构)|^\\s*(?:电动|电控|电机驱动)\\s*$");

	/** 名称已明确的控制类型优先；只有普通调节阀才从本行规格或备注补充电动要求。 */
	static boolean supplementaryElectricControl(Map<String, Object> row) {
		String name = mainProductText(s(row, "name"));
		if (!has("调节(?:风)?阀|蝶阀", name) || has("防火|风机|止回|余压|排烟|风口", name)
				|| name.contains("手动") || electricControlRequired(name)) return false;
		for (String field : List.of("spec", "remark")) {
			String detail = positiveElectricText(s(row, field)).replaceFirst(
					"(?s)(?:联动|联锁|控制对象|附带部件|附件|配套设备).*$", "");
			for (String clause : detail.split("[；;，,\\r\\n]")) {
				clause = clause.replaceFirst("^\\s*(?:厂家备注|报价备注|备注)\\s*[:：]\\s*", "");
				if (ELECTRIC_CONTROL_DETAIL.matcher(clause).find()) return true;
			}
		}
		return false;
	}

	/** 本体的电动执行机构是控制要求，不作为规格里的第二个采购产品。 */
	static String controlValveProductText(Map<String, Object> row) {
		String spec = s(row, "spec");
		if (supplementaryElectricControl(row)) spec = ELECTRIC_CONTROL_DETAIL.matcher(spec).replaceAll(" ");
		return s(row, "name") + "；" + spec;
	}

	/** 未覆盖的电动结构或相反控制要求必须核验，不能直接采用通用手动阀成本。 */
	static String electricControlIssue(Map<String, Object> row) {
		if (supplementaryElectricControl(row) && s(map(row.get("attributes")), "equipment_kind").equals("manual"))
			return "本行规格或备注明确要求电动机构，与所选手动调节阀计算类型不一致，请核对计算类型";
		String name = positiveElectricText(mainProductText(s(row, "name")));
		if (!has("调节(?:风)?阀|蝶阀", name) || !electricControlRequired(name)) return "";
		String kind = classifySource(obj("name", name));
		if (!Set.of("", "manual", "electric_control").contains(kind)) return "";
		if (name.contains("手动")) return "主产品同时包含手动和电动要求，控制方式冲突，请核对实际产品类型";
		if (!ELECTRIC_CONTROL_NAME.matcher(name).find())
			return "主产品含电动要求，但名称尚未对应唯一的电动调节阀规则，请核对产品类型，不能套用手动调节阀成本";
		if (s(map(row.get("attributes")), "equipment_kind").equals("manual"))
			return "主产品明确为电动调节阀，与所选手动调节阀计算类型冲突，请核对计算类型";
		return "";
	}

	/**
	 * 只从原文识别主产品，不采用 AI 或已有属性里的类别建议。
	 * 先排除明确的控制分段和附件描述，再取主产品段中最先出现的产品短语，同位置优先更完整的短语。
	 */
	public static String classifySource(Map<String, Object> row) {
		String name = positiveElectricText(mainProductText(s(row, "name")));
		if (EngineeringImportReview.water(s(EngineeringImportReview.source(row), "section"), name)) return "purchased";
		if (name.contains("电动排烟防火阀")) return "electric_fire";
		if (name.matches("^(?:CO浓度探测仪|压力传感器|(?:电动加压送风口|排风阀)?手动执行机构)(?:[，, -].*)?$")) return "purchased";
		String productName = AiInputReview.productName(name);
		if (productName.matches("(?s).*(?:执行机构|执行器|探测仪|传感器|控制器)$")
				&& !productName.matches("(?s).*(?:带|附|配|含).*(?:执行机构|执行器|探测仪|传感器|控制器)$")) return "";
		String[][] rules = { { "purchased", "风机控制柜", "风机控制箱", "通风器", "换气扇", "排气扇" },
				{ "elbow", "消声弯头", "消音弯头" }, { "plenum", "接管箱", "静压箱", "消声静压箱", "风管连接箱" },
				{ "silencer", "消声器", "消音器" }, { "plate_smoke", "板式排烟" },
				{ "multi_leaf", "多叶排烟", "电动百叶风口", "多叶送风口", "多叶加压送风口", "多叶加压送风", "正压送风口", "加压送风口", "正压送风阀", "组合送风口" },
				{ "pressure_relief", "余压", "泄压阀" },
				{ "check", "止回" }, { "electric_fire", "电动防火" },
				{ "fire", "防火调节阀", "防火阀", "防烟防火阀", "排烟阀", "防烟阀" },
				{ "electric_control", "电动调节", "电动风量", "电动阀", "电动蝶阀" },
				{ "manual", "调节阀", "调节风阀", "蝶阀" }, { "fan", "风机", "通风机" },
				{ "louver", "百叶", "散流器", "检修口", "检修门", "条缝风口", "线形风口" } };
		String kind = "";
		int first = Integer.MAX_VALUE, longest = 0;
		for (String[] rule : rules)
			for (int i = 1; i < rule.length; i++) {
				int at = name.indexOf(rule[i]);
				if (at >= 0 && (at < first || at == first && rule[i].length() > longest)) {
					kind = rule[0];
					first = at;
					longest = rule[i].length();
				}
			}
		// 对开、多叶等结构修饰不能把电动调节阀降为手动，仍按主产品首次出现的位置判别。
		if (kind.equals("manual") && electricControlRequired(name) && name.contains("手动")) return "";
		Matcher electricControl = ELECTRIC_CONTROL_NAME.matcher(name);
		if (electricControl.find() && electricControl.start() <= first)
			return "electric_control";
		if (kind.equals("manual") && supplementaryElectricControl(row)) return "electric_control";
		if (kind.equals("manual") && electricControlRequired(name)) return "";
		if (kind.isEmpty() || kind.equals("fan")) {
			String fanText = name + " " + mainProductText(s(row, "spec"));
			if (FanModelNames.outsourced(fanText)) return "purchased";
		}
		if (!kind.isEmpty())
			return kind;
		// 位置编号等名称没有明确产品短语时，才用原文中的已知风机型号补充识别。
		String modelText = name + " " + mainProductText(s(row, "spec")) + " " + mainProductText(s(row, "remark"))
				+ " " + mainProductText(s(row, "source_text"));
		if (FanModelNames.outsourced(modelText)) return "purchased";
		if (has(FanModelNames.MODEL_PATTERN, modelText))
			return "fan";
		return s(row, "category").equals("外购设备") ? "purchased" : "";
	}

	/** 明确带/配的括号和前置附件，以及联锁/复位等控制分段，都不作为申购本体。 */
	static String mainProductText(String text) {
		String main = text.replaceAll("[（(]\\s*(?:自?带|附(?:带)?|含|配(?:套)?)[^（）()]*[）)]", " ");
		String remaining = main.replaceFirst("^\\s*(?:带|附(?:带)?|含|配(?:套)?)[^\\r\\n;；（）()]*?(?:阀|百叶(?:风口)?)\\s*", "");
		// 只有后面还有明确主产品或型号时，前面的带阀/百叶才是另一设备的附件。
		if (!remaining.equals(main) && !remaining.isBlank() && !classifySource(obj("name", remaining)).isBlank())
			main = remaining;
		Matcher description = match("(?:[\\r\\n;；,，]\\s*)?(?:控制(?:要求|说明)|联锁|熔断|(?:远距离|远程)?手动复位|消控室)"
				+ "|[\\r\\n;；,，]\\s*(?:附(?:带)?|带|含|配(?:套)?)", main);
		return (description.find() ? main.substring(0, description.start()) : main).trim();
	}

	/**
	 * 从名称、规格和备注中提取尺寸、型号、风量、风压、功率等参数。已有属性优先，复合尺寸则按明确表达式拆分阀体与面板段。
	 */
	public static Map<String, Object> parseAttributes(Map<String, Object> row) {
		boolean valveAlias = !aliasFireValveKind(row, map(row.get("product_rule"))).isEmpty()
				|| !aliasFireValveKind(row, map(map(row.get("_product_rule_resource")).get("body"))).isEmpty();
		return parseAttributes(row, classify(row), valveAlias);
	}

	/** 已选防火阀别名按该类型识别原清单，避免陌生名称先损坏阀体/叶片或截面信息。 */
	static Map<String, Object> parseValveAttributes(Map<String, Object> row, String kind) {
		return Set.of("fire", "electric_fire").contains(kind) ? parseAttributes(row, kind, true) : parseAttributes(row);
	}

	/** 本轮防火阀别名的明确算法选择优先；未明确选择时继续保留本行人工类型。 */
	static String aliasFireValveKind(Map<String, Object> row, Map<String, Object> definition) {
		if (!s(definition, "rule_type").equals("alias")) return "";
		String selected = s(map(definition.get("attributes")), "equipment_kind");
		if (Set.of("fire", "electric_fire").contains(selected)) return selected;
		var attrs = new LinkedHashMap<>(map(definition.get("attributes")));
		attrs.putAll(map(row.get("attributes")));
		String kind = classify(obj("name", definition.get("canonical_name"), "attributes", attrs));
		return Set.of("fire", "electric_fire").contains(kind) ? kind : "";
	}

	private static Map<String, Object> parseAttributes(Map<String, Object> row, String kind, boolean valveAlias) {
		Map<String, Object> a = obj();
		map(row.get("attributes")).forEach((k, v) -> {
			if (v != null && !"".equals(v))
				a.put(k, v);
		});
		String sourceText = s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark") + " " + s(row, "source_text");
		String all = kind.equals("fan") ? FanSpeedParameters.normalizeUnits(sourceText) : sourceText;
		boolean fireValveInput = Set.of("fire", "electric_fire").contains(kind);
		if (fireValveInput && has("(?<![0-9])150\\s*(?:℃|°\\s*C?|度|C\\b)", all))
			a.put("valve_material_variant", "fire_150");
		// 括号加法尺寸先拆分阀体与面板段；正压及其同类名称在末尾恢复整体高。
		String mainDimensions = mainProductText(s(row, "name")) + " " + mainProductText(s(row, "spec"))
				+ " " + mainProductText(s(row, "remark")) + " " + mainProductText(s(row, "source_text"));
		mainDimensions = AiInputReview.unusableGeometry(mainDimensions) ? "" : AiInputReview.normalizeDimensionUnits(mainDimensions);
		String dimensionsText = has(DECIMAL + "\\s*[×xX*＊]", mainProductText(s(row, "spec")))
				? mainProductText(s(row, "spec")) : mainProductText(s(row, "name"));
		if (dimensionsText.isBlank()) dimensionsText = s(a, "dimension_expression");
		dimensionsText = AiInputReview.unusableGeometry(dimensionsText) ? "" : AiInputReview.normalizeDimensionUnits(dimensionsText);
		Matcher panel = match(DECIMAL + "\\s*[×xX*＊]\\s*[（(]\\s*" + DECIMAL + "\\s*[+＋]\\s*" + DECIMAL + "\\s*[）)]", dimensionsText);
		if (panel.find()) {
			if (Set.of("multi_leaf", "plate_smoke").contains(kind))
				a.putAll(obj("width", panel.group(1), "height", panel.group(2), "body_width", panel.group(1),
						"body_height", panel.group(2), "panel_height", panel.group(3), "dimension_expression", panel.group(),
						"overall_height", text(add(n(panel.group(2)), n(panel.group(3))))));
			else {
				a.putIfAbsent("width", panel.group(1));
				a.putIfAbsent("height", text(add(n(panel.group(2)), n(panel.group(3)))));
			}
		}
		Matcher dims = match(DECIMAL + "\\s*[×xX*＊]\\s*" + DECIMAL + "(?:\\s*[×xX*＊]\\s*" + DECIMAL + ")?", dimensionsText + " " + mainDimensions);
		if (dims.find()) {
			String[] keys = { "width", "height", "length" };
			for (int i = 0; i < 3; i++)
				if (dims.group(i + 1) != null)
					a.putIfAbsent(keys[i], dims.group(i + 1));
		}
		Matcher diameter = match("(?:[ΦφØø]|DN|直径\\s*[:：=]?)\\s*" + DECIMAL, mainDimensions);
		if (diameter.find())
			a.putIfAbsent("diameter", diameter.group(1));
		if (RoundDimensions.confirmed(row) && RoundDimensions.hasChoice(row))
			a.put("diameter", a.get("confirmed_diameter"));
		// 圆形尺寸统一用直径作为宽高，并记录 round；不能同时把直径和矩形尺寸重复计入。
		if (!blank(a.get("diameter")) && (!valveAlias || !Set.of("square", "rectangular").contains(s(a, "shape")))) {
			a.put("width", a.get("diameter"));
			a.put("height", a.get("diameter"));
			a.putIfAbsent("shape", "round");
		}
		if (contains(all, "圆形", "圆型"))
			a.putIfAbsent("shape", "round");
		if (valveAlias) {
			if (!blank(a.get("width")) && !blank(a.get("height"))) a.putIfAbsent("shape", "square");
			if (s(a, "shape").equals("round") && blank(a.get("diameter"))) {
				a.remove("width"); a.remove("height");
			}
		}
		if (s(a, "valve_material_variant").equals("fire_150"))
			a.put("material", fire150Material(a.get("material"), all));
		else {
			for (String grade : List.of("304", "201"))
				if (all.contains(grade) && (all.contains("不锈") || has(grade + "\\s*(?:钢|SS)", all)))
					a.putIfAbsent("material", "stainless_" + grade);
			if (all.contains("不锈钢"))
				a.putIfAbsent("material", "不锈钢");
			if (valveAlias && all.contains("镀锌"))
				a.putIfAbsent("material", "galvanized" + (has("80\\s*g", all) ? "_80" : has("40\\s*g", all) ? "_40" : ""));
		}
		for (String[] rule : new String[][] { { "防爆", "explosion", "true" },
				{ "双层", "layer", "double" }, { "角钢", "flange", "angle" }, { "喉部", "dimension_basis", "throat" } })
			if (all.contains(rule[0]))
				a.putIfAbsent(rule[1], rule[2]);
		boolean controlledValve = Set.of("fire", "electric_fire", "plate_smoke").contains(kind)
				|| PressureSupplyOutlet.applies(row) || FireOutletRule.applies(row);
		if (controlledValve) {
			String control = valveControl(a.get("control"));
			if (control.isEmpty()) {
				String evidence = (all + " " + s(row, "control_description") + " " + s(a, "control_description"))
						.replaceAll("(?:非|无(?:需|须)?|不(?:需(?:要)?|要|带|含|采用)?|未(?:要求)?|不用)\\s*(?:远控(?:机构)?|全自动(?:机构)?)", "")
						.replaceAll("(?:远\\s*[/／、或]\\s*近控|近\\s*[/／、或]\\s*远控|远控\\s*(?:或|/|／)\\s*近控|近控\\s*(?:或|/|／)\\s*远控)", "");
				if (evidence.contains("全自动")) control = "auto";
				else if (a.containsKey("remote")) control = enabled(a.get("remote")) ? "remote" : "near";
				else if (evidence.contains("远控")) control = "remote";
			}
			if (!control.isEmpty()) {
				a.put("control", control);
				a.put("remote", control.equals("remote"));
			}
		}
		else if (all.contains("远控")) a.putIfAbsent("remote", "true");
		Map<String, String> patterns = new LinkedHashMap<>();
		String thicknessText = all;
		if (valveAlias) {
			String bladePattern = "叶片\\s*(?:板材\\s*)?(?:板厚|厚度|厚)\\s*[:：=＝]?\\s*" + DECIMAL;
			Matcher blade = match(bladePattern, all);
			if (blade.find()) a.putIfAbsent("blade_thickness", blade.group(1));
			thicknessText = all.replaceAll(bladePattern, "");
		}
		String bodyThicknessText = thicknessText;
		String pressureText = kind.equals("fan") ? FanSpeedParameters.withoutStaticPressure(all) : all;
		patterns.put("airflow", "(?:风量|(?<![A-Z])(?:" + (Set.of("silencer", "plenum", "elbow").contains(kind) ? "Q" : "Q|L") + ")\\s*[:：=＝])\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("pressure", (kind.equals("fan") ? FanSpeedParameters.pressurePrefix(pressureText)
				: "(?:全压|静压|风压|CMH\\s*P|(?<![A-Z0-9.])(?:P|H))\\s*[:：=＝]?\\s*") + DECIMAL);
		patterns.put("speed", "(?:转速\\s*[:：=＝]?\\s*|(?<![A-Z])N\\s*[:：=＝]\\s*)?" + DECIMAL + "\\s*(?:r/min|rpm|转/分)");
		patterns.put("thickness", "(?:厚度|板厚|厚)\\s*[:：=＝]?\\s*" + DECIMAL);
		patterns.put("power", "(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?)?)\\s*(?:kW|千瓦)");
		patterns.forEach((key, pattern) -> {
			Matcher m = match(pattern, key.equals("thickness") ? bodyThicknessText : key.equals("pressure") ? pressureText : all);
			if (m.find())
				a.putIfAbsent(key, m.group(1).replace(" ", ""));
		});
		AcousticLengthInputs.parse(row, a);
		// 斜线高低档风量、风压分别记录，双速选型时低档条件也要满足。
		for (String key : List.of("airflow", "pressure")) {
			Matcher pair = match(patterns.get(key) + "\\s*/\\s*" + DECIMAL, key.equals("pressure") ? pressureText : all);
			if (pair.find())
				a.putIfAbsent(key + "_low", pair.group(2));
		}
		// 客户也会直接写 1200CMH/80Pa；仅补齐未由独立字段或参数名称识别出的值。
		for (String key : List.of("airflow", "pressure")) {
			String unit = key.equals("airflow") ? "(?:CMH|m[³3]\\s*/\\s*h)" : "Pa";
			Matcher value = match("(?<![A-Z0-9.])" + DECIMAL + "\\s*" + unit + "(?![A-Z0-9])", key.equals("pressure") ? pressureText : all);
			if (value.find()) a.putIfAbsent(key, value.group(1));
		}
		String modelPattern = kind.equals("purchased") ? "(?:" + FanModelNames.MODEL_PATTERN + "|\\b[A-Z]{2,}[A-Z\\d]*[-－][A-Z\\d.－-]+)"
				: FanModelNames.MODEL_PATTERN;
		// 名称常只写系列缩写，完整客户型号以规格列优先，避免“PYHL-14A”挡住规格中的“PYHL-14A-5”。
		Matcher model = match(modelPattern, kind.equals("fan") ? s(row, "spec") + " " + all : all);
		if (!a.containsKey("power")) {
			Matcher watts = match(DECIMAL + "\\s*W\\b", all);
			if (watts.find())
				a.put("power", text(div(n(watts.group(1)), 1000)));
		}
		if (model.find())
			a.putIfAbsent("model", model.group());
		else if (kind.equals("fan") && blank(a.get("model"))) {
			// 新品牌仅采纳明确标注的型号或规格开头的型号，不把任意附件编码当作风机型号。
			String unknownModel = "([A-Z]{2,}[A-Z\\d()]*[-－][A-Z\\d.－-]+)";
			Matcher explicit = match("(?<!电机)型号\\s*[:：=]?\\s*" + unknownModel, all);
			Matcher leading = match("^\\s*" + unknownModel, s(row, "spec"));
			if (leading.find()) a.put("model", leading.group(1));
			else if (explicit.find()) a.put("model", explicit.group(1));
		}
		if (kind.equals("fan") && blank(a.get("motor_model"))) {
			Matcher motorModel = match("电机\\s*(?:型号)?\\s*[:：=]?\\s*([A-Z][A-Z\\d()]*[-－][A-Z\\d.－/-]+)", all);
			if (motorModel.find()) a.put("motor_model", motorModel.group(1));
		}
		if (!blank(a.get("model")))
			a.put("raw_model", a.get("model"));
		if (kind.equals("purchased")) {
			String purchasedType = PurchasedEquipment.requestedType(row);
			if (!purchasedType.isEmpty()) a.putIfAbsent("purchased_type", purchasedType);
		}
		if (kind.equals("fan")) FanSpeedParameters.normalize(row, a, all);
		// 普通风阀的钢制/钢质沿用镀锌板报价口径；150°专用不锈钢及其他明确材质不覆盖。
		if (EquipmentCatalog.VALVES.contains(kind) && !s(a, "valve_material_variant").equals("fire_150")
				&& Set.of("钢制", "钢质").contains(s(a, "material"))) a.put("material", "galvanized");
		PressureSupplyOutlet.normalize(row, a);
		AiInputReview.clearedFields(a).forEach(a::remove);
		return a;
	}

	// 已发布的旧指标缺少正压专用项时沿用本次核对的源表值；已填写的指标仍需有效。
	private static BigDecimal pressureSupplyRate(Map<String, Object> rates, String key, String fallback) {
		return nonnegative(rates.containsKey(key) ? rates.get(key) : fallback, "正压送风口指标 " + key);
	}

	// 新机构附加项缺失的旧发布指标沿用本次确认值，避免误用旧的整件远控附加费。
	private static BigDecimal mechanismRate(Map<String, Object> rates, String key, String fallback) {
		return nonnegative(rates.containsKey(key) ? rates.get(key) : fallback, "机构附加指标 " + key);
	}

	private static final String PRESSURE_SUPPLY_LINK =
			"width / 200 * 0.5 + (width / 200 - 1) * 0.2 * density * frame_thickness * frame_price * 0.035 / 1000";

	/** 按阀体尺寸、叶片数量、材料、机构、拼接和面板等组成项计算阀门基础成本。 */
	public static Cost valveCost(String originalKind, Map<String, Object> a, Map<String, Object> catalog) {
		// 阀门宽高和板厚用 mm，材料吨价由镀锌基础价或不锈钢板厚价提供。
		Map<String, Object> rates = map(catalog.get("rates"));
		// 余压阀按对应尺寸防火阀成本计算，并在明细中保留代价依据。
		boolean proxy = originalKind.equals("pressure_relief");
		String kind = proxy ? "fire" : originalKind;
		Map<String, Object> cfg = map(map(catalog.get("valves")).get(kind));
		String label = EquipmentReview.KIND_LABELS.getOrDefault(kind, kind);
		if (cfg.isEmpty()) throw new RuleException(label + "成本规则未发布，请补齐该类型的机构、叶片及人工成本资料");
		if (List.of("blade_thickness", "mechanism", "labor_base").stream().anyMatch(k -> blank(cfg.get(k))))
			throw new RuleException(label + "成本规则缺少叶片板厚、机构费或基础人工费，请补齐成本资料");
		boolean roundValve = s(a, "shape").equals("round");
		boolean diameterDimensions = roundValve && !blank(a.get("diameter"));
		BigDecimal w = diameterDimensions ? required(a, "diameter", "圆阀直径（mm）") : required(a, "width", "宽度（mm）");
		BigDecimal h = diameterDimensions ? w : required(a, "height", "高度（mm）");
		boolean pressureSupply = kind.equals("multi_leaf") && yes(a.get("_pressure_supply"));
		boolean fireValve = !proxy && Set.of("fire", "electric_fire").contains(kind);
		boolean plateSmoke = kind.equals("plate_smoke");
		BigDecimal longSide = w.max(h), shortSide = w.min(h);
		// 防火阀按长短边选档；板式全部沿用原板式结构公式；正压仍按整体高独立选档。
		boolean split = pressureSupply ? h.compareTo(positive(pressureSupplyRate(rates, "pressure_supply_split_height", "1450"), "正压拆分高度")) > 0
				: plateSmoke ? false : fireValve ? longSide.compareTo(rate(rates, "split_width")) > 0
						|| shortSide.compareTo(rate(rates, "split_height")) > 0
				: w.compareTo(rate(rates, "split_width")) > 0 || h.compareTo(rate(rates, "split_height")) > 0;
		// 基础1个，长边每2000与短边每1000增加的机构相加；正压短边界限仍读取其专用指标。
		BigDecimal mechanismCount = pressureSupply ? add(longSide.divide(n(2000), 0, RoundingMode.CEILING),
				shortSide.divide(positive(pressureSupplyRate(rates, "pressure_supply_mechanism_width", "1000"),
						"正压机构宽度"), 0, RoundingMode.CEILING)).subtract(ONE)
				: fireValve || plateSmoke ? add(longSide.divide(n(2000), 0, RoundingMode.CEILING),
						shortSide.divide(n(1000), 0, RoundingMode.CEILING)).subtract(ONE) : ONE;
		BigDecimal groups = n(split ? 2 : 1), blades = mul(ceil(div(kind.equals("multi_leaf") ? w : h, 200)), groups);
		BigDecimal ft = positive(a.getOrDefault("thickness", "1.2"), "阀体板厚");
		String materialVariant = str(a.getOrDefault("valve_material_variant", "standard"));
		if (!Set.of("standard", "fire_150").contains(materialVariant)
				|| materialVariant.equals("fire_150") && (proxy || !Set.of("fire", "electric_fire").contains(kind)))
			throw new RuleException("150°专用材料指标仅适用于防火阀，请核对阀门类型和材料指标");
		boolean fire150 = materialVariant.equals("fire_150");
		String material = fire150 ? fire150Material(a.get("material"), "") : s(a, "material");
		if (fire150 && !Set.of("stainless_201", "stainless_304").contains(material))
			throw new RuleException("150°防火阀材质待确认：仅支持201或304不锈钢，请核对明确的材质要求");
		String materialRate = fire150 ? "fire_150_" + material.substring("stainless_".length()) + "_steel" : "valve_steel";
		BigDecimal bt = positive(a.getOrDefault("blade_thickness", cfg.get("blade_thickness")), "叶片板厚"),
				steel = fire150 ? EquipmentCatalog.fire150Price(rates, material.substring("stainless_".length())) : rate(rates, materialRate);
		var env = obj("width", w, "height", h, "groups", groups, "blades", blades, "density", rate(rates, "density"),
				"frame_thickness", ft, "blade_thickness", bt, "labor_base", n(cfg.get("labor_base")), "brace_count",
				n(!split || kind.equals("manual") ? 2 : 4), "frame_price",
				fire150 ? steel : EquipmentFanSelector.materialPrice(catalog, a.get("material"), ft, steel), "blade_price",
				fire150 ? steel : EquipmentFanSelector.materialPrice(catalog, a.get("material"), bt, steel), "back_price",
				kind.equals("multi_leaf") ? EquipmentFanSelector.materialPrice(catalog, a.get("material"), ".5", steel)
						: steel);
		List<String> keys = List.of("frame", "blade", "labor", "link", "corners", "shaft", "consumables", "brace");
		if (kind.equals("multi_leaf"))
			keys = List.of("multi_frame", "multi_blade", "labor", "link", "shaft", "consumables", "multi_brace",
					"multi_back");
		if (kind.equals("plate_smoke")) {
			keys = List.of("plate_frame", "plate_blade", "labor", "link", "corners", "shaft", "plate_consumables",
					"plate_brace");
			blades = ceil(div(h, 200));
			env.put("blades", blades);
		}
		// 按阀门结构选择组成公式，随后应用板式、止回及多叶分组的已确认差异。
		Map<String, BigDecimal> parts = new LinkedHashMap<>();
		for (String key : keys)
			parts.put(key, DecimalFormula.evaluate(s(map(catalog.get("valve_expressions")), key), env));
		String pressureLink = str(map(catalog.get("valve_expressions")).getOrDefault("pressure_supply_link", PRESSURE_SUPPLY_LINK));
		if (pressureSupply)
			parts.put("link", DecimalFormula.evaluate(pressureLink, env));
		if (kind.equals("plate_smoke")) {
			parts.put("link", ZERO);
			parts.put("shaft", ZERO);
		}
		if (kind.equals("check")) {
			parts.put("link", ZERO);
			if (!split)
				parts.put("blade", mul(parts.get("blade"), n("1.2")));
		}
		if (kind.equals("multi_leaf") && split)
			parts.put("shaft", mul(parts.get("shaft"), n(2)));
		// 三类产品以普通机构单价为基础；远控每机构加40，全自动仅防火阀每机构加150。
		BigDecimal mechanism = pressureSupply ? pressureSupplyRate(rates, "pressure_supply_mechanism", "20") : n(cfg.get("mechanism"));
		String control = blank(a.get("control")) ? enabled(a.get("remote")) ? "remote" : "near" : valveControl(a.get("control"));
		if (!Set.of("near", "remote", "auto").contains(control))
			throw new RuleException("机构方式未识别");
		if (control.equals("auto") && (plateSmoke || pressureSupply))
			throw new RuleException("全自动机构仅适用于防火阀，请核对本行控制要求");
		if (plateSmoke) mechanism = rate(rates, "near_control");
		if (!blank(a.get("mechanism_cost")))
			mechanism = n(a.get("mechanism_cost"));
		BigDecimal mechanismBase = mechanism, remoteExtra = ZERO, autoExtra = ZERO;
		mechanism = mul(mechanismBase, mechanismCount);
		boolean perMechanism = fireValve || plateSmoke || pressureSupply;
		if (fireValve && control.equals("auto")) {
			autoExtra = mechanismRate(rates, "fire_auto_extra", "150");
			mechanism = mul(add(mechanismBase, autoExtra), mechanismCount);
		}
		else if (perMechanism && control.equals("remote")) {
			remoteExtra = mechanismRate(rates, "valve_remote_extra", "40");
			mechanism = mul(add(mechanismBase, remoteExtra), mechanismCount);
		}
		else if (!perMechanism && (enabled(a.get("remote")) || control.equals("remote"))) {
			remoteExtra = rate(rates, "remote_extra");
			mechanism = add(mechanism, remoteExtra);
		}
		parts.put("mechanism", mechanism);
		// 两个方向分别按分段数减1计拼接，再乘对应产品的每次成本；不再采用历史人工拼接费。
		BigDecimal splice = ZERO, widthSplices = ZERO, heightSplices = ZERO, spliceRate = ZERO;
		BigDecimal spliceWidth = null, spliceHeight = null;
		if (Set.of("fire", "electric_fire", "electric_control", "manual").contains(kind)) {
			spliceWidth = rate(rates, "splice_width");
			spliceHeight = rate(rates, "splice_height");
			widthSplices = spliceCount(w, spliceWidth);
			heightSplices = spliceCount(h, spliceHeight);
			spliceRate = rate(rates, kind.equals("manual") ? "manual_splice" : "fire_splice");
			splice = mul(add(widthSplices, heightSplices), spliceRate);
		}
		parts.put("splice", splice);
		BigDecimal panelRate = pressureSupply ? pressureSupplyRate(rates, "pressure_supply_panel_rate", "70") : rate(rates, "panel_rate");
		if (Set.of("multi_leaf", "plate_smoke").contains(kind))
			parts.put("panel", div(mul(add(w, rate(rates, "panel_edge")), add(h, rate(rates, "panel_edge")),
					panelRate), 1000000));
		if (parts.values().stream().anyMatch(v -> v.signum() < 0))
			throw new RuleException("尺寸或成本参数使阀体成本为负，请核对");
		// 组成项金额及实际公式同时保存，明细反映零项、倍率、机构和面板的真实计算口径。
		// 圆阀先按直径×直径算全成本（含机构、控制附加、拼接及面板），再统一加30%加工费。
		BigDecimal ordinaryValveCost = parts.values().stream().reduce(ZERO, QuoteData::add);
		if (roundValve) parts.put("round_processing", mul(ordinaryValveCost, n("0.3")));
		Map<String, Object> components = obj();
		parts.forEach((k, v) -> components.put(k, text(v)));
		Map<String, Object> componentFormulas = obj();
		Map<String, Object> expressions = map(catalog.get("valve_expressions"));
		for (String key : parts.keySet())
			componentFormulas.put(key, expressions.getOrDefault(key, text(parts.get(key))));
		if (kind.equals("plate_smoke")) {
			componentFormulas.put("link", "0（板式不计连杆）");
			componentFormulas.put("shaft", "0（板式不计圆钢）");
		}
		if (kind.equals("check")) {
			componentFormulas.put("link", "0（止回阀不计连杆）");
			if (!split)
				componentFormulas.put("blade", "(" + s(expressions, "blade") + ") × 1.2");
		}
		if (kind.equals("multi_leaf") && split)
			componentFormulas.put("shaft", "(" + s(expressions, "shaft") + ") × 2");
		if (pressureSupply) componentFormulas.put("link", pressureLink);
		if (roundValve) componentFormulas.put("round_processing", text(ordinaryValveCost) + " × (1.3 - 1)");
		componentFormulas.put("mechanism", perMechanism
				? "(" + text(mechanismBase) + " + " + text(add(remoteExtra, autoExtra)) + ") × " + text(mechanismCount)
				: text(mechanismBase) + " + " + text(remoteExtra));
		componentFormulas.put("splice", spliceWidth == null ? "0" : "(" + text(widthSplices) + "（宽" + text(w)
				+ "÷" + text(spliceWidth) + "，向上取整后减1） + " + text(heightSplices) + "（高" + text(h)
				+ "÷" + text(spliceHeight) + "，向上取整后减1）) × " + text(spliceRate));
		if (parts.containsKey("panel"))
			componentFormulas.put("panel", "(" + text(w) + " + " + s(rates, "panel_edge") + ") × ("
					+ text(h) + " + " + s(rates, "panel_edge") + ") × " + text(panelRate) + " ÷ 1000000");
		return new Cost(parts.values().stream().reduce(ZERO, QuoteData::add), obj("components", components, "split",
				split, "groups", text(groups), "blades", text(blades), "price_proxy", proxy ? "对应规格防火阀" : null,
				"component_inputs", env, "component_formulas", componentFormulas,
				"splice_width_count", text(widthSplices), "splice_height_count", text(heightSplices),
				"splice_width", spliceWidth == null ? null : text(spliceWidth), "splice_height", spliceHeight == null ? null : text(spliceHeight),
				"splice_rate", text(spliceRate),
				"body_width", text(w), "body_height", text(h), "panel_height", a.get("panel_height"),
				"overall_height", a.get("overall_height"), "dimension_expression", a.get("dimension_expression"),
				"panel_outer_width", parts.containsKey("panel") ? text(add(w, rate(rates, "panel_edge"))) : null,
				"panel_outer_height", parts.containsKey("panel") ? text(add(h, rate(rates, "panel_edge"))) : null,
				"pressure_supply", pressureSupply, "mechanism_count", mechanismCount,
				"mechanism_base", text(mechanismBase), "remote_extra", text(remoteExtra), "auto_extra", text(autoExtra),
				"control", control, "long_side", text(longSide), "short_side", text(shortSide), "material_prices",
				obj("frame_price", text(n(env.get("frame_price"))), "blade_price", text(n(env.get("blade_price")))),
				"valve_material_variant", materialVariant, "material_rate_key", materialRate));
	}

	// 风口类型和计价口径共用核验规则，有冲突或未知类型时先待确认。
	private static String louverType(Map<String, Object> row, Map<String, Object> a) {
		var recognition = EquipmentReview.louver(row, a);
		if (!blank(recognition.get("issue"))) throw new RuleException(s(recognition, "issue"));
		return s(recognition, "louver_type");
	}

	/**
	 * 计算风口、消声器和静压箱的面积成本。缺少尺寸、特殊材质成本或板厚系数时直接待确认，避免套用普通镀锌产品价格。
	 */
	public static Cost areaCost(String kind, Map<String, Object> row, Map<String, Object> a,
			Map<String, Object> catalog) {
		BigDecimal w = required(a, "width", "宽度（mm）"), h = required(a, "height", "高度（mm）");
		Map<String, Object> rates = map(catalog.get("rates"));
		String material = s(a, "material");
		if ((contains(material, "不锈", "stainless") || Set.of("201", "304").contains(material))
				&& blank(a.get("area_cost")))
			throw new RuleException("该产品采用综合面积成本，源表未分解不锈钢用量；请提供已核实的每㎡成本");
		if (kind.equals("louver")) {
			String type = louverType(row, a);
			var recognition = EquipmentReview.louver(row, a);
			String basis = s(recognition, "pricing_louver_type");
			// 普通自垂百叶按独立面积成本取价；带阀组合仍沿用原单层带阀口径。
			String pricingType = basis.equals("gravity_valve") ? "single_valve" : basis;
			if (!map(catalog.get("louvers")).containsKey(pricingType))
				throw new RuleException("该风口类型尚无成本依据，请核对类型");
			// 风口配置四项依次为元/㎡成本、最低元/个成本、宽高增加 mm 值和附件倍率。
			List<?> values = (List<?>) map(catalog.get("louvers")).get(pricingType);
			BigDecimal rate = n(values.get(0)), minimum = n(values.get(1)), edge = n(values.get(2)),
					factor = n(values.get(3));
			if (s(a, "dimension_basis").equals("throat") && type.startsWith("diffuser"))
				edge = n(130);
			// 宽高增加边距后除以 1000000 换成㎡；实际成本为面积×成本价×倍率与最低成本的较大值。
			BigDecimal area = div(mul(add(w, edge), add(h, edge)), 1000000);
			return new Cost(mul(area, n(a.getOrDefault("area_cost", rate)), factor).max(minimum),
					obj("width", text(w), "height", text(h), "edge", text(edge), "area", text(area),
						"area_rate", text(n(a.getOrDefault("area_cost", rate))), "area_factor", text(factor),
						"louver_type", type, "pricing_louver_type", pricingType, "minimum_cost", text(minimum),
						"pricing_louver_basis", basis, "recognition_evidence", recognition.get("recognition_evidence")));
		}
		BigDecimal length = required(a, "length", "长度（mm）");
		BigDecimal sourceWidth = w, sourceHeight = h;
		if (kind.equals("silencer")) {
			w = add(w, mul(n(2), rate(rates, "silencer_edge")));
			h = add(h, mul(n(2), rate(rates, "silencer_edge")));
		}
		// 消声器先加两侧边距，箱体按六面展开面积计算：[2×(宽+高)×长+2×宽×高]÷1000000，单位㎡。
		BigDecimal area = div(add(mul(add(w, h), length, n(2)), mul(w, h, n(2))), 1000000);
		String layer = str(a.getOrDefault("layer", "single"));
		if (!Set.of("single", "double").contains(layer))
			throw new RuleException("请明确单层或双层");
		BigDecimal rate = AcousticRegionalCost.rate(kind, a, catalog);
		BigDecimal cost = mul(area, n(a.getOrDefault("area_cost", rate))),
				thickness = n(a.getOrDefault("thickness", ".75"));
		// 0.75mm 为基础口径，1.0/1.2mm 和角钢分别除以指标系数；其他板厚没有依据就待确认。
		BigDecimal thicknessDivisor = ONE, angleDivisor = ONE;
		if (thickness.compareTo(ONE) == 0) {
			thicknessDivisor = rate(rates, "thickness_1_factor");
			cost = div(cost, thicknessDivisor);
		}
		else if (thickness.compareTo(n("1.2")) == 0) {
			thicknessDivisor = rate(rates, "thickness_1_2_factor");
			cost = div(cost, thicknessDivisor);
		}
		else if (thickness.compareTo(n(".75")) != 0)
			throw new RuleException("该板厚没有已确认的消声设备成本系数");
		if (s(a, "flange").equals("angle")) {
			angleDivisor = rate(rates, "angle_factor");
			cost = div(cost, angleDivisor);
		}
		return new Cost(cost,
				obj("width", text(sourceWidth), "height", text(sourceHeight), "length", text(length),
						"adjusted_width", text(w), "adjusted_height", text(h), "area", text(area),
						"area_cost_region", !a.containsKey("area_cost") && !layer.equals("double") && catalog.containsKey("acoustic_region_costs") ? s(catalog, "_acoustic_region") : "",
						"area_rate", text(n(a.getOrDefault("area_cost", rate))), "layer", layer,
						"thickness_divisor", text(thicknessDivisor), "angle_divisor", text(angleDivisor)));
	}

	// 记录设备成本、售价及取整步骤，仅供内部核对，不额外生成客户产品行。
	private static Map<String, Object> trace(String step, String expression, String value) {
		return obj("step", step, "expression", expression, "value", value);
	}

	/**
	 * 计算一条设备产品：识别类别和参数，选择对应成本模型，再依次应用毛利、税口径和向上取整规则。
	 * 无法唯一选型时保留候选产品；业务参数不完整时仅将本行标为待确认。
	 */
	public Map<String, Object> calculateRow(Map<String, Object> row, Map<String, Object> catalog,
			Map<String, Object> options, Map<String, Object> resource) {
		return calculateRow(row, catalog, options, resource, new EquipmentFanSelector.CostLookup(catalog));
	}

	private Map<String, Object> calculateRow(Map<String, Object> row, Map<String, Object> catalog,
			Map<String, Object> options, Map<String, Object> resource, EquipmentFanSelector.CostLookup fanLookup) {
		var scope = InstallationPricingEngine.materialScopeRow(row, yes(options.get("include_installation")));
		if (scope != null)
			return scope;
		List<Map<String, Object>> traces = new ArrayList<>();
		Map<String, Object> out = obj("status", "pending", "reason", "", "unit_price", null, "amount", null, "trace",
				traces, "candidates", new ArrayList<>(), "pricing_mode", "equipment_v1");
		try {
			EngineeringImportReview.requirePricingBasis(row);
			String lengthReason = AcousticLengthInputs.reason(row);
			if (!lengthReason.isBlank()) throw new RuleException(lengthReason);
			if (!blank(row.get("product_rule_issue")))
				throw new RuleException(s(row, "product_rule_issue"));
			if (blank(row.get("name")))
				throw new RuleException("请填写设备名称");
			String sourceReason = EquipmentReview.sourceReason(row);
			if (!sourceReason.isBlank() && !RoundDimensions.confirmed(row))
				throw new RuleException(sourceReason);
			if (catalog == null)
				throw new RuleException("设备指标尚未发布或存在多个有效指标，请先核对指标版本");
			var regionalCatalog = AcousticRegionalCost.withRegion(catalog, options);
			BigDecimal qty = required(row, "quantity", "数量");
			String productRuleType = s(map(map(row.get("_product_rule_resource")).get("body")), "rule_type");
			// 组合和全新公式先返回每 1 报价单位未税成本，再进入同一毛利、税费和数量结算流程。
			boolean customCost = Set.of("combination", "formula").contains(productRuleType);
			String kind = customCost ? "custom" : classify(row);
			var a = parseAttributes(row);
			out.putAll(obj("attributes", a, "equipment_kind", kind));
			if (productRuleType.equals("alias") && Set.of("fire", "electric_fire").contains(kind) && s(a, "shape").equals("round")) {
				try { positive(a.get("diameter"), "圆形直径"); }
				catch (RuleException | NumberFormatException e) { throw new RuleException("请补充圆形直径（mm）"); }
			}
			if (!customCost && !KINDS.contains(kind))
				throw new RuleException("未识别设备类型，请明确本行产品类型");
			if (!customCost && kind.equals("plenum") && AcousticQuantityRows.areaUnit(s(row, "unit")))
				throw new RuleException("静压箱按个计价，客户面积仅用于核对，请确认设备个数和数量单位");
			if (!customCost && !kind.equals("elbow")
					&& !Set.of("台", "个", "只", "套", "件", "组", "樘").contains(s(row, "unit")))
				throw new RuleException("设备数量单位应为台、个、只、套、件、组或樘");
			var rates = map(catalog.get("rates"));
			var term = list(catalog.get("payment_terms")).stream()
				.filter(t -> s(t, "code").equals(s(options, "payment_option")) && s(t, "mode")
					.equals(yes(options.get("include_installation")) ? "with_install" : "no_install"))
				.findFirst()
				.orElse(null);
			if (term == null)
				throw new RuleException("请选择与安装状态对应的整单付款方式");
			if (!bool(options.get("tax_included")))
				throw new RuleException("请明确设备报价是否含税");
			if (!bool(options.get("include_freight")))
				throw new RuleException("请明确设备是否包含运费");
			// 风机、阀门、面积类产品和外购设备分别使用各自的成本模型。
			Cost calculated = null;
			if (productRuleType.equals("formula"))
				calculated = ProductRuleCatalog.formulaCost(row, catalog);
			else if (productRuleType.equals("combination"))
				calculated = ProductRuleCatalog.combinationCost(row, regionalCatalog);
			else if (kind.equals("fan")) {
				String reviewReason = EquipmentReview.reason(row, a);
				if (!reviewReason.isBlank()) throw new RuleException(reviewReason);
				calculated = FanCostTemplate.definition(row).isEmpty() ? EquipmentFanSelector.select(row, catalog, a, fanLookup)
						: FanCostTemplate.cost(row, catalog, a);
				if (!FanCostTemplate.definition(row).isEmpty()) {
					var fanCost = map(calculated.detail().get("fan_cost")); var body = map(fanCost.get("body"));
					traces.add(trace("风机本体材料成本", s(body, "material_expression"), s(fanCost, "material_cost")));
					traces.add(trace("风机本体其他成本", s(body, "other_expression"), s(fanCost, "body_other_cost")));
					traces.add(trace("电机成本", s(map(fanCost.get("motor")), "model") + "（未税成本）", s(fanCost, "motor_cost")));
				}
			}
			else if (Set.of("fire", "electric_fire", "electric_control", "manual", "check", "pressure_relief", "multi_leaf", "plate_smoke").contains(kind))
				calculated = valveCost(kind, a, catalog);
			else if (Set.of("louver", "silencer", "plenum").contains(kind))
				calculated = areaCost(kind, row, a, regionalCatalog);
			else if (kind.equals("purchased"))
				calculated = PurchasedEquipment.cost(row, a, catalog);
			Map<String, Object> detail = calculated == null ? obj() : calculated.detail();
			EquipmentFraction base;
			if (calculated != null) {
				BigDecimal cost = calculated.amount();
				if (cost.signum() < 0)
					throw new RuleException("设备成本不能为负");
				boolean bundle = kind.equals("fan") && EquipmentReview.hasAttachedValve(row, a);
				if (bundle) detail.put("fan_ordinary_cost", text(cost));
				// 普通风机防爆倍率按成本档位取值，上限含本数；已经确认含阀整套成本时不重复增加防爆成本。
				if (kind.equals("fan") && enabled(a.get("explosion")) && !yes(map(detail.get("fan_cost")).get("explosion_included"))
						&& !(bundle && Boolean.TRUE.equals(EquipmentReview.included(a)))) {
					BigDecimal ordinary = cost;
					var tier = list(catalog.get("explosion_tiers")).stream()
						.filter(t -> t.get("limit") == null || ordinary.compareTo(n(t.get("limit"))) <= 0)
						.findFirst()
						.orElseThrow();
					detail.putAll(obj("ordinary_cost", text(cost), "explosion_factor", tier.get("factor")));
					cost = mul(cost, n(tier.get("factor")));
				}
				// 附阀数量是每台数量：未含阀时先合并风机与所有附阀成本，已含阀时直接采用核实的整套未税成本。
				if (bundle) {
					var valveAttributes = EquipmentReview.valveAttributes(a);
					String valveKind = EquipmentReview.valveKind(row, a);
					boolean included = Boolean.TRUE.equals(EquipmentReview.included(a));
					var valveDetail = obj("kind", valveKind, "attributes", valveAttributes,
							"count_per_fan", a.get("attached_valve_count"), "included", included);
					if (included) {
						cost = required(a, "fan_set_cost", "已核实整套不含税成本");
						valveDetail.put("confirmed_set_cost", text(cost));
						traces.add(trace("整套成本", "人工核实整套不含税成本已含阀门及适用防爆成本，不再加附阀成本", text(cost)));
					}
					else {
						Cost valve;
						try { valve = valveCost(valveKind, valveAttributes, catalog); }
						catch (RuleException e) { throw new RuleException("附带阀门：" + e.getMessage()); }
						// 阀门成本已经包含圆阀加工费，保留旧明细字段但不得再次乘外形系数。
						BigDecimal round = ONE;
						BigDecimal valvePerUnit = valve.amount();
						BigDecimal valveTotal = mul(valvePerUnit, required(a, "attached_valve_count", "每台风机附阀数量"));
						detail.put("fan_adjusted_cost", text(cost));
						valveDetail.putAll(obj("detail", valve.detail(), "ordinary_cost", text(valve.amount()),
								"round_factor", text(round), "unit_cost", text(valvePerUnit), "cost_per_set", text(valveTotal)));
						traces.add(trace("附阀成本/套", text(valve.amount()) + " × " + text(round) + "（阀门外形系数） × "
								+ s(a, "attached_valve_count") + "（每台数量）", text(valveTotal)));
						traces.add(trace("风机与附阀合计成本", text(cost) + " + " + text(valveTotal), text(add(cost, valveTotal))));
						cost = add(cost, valveTotal);
					}
					detail.put("attached_valve", valveDetail);
				}
				out.put("unit_cost", text(cost));
				// 外购已定售价不再加毛利；组合内其余成本仍沿用原毛利口径。
				var fixedSales = PurchasedEquipment.fixedSales(detail);
				base = fraction(cost).divide(fraction(ONE.subtract(rate(rates, "gross_margin"), MC))).add(fixedSales);
				boolean hasFixedSales = PurchasedEquipment.isSale(map(detail.get("purchased"))) || fixedSales.compareTo(fraction(ZERO)) > 0;
				if (hasFixedSales) {
					detail.put("fixed_sale_price", ftext(fixedSales));
					traces.add(trace("外购不含税售价", "引用外购已定售价，不重复加毛利", ftext(fixedSales)));
				}
				detail.putAll(obj("gross_margin", text(rate(rates, "gross_margin")), "price_before_tax", ftext(base),
						"tax_factor", text(rate(rates, "tax_factor"))));
				if (productRuleType.equals("formula"))
					for (var item : list(detail.get("formula_costs")))
						traces.add(trace(s(item, "name"), s(item, "expression"), s(item, "amount")));
				if (!hasFixedSales || cost.signum() > 0)
					traces.add(trace("不含税成本", productRuleType.equals("formula") ? "自定义成本项目合计（每1报价单位）" : "本体、机构、电机、面板等按本行适用规则合计", text(cost)));
				traces.add(trace("不含税供货价", hasFixedSales ? "其余成本 ÷ (1 − 毛利率) + 外购已定售价" : "成本 ÷ (1 − 毛利率)", ftext(base)));
				if (yes(options.get("tax_included")))
					base = base.multiply(fraction(rates.get("tax_factor")));
				detail.put("price_after_tax", ftext(base));
			}
			else {
				if (!kind.equals("elbow")) throw new RuleException("该产品尚无已确认的成本计算规则，请维护成本资料后重新试算");
				// 消声弯头采用人工确认面积（或清单㎡数量）乘固定含税单价，避免从不完整尺寸推算错误面积。
				BigDecimal area;
				if (Set.of("㎡", "m2", "m²", "平方米").contains(s(row, "unit")))
					area = ONE;
				else if (Set.of("台", "个", "只", "件", "组", "套").contains(s(row, "unit")))
					area = required(a, "area_per_unit", "每个消声弯头人工计算面积/方量");
				else
					throw new RuleException("消声弯头请明确按个或㎡计价，避免面积重复乘数量");
				base = fraction(mul(area, rate(rates, "elbow_taxed_rate")));
				detail.putAll(obj("area_per_unit", text(area), "fixed_taxed_base", ftext(base),
						"fixed_taxed_rate", text(rate(rates, "elbow_taxed_rate")),
						"tax_factor", text(rate(rates, "tax_factor"))));
				if (!yes(options.get("tax_included")))
					base = base.divide(fraction(rates.get("tax_factor")));
				traces.add(trace("消声弯头固定供货价", "人工面积 × 200（含税）；不含税报价除税系数", ftext(base)));
			}
			// 圆阀已在完整成本中加加工费；圆形风口、消声器和静压箱仍沿用原售价系数。
			if (s(a, "shape").equals("round") && Set.of("louver", "silencer", "plenum").contains(kind)) {
				base = base.multiply(fraction(rates.get("round_factor")));
				detail.put("round_factor", text(rate(rates, "round_factor")));
			}
			var supplyAmount = base.multiply(fraction(qty));
			out.putAll(obj("supply_unit_unrounded", ftext(base), "supply_amount_unrounded", ftext(supplyAmount)));
			// 先将精确供货单价向上取整，再用整数单价×数量并向上取整行金额，不能把未取整单价直接乘数量。
			BigDecimal unit = base.ceil(), amount = ceil(mul(unit, qty));
			traces.add(trace("付款前供货价", "供货价已按本单材料税口径；付款系数在整单末尾应用", ftext(base)));
			if (yes(options.get("include_freight"))) {
				traces.add(trace("设备运费", "先按每台售价和地区系数试算，整单运费门槛判断后再决定计入单价或另列", "0"));
				traces.add(trace("设备原单价", "CEILING(供货单价)，设备运费在整单设备行计算完成后判断", text(unit)));
				traces.add(trace("设备原行金额", "CEILING(设备原单价 × 数量)", text(amount)));
			}
			else {
				traces.add(trace("设备运费", "本单选择不含运费", "0"));
				traces.add(trace("设备单价", "CEILING(供货单价)", text(unit)));
				traces.add(trace("设备行金额", "CEILING(设备单价 × 数量)", text(amount)));
			}
			// 仅保留本次设备指标的版本和来源概要，便于历史明细追溯计算依据。
			var rule = obj();
			for (String key : List.of("id", "kind", "family", "version"))
				rule.put(key, resource.get(key));
			var rb = obj();
			for (String key : List.of("name", "source", "effective_from"))
				rb.put(key, map(resource.get("body")).get(key));
			rule.put("body", rb);
			out.putAll(obj("status", "priced", "unit_price", text(unit), "amount", text(amount), "body_amount",
					text(amount), "billing_quantity", text(qty), "billing_unit", row.get("unit"), "detail", detail,
					"attributes", a, "equipment_kind", kind, "payment", term, "freight_per_unit", "0",
					"coverage", obj("installation", false, "freight", false), "tax_basis",
					yes(options.get("tax_included")) ? "included" : "excluded", "rule", rule));
		}
		catch (EquipmentFanSelector.SelectionException e) {
			out.put("reason", e.getMessage());
			QuoteProblem.attach(out, e);
			out.put("candidates",
					e.candidates.stream()
						.map(p -> obj("id", p.get("id"), "body", obj("name", p.get("model"), "attributes", p)))
						.toList());
		}
		catch (RuleException e) {
			out.put("reason", e.getMessage());
			QuoteProblem.attach(out, e);
		}
		catch (NullPointerException | ClassCastException | ArithmeticException | NoSuchElementException e) {
			out.put("reason", "设备规则或数据结构不完整，请核对指标");
		}
		return out;
	}

	/**
	 * 逐行生成按地区系数计算的设备运费试算方案。是否采用该方案，必须等风管、安装和付款系数都汇总后，按整单试算销售额判断。
	 */
	static Map<String, Object> equipmentFreight(Map<String, Object> options, Map<String, Object> catalog,
			List<Map<String, Object>> rows, int pendingRows, boolean hasEquipmentRows) {
		BigDecimal equipmentSubtotal = rows.stream()
			.filter(r -> s(r, "status").equals("priced"))
			.map(r -> n(r.get("amount")))
			.reduce(ZERO, QuoteData::add);
		if (!hasEquipmentRows)
			return obj("status", "not_applicable", "amount", "0", "reason", "本单没有设备产品", "basis", "none",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		if (!yes(options.get("include_freight")))
			return obj("status", "not_applicable", "amount", "0", "reason", "本单不含运费", "basis", "none",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		if (catalog == null)
			return obj("status", "pending", "amount", null, "reason", "设备指标无效，无法计算整单设备运费", "basis", "pending",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		String region = s(options, "region").replaceFirst("省$", "").replaceFirst("壮族自治区$", "");
		var regions = map(catalog.get("regions"));
		if (!regions.containsKey(region))
			return obj("status", "pending", "amount", null,
					"reason", "含运费报价仅适用贵州、四川、广西、云南、陕西、海南，请明确目的省区", "basis", "pending",
					"included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal));
		BigDecimal threshold = rate(map(catalog.get("rates")), "freight_threshold");
		if (pendingRows > 0)
			return obj("status", "pending", "amount", null, "reason", "设备材料尚有待确认项，无法计算整单设备运费",
					"basis", "pending", "included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal), "threshold", text(threshold));
		// 运费基于已经取整的设备原单价；地区倍率形成新单价，再乘数量形成新金额，与原金额差即试算运费。
		BigDecimal factor = n(regions.get(region)), calculatedFreight = ZERO;
		List<Map<String, Object>> adjusted = new ArrayList<>();
		for (var row : rows) {
			if (!s(row, "status").equals("priced"))
				continue;
			BigDecimal baseUnit = n(row.get("unit_price")), quantity = n(row.get("billing_quantity")),
					baseAmount = n(row.get("amount")), finalUnit = ceil(mul(baseUnit, factor)),
					finalAmount = ceil(mul(finalUnit, quantity)), rowFreight = finalAmount.subtract(baseAmount, MC);
			calculatedFreight = add(calculatedFreight, rowFreight);
			adjusted.add(obj("row", row, "base_unit", baseUnit, "base_amount", baseAmount, "final_unit", finalUnit,
					"final_amount", finalAmount, "freight_per_unit", finalUnit.subtract(baseUnit, MC), "freight_amount", rowFreight));
		}
		return obj("status", "trial", "amount", text(calculatedFreight), "reason", "设备运费已按地区系数试算，待整单销售额判断",
				"basis", "regional_per_unit_trial", "included_in_rows", false, "equipment_subtotal", text(equipmentSubtotal),
				"calculated_amount", text(calculatedFreight), "threshold", text(threshold), "region", region, "factor", text(factor),
				"_adjustments", adjusted);
	}

	/** 整单试算销售额达到门槛后，才把试算运费写入设备行。 */
	static void applyEquipmentFreightPlan(Map<String, Object> freight) {
		for (var values : list(freight.remove("_adjustments"))) {
			var row = map(values.get("row"));
			row.putAll(obj("base_unit_price", text(n(values.get("base_unit"))), "base_amount", text(n(values.get("base_amount"))),
					"unit_price", text(n(values.get("final_unit"))), "amount", text(n(values.get("final_amount"))), "body_amount",
					text(n(values.get("final_amount"))), "freight_per_unit", text(n(values.get("freight_per_unit"))), "freight_amount",
					text(n(values.get("freight_amount")))));
			map(row.get("coverage")).put("freight", true);
			map(row.get("detail")).putAll(obj("freight_basis", "regional_per_unit", "freight_factor", freight.get("factor")));
			list(row.get("trace")).add(trace("含运费单价", "CEILING(设备原单价 × 送货省区运费系数)", text(n(values.get("final_unit")))));
			list(row.get("trace")).add(trace("含运费行金额", "CEILING(含运费单价 × 数量)", text(n(values.get("final_amount")))));
		}
	}

	/**
	 * 未达到门槛或存在待确认项时删除暂存行调整，原设备单价保留；不能把试算运费提前写回。
	 *
	 */
	static void discardEquipmentFreightPlan(Map<String, Object> freight) {
		freight.remove("_adjustments");
	}

	/** 计算纯设备订单并汇总设备材料和整单设备运费，随后交给统一安装与付款边界处理。 */
	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		return calculate(quote, resources, policy, false);
	}

	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy, boolean supplyOnly) {
		if (!list(quote.get("rows")).isEmpty() && list(quote.get("rows")).stream()
				.allMatch(r -> yes(r.get("excluded")) || yes(r.get("installation_only"))))
			return InstallationPricingEngine.finalizeOrder(quote,
					InstallationPricingEngine.scopeOnlyResult(quote, policy, VERSION), resources, supplyOnly, LocalDate.now());
		List<String> issues = new ArrayList<>();
		// 整单必须只有一个有效设备指标，多个系列同时生效或指标不合法都不能任选其一。
		var active = EquipmentCatalog.active(resources, LocalDate.now());
		Map<String, Object> catalog = null, resource = null;
		if (active.size() == 1) {
			resource = active.get(0);
			try {
				EquipmentCatalog.validate(map(resource.get("body")));
				catalog = map(resource.get("body"));
			}
			catch (RuleException e) {
				issues.add("设备指标无效：" + e.getMessage());
			}
		}
		else
			issues.add("需要一个已发布且生效的设备指标版本");
		var options = new LinkedHashMap<>(map(quote.get("equipment_options")));
		options.put("include_installation", InstallationPricingEngine.enabled(quote));
		List<Map<String, Object>> rows = new ArrayList<>();
		var fanLookup = new EquipmentFanSelector.CostLookup(catalog);
		for (var row : list(quote.get("rows")))
			rows.add(calculateRow(row, catalog, options, resource, fanLookup));
		BigDecimal equipmentMaterialSubtotal = rows.stream()
			.filter(r -> s(r, "status").equals("priced"))
			.map(r -> n(r.get("amount")))
			.reduce(ZERO, QuoteData::add);
		int pending = (int) rows.stream().filter(r -> s(r, "status").equals("pending")).count();
		if (pending > 0)
			issues.add("有" + pending + "项设备待确认，未计入小计");
		if (rows.stream().noneMatch(r -> s(r, "status").equals("priced")))
			issues.add("没有已计算的设备项目");
		Object term = catalog == null ? null
				: list(catalog.get("payment_terms")).stream()
					.filter(t -> s(t, "code").equals(s(options, "payment_option")))
					.findFirst()
					.orElse(null);
		// 整组均有理由排除时不需要设备运费；正常计价行或未明确排除原因的行仍参与条件核验。
		boolean hasEquipmentRows = list(quote.get("rows")).stream()
			.anyMatch(r -> !yes(r.get("excluded")) && !yes(r.get("installation_only")));
		var freight = equipmentFreight(options, catalog, rows, pending, hasEquipmentRows);
		if (s(freight, "status").equals("pending"))
			issues.add("设备运费：" + s(freight, "reason"));
		BigDecimal subtotal = rows.stream()
			.filter(r -> s(r, "status").equals("priced"))
			.map(r -> n(r.get("amount")))
			.reduce(ZERO, QuoteData::add);
		BigDecimal extraFreight = yes(freight.get("included_in_rows")) || blank(freight.get("amount")) ? ZERO
				: n(freight.get("amount"));
		String total = issues.isEmpty() ? text(add(subtotal, extraFreight)) : null;
		if (!yes(policy.get("formal_template_confirmed")))
			issues.add("正式客户模板尚未确认");
		var result = obj("rows", rows, "subtotal", text(subtotal), "material_total", text(equipmentMaterialSubtotal),
				"equipment_material_total", text(equipmentMaterialSubtotal), "equipment_freight_total", freight.get("amount"), "total", total,
				"pending_count", pending, "issues", issues, "formal_ready", issues.isEmpty(), "fees",
				obj("equipment_freight", freight), "policy", policy, "payment", term,
				"accessory_items", new ArrayList<>(), "rounding_difference", "0", "engine_version", VERSION);
		return InstallationPricingEngine.finalizeOrder(quote, result, resources, supplyOnly, LocalDate.now());
	}

	/**
	 * 复合报价先按原清单行号拆成风管组和设备组，分别调用各自引擎，再按原顺序合并。
	 * 安装费和付款系数只在合并后的整单结果上处理一次。
	 */
	public Map<String, Object> mixed(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		var input = list(quote.get("rows"));
		List<Map<String, Object>> rows = new ArrayList<>(Collections.nCopies(input.size(), null));
		Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
		for (String label : List.of("duct", "equipment")) {
			List<Integer> indices = new ArrayList<>();
			for (int i = 0; i < input.size(); i++)
				if (s(input.get(i), "category").equals("风管") == label.equals("duct"))
					indices.add(i);
			if (indices.isEmpty())
				continue;
			if (indices.stream().allMatch(i -> yes(input.get(i).get("excluded")) || yes(input.get(i).get("installation_only")))) {
				for (int i : indices)
					rows.set(i, InstallationPricingEngine.materialScopeRow(input.get(i), InstallationPricingEngine.enabled(quote)));
				continue;
			}
			Map<String, Object> sub = new LinkedHashMap<>(quote);
			sub.put("rows", indices.stream().map(input::get).toList());
			var group = label.equals("duct")
					? new DuctPricingEngine().calculate(sub, resources, policy, LocalDate.now(), true)
					: calculate(sub, resources, policy, true);
			var groupRows = list(group.get("rows"));
			for (int j = 0; j < indices.size(); j++)
				rows.set(indices.get(j), groupRows.get(j));
			groups.put(label, group);
		}
		List<String> issues = new ArrayList<>();
		groups.values().forEach(g -> ((List<?>) g.get("issues")).forEach(i -> issues.add(str(i))));
		if (groups.isEmpty() && rows.stream().noneMatch(r -> s(r, "status").equals("installation_only")))
			issues.add("清单没有需要计价的产品行");
		int pending = (int) rows.stream().filter(r -> s(r, "status").equals("pending")).count();
		if (pending > groups.values().stream().mapToInt(g -> n(g.get("pending_count")).intValue()).sum())
			issues.add("有报价范围待确认，请核对不报价原因或只安装选项");
		if (groups.isEmpty() && !yes(policy.get("formal_template_confirmed")))
			issues.add("正式客户模板尚未确认");
		var dopt = map(quote.get("duct_options"));
		var eopt = map(quote.get("equipment_options"));
		// 只安装组不要求材料指标，但其明确选择的税口径仍须与唯一供货组一致。
		if (groups.size() == 1) {
			String supplyGroup = groups.keySet().iterator().next();
			var supplyOptions = supplyGroup.equals("duct") ? dopt : eopt;
			var installationOptions = supplyGroup.equals("duct") ? eopt : dopt;
			boolean otherInstallation = input.stream().anyMatch(r -> !yes(r.get("excluded")) && yes(r.get("installation_only"))
					&& s(r, "category").equals("风管") != supplyGroup.equals("duct"));
			if (otherInstallation && installationOptions.containsKey("tax_included")
					&& !Objects.equals(supplyOptions.get("tax_included"), installationOptions.get("tax_included")))
				issues.add("复合报价的供货与只安装项目含税口径需保持一致");
		}
		// 同单材料税口径和付款方式需一致，两个指标中的同付款方案系数也必须一致，防止合并结果采用不同条件。
		if (groups.containsKey("duct") && groups.containsKey("equipment")) {
			if (!Objects.equals(dopt.get("tax_included"), eopt.get("tax_included")))
				issues.add("复合报价的风管和设备含税口径需保持一致");
			if (!Objects.equals(dopt.get("payment_option"), eopt.get("payment_option")))
				issues.add("复合报价的风管和设备付款方式需保持一致");
			var dterm = map(groups.get("duct").get("payment"));
			var eterm = map(groups.get("equipment").get("payment"));
			if (!dterm.isEmpty() && !eterm.isEmpty()
					&& n(dterm.get("coefficient")).compareTo(n(eterm.get("coefficient"))) != 0)
				issues.add("风管与设备指标的整单付款系数不一致，请领导核对");
		}
		BigDecimal subtotal = groups.values().stream().map(g -> n(g.get("subtotal"))).reduce(ZERO, QuoteData::add);
		String total = issues.isEmpty() ? text(subtotal) : null;
		var duct = groups.getOrDefault("duct", obj());
		var equipment = groups.getOrDefault("equipment", obj());
		var fees = new LinkedHashMap<>(map(duct.get("fees")));
		if (!equipment.isEmpty() && map(equipment.get("fees")).containsKey("equipment_freight"))
			fees.put("equipment_freight", map(equipment.get("fees")).get("equipment_freight"));
		BigDecimal materialTotal = add(n(duct.getOrDefault("subtotal", "0")),
				n(equipment.getOrDefault("equipment_material_total", "0")));
		var result = obj("rows", rows, "subtotal", text(subtotal), "material_total", text(materialTotal), "total", total, "pending_count",
				pending, "issues", issues,
				"formal_ready", issues.isEmpty(), "fees", fees, "policy", policy, "payment",
				groups.getOrDefault("equipment", duct).get("payment"), "accessory_items",
				duct.getOrDefault("accessory_items", new ArrayList<>()), "aggregate_flanges",
				duct.getOrDefault("aggregate_flanges", new ArrayList<>()), "equipment_material_total",
				equipment.getOrDefault("subtotal", "0"), "equipment_freight_total",
				map(equipment.get("fees")).containsKey("equipment_freight")
						? map(map(equipment.get("fees")).get("equipment_freight")).get("amount") : "0",
				"duct_result", duct, "engine_version",
				MIXED_VERSION, "duct_engine_version", DuctPricingEngine.VERSION);
		return InstallationPricingEngine.finalizeOrder(quote, result, resources, false, LocalDate.now());
	}

}
