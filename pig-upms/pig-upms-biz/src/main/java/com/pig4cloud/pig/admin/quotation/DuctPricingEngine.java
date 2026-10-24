package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 风管材料报价引擎。
 * <p>
 * 按“行参数识别 → 材料与加工成本 → 法兰及附件 → 面积换算、运费和税费 → 行金额 → 整单汇总”的顺序计算。
 * 规则和单价来自已发布的风管指标，无法唯一确定的材质、厚度、法兰或计价口径会保留为待确认，不会自行猜测。
 * 付款系数不在单行内重复计算，而是在材料、设备运费和安装费汇总后由安装引擎统一应用一次。
 */
public final class DuctPricingEngine {

	// 引擎规则版本随计算行为变化递增，历史报价可据此识别是否需要重新试算。
	public static final String VERSION = "duct-v33";

	// 含安装报价沿用的施工范围说明，会随计算结果进入页面或导出备注。
	public static final List<String> INSTALLATION_NOTES = List.of("不含风机控制箱及控制箱到风机的电线、电器。", "不含风机基础预制的制作；不含抗震支架。",
			"不含设备及管道预留洞孔的开启与修复。");

	// 直管、异形件和半成品不区分分别匹配不同加工费。
	private static final Set<String> SHAPES = Set.of("straight", "irregular", "semi_unspecified");

	// 名称与规格用于风管材料和结构识别；成品状态另会参考备注和原始文字。
	private static String description(Map<String, Object> row) {
		return s(row, "name") + " " + s(row, "spec");
	}

	// 把中文参数选项转换为内部代号，已是内部代号的值保持原样。
	private static String alias(Object value, Map<String, String> aliases) {
		return aliases.getOrDefault(str(value), str(value));
	}

	private static Matcher match(String pattern, String text) {
		return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
	}

	// 使用指标中的指定公式计算价格，所有变量来自已确认费率和当前风管参数。
	private static BigDecimal eval(Map<String, Object> expressions, String key, Map<String, Object> env) {
		return DecimalFormula.evaluate(s(expressions, key), env);
	}

	// 复制费率或付款配置再补充行变量，避免计算一行时修改共享指标。
	private static Map<String, Object> env(Map<String, Object> source, Object... pairs) {
		Map<String, Object> e = new LinkedHashMap<>(source);
		e.putAll(obj(pairs));
		return e;
	}

	/** 行内已确认属性优先，其次解析名称和规格；只有镀锌层级缺失时才使用整单默认值。 */
	static String material(Map<String, Object> row, Map<String, Object> options) {
		String explicit = alias(attr(row, "material"), Map.of("镀锌板", "galvanized", "40g镀锌板", "galvanized_40", "80g镀锌板",
				"galvanized_80", "201不锈钢", "stainless_201", "304不锈钢", "stainless_304"));
		if (DuctCatalog.MATERIALS.contains(explicit))
			return explicit;
		String source = explicit.isEmpty() ? description(row) : explicit;
		if (source.contains("不锈钢")) {
			if (source.contains("201"))
				return "stainless_201";
			if (source.contains("304"))
				return "stainless_304";
			throw new RuleException("待确认：请选择201或304不锈钢");
		}
		if (source.contains("镀锌") || explicit.equals("galvanized")) {
			if (match("80\\s*g", source).find())
				return "galvanized_80";
			if (match("40\\s*g", source).find())
				return "galvanized_40";
			String layer = blank(options.get("zinc_layer")) ? "40" : s(options, "zinc_layer");
			if (!Set.of("40", "80").contains(layer))
				throw new RuleException("待确认：请选择40g或80g锌层");
			return "galvanized_" + layer;
		}
		if (!explicit.isBlank())
			throw new RuleException(new QuoteProblem("duct_material_unsupported", "rule", "风管材质已明确为“" + explicit + "”，现有供货计价规则不支持该材质",
					"维护对应材质的供货规则，或向供应商询价后人工报价", List.of()));
		throw new RuleException("待确认：请明确风管材质");
	}

	/** 厚度必须得到唯一值；规格中出现多个候选厚度时交由报价人员确认。 */
	static BigDecimal thickness(Map<String, Object> row) {
		if (DimensionText.ranged("thickness", s(row, "name") + " " + s(row, "spec")))
			throw new RuleException("待确认：风管厚度仅给出范围，请填写实际毫米厚度，不能将范围上限直接计价");
		if (!blank(attr(row, "thickness")))
			return n(DimensionText.column(str(attr(row, "thickness")), ""));
		Set<BigDecimal> labeled = DimensionText.thicknesses(s(row, "spec") + " " + s(row, "name") + " " + s(row, "source_text"));
		if (labeled.size() == 1) return labeled.iterator().next();
		if (labeled.size() > 1) throw new RuleException("待确认：风管厚度存在多个不同数值，请核对毫米厚度");
		var unitValues = match("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(" + DimensionText.LENGTH_UNIT + ")(?![A-Z])", s(row, "spec"));
		Set<BigDecimal> converted = new TreeSet<>();
		while (unitValues.find())
			if (!sectionDimension(s(row,"spec"),unitValues.start(),unitValues.end()))
				converted.add(n(DimensionText.scalar(unitValues.group(1) + unitValues.group(2))));
		if (converted.size() == 1) return converted.iterator().next();
		if (!converted.isEmpty()) throw new RuleException("待确认：请明确风管厚度(mm)，不能从多个带单位数值中选取厚度");
		String thicknessText = s(row,"spec") + " " + s(row,"name");
		Matcher m = match("(?<!\\d)(0\\.5|0\\.6|0\\.75|1\\.0|1\\.2|1\\.5|2\\.0|1|2)\\s*(?:mm|毫米|厚)?(?![\\dA-Zµμ]|英寸|英尺|微米|纳米|公分|公厘|公尺|分米|厘米|米)",thicknessText);
		Set<BigDecimal> values = new TreeSet<>();
		while (m.find())
			if (!sectionDimension(thicknessText,m.start(),m.end())) values.add(n(m.group(1)));
		Matcher source = match("(?:厚度|板厚|厚)\\s*[:：=＝]?\\s*(0\\.5|0\\.6|0\\.75|1\\.0|1\\.2|1\\.5|2\\.0|1|2)\\s*(?:mm|毫米)?",
				s(row, "source_text"));
		while (source.find())
			values.add(n(source.group(1)));
		if (values.size() == 1)
			return values.iterator().next();
		if (values.size() > 1) throw new RuleException("待确认：风管厚度存在多个不同数值，请核对毫米厚度");
		throw new RuleException("待确认：请明确风管厚度(mm)");
	}

	private static boolean inferredThickness(Map<String, Object> row) {
		try { thickness(row); return false; }
		catch (RuleException missing) { return true; }
	}

	/** 明确板厚优先；只有真正缺失时按已确认的压力表补充，不掩盖范围或冲突。 */
	static BigDecimal thickness(Map<String, Object> row, Map<String, Object> options) {
		try { return thickness(row); }
		catch (RuleException e) {
			if (!e.getMessage().equals("待确认：请明确风管厚度(mm)")) throw e;
		}
		String text = description(row);
		if (match("(?:板材厚度|板厚|厚度|厚|[δΔ])", text + " " + s(row, "source_text")).find())
			throw new RuleException("待确认：原文已描述板厚但未提供有效数值，请核对实际毫米厚度，不能按尺寸覆盖");
		if (!DimensionText.normalize(text).issue().isEmpty() || DimensionText.ranged("width", text) || DimensionText.ranged("diameter", text))
			throw new RuleException("待确认：风管尺寸存在范围、冲突或单位不明，不能自动确定板厚");
		BigDecimal size;
		if (crossSection(row).equals("circular")) size = diameter(row);
		else {
			BigDecimal[] dimensions = dimensions(row);
			if (dimensions != null) size = dimensions[0].max(dimensions[1]);
			else {
				size = longSide(row);
				if (size == null) throw new RuleException("待确认：缺少风管板厚及明确长边或直径，无法按压力表补充");
			}
		}
		boolean high = text.contains("排烟") || flange(row, options).equals("angle");
		int[] limits = {320, 450, 630, 1000, 1550, 2000, 4000};
		String[] values = high ? new String[] {"0.75", "0.75", "1", "1", "1.2", "1.5", "1.5"}
				: new String[] {"0.5", "0.6", "0.75", "0.75", "1", "1.2", "1.2"};
		for (int i = 0; i < limits.length; i++) if (size.compareTo(n(limits[i])) <= 0) return n(values[i]);
		throw new RuleException("待确认：风管长边或直径超过4000mm，请明确实际板厚");
	}

	/** 只有明确的长边标注才能单独选厚度和角钢号，不补造另一边参与窄管加工费。 */
	private static BigDecimal longSide(Map<String, Object> row) {
		String normalized = DimensionText.typography(description(row));
		if (DimensionText.ranged("width", normalized)) throw new RuleException("待确认：风管长边为范围值，请明确实际毫米长边");
		Set<BigDecimal> longSides = new TreeSet<>();
		Matcher side = match("长边\\s*[:：=＝]?\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*((?:[A-Z]+|毫米|厘米|米|㎜|㎝|英寸|英尺|微米|纳米|公分|公厘|公尺|分米|千米|公里|尺|寸)(?:[²³23]|/\\w+|\\^\\d+)?)?", normalized);
		while (side.find()) {
			if (normalized.substring(side.end()).stripLeading().matches("(?s)^(?:[~～至到±－–—/／-]\\s*[+-]?\\d|[.,，]\\d).*"))
				throw new RuleException("待确认：风管长边存在范围或不完整数字，请核对实际规格");
			String value = DimensionText.scalar(side.group(1) + str(side.group(2)));
			if (value == null) throw new RuleException("待确认：风管长边数值或单位无效，请填写唯一明确的毫米长边");
			longSides.add(positive(value, "风管长边"));
		}
		if (longSides.size() > 1) throw new RuleException("待确认：风管长边存在多个不同数值，请核对实际规格");
		return longSides.isEmpty() ? null : longSides.iterator().next();
	}

	/** 仅生成计算副本，不把面积、地区推断写回客户参数。 */
	static Map<String, Object> defaults(Map<String, Object> row, Map<String, Object> options) {
		var copy = new LinkedHashMap<>(row);
		var attributes = new LinkedHashMap<>(map(row.get("attributes")));
		Set<String> inferred = new HashSet<>();
		copy.put("attributes", attributes);
		if (s(options, "project_region").isEmpty() || yes(row.get("excluded")) || yes(row.get("installation_only"))
				|| !s(row, "category").equals("风管") && !s(row, "name").contains("风管")) return copy;
		String material;
		try { material = flangeMaterial(row); }
		catch (RuleException ignored) { return copy; }
		boolean explicitMaterial = explicitFlangeMaterial(row);
		if (!explicitMaterial) {
			material = s(options, "area_basis").equals("open") && s(options, "project_region").equals("贵州") ? "black" : "hot_galvanized";
			attributes.put("flange_material", material);
			inferred.add("flange_material");
		}
		if (!DuctRegionalPrices.purchased(material) && blank(attr(row, "flange_grade")) && blank(attr(row, "flange_weight"))
				&& !match("\\d+(?:\\.\\d+)?\\s*档", description(row)).find()) {
			try {
				int size = flangeSize(row, blank(attr(row, "flange_size")) ? thickness(row, options) : ZERO);
				if (size == 30 || size == 40) {
					boolean net = s(options, "area_basis").equals("net");
					String value = material.equals("black") ? size == 30 ? "6.5" : "11.5"
							: net ? size == 30 ? "8.5" : "14.5" : size == 30 ? "7.5" : "12.5";
					attributes.put("flange_grade", value);
					inferred.add("flange_grade");
				}
			}
			catch (RuleException ignored) { /* 本行计价提供具体缺参提示。 */ }
		}
		copy.put("_duct_default_fields", inferred);
		return copy;
	}

	/** 截面、周长及其区间边界不是板厚；裸带单位板厚仍保留原有读取方式。 */
	private static boolean sectionDimension(String text,int start,int end) {
		String before=text.substring(0,start).stripTrailing(), after=text.substring(end).stripLeading();
		String label="(?:周长|长边|短边|边长|直径|口径|长度|宽度|高度|长|宽|高|[ΦφØøФф∅⌀]|(?<![A-Z])(?:A\\s*\\+\\s*B|A|B|DN|D|L|W|H))";
		return before.matches("(?is).*"+label+"\\s*[（(]?\\s*(?:[:：=<>＜＞≤≥]|不大于|不小于|不超过|不少于|大于|小于|超过)*\\s*$")
				|| before.matches("(?s).*[×xX*＊]\\s*$") || after.matches("(?s)^[×xX*＊].*")
				|| after.matches("(?is)^[<>＜＞≤≥]\\s*"+label+".*")
				|| after.matches("(?s)^(?:以下|以上|以内|以外|[~～至到±]\\s*\\d).*")
				|| before.matches("(?s).*\\d\\s*[~～至到±]\\s*$");
	}

	/**
	 * 管件类型、法兰形式和成品状态均按“行内确认值 → 行文字 → 整单默认值”的优先级读取。
	 */
	static String shape(Map<String, Object> row, Map<String, Object> options) {
		Map<String, String> aliases = Map.of("直管", "straight", "异形件", "irregular", "不区分", "semi_unspecified", "半成品不区分",
				"semi_unspecified");
		String explicit = alias(attr(row, "shape"), aliases), desc = description(row);
		if (explicit.isEmpty())
			explicit = desc.contains("直管") && !desc.contains("异形") ? "straight"
					: desc.contains("异形") && !desc.contains("直管") ? "irregular" : "";
		if (SHAPES.contains(explicit))
			return explicit;
		String selected = alias(options.get("shape"), aliases);
		return SHAPES.contains(selected) ? selected : "semi_unspecified";
	}

	/**
	 * 依次读取行属性、名称/规格和整单法兰设置；混合法兰要求逐行明确，不能直接套一个默认形式。
	 *
	 */
	static String flange(Map<String, Object> row, Map<String, Object> options) {
		String value = alias(attr(row, "flange"), Map.of("共板法兰", "common", "共板", "common", "角钢法兰", "angle", "角钢",
				"angle", "角铁法兰", "angle", "角铁", "angle", "圆法兰", "round", "圆形法兰", "round"));
		// “法兰连接”只描述连接方式，不能覆盖明确的本行描述或整单默认；其他未知形式仍需确认。
		if (Set.of("法兰", "法兰连接", "法兰连接形式", "法兰连接方式").contains(value.replaceAll("\\s+", "")))
			value = "";
		String desc = description(row);
		if (value.isEmpty())
			value = desc.contains("圆法兰") ? "round"
					: desc.contains("角钢法兰") || desc.contains("角铁法兰") ? "angle" : desc.contains("共板法兰") ? "common" : s(options, "flange");
		if (value.equals("mixed"))
			throw new RuleException("待确认：混合法兰模式下请明确本行法兰形式");
		if (value.isBlank() && crossSection(row).equals("circular")) return "round";
		if (!Set.of("common", "angle", "round").contains(value))
			throw new RuleException("待确认：请选择共板、角钢或圆法兰");
		return value;
	}

	/**
	 * 确认半成品或成品，先读行属性，再读名称、规格、备注及原始文字，最后读整单默认值。
	 * 成品状态决定是否增加合口费，安装引擎还会据此要求确认成品安装单价。
	 *
	 */
	static String state(Map<String, Object> row, Map<String, Object> options) {
		String value = alias(attr(row, "product_state"), Map.of("半成品", "semi", "成品", "finished"));
		String desc = description(row) + " " + s(row, "remark") + " " + s(row, "source_text");
		if (value.isEmpty())
			value = desc.contains("半成品") ? "semi" : desc.contains("成品") ? "finished" : s(options, "product_state");
		if (!Set.of("semi", "finished").contains(value))
			throw new RuleException("待确认：请选择半成品或成品");
		return value;
	}

	// 读取矩形宽高，单位 mm；尺寸用于窄管附加加工费和角钢规格判断，不用于重新推算总工程量。
	static BigDecimal[] dimensions(Map<String, Object> row) {
		if (s(map(row.get("attributes")), "duct_cross_section").equals("circular")) return null;
		String raw = s(row, "spec") + " " + str(attr(row, "model")) + " " + s(row, "name");
		// 30×30等紧随法兰名称的是角钢型号，不是第二组风管截面。
		raw = raw.replaceAll("(?i)((?:角铁|角钢)?法兰)\\s*(30|40|50)\\s*[×xX*＊]\\s*\\2(?![\\d.])", "$1 $2号")
			.replaceAll("(?i)(?<![\\d.])(30|40|50)\\s*[×xX*＊]\\s*\\1\\s*(?=(?:热镀锌|黑|201|304|不锈钢|角铁|角钢)*法兰)", "$1号 ");
		String issue = AiInputReview.geometryIssue(raw);
		if (!issue.isBlank()) throw new RuleException("待确认：" + issue);
		if (match("[×xX*＊]\\s*[-−]\\s*\\d", DimensionText.typography(raw)).find())
			throw new RuleException("待确认：风管截面尺寸含负数，请填写实际毫米尺寸");
		BigDecimal width = blank(attr(row, "width")) ? null : positive(DimensionText.column(str(attr(row, "width")), ""), "宽"),
				height = blank(attr(row, "height")) ? null : positive(DimensionText.column(str(attr(row, "height")), ""), "高");
		if (!blank(attr(row, "width")) && !blank(attr(row, "height")))
			return new BigDecimal[] { width, height };
		Matcher m = match("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*[x×*]\\s*(\\d+(?:\\.\\d+)?)(?![\\d.])",
				AiInputReview.normalizeDimensionUnits(raw));
		return m.find() ? new BigDecimal[] { width == null ? positive(m.group(1), "宽") : width,
				height == null ? positive(m.group(2), "高") : height } : null;
	}

	// 外购不锈钢法兰和热镀锌角钢需有明确依据，不把风管板材的201/304钢号直接当作法兰材质。
	static String flangeMaterial(Map<String, Object> row) {
		String explicit = alias(str(attr(row, "flange_material")).replace("角钢法兰", "角铁法兰"),
				Map.of("黑角铁", "black", "黑角钢", "black", "热镀锌角铁", "hot_galvanized", "热镀锌角钢", "hot_galvanized",
					"201不锈钢", "stainless_201", "304不锈钢", "stainless_304",
					"201不锈钢角铁法兰", "stainless_201", "304不锈钢角铁法兰", "stainless_304",
					"黑角铁法兰", "black", "热镀锌角铁法兰", "hot_galvanized"));
		if (Set.of("black", "hot_galvanized", "stainless_201", "stainless_304").contains(explicit))
			return explicit;
		if (!explicit.isEmpty()) throw new RuleException("待确认：角钢法兰材质未识别，请选择黑角铁、热镀锌或201/304不锈钢");
		String desc = description(row);
		Set<String> materials = new HashSet<>();
		Matcher stainless = match("(?<!\\d)(201|304)\\s*(?:不锈钢\\s*)?(?:(?:30|40|50)\\s*[#＃]?\\s*)?(?:角钢|角铁|法兰)|不锈钢\\s*(201|304)\\s*(?:(?:30|40|50)\\s*)?(?:角钢|角铁|法兰)", desc);
		while (stainless.find()) materials.add("stainless_" + (stainless.group(1) == null ? stainless.group(2) : stainless.group(1)));
		if (desc.matches("(?s).*?(?:201\\s*[/／或、和与及]\\s*304|304\\s*[/／或、和与及]\\s*201).*?不锈钢.*?(?:角铁|角钢|法兰).*"))
			throw new RuleException("待确认：不锈钢法兰钢号存在备选，请明确201或304");
		if (desc.contains("热镀锌角")) materials.add("hot_galvanized");
		if (desc.contains("黑角铁") || desc.contains("黑角钢")) materials.add("black");
		if (materials.size() > 1) throw new RuleException("待确认：法兰材质存在冲突，请明确本行法兰材质");
		if (!materials.isEmpty()) return materials.iterator().next();
		if (desc.matches("(?s).*不锈钢\\s*(?:(?:30|40|50)\\s*)?(?:角铁|角钢|法兰).*"))
			throw new RuleException("待确认：不锈钢法兰请明确201或304，不能按黑角铁计价");
		return "black";
	}

	/**
	 * 人工或文字明确的 30/40/50 规格优先；默认按最大边及板厚判断。
	 * 最大边 ≥1600mm 用 40，0.5～1.2mm 用 30，1.5/2mm 用 40；其他板厚需确认。
	 *
	 */
	static int flangeSize(Map<String, Object> row, BigDecimal thickness) {
		if (!blank(attr(row, "flange_size"))) {
			String value = str(attr(row, "flange_size")).replace("角钢", "").trim();
			value = value.replaceAll("(?i)^(30|40|50)\\s*[x×*]\\s*\\1\\s*(?:mm|毫米)?$", "$1");
			if (!Set.of("30", "40", "50").contains(value))
				throw new RuleException("待确认：角钢规格必须是30、40或50");
			return Integer.parseInt(value);
		}
		Matcher m = match("(?<!\\d)(30|40|50)\\s*[#＃]?\\s*(?:角钢|角铁|法兰)", description(row));
		if (m.find())
			return Integer.parseInt(m.group(1));
		Matcher pair = match("(?:角钢|角铁|法兰)\\s*(30|40|50)\\s*[x×*]\\s*\\1(?!\\d)", description(row));
		if (pair.find()) return Integer.parseInt(pair.group(1));
		Matcher standalone = match("^\\s*(30|40|50)\\s*[x×*]\\s*\\1\\s*(?:mm|毫米)?\\s*$", s(row, "spec"));
		if (!s(row, "name").contains("风管") && standalone.find()) return Integer.parseInt(standalone.group(1));
		BigDecimal[] size = dimensions(row);
		// 已有明确板厚时，清单常用的长边区间不代表唯一尺寸，继续按板厚默认选型，不截取区间端点。
		BigDecimal max = crossSection(row).equals("circular") ? diameter(row) : size == null
				? DimensionText.ranged("width", description(row)) ? null : longSide(row) : size[0].max(size[1]);
		if (max != null && max.compareTo(n("1600")) >= 0)
			return 40;
		if (Set.of("0.5", "0.6", "0.75", "1", "1.2").contains(thickness.stripTrailingZeros().toPlainString()))
			return 30;
		if (Set.of("1.5", "2").contains(thickness.stripTrailingZeros().toPlainString()))
			return 40;
		throw new RuleException("待确认：此厚度没有默认角钢规格");
	}

	/**
	 * 读取角钢计算档位，兼容旧 flange_weight 字段；30 和 40 分别默认 7.5、12.5，50 必须人工明确。
	 * 档位进入指标公式，不能直接当作法兰每米价格。
	 *
	 */
	static BigDecimal grade(Map<String, Object> row, int size) {
		Object explicit = blank(attr(row, "flange_grade")) ? attr(row, "flange_weight") : attr(row, "flange_grade");
		if (!blank(explicit))
			return positive(explicit, "角钢法兰计算档位");
		Matcher m = match("(?<!\\d)(\\d+(?:\\.\\d+)?)\\s*档", description(row));
		if (m.find())
			return n(m.group(1));
		if (size == 30)
			return n("7.5");
		if (size == 40)
			return n("12.5");
		throw new RuleException("待确认：50角钢必须明确计算档位");
	}

	private static String flangeKey(Map<String, Object> row, int size) {
		String material = flangeMaterial(row);
		return material + ":" + size + ":" + (DuctRegionalPrices.purchased(material) ? "fixed" : text(grade(row, size)));
	}

	private static Map<String, Object> flangeInputDefaults(Map<String, Object> input, int size, Map<String, Object> options) {
		var copy = new LinkedHashMap<>(input);
		var attributes = new LinkedHashMap<>(map(input.get("attributes")));
		attributes.putIfAbsent("flange_size", size);
		copy.putAll(obj("category", "风管", "attributes", attributes));
		return defaults(copy, options);
	}

	private static boolean explicitFlangeMaterial(Map<String, Object> row) {
		boolean inferred = row.get("_duct_default_fields") instanceof Set<?> fields && fields.contains("flange_material");
		return !inferred && (!blank(attr(row, "flange_material")) || !flangeMaterial(row).equals("black"))
				|| description(row).matches("(?s).*(?:不锈钢\\s*(?:(?:201|304|30|40|50)\\s*)?(?:角铁|角钢|法兰)|热镀锌角|黑角铁|黑角钢).*");
	}

	private static boolean explicitFlangeGrade(Map<String, Object> row) {
		boolean inferred = row.get("_duct_default_fields") instanceof Set<?> fields && fields.contains("flange_grade");
		return !blank(attr(row, "flange_weight")) || !inferred && !blank(attr(row, "flange_grade"))
				|| match("\\d+(?:\\.\\d+)?\\s*档", description(row)).find();
	}

	/** 截面只管理尺寸口径，与直管/异形件加工和连接形式独立。 */
	static String crossSection(Map<String, Object> row) {
		String explicit = alias(attr(row, "duct_cross_section"), Map.of("矩形", "rectangular", "圆形", "circular"));
		if (Set.of("rectangular", "circular").contains(explicit)) return explicit;
		String desc = AiInputReview.normalizeDimensionUnits(description(row));
		if (desc.contains("圆形") || desc.contains("圆型") || desc.contains("螺旋")
				|| match("(?:[ΦφØøФф∅⌀]|DN|直径\\s*[:：=]?)\\s*\\d", desc).find()) return "circular";
		if (desc.contains("矩形") || desc.contains("方形")) return "rectangular";
		// 旧录入界面可能保留隐藏直径，客户明确矩形规格不能因此被改按圆管报价。
		if (dimensions(row) != null) return "rectangular";
		if (!blank(attr(row, "diameter")) || s(map(row.get("attributes")), "flange").equals("round")) return "circular";
		return "";
	}

	private static boolean circular(Map<String, Object> row, Map<String, Object> options) {
		if (s(map(row.get("attributes")), "duct_cross_section").equals("rectangular")) return false;
		return crossSection(row).equals("circular") || flange(row, options).equals("round");
	}

	static BigDecimal diameter(Map<String, Object> row) {
		if (!blank(attr(row, "diameter"))) return positive(DimensionText.column(str(attr(row, "diameter")), ""), "圆风管直径");
		String text = s(row, "spec") + " " + str(attr(row, "model")) + " " + s(row, "name");
		if (!DimensionText.normalize(text).issue().isEmpty() || DimensionText.ranged("diameter", text)) throw new RuleException("圆风管直径存在范围、冲突或单位不明，请填写唯一毫米值");
		Matcher m = match("(?:[ΦφØøФф∅⌀]|DN|直径\\s*[:：=]?)\\s*(\\d+(?:\\.\\d+)?)",
				DimensionText.normalize(text).text());
		Set<BigDecimal> values = new TreeSet<>();
		while (m.find()) values.add(positive(m.group(1), "圆风管直径"));
		if (values.isEmpty()) throw new RuleException("客户清单未提供圆风管直径，请报价人员填写");
		if (values.size() > 1) throw new RuleException("圆风管直径存在多个不同数值，请核对实际规格");
		return values.iterator().next();
	}

	// 法兰价必须同时唯一命中材质、规格和数值相等的档位，缺价或重复都转为待确认。
	private static Map<String, Object> selectFlange(Map<String, Object> catalog, String material, int size,
			BigDecimal grade) {
		if (material.equals("black") && !DuctRegionalPrices.blackAvailable(catalog, s(catalog, "price_region")))
			throw new RuleException("待确认：" + s(catalog, "price_region") + "不提供黑角铁法兰，请核对实际法兰材质；不能借用贵州价格");
		if (DuctRegionalPrices.purchased(material)) {
			var matches = list(catalog.get("purchased_flanges")).stream()
				.filter(f -> s(f, "material").equals(material) && n(f.get("size")).compareTo(n(size)) == 0).toList();
			if (matches.size() != 1) throw new RuleException("待确认：外购不锈钢法兰材质、规格未配置或重复，请核对" + material + " " + size + "#");
			var selected = matches.get(0);
			if (blank(selected.get("price_per_m"))) throw new RuleException("待确认：" + s(catalog, "price_region") + " " + s(selected, "name") + "外购成品每米价格未配置");
			positive(selected.get("price_per_m"), "外购法兰每米价格");
			return selected;
		}
		List<Map<String, Object>> matches = list(catalog.get("flanges")).stream()
			.filter(f -> s(f, "material").equals(material) && n(f.get("size")).compareTo(n(size)) == 0
					&& n(f.getOrDefault("grade", f.get("kg_per_bar"))).compareTo(grade) == 0)
			.toList();
		if (matches.size() != 1)
			throw new RuleException("角钢法兰价格缺失或档位冲突，请核对材质、规格和计算档位：" + s(catalog, "price_region") + " " + material + " " + size + "# " + text(grade) + "档");
		DuctRegionalPrices.requirePrice(matches.get(0));
		return matches.get(0);
	}

	/**
	 * 计算角钢每米综合价；基准公式按档位、元/吨价格、6 米/根和每米加工费换算，具体算式以发布指标为准。
	 *
	 */
	private static BigDecimal flangePrice(Map<String, Object> f, Map<String, Object> catalog) {
		if (s(f, "pricing_mode").equals("fixed")) return positive(f.get("price_per_m"), "外购法兰成品每米价");
		return DecimalFormula.evaluate(flangeMeterFormula(catalog),
				env(map(catalog.get("rates")), "flange_grade", f.getOrDefault("grade", f.get("kg_per_bar")),
						"ton_price", f.get("ton_price"), "flange_processing", f.get("processing_per_m")));
	}

	/** 只兼容已知标准模板；不通过包含0.92等片段推测自定义公式的折减口径。 */
	private static String flangeMeterFormula(Map<String, Object> catalog) {
		String formula = s(map(catalog.get("expressions")), "flange_per_meter"), normalized = formula.replaceAll("\\s+", "");
		// 只去掉完整包住表达式的括号，不能将除数(1000/6)等有运算意义的括号删除。
		while (normalized.startsWith("(") && normalized.endsWith(")")) {
			int depth = 0, closing = -1;
			for (int i = 0; i < normalized.length(); i++) {
				if (normalized.charAt(i) == '(') depth++;
				else if (normalized.charAt(i) == ')' && --depth == 0) { closing = i; break; }
			}
			if (closing != normalized.length() - 1) break;
			normalized = normalized.substring(1, normalized.length() - 1);
		}
		if (normalized.equals("flange_grade*ton_price/1000/6+flange_processing"))
			return formula.replaceAll("\\bton_price\\b", "(ton_price * 0.92)");
		if (normalized.equals("flange_grade*flange_weight_factor*ton_price/1000/6+flange_processing")) {
			Object factor = map(catalog.get("rates")).get("flange_weight_factor");
			if (blank(factor) || n(factor).compareTo(n("0.92")) != 0)
				throw new RuleException("旧式角钢法兰公式的材料折减系数须明确为0.92，请核对指标");
			return formula;
		}
		throw new RuleException("角钢法兰每米公式不是已确认的标准旧、新模板，请核对材料折减口径");
	}

	// 整单付款方案必须对应是否安装；这里只读取系数，行金额尚不乘入。
	private static Map<String, Object> payment(Map<String, Object> options, Map<String, Object> catalog) {
		String code = s(options, "payment_option");
		if (code.isEmpty())
			throw new RuleException("待确认：请选择整单付款方式");
		Map<String, Object> selected = DuctCatalog.paymentTerms(catalog)
			.stream()
			.filter(t -> s(t, "code").equals(code))
			.findFirst()
			.orElseThrow(() -> new RuleException("待确认：付款方案不存在或配置冲突"));
		if (!s(selected, "mode").equals(yes(options.get("include_installation")) ? "with_install" : "no_install"))
			throw new RuleException("待确认：安装状态已变化，请重新选择对应的整单付款方式");
		return env(selected, "coefficient", positive(selected.get("coefficient"), "付款系数"));
	}

	/**
	 * 法兰或附件先按面积及税费系数形成单价并四舍五入到 1 位，再乘实际米数/数量并保留 1 位。
	 * 含税金额和未税金额分别计算，差额供明细显示。
	 *
	 */
	private static Map<String, Object> pricedItem(BigDecimal base, BigDecimal quantity, BigDecimal areaFactor,
			BigDecimal taxFactor) {
		BigDecimal untaxedUnit = round(mul(base, areaFactor)), unit = round(mul(base, areaFactor, taxFactor));
		BigDecimal untaxedAmount = round(mul(untaxedUnit, quantity)), amount = round(mul(unit, quantity));
		return obj("base_unit_price", text(base), "untaxed_unit_price", text(untaxedUnit), "unit_price", text(unit),
				"area_factor", text(areaFactor), "tax_factor", text(taxFactor),
				"untaxed_amount", text(untaxedAmount), "amount", text(amount), "tax_amount",
				text(amount.subtract(untaxedAmount, MC)));
	}

	// 费用展示金额统一保留 1 位小数；null 保持待确认，不能替换成零。
	private static Map<String, Object> fee(BigDecimal amount, String status, String reason) {
		return obj("amount", text(amount == null ? null : round(amount)), "status", status, "reason", reason);
	}

	// 保留公式与阶段结果，供内部计算明细核对，不额外增加报价金额。
	private static Map<String, Object> trace(String step, String expression, BigDecimal value) {
		return obj("step", step, "expression", expression, "value", text(value));
	}

	// 净面积分摊只区分镀锌和不锈钢两类，具体锌层或钢号仍在材料查价时分别匹配。
	private static String materialFamily(String material) {
		return material.startsWith("stainless") ? "stainless" : "galvanized";
	}

	/**
	 * 把整单录入的法兰米数或附件数量换算到当前面积口径。净面积报价同时包含镀锌和不锈钢时系数不唯一，必须拆分确认。
	 */
	private static BigDecimal wholeQuoteFactor(Map<String, Object> rates, String areaBasis, Set<String> families,
			String galvanizedRate, String label) {
		if (families.isEmpty())
			throw new RuleException("待确认：" + label + "没有对应到已识别的风管材质");
		if (!areaBasis.equals("net"))
			return ONE;
		if (families.size() > 1)
			throw new RuleException("待确认：" + label + "同时对应镀锌和不锈钢风管，无法确定净面积系数，请按材质分开填写");
		return rate(rates, families.contains("stainless") ? "net_stainless" : galvanizedRate);
	}

	/**
	 * 计算一条风管产品。所有校验失败都会转成该行的待确认原因，使其他已明确行仍可继续试算。
	 */
	/** 圆管按对应角钢直管（同成品状态）的面积单价加30，所有法兰已经包含，旧数量参数不另收费。 */
	private Map<String, Object> circularRow(Map<String, Object> source, Map<String, Object> options,
			Map<String, Object> resource, Map<String, Object> payment) {
		BigDecimal d = diameter(source), thickness = thickness(source, options);
		String state = state(source, options), material = material(source, options);
		var reference = new LinkedHashMap<>(source);
		var attributes = new LinkedHashMap<>(map(source.get("attributes")));
		for (String key : List.of("diameter", "round_flange_count", "flange_meters")) attributes.remove(key);
		attributes.putAll(obj("material", material, "thickness", text(thickness), "duct_cross_section", "rectangular",
			"width", text(d), "height", text(d), "flange", "angle", "shape", "straight", "product_state", state));
		reference.putAll(obj("name", material.startsWith("stainless") ? "不锈钢方形直管" : "镀锌方形直管", "spec", text(d) + "×" + text(d), "attributes", attributes));
		var referenceOptions = new LinkedHashMap<>(options);
		referenceOptions.putAll(obj("flange", "angle", "shape", "straight", "include_freight", false));
		var base = row(reference, referenceOptions, resource, payment, ZERO, false);
		var result = new LinkedHashMap<>(base);
		result.putAll(obj("name", source.get("name"), "spec", source.get("spec"), "attributes", source.get("attributes")));
		if (!s(base, "status").equals("priced")) return result;
		var inputs = new LinkedHashMap<>(map(base.get("calculation_inputs")));
		Map<String, Object> rates = map(map(resource.get("body")).get("rates"));
		BigDecimal extra = rate(rates, "circular_finished_extra", "30"),
				basis = n(inputs.get("combined_after_area_factor")), tax = n(inputs.get("tax_factor")),
				freight = yes(options.get("include_freight")) ? rate(rates, "freight_per_sqm", "1") : ZERO,
				beforeFreight = add(basis, extra), combined = add(beforeFreight, freight),
				unit = round(mul(combined, tax)), untaxedUnit = round(combined), area = n(base.get("billing_quantity"));
		inputs.putAll(obj("circular_finished_included_flange", true, "circular_finished_extra", text(extra),
			"rectangular_reference_unit_price", text(basis), "combined_after_area_factor", text(beforeFreight),
			"combined_with_freight", text(combined), "freight", text(freight), "round_area", "0",
			"thickness_inferred", inferredThickness(source), "thickness_rule", "圆形风管按直径及压力表补充缺失板厚，客户明确板厚优先"));
		result.putAll(obj("unit_price", text(unit), "untaxed_unit_price", text(untaxedUnit), "amount", text(round(mul(unit, area))),
			"untaxed_amount", text(round(mul(untaxedUnit, area))), "body_amount", text(round(mul(unit, area))),
			"tax_amount", text(round(mul(unit, area)).subtract(round(mul(untaxedUnit, area)), MC)),
			"effective_flange", "angle", "effective_product_state", state, "flange_basis", "circular_included", "round_flange_diameter", text(d),
			"calculation_inputs", inputs, "flange_per_area", null, "flange_per_area_base", null));
		List<Map<String, Object>> traces = new ArrayList<>(list(base.get("trace")));
		traces.removeIf(t -> Set.of("面积换算后风管费用/㎡", "运费/㎡", "风管综合销售单价/㎡").contains(s(t, "step")));
		traces.add(trace("角钢方形直管基准单价/㎡", "同地区、板厚、面积口径与成品状态，已包含法兰", basis));
		traces.add(trace("圆形风管附加价/㎡", "基准面积单价后增加，已含法兰，不按旧米数或数量另收", extra));
		traces.add(trace("运费/㎡", "圆管附加价后加入，不乘净面积系数", freight));
		traces.add(trace("风管综合销售单价/㎡", "（角钢方管基准价+圆管附加价+运费）×税系数，保留一位", unit));
		result.put("trace", traces);
		return result;
	}

	private Map<String, Object> row(Map<String, Object> source, Map<String, Object> options,
			Map<String, Object> resource, Map<String, Object> payment, BigDecimal accessory, boolean aggregate) {
		var scope = InstallationPricingEngine.materialScopeRow(source, yes(options.get("include_installation")));
		if (scope != null)
			return scope;
		Map<String, Object> out = obj("status", "pending", "reason", "", "unit_price", null, "amount", null, "trace",
				new ArrayList<>(), "billing_unit", "㎡");
		try {
			EngineeringImportReview.requirePricingBasis(source);
			var reviewedSource = new LinkedHashMap<>(source);
			String unitReview = EquipmentReview.sourceReason(reviewedSource);
			if (!unitReview.isBlank() && !yes(reviewedSource.get("source_issue_confirmed"))) throw new RuleException(unitReview);
			if (!blank(source.get("product_rule_issue")))
				throw new RuleException(s(source, "product_rule_issue"));
			String productDescription = description(source);
			if (map(source.get("_product_rule_resource")).isEmpty() && productDescription.contains("风管")
					&& (productDescription.contains("复合") || productDescription.contains("彩钢") && productDescription.contains("镀锌")))
				throw new RuleException("待确认：复合风管缺少完整计价规则，请确认面层、芯材、内层及加工成本，不能仅按普通镀锌风管报价");
			if (yes(source.get("source_issue")) && !yes(source.get("source_issue_confirmed"))) {
				String sourceReason = EquipmentReview.sourceReason(new LinkedHashMap<>(source));
				throw new RuleException(sourceReason.isBlank() ? "原文件存在未缓存公式或错误值，请先核对" : sourceReason);
			}
			if ((!s(source, "category").isEmpty() && !s(source, "category").equals("风管"))
					|| (s(source, "category").isEmpty() && !s(source, "name").contains("风管")))
				throw new RuleException("首版仅对风管类自动报价");
			if (blank(source.get("name")))
				throw new RuleException("缺少产品名称");
			if (resource == null)
				throw new RuleException("新版风管指标尚未发布，不能使用旧规则报价");
			if (payment == null)
				throw new RuleException("待确认：请选择与安装状态对应的整单付款方式");
			if (!bool(options.get("tax_included")))
				throw new RuleException("待确认：请选择整单含税或不含税");
			String state = state(source, options), flange = flange(source, options),
					areaBasis = s(options, "area_basis");
			if (circular(source, options))
				return circularRow(source, options, resource, payment);
			if (s(map(source.get("attributes")), "duct_cross_section").equals("rectangular") && flange.equals("round"))
				throw new RuleException("矩形截面与圆法兰口径需核对，请重新选择截面或连接方式");
			if (!Set.of("open", "net").contains(areaBasis))
				throw new RuleException("待确认：请选择开板面积或净面积");
			if (flange.equals("common") && !bool(options.get("include_common_punch")))
				throw new RuleException("待确认：请选择共板法兰是否需要打孔");
			// 先固定材质、厚度、管件类型和面积，这些参数共同决定材料档位及加工费。
			String material = material(source, options), shape = shape(source, options);
			// 工程量已经是整行总面积（㎡），最终只乘一次，不再乘设备式数量或按宽高重算面积。
			BigDecimal thickness = thickness(source, options);
			// 历史内部行的 engineering_quantity 已是面积；明确填写的客户单位必须匹配面积维度。
			if (!blank(source.get("unit")) && !QuantityUnits.area(source.get("unit"))) throw new RuleException("风管工程量单位必须为平方米（㎡），请核对原单位并补充总面积；不能将长度、重量或体积直接按面积报价");
			BigDecimal area = positive(source.get("engineering_quantity"), "工程量总面积");
			Map<String, Object> catalog = map(resource.get("body")), rates = map(catalog.get("rates")),
					expressions = map(catalog.get("expressions"));
			List<Map<String, Object>> materials = list(catalog.get("materials")).stream()
				.filter(m -> s(m, "material").equals(material) && n(m.get("thickness")).compareTo(thickness) == 0)
				.toList();
			if (materials.size() != 1)
				throw new RuleException("材料价格缺失或冲突：" + material + " " + text(thickness) + "mm");
			DuctRegionalPrices.requirePrice(materials.get(0));
			BigDecimal coefficient = n(payment.get("coefficient")),
					tax = yes(options.get("tax_included")) ? rate(rates, "material_tax_factor") : ONE;
			BigDecimal freight = yes(options.get("include_freight")) ? rate(rates, "freight_per_sqm", "1") : ZERO;
			BigDecimal processing;
			if (material.startsWith("stainless"))
				processing = rate(rates, shape.equals("semi_unspecified") ? "stainless_semi" : "stainless_" + shape);
			else if (shape.equals("semi_unspecified"))
				processing = thickness.compareTo(n("1.5")) == 0 ? rate(rates, "galvanized_1_5_unspecified", "5.5")
						: rate(rates, "galvanized_semi");
			else
				processing = rate(rates,
						(thickness.compareTo(n("1.5")) == 0 ? "galvanized_1_5_" : "galvanized_") + shape);
			// 自定义规则的圆法兰口径不沿用隐藏矩形尺寸；未进入新规则的历史行保留原计价口径。
			BigDecimal[] dimensions = flange.equals("round") && !map(source.get("_product_rule_resource")).isEmpty()
					? null : dimensions(source);
			// 矩形宽高之和 ≤520mm 时增加窄管加工费，520mm 边界也计入。
			BigDecimal extra = dimensions != null && add(dimensions).compareTo(n("520")) <= 0
					? rate(rates, "galvanized_narrow_extra") : ZERO;
			processing = add(processing, extra);
			// 半成品不计合口，成品按法兰形式取费；0.5mm 成品另加对应薄板费用。
			BigDecimal closing = state.equals("finished")
					? (flange.equals("round") ? rate(rates, "closing_round", "20") : rate(rates, "closing_" + flange))
					: ZERO;
			if (state.equals("finished") && thickness.compareTo(n("0.5")) == 0)
				closing = add(closing, rate(rates, "closing_0_5_extra"));
			// 开板面积系数为 1；净面积系数按圆法兰、材质和角钢/共板形式分别读取。
			BigDecimal factor = areaBasis.equals("open") ? ONE
					: flange.equals("round") ? rate(rates, "net_round", s(rates, "net_galvanized_common"))
							: rate(rates, material.startsWith("stainless") ? "net_stainless"
									: flange.equals("angle") ? "net_galvanized_angle" : "net_galvanized_common");
			BigDecimal rowAccessory = flange.equals("common") ? accessory : ZERO;
			// 主体公式的 area_factor 固定 1，稍后在加入运费前乘真实面积系数，避免重复放大。
			Map<String, Object> env = env(rates, "ton_price", materials.get(0).get("ton_price"), "density",
					rate(rates, material.startsWith("stainless") ? "density_stainless" : "density_galvanized"),
					"thickness", thickness, "processing", processing, "closing", closing, "accessory_per_sqm",
					rowAccessory, "area_factor", ONE);
			// 板材材料部分乘0.92；角铁材料另在法兰公式中折算，加工、合管及安装不参与。
			String materialFormula = "(" + s(expressions, "material_price") + ") * 0.92";
			BigDecimal materialPrice = mul(eval(expressions, "material_price", env), n("0.92"));
			env.put("material_price", materialPrice);
			BigDecimal body = eval(expressions, "body_sale", env), combined = body, punch = ZERO, flangePerMeter = ZERO,
					flangePerArea = ZERO;
			BigDecimal flangeMeters = null, roundCount = null, diameter = null, roundRate = null;
			BigDecimal roundUntaxedArea = ZERO, roundArea = ZERO;
			Map<String, Object> selectedFlange = null, actualFlange = null, roundItem = null;
			String basis;
			// 三类法兰采用不同计价基础：共板按㎡，角钢可按㎡折算或实际米数，圆法兰可按㎡折算或实际数量。
			if (flange.equals("common")) {
				punch = yes(options.get("include_common_punch")) ? rate(rates, "common_punch") : ZERO;
				combined = add(body, punch);
				basis = yes(options.get("include_common_punch")) ? "common_punch" : "common_no_punch";
			}
			else if (flange.equals("angle")) {
				Object raw = attr(source, "flange_meters");
				if (aggregate) {
					if (!blank(raw))
						throw new RuleException("已使用整单30#/40#法兰米数，本行不能再填写法兰米数");
					basis = "aggregate_meters";
				}
				else {
					int size = flangeSize(source, thickness);
					String flangeMaterial = flangeMaterial(source);
					selectedFlange = selectFlange(catalog, flangeMaterial, size, DuctRegionalPrices.purchased(flangeMaterial) ? ZERO : grade(source, size));
					flangePerMeter = flangePrice(selectedFlange, catalog);
					if (!blank(raw)) {
						flangeMeters = positive(raw, "角钢法兰实际米数");
						actualFlange = pricedItem(flangePerMeter, flangeMeters, factor, tax);
						basis = "actual_meters";
					}
					else {
						flangePerArea = s(selectedFlange, "pricing_mode").equals("fixed") ? mul(flangePerMeter, rate(rates, "flange_area_factor"))
							: eval(expressions, "flange_per_area",
								env(rates, "flange_per_meter", flangePerMeter, "flange_grade",
										selectedFlange.getOrDefault("grade", selectedFlange.get("kg_per_bar")),
										"ton_price", mul(n(selectedFlange.get("ton_price")), n("0.92")), "flange_processing",
										selectedFlange.get("processing_per_m")));
						combined = add(body, flangePerArea);
						basis = "per_area";
					}
				}
			}
			else {
				diameter = diameter(source);
				// 直径为 mm，π×直径÷1000 得到米；圆法兰分别选含税或未税材料价，不再重复乘材料税系数。
				BigDecimal circumference = mul(rate(rates, "round_flange_pi", "3.14"), diameter).divide(n("1000"), MC);
				BigDecimal untaxedRate = rate(rates, "round_flange_untaxed", "10");
				roundRate = yes(options.get("tax_included")) ? rate(rates, "round_flange_taxed", "12") : untaxedRate;
				if (blank(attr(source, "round_flange_count"))) {
					roundUntaxedArea = mul(circumference, untaxedRate, rate(rates, "flange_area_factor"));
					roundArea = mul(circumference, roundRate, rate(rates, "flange_area_factor"));
					basis = "round_area";
				}
				else {
					roundCount = positive(attr(source, "round_flange_count"), "圆法兰数量");
					BigDecimal untaxedUnit = round(mul(circumference, untaxedRate, factor)),
							unit = round(mul(circumference, roundRate, factor));
					roundItem = obj("untaxed_unit_price", text(untaxedUnit), "unit_price", text(unit), "untaxed_amount",
							text(round(mul(untaxedUnit, roundCount))), "amount", text(round(mul(unit, roundCount))));
					basis = "round_count";
				}
			}
			// 风管费用先换算面积口径，运费按清单面积计取；汇总中的运费只展示，不二次加入总额。
			BigDecimal combinedBeforeAreaFactor = combined, combinedAfterAreaFactor = mul(combined, factor);
			combined = add(combinedAfterAreaFactor, freight);
			// 综合单价和行金额依次四舍五入保留 1 位；实际法兰米数或数量费用随后单独加到本行。
			BigDecimal untaxedUnit = round(add(combined, mul(roundUntaxedArea, factor)));
			BigDecimal unit = round(add(mul(combined, tax), mul(roundArea, factor)));
			BigDecimal bodyAmount = round(mul(unit, area)), untaxedAmount = round(mul(untaxedUnit, area)),
					amount = bodyAmount;
			if (actualFlange != null) {
				amount = add(amount, n(actualFlange.get("amount")));
				untaxedAmount = add(untaxedAmount, n(actualFlange.get("untaxed_amount")));
			}
			if (roundItem != null) {
				amount = add(amount, n(roundItem.get("amount")));
				untaxedAmount = add(untaxedAmount, n(roundItem.get("untaxed_amount")));
			}
			out.putAll(obj("status", "priced", "unit_price", text(unit), "amount", text(amount), "billing_quantity",
					text(area), "body_amount", text(bodyAmount), "untaxed_unit_price", text(untaxedUnit),
					"untaxed_amount", text(untaxedAmount), "tax_amount", text(amount.subtract(untaxedAmount, MC)),
					"body_unit_price", text(round(mul(body, factor, tax))), "body_base_unit_price",
					text(mul(body, factor)), "flange_unit_price",
					actualFlange == null ? null : actualFlange.get("unit_price"), "flange_untaxed_unit_price",
					actualFlange == null ? null : actualFlange.get("untaxed_unit_price"), "flange_amount",
					actualFlange == null ? null : actualFlange.get("amount"), "flange_untaxed_amount",
					actualFlange == null ? null : actualFlange.get("untaxed_amount"), "flange_meters",
					text(flangeMeters), "flange_per_area",
					basis.equals("per_area") ? text(round(mul(flangePerArea, factor, tax))) : null,
					"flange_per_area_base", basis.equals("per_area") ? text(flangePerArea) : null,
					"round_flange_diameter", text(diameter), "round_flange_count", text(roundCount),
					"round_flange_rate", text(roundRate), "round_flange_unit_price",
					roundItem == null ? null : roundItem.get("unit_price"), "round_flange_untaxed_unit_price",
					roundItem == null ? null : roundItem.get("untaxed_unit_price"), "round_flange_amount",
					roundItem == null ? null : roundItem.get("amount"), "round_flange_untaxed_amount",
					roundItem == null ? null : roundItem.get("untaxed_amount"), "round_flange_area_unit_price",
					basis.equals("round_area") ? text(round(mul(roundArea, factor))) : null,
					"round_flange_untaxed_area_unit_price",
					basis.equals("round_area") ? text(round(mul(roundUntaxedArea, factor))) : null, "flange_basis",
					basis, "selected_material", materials.get(0), "selected_flange", selectedFlange, "selected_shape",
					shape, "effective_flange", flange, "effective_product_state", state, "area_factor", text(factor),
					"rule", resource, "payment_coefficient", text(coefficient), "payment_applied", false, "coverage",
					obj("installation", false, "freight", yes(options.get("include_freight"))), "tax_basis",
					yes(options.get("tax_included")) ? "included" : "excluded"));
			// 保存本次试算真实使用的公式与数值，内部明细无需重新读取可能已经变化的指标。
			out.put("calculation_inputs", obj("material_formula", materialFormula, "material_price_factor", "0.92", "body_formula",
					s(expressions, "body_sale"), "ton_price", materials.get(0).get("ton_price"), "price_region", s(catalog, "price_region"), "density",
					text(n(env.get("density"))), "thickness", text(thickness), "thickness_inferred", inferredThickness(source),
					"thickness_rule", "按矩形长边或圆形直径及中/高压表补充缺失板厚，客户明确板厚优先", "material_price", text(materialPrice),
					"processing", text(processing), "closing", text(closing), "accessory_per_sqm", text(rowAccessory),
					"body", text(body), "punch", text(punch), "flange_per_area", text(flangePerArea),
					"round_area", text(roundArea), "freight_after_area_factor", true,
					"combined_before_area_factor", text(combinedBeforeAreaFactor),
					"combined_after_area_factor", text(combinedAfterAreaFactor),
					"combined_with_freight", text(combined), "freight", text(freight),
					"tax_factor", text(tax), "area_factor", text(factor), "flange_per_meter", text(flangePerMeter),
					"flange_pricing_mode", s(map(selectedFlange), "pricing_mode"), "flange_source_cell", s(map(selectedFlange), "source_cell"),
					"flange_material_factor", s(map(selectedFlange), "pricing_mode").equals("fixed") ? "1" : "0.92",
					"flange_per_meter_formula", s(map(selectedFlange), "pricing_mode").equals("fixed") ? s(selectedFlange, "price_per_m")
							: selectedFlange == null ? s(expressions, "flange_per_meter").replaceAll("\\bton_price\\b", "(ton_price * 0.92)") : flangeMeterFormula(catalog),
					"flange_per_area_formula", s(map(selectedFlange), "pricing_mode").equals("fixed") ? "flange_per_meter * flange_area_factor" : s(expressions, "flange_per_area").replaceAll("\\bton_price\\b", "(ton_price * 0.92)"),
					"flange_area_factor", s(rates, "flange_area_factor"),
					"flange_grade", selectedFlange == null ? null
							: selectedFlange.getOrDefault("grade", selectedFlange.get("kg_per_bar")),
					"flange_ton_price", selectedFlange == null ? null : selectedFlange.get("ton_price"),
					"flange_processing", selectedFlange == null ? null : selectedFlange.get("processing_per_m"),
					"round_pi", roundRate == null ? null : text(rate(rates, "round_flange_pi", "3.14")),
					"round_rate", roundRate == null ? null : text(roundRate)));
			List<Map<String, Object>> traces = new ArrayList<>(
					List.of(trace("铁皮材料价/㎡", materialFormula, materialPrice),
							trace("风管基础加工费/㎡", "按材质、厚度、管件类型取费", processing.subtract(extra, MC)),
							trace("口型风管A+B≤520附加加工费/㎡", "宽+高≤520mm", extra),
							trace("成品合管费/㎡", "半成品为0；成品按法兰形式和厚度", closing), trace("按㎡角码卡扣/㎡", "仅共板法兰适用", rowAccessory),
							trace("净面积系数后风管本体/㎡", s(expressions, "body_sale"), mul(body, factor)),
							trace("面积换算前风管费用/㎡", "本体+打孔+角钢法兰折算", combinedBeforeAreaFactor),
							trace("面积换算后风管费用/㎡", "运费加入前乘面积结算系数", combinedAfterAreaFactor),
							trace("运费/㎡", "面积换算后加入，按清单面积计取，不重复加总", freight),
							trace("面积结算系数", "用于风管及法兰费用，不放大运费", factor),
							trace("整单付款条件", s(payment, "label") + "；材料与安装汇总后统一应用一次，本行金额未乘付款系数", coefficient),
							trace("材料含税系数", "圆法兰独立使用含税或未税材料价", tax)));
			if (flange.equals("common"))
				traces.add(trace("共板打孔费/㎡", "按整单明确选择", punch));
			if (flange.equals("angle") && !aggregate)
				traces.add(trace(s(selectedFlange, "pricing_mode").equals("fixed") ? "外购法兰成品固定价/米" : "角钢法兰基础综合价/米",
						s(selectedFlange, "pricing_mode").equals("fixed") ? "原表成品总单价，已含加工费：" + s(selectedFlange, "source_cell") : s(map(out.get("calculation_inputs")), "flange_per_meter_formula"), flangePerMeter));
			if (basis.equals("per_area"))
				traces.add(trace("角钢法兰基础折算价/㎡", s(selectedFlange, "pricing_mode").equals("fixed") ? "外购每米成品价 × 法兰面积折算系数" : s(map(out.get("calculation_inputs")), "flange_per_area_formula"), flangePerArea));
			if (roundItem != null)
				traces.add(trace("圆法兰每个销售价", "圆周长×材料价×面积结算系数（付款前）", n(roundItem.get("unit_price"))));
			if (basis.equals("round_area"))
				traces.add(trace("圆法兰折算价/㎡", "圆周长×材料价×1.72×面积结算系数（付款前）", n(out.get("round_flange_area_unit_price"))));
			traces.add(trace("风管综合销售单价/㎡", "各项合计后四舍五入保留一位", unit));
			out.put("trace", traces);
		}
		catch (RuleException e) {
			out.put("reason", e.getMessage());
			QuoteProblem.attach(out, e);
		}
		catch (ArithmeticException | NullPointerException | ClassCastException e) {
			out.put("reason", "风管输入或指标不完整，请核对");
		}
		return out;
	}

	/**
	 * 计算风管整单：锁定唯一生效指标与付款方案，处理整单法兰/附件，再逐行计价并汇总待确认项。
	 * {@code supplyOnly} 用于复合报价的分组计算，避免在风管子结果中提前叠加安装费和付款系数。
	 */
	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy) {
		return calculate(quote, resources, policy, LocalDate.now());
	}

	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy, LocalDate day) {
		return calculate(quote, resources, policy, day, false);
	}

	public Map<String, Object> calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			Map<String, Object> policy, LocalDate day, boolean supplyOnly) {
		if (!list(quote.get("rows")).isEmpty() && list(quote.get("rows")).stream()
				.allMatch(r -> yes(r.get("excluded")) || yes(r.get("installation_only"))))
			return InstallationPricingEngine.finalizeOrder(quote,
					InstallationPricingEngine.scopeOnlyResult(quote, policy, VERSION), resources, supplyOnly, day);
		Map<String, Object> options = new LinkedHashMap<>(map(quote.get("duct_options")));
		options.put("project_region", QuoteRegion.project(quote));
		var supplyRows = list(quote.get("rows")).stream().filter(r -> !yes(r.get("excluded")) && !yes(r.get("installation_only"))).toList();
		boolean circularOnly = !supplyRows.isEmpty() && supplyRows.stream().allMatch(r -> {
			try { return circular(r, options); }
			catch (RuleException invalid) { return false; }
		});
		if (circularOnly) {
			options.remove("flange_30_meters"); options.remove("flange_40_meters");
			if (options.containsKey("accessory_review")) options.put("accessory_review", list(options.get("accessory_review")).stream()
				.filter(g -> !Set.of("flange_30_meters", "flange_40_meters").contains(s(g, "key"))).toList());
		}
		AccessoryImportReview.resolve(options);
		options.put("include_installation", InstallationPricingEngine.enabled(quote));
		List<Map<String, Object>> catalogs = DuctCatalog.active(resources, day);
		Map<String, Object> resource = DuctRegionalPrices.resolve(catalogs.size() == 1 ? catalogs.get(0) : null, QuoteRegion.project(quote)),
				catalog = resource == null ? null : map(resource.get("body"));
		List<String> issues = new ArrayList<>();
		issues.addAll(AccessoryImportReview.issues(options));
		if (resource == null)
			issues.add("新版风管指标未发布或版本冲突");
		Map<String, Object> rates = catalog == null ? null : map(catalog.get("rates")), payment = null;
		if (catalog != null) {
			try {
				payment = payment(options, catalog);
			}
			catch (RuleException e) {
				issues.add(e.getMessage());
			}
		}
		BigDecimal tax = rates != null && yes(options.get("tax_included")) ? rate(rates, "material_tax_factor") : ONE;
		List<Map<String, Object>> sources = list(quote.get("rows")).stream().map(r -> defaults(r, options)).toList();
		// 预扫描用于判断整单附件是否适用，以及整单实际法兰米数应采用哪一种净面积系数。
		boolean common = false, semiCommon = false;
		Set<String> angleFamilies = new HashSet<>(), semiCommonFamilies = new HashSet<>();
		for (Map<String, Object> source : sources) {
			if (yes(source.get("excluded")) || yes(source.get("installation_only"))
					|| (!s(source, "category").isEmpty() && !s(source, "category").equals("风管"))
					|| (s(source, "category").isEmpty() && !s(source, "name").contains("风管")))
				continue;
			try {
				if (circular(source, options)) continue;
				String f = flange(source, options), st = state(source, options),
						family = materialFamily(material(source, options));
				common |= f.equals("common");
				semiCommon |= f.equals("common") && st.equals("semi");
				if (f.equals("angle"))
					angleFamilies.add(family);
				if (f.equals("common") && st.equals("semi"))
					semiCommonFamilies.add(family);
			}
			// 预扫描失败由逐行计算给出具体原因，不在这里重复添加同一行的问题。
			catch (RuleException ignored) {
				/* 具体原因在本行试算时返回。 */ }
		}
		// 一旦填写整单30#/40#法兰米数，就按整单另列，相关行不再重复按㎡折算法兰。
		boolean aggregate = !blank(options.get("flange_30_meters")) || !blank(options.get("flange_40_meters"))
				|| AccessoryImportReview.has(options, "flange_30_meters") || AccessoryImportReview.has(options, "flange_40_meters");
		List<Map<String, Object>> aggregateItems = new ArrayList<>(), accessoryItems = new ArrayList<>();
		Set<Map<String, Object>> aggregateRows = new HashSet<>();
		// 未引入地区、外购价格的历史指标维持原整单口径；新版按材质规格关联。
		boolean legacyAggregate = catalog != null && s(options, "project_region").isEmpty()
				&& !catalog.containsKey("price_regions") && !catalog.containsKey("purchased_flanges");
		BigDecimal accessory = ZERO;
		String accessoryMode = "pending";
		if (aggregate && catalog != null && payment != null) {
			try {
				List<Map<String, Object>> staged = new ArrayList<>();
				Map<Map<String, Object>, String> assigned = new HashMap<>();
				for (int size : List.of(30, 40)) {
					String key = "flange_" + size + "_meters";
					if (blank(options.get(key))) continue;
					var candidates = sources.stream().filter(r -> !yes(r.get("excluded")) && !yes(r.get("installation_only")))
						.filter(r -> {
							try { return !circular(r, options) && flange(r, options).equals("angle") && (legacyAggregate || flangeSize(r, thickness(r, options)) == size); }
							catch (RuleException e) { return false; }
						}).toList();
					var origins = list(quote.get("source_special_rows")).stream().filter(r -> s(r, "kind").equals(key)).toList();
					for (var selectedSource : AccessoryImportReview.items(options, key)) {
						var original = origins.stream().filter(r -> !selectedSource.containsKey("source_row") || s(r, "source_row").equals(s(selectedSource, "source_row")) && s(r, "source_sheet").equals(s(selectedSource, "source_sheet"))).toList();
						// 合并只合并用量；不同材质、档位仍分别取价，保留来源行。
						var meterSources = original.stream().anyMatch(r -> r.containsKey("flange_input")) ? original : List.of(selectedSource);
						if (original.stream().anyMatch(r -> r.containsKey("flange_input"))
								&& sum(meterSources, "quantity").compareTo(n(selectedSource.get("quantity"))) != 0)
							throw new RuleException("待确认：法兰实际米数与原表来源不一致，请核对原行用量");
						for (var source : meterSources) {
							var flangeInput = map(source.get("flange_input"));
							Map<String, Object> input;
							if (!flangeInput.isEmpty() && explicitFlangeMaterial(flangeInput)) input = flangeInputDefaults(flangeInput, size, options);
							else if (legacyAggregate) input = flangeInputDefaults(obj("attributes", obj("flange_material", "black")), size, options);
							else {
								var keys = candidates.stream().map(r -> flangeKey(r, size)).distinct().toList();
								if (keys.size() != 1) throw new RuleException("待确认：整单" + size + "#法兰米数无法唯一关联材质和档位，请按材质规格分别填写本行实际米数");
								input = candidates.get(0);
							}
							String group = flangeKey(input, size), material = flangeMaterial(input);
							var matching = candidates.stream().filter(r -> legacyAggregate || (!explicitFlangeMaterial(r) && (!explicitFlangeGrade(r) || DuctRegionalPrices.purchased(material) || grade(r, size).compareTo(grade(input, size)) == 0)) || flangeKey(r, size).equals(group)).toList();
							if (matching.isEmpty()) throw new RuleException("待确认：原表法兰米数与风管材质、规格或档位无法关联");
							Set<String> families = new HashSet<>();
							for (var r : matching) {
								if (!legacyAggregate && assigned.containsKey(r) && !assigned.get(r).equals(group)) throw new RuleException("待确认：同一风管对应多种法兰材质，请明确每行实际法兰用量");
								assigned.put(r, group); families.add(materialFamily(material(r, options)));
							}
							BigDecimal factor = wholeQuoteFactor(rates, s(options, "area_basis"), families, "net_galvanized_angle", "整单角钢法兰实际米数");
							BigDecimal meters = positive(source.get("quantity"), "整单" + size + "#角钢法兰米数");
							var selected = selectFlange(catalog, material, size, DuctRegionalPrices.purchased(material) ? ZERO : grade(input, size));
							var item = pricedItem(flangePrice(selected, catalog), meters, factor, tax);
							item.putAll(obj("size", size, "name", (blank(selected.get("name")) ? size + "#角钢法兰" : s(selected, "name")) + AccessoryImportReview.location(source),
								"meters", text(meters), "source", "客户清单整单实际米数", "material", material, "pricing_mode", selected.get("pricing_mode"),
								"price_region", s(selected, "price_region"), "ton_price", selected.get("ton_price"), "grade", selected.getOrDefault("grade", selected.get("kg_per_bar")), "source_cell", selected.get("source_cell")));
							staged.add(item);
						}
					}
				}
				aggregateRows.addAll(assigned.keySet());
				aggregateItems = staged;
			}
			catch (RuleException e) {
				issues.add(e.getMessage());
			}
		}
		if (rates != null && payment != null && bool(options.get("tax_included"))) {
			try {
				if (AccessoryImportReview.pending(options, "corner_count") || AccessoryImportReview.pending(options, "clip_meters"))
					accessoryMode = "pending";
				else if (!common)
					accessoryMode = "not_applicable";
				// 实际角码/卡扣另列仅适用于存在半成品共板风管的整单；否则仍按每㎡附件费计入。
				else if ((blank(options.get("corner_count")) && blank(options.get("clip_meters"))
						&& !AccessoryImportReview.has(options, "corner_count") && !AccessoryImportReview.has(options, "clip_meters")) || !semiCommon) {
					accessoryMode = "per_sqm";
					accessory = rate(rates, "accessory_per_sqm");
				}
				else {
					BigDecimal factor = wholeQuoteFactor(rates, s(options, "area_basis"), semiCommonFamilies,
							"net_galvanized_common", "整单角码、卡扣实际数量");
					List<Map<String, Object>> staged = new ArrayList<>();
					for (String key : List.of("corner_count", "clip_meters")) {
						if (blank(options.get(key)))
							continue;
						boolean corner = key.equals("corner_count");
						for (var source : AccessoryImportReview.items(options, key)) {
						BigDecimal quantity = nonnegative(source.get("quantity"), corner ? "角码数量" : "卡扣长度");
						Map<String, Object> item = pricedItem(
								rate(rates, corner ? "corner_unit_price" : "clip_per_meter_price"), quantity, factor,
								tax);
						item.putAll(obj("code", corner ? "corner" : "clip", "name", (corner ? "角码" : "卡扣") + AccessoryImportReview.location(source), "unit",
								corner ? "个" : "米", "quantity", text(quantity)));
						staged.add(item);
						}
					}
					accessoryItems = staged;
					accessoryMode = "actual";
				}
			}
			catch (RuleException e) {
				issues.add(e.getMessage());
			}
		}
		// 单行待确认不会阻断其他行计算，但正式报价必须等所有待确认项处理完成。
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> source : sources)
			rows.add(row(source, options, resource, payment, accessory, (legacyAggregate && aggregate || aggregateRows.contains(source))));
		List<Map<String, Object>> priced = rows.stream().filter(r -> s(r, "status").equals("priced")).toList();
		int pending = (int) rows.stream().filter(r -> s(r, "status").equals("pending")).count();
		// 材料行、整单实际法兰和另列附件各汇总一次；待确认与已排除行不计入已确定小计。
		BigDecimal subtotal = add(sum(priced, "amount"), sum(aggregateItems, "amount"), sum(accessoryItems, "amount"));
		BigDecimal untaxedSubtotal = add(sum(priced, "untaxed_amount"), sum(aggregateItems, "untaxed_amount"),
				sum(accessoryItems, "untaxed_amount"));
		if (rows.isEmpty())
			issues.add("清单没有产品行");
		if (priced.isEmpty() && !rows.isEmpty())
			issues.add("没有已计算的风管项目");
		if (pending > 0)
			issues.add("有" + pending + "项待确认，未计入小计");
		if (!bool(options.get("tax_included")))
			issues.add("请选择整单含税或不含税");
		if (common && !bool(options.get("include_common_punch")))
			issues.add("请选择共板法兰是否需要打孔");
		Map<String, Object> fees = obj("accessories", fee(sum(accessoryItems, "amount"), switch (accessoryMode) {
			case "actual" -> "ready";
			case "per_sqm" -> "included";
			case "not_applicable" -> "not_applicable";
			default -> "pending";
		}, accessoryMode.equals("actual") ? "按实际数量另列，未填写项不计费"
				: accessoryMode.equals("per_sqm") ? "已计入共板风管综合单价，不重复加总"
						: accessoryMode.equals("not_applicable") ? "本单没有共板法兰，不计角码卡扣" : "整单实际角码、卡扣尚未明确对应材质，未计入小计"),
				"aggregate_flanges",
				fee(sum(aggregateItems, "amount"),
						!aggregateItems.isEmpty() ? "ready" : aggregate ? "pending" : "not_applicable",
						!aggregateItems.isEmpty() ? "整单实际米数另列，不再按1.72折算"
								: aggregate ? "整单实际法兰米数尚未明确对应材质，未计入小计" : "未使用整单实际米数"));
		if (AccessoryImportReview.pending(options, "flange_30_meters") || AccessoryImportReview.pending(options, "flange_40_meters"))
			fees.put("aggregate_flanges", fee(sum(aggregateItems, "amount"), "pending", "存在同类角铁用量待确认，未确认部分未计入小计"));
		else if (aggregate && blank(options.get("flange_30_meters")) && blank(options.get("flange_40_meters")))
			fees.put("aggregate_flanges", fee(ZERO, "not_applicable", "原表角铁行已明确排除计价"));
		if (AccessoryImportReview.pending(options, "corner_count") || AccessoryImportReview.pending(options, "clip_meters"))
			fees.put("accessories", fee(sum(accessoryItems, "amount"), "pending", "存在同类配件用量待确认，未确认部分未计入小计"));
		if (!yes(options.get("include_freight")))
			fees.put("freight", fee(ZERO, "not_applicable", "本单不需要"));
		else if (rates == null || priced.isEmpty() || pending > 0) {
			fees.put("freight", fee(null, "pending", "风管指标或工程量未全部确认"));
			issues.add("运费：风管工程量总面积待确认");
		}
		else {
			BigDecimal base = rate(rates, "freight_per_sqm", "1"), freight = ZERO;
			for (Map<String, Object> r : priced) {
				freight = add(freight, mul(n(r.get("billing_quantity")), base));
			}
			Map<String, Object> f = fee(mul(freight, tax), "included", "运费已进入材料单价，本项仅展示，不重复加总");
			f.put("untaxed_amount", text(round(freight)));
			f.put("unit_price", text(round(mul(base, tax))));
			fees.put("freight", f);
		}
		// 税额只展示已含在各项金额中的含税差额，整单合计不再另加税额。
		BigDecimal taxAmount = yes(options.get("tax_included")) ? subtotal.subtract(untaxedSubtotal, MC) : ZERO;
		fees.put("tax",
				fee(taxAmount, yes(options.get("tax_included")) ? "ready" : "not_applicable", "含税差额已计入各项，不重复加总"));
		String total = issues.isEmpty() ? text(subtotal) : null;
		if (!yes(policy.get("formal_template_confirmed")))
			issues.add("正式客户模板尚未确认，当前仅可导出通用草稿");
		Map<String, Object> result = obj("rows", rows, "subtotal", text(subtotal), "pending_count", pending, "fees",
				fees, "taxable_base", text(round(untaxedSubtotal)), "total", total, "rounding_difference",
				total == null ? null : "0.0", "issues", issues, "formal_ready", issues.isEmpty(), "policy", policy,
				"engine_version", VERSION, "implementation_version", "java-1", "installation_notes",
				yes(options.get("include_installation")) ? INSTALLATION_NOTES : List.of(), "accessory_mode",
				accessoryMode, "accessory_items", accessoryItems, "aggregate_flanges", aggregateItems, "payment",
				payment == null ? null : env(payment, "coefficient", text(n(payment.get("coefficient")))),
				"duct_catalog",
				resource == null ? null : obj("id", resource.get("id"), "version", resource.get("version"), "price_region", s(catalog, "price_region")));
		return InstallationPricingEngine.finalizeOrder(quote, result, resources, supplyOnly, day);
	}

}
