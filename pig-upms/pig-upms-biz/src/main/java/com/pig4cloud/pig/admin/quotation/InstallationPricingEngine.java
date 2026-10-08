package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 安装费用计算与整单最终汇总引擎。
 * <p>
 * 安装费按产品逐行匹配项目，也允许报价人员逐行指定项目、单价、数量和单位。人工确认值优先于自动匹配。
 * 本类同时是所有报价模式唯一的付款系数边界：材料、设备运费和安装费先汇总，付款系数最后只应用一次。
 */
public final class InstallationPricingEngine {

	// 安装规则版本用于历史计算追溯，改变结算口径时需重新试算。
	public static final String VERSION = "installation-v5";

	// 安装支持的计量单位；只有已确认的少量产品允许等量使用“台/个/套”，其余不能自动换算。
	private static final Set<String> UNITS = Set.of("㎡", "台", "个", "套");

	/** 新版整单安装选项优先；仅为兼容旧报价，缺少该字段时才读取风管或设备中的旧开关。 */
	public static boolean enabled(Map<String, Object> quote) {
		if (quote.containsKey("installation_options"))
			return yes(map(quote.get("installation_options")).get("included"));
		return yes(map(quote.get("duct_options")).get("include_installation"))
				|| yes(map(quote.get("equipment_options")).get("include_installation"));
	}

	/** 复合订单和设备订单沿用设备税口径，纯风管订单沿用风管税口径。 */
	static boolean taxIncluded(Map<String, Object> quote) {
		String mode = s(quote, "pricing_mode");
		var equipment = map(quote.get("equipment_options"));
		var duct = map(quote.get("duct_options"));
		if (Set.of("equipment_v1", "mixed_v1").contains(mode) && equipment.containsKey("tax_included"))
			return yes(equipment.get("tax_included"));
		if (duct.containsKey("tax_included"))
			return yes(duct.get("tax_included"));
		return yes(equipment.get("tax_included"));
	}

	// 移除常见省区后缀后按指标地区匹配，不扩展到没有价格依据的省区。
	private static String region(Object value) {
		return str(value).replaceFirst("省$", "").replaceFirst("壮族自治区$", "");
	}

	// 仅把常见平方米写法规整为㎡，不进行台、个、套之间的普遍换算。
	private static String unit(Object value) {
		return Set.of("m2", "m²", "平方米", "平米", "平方").contains(str(value)) ? "㎡" : str(value);
	}

	// 安装数量必须正数；人工销售单价、辅材价和普通现场附加费允许明确填 0，但不接受空值或负值。
	private static BigDecimal amount(Object value, String label, boolean positive) {
		if (blank(value))
			throw new RuleException("请填写" + label);
		BigDecimal number = n(value);
		if (number.signum() < 0 || (positive && number.signum() == 0))
			throw new RuleException(label + (positive ? "必须大于0" : "不能为负"));
		return number;
	}

	// 明确类别优先；未填类别且名称含风管时按风管工程量读取。
	private static boolean duct(Map<String, Object> row) {
		return s(row, "category").equals("风管") || (blank(row.get("category")) && s(row, "name").contains("风管"));
	}

	// 安装识别同时参考名称、规格和备注中的用途、结构与范围说明。
	private static String description(Map<String, Object> row) {
		return s(row, "name") + " " + s(row, "spec") + " " + s(row, "remark");
	}

	private static boolean contains(String text, String... words) {
		return Arrays.stream(words).anyMatch(text::contains);
	}

	// 只有明确“远控/远程控制”文字才增加适用的远程机构安装费，电动名称本身不表示远控。
	private static boolean explicitRemoteControl(Map<String, Object> row) {
		return contains(description(row), "远控", "远程控制");
	}

	/** 仅明确说明安装包含法兰时采用含法兰安装价；单独填写法兰形式不代表安装范围。 */
	private static boolean installationIncludesFlange(Map<String, Object> row) {
		String text = (description(row) + " " + str(attr(row, "flange"))).replaceAll("\\s+", "");
		return contains(text, "安装含法兰", "安装包含法兰", "安装包括法兰", "含法兰安装", "包含法兰安装", "包括法兰安装",
				"法兰随风管安装", "法兰随管安装");
	}

	// 按㎡供货的消声弯头安装沿用面积工程量；有个数的弯头仍走每个安装项目。
	private static boolean areaPricedSilencerElbow(Map<String, Object> row) {
		return s(row, "name").contains("消声弯头") && unit(row.get("unit")).equals("㎡");
	}

	// 安装用法兰形式来自行属性或明确法兰文字，供面积弯头与风管项目匹配。
	private static String flange(Map<String, Object> row) {
		String value = str(attr(row, "flange")), text = description(row);
		if (value.equals("angle") || contains(text, "角钢法兰", "角铁法兰"))
			return "angle";
		if (value.equals("common") || text.contains("共板法兰"))
			return "common";
		return "";
	}

	/** 无安装个数的消声弯头按整单法兰口径计价；整单同时出现两种法兰时采用角钢法兰。 */
	private static String silencerElbowAreaProject(Map<String, Object> row, Map<String, Object> quote, boolean shaanxi) {
		Set<String> flanges = new HashSet<>();
		for (var quoteRow : list(quote.get("rows"))) {
			String value = flange(quoteRow);
			if (!value.isEmpty())
				flanges.add(value);
		}
		String defaultFlange = str(map(quote.get("duct_options")).get("flange"));
		if (Set.of("common", "angle").contains(defaultFlange))
			flanges.add(defaultFlange);
		String selected = flanges.contains("angle") ? "angle" : flanges.contains("common") ? "common" : flange(row);
		if (selected.equals("angle"))
			return shaanxi ? "sx_4" : "four_4";
		if (selected.equals("common"))
			return shaanxi ? "sx_3" : "four_3";
		return null;
	}

	// 按壁式、方形、管道/油烟及消防送排风用途区分地区项目，陕西信息不足时返回待选择。
	private static String fanProject(Map<String, Object> row, boolean shaanxi) {
		String text = description(row), name = s(row, "name");
		if (name.contains("壁式"))
			return shaanxi ? name.contains("方形") ? "sx_22" : null : "four_14";
		if (contains(name, "油烟净化", "管道风机", "管道式排风机"))
			return shaanxi ? "sx_20" : "four_13";
		return shaanxi ? contains(text, "消防", "排烟", "送风", "排风") ? "sx_19" : null : "four_13";
	}

	// 人工参数、指标值、材料行值依次取首个非空项，明确填 0 仍视为有效输入。
	private static Object first(Object... values) {
		for (Object v : values)
			if (!blank(v))
				return v;
		return null;
	}

	// 行内实际成品状态和描述优先于安装设置及整单默认，防止半成品安装价误用于成品。
	private static String state(Map<String, Object> row, Map<String, Object> quote, Map<String, Object> entry) {
		if (!blank(attr(row, "product_state")))
			return str(attr(row, "product_state"));
		String text = description(row);
		if (text.contains("半成品"))
			return "semi";
		if (text.contains("成品"))
			return "finished";
		return str(first(entry.get("product_state"), map(quote.get("duct_options")).get("product_state"), "semi"));
	}

	/** 四地区防雨百叶按原表明确的“长+宽”分档；尺寸缺失时不猜测。 */
	private static String rainLouverProject(Map<String, Object> row, boolean shaanxi) {
		if (shaanxi)
			return "sx_33";
		var attributes = EquipmentPricingEngine.parseAttributes(row);
		if (blank(attributes.get("width")) || blank(attributes.get("height")))
			return null;
		try {
			BigDecimal width = n(attributes.get("width")), height = n(attributes.get("height"));
			if (width.signum() <= 0 || height.signum() <= 0)
				return null;
			return width.add(height).compareTo(n("1500")) < 0 ? "four_23" : "four_24";
		}
		catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 根据地区、产品类别、材质、法兰和用途匹配安装指标项目。只有规则能够唯一确定时才返回项目编号，否则交由报价人员选择。
	 */
	public static String autoProject(Map<String, Object> row, Map<String, Object> quote, String region) {
		String text = description(row), name = s(row, "name");
		boolean sx = region.equals("陕西");
		if (!sx && !InstallationCatalog.FOUR_REGIONS.contains(region))
			return null;
		if (areaPricedSilencerElbow(row))
			return silencerElbowAreaProject(row, quote, sx);
		String prefix = sx ? "sx_" : "four_";
		if (duct(row)) {
			if (contains(text, "包裹", "复合", "彩钢"))
				return null;
			if (text.contains("不锈钢") || str(attr(row, "material")).startsWith("stainless")) {
				if (sx)
					return "sx_6";
				return installationIncludesFlange(row) ? "four_6_flange" : "four_6";
			}
			String flange = str(first(attr(row, "flange"), map(quote.get("duct_options")).get("flange")));
			if (contains(text, "螺旋", "圆形") || flange.equals("round"))
				return sx ? "sx_7" : null;
			if (contains(text, "角钢", "角铁"))
				flange = "angle";
			else if (text.contains("共板"))
				flange = "common";
			if (sx) {
				if (flange.equals("angle") && contains(text, "防排烟", "排烟", "防烟"))
					return "sx_4";
				if (flange.equals("common")) {
					if (contains(text, "卫生间", "空调"))
						return "sx_5";
					if (contains(text, "正压送风", "地下室送风"))
						return "sx_3";
				}
			}
			else {
				if (text.contains("空调"))
					return contains(text, "小管", "装修") ? "four_5" : null;
				return Map.of("common", "four_3", "angle", "four_4").get(flange);
			}
			return null;
		}
		// 风机附带自垂或防雨百叶时只计风机安装，不重复计算百叶安装。
		boolean fan = EquipmentPricingEngine.classify(row).equals("fan");
		if (fan && name.contains("百叶"))
			return fanProject(row, sx);
		if (name.contains("防雨百叶"))
			return rainLouverProject(row, sx);
		if (contains(name, "消声弯头", "防雨弯头"))
			return sx ? "sx_17" : "four_26";
		if (name.contains("散流器"))
			return sx ? "sx_14" : "four_25";
		if (name.contains("电动泄压阀"))
			return sx ? "sx_23" : "four_16";
		if (sx) {
			if (contains(name, "远控常闭排烟阀") || name.contains("280") && name.contains("排烟防火阀"))
				return "sx_24";
			if (name.contains("电动多叶排烟风口"))
				return "sx_32";
			if (contains(name, "板式排烟口", "远程多叶送风口"))
				return "sx_33";
			if (contains(name, "多叶排烟口", "多叶送风口", "多叶加压送风口", "正压送风口", "加压送风口", "电动防火风口"))
				return "sx_31";
			if (name.contains("防火风口"))
				return contains(name, "70", "弱电", "常开") ? "sx_29" : "sx_26";
			if (contains(name, "成品防火软连接", "柔性软接头"))
				return "sx_15";
			if (contains(name, "防虫网", "防鼠网"))
				return "sx_16";
			if (name.contains("可开链式百叶"))
				return "sx_30";
			if (contains(name, "AH单层格栅风口", "AH/D单层格栅风口"))
				return "sx_28";
		}
		else {
			if (contains(name, "板式排烟口", "板式排烟风口", "多叶排烟口"))
				return "four_20";
			if (contains(name, "多叶送风口", "多叶加压送风口", "正压送风口", "加压送风口", "组合送风口"))
				return "four_20";
			if (name.contains("防火风口"))
				return "four_22";
		}
		if (contains(name, "防火阀", "排烟阀", "调节阀", "止回阀", "风阀", "电动阀", "蝶阀")) {
			if (!sx)
				return "four_16";
			if (!contains(name, "电动", "自动", "远程", "远控"))
				return "sx_23";
		}
		if (((name.contains("单层") || name.contains("双层")) && name.contains("百叶")) || name.contains("带阀风口"))
			return sx ? "sx_25" : "four_18";
		// 带阀只表示风口附带控制开关，不另计风阀安装；自垂百叶仍按自垂百叶项目计价。
		if (name.contains("自垂百叶"))
			return sx ? "sx_27" : "four_19";
		// 这些名称包含执行机构或复合配置，最新版安装表没有给出四地区叠加关系。
		if (contains(name, "防雨", "防火风口", "多叶", "远程", "远控", "不燃", "电动", "自动", "带阀"))
			return null;
		if (contains(name, "换气扇", "排气扇"))
			return sx ? "sx_21" : "four_15";
		if (fan)
			return fanProject(row, sx);
		if (contains(name, "静压箱", "消声器"))
			return sx ? "sx_18" : "four_21";
		if (name.contains("正压送风口"))
			return sx ? "sx_31" : "four_20";
		return null;
	}

	/**
	 * 计算一条产品的安装费。行内人工项目/单价优先于自动匹配；供货单位和安装单位不同时禁止自动换算，必须明确安装工程量。
	 */
	public static Map<String, Object> line(Map<String, Object> row, Map<String, Object> entry,
			Map<String, Object> quote, Map<String, Object> catalog, Map<String, Object> calculated) {
		String region = region(map(quote.get("installation_options")).get("region"));
		var out = obj("name", first(row.get("name"), "安装项目"), "status", "pending", "reason", "", "amount", null);
		try {
			if (yes(entry.get("excluded")) || yes(row.get("excluded"))) {
				Object reason = first(entry.get("exclude_reason"), row.get("exclude_reason"));
				if (blank(reason))
					throw new RuleException("不计安装需要填写原因");
				out.putAll(obj("status", "excluded", "reason", reason));
				return out;
			}
			if (region.isEmpty())
				throw new RuleException("请选择安装地区");
			String productRuleType = s(map(map(row.get("_product_rule_resource")).get("body")), "rule_type");
			if (Set.of("combination", "formula").contains(productRuleType)
					&& blank(entry.get("project_id")) && blank(entry.get("unit_price")))
				throw new RuleException(productRuleType.equals("formula") ? "自定义产品请明确安装项目或填写未税安装单价" : "组合产品请明确整套安装项目或填写整套未税安装单价");
			String projectId = str(first(entry.get("project_id"), autoProject(row, quote, region)));
			var project = list(catalog.get("projects")).stream()
				.filter(p -> s(p, "id").equals(projectId))
				.findFirst()
				.orElse(null);
			if (!blank(entry.get("project_id")) && project == null)
				throw new RuleException("安装项目不存在，请重新选择");
			if (project != null
					&& (!((List<?>) project.get("regions")).contains(region) || s(project, "kind").equals("auxiliary")))
				throw new RuleException("所选安装项目不适用于本地区或属于辅材，请重新选择");
			boolean areaElbow = areaPricedSilencerElbow(row) && project != null && s(project, "unit").equals("㎡");
			boolean isDuct = project != null ? s(project, "kind").equals("duct") && !areaElbow : duct(row);
			String state = isDuct ? state(row, quote, entry) : "semi";
			if (!Set.of("semi", "finished").contains(state))
				throw new RuleException("安装成品状态未识别");
			// 手填安装单价代表报价人员已确认本行口径，因此覆盖指标项目中的自动单价。
			boolean manual = !blank(entry.get("unit_price"));
			if (isDuct && state.equals("finished") && !manual)
				throw new RuleException("成品风管请填写每㎡未税安装销售单价");
			if (project == null && !manual)
				throw new RuleException("未找到唯一安装价，请选择安装项目或手填未税安装单价");
			String unit = unit(first(entry.get("unit"), project == null ? null : project.get("unit"),
					isDuct ? "㎡" : row.get("unit")));
			if (!UNITS.contains(unit))
				throw new RuleException("请确认安装计量单位（㎡、台、个、套）");
			if (project != null && !unit.equals(s(project, "unit")))
				throw new RuleException("安装单位与所选项目不一致，不能自动换算");
			if (isDuct && state.equals("finished") && !unit.equals("㎡"))
				throw new RuleException("成品风管安装单价必须按每㎡填写");
			// 只对已核对项目开放计数等量例外：四地区静压箱台→个、指定风口套→个、陕西电动泄压阀个→套。
			boolean staticBoxCountEquivalent = projectId.equals("four_21") && s(row, "name").contains("静压箱")
					&& unit.equals("个") && unit(row.get("unit")).equals("台");
			String rowName = s(row, "name");
			boolean reviewedOutletSet = Set.of("four_18", "sx_25").contains(projectId)
					|| Set.of("four_20", "sx_31").contains(projectId)
							&& contains(rowName, "多叶排烟口", "多叶送风口", "多叶加压送风口", "正压送风口", "加压送风口")
					|| Set.of("four_22", "sx_26", "sx_29", "sx_31").contains(projectId) && rowName.contains("防火风口");
			boolean outletCountEquivalent = reviewedOutletSet && unit.equals("个")
					&& unit(row.get("unit")).equals("套");
			boolean pressureReliefCountEquivalent = projectId.equals("sx_23") && rowName.contains("电动泄压阀")
					&& unit.equals("套") && unit(row.get("unit")).equals("个");
			// 人工安装数量优先；风管取原工程量总面积，设备取原数量，不乘材料净面积系数。
			Object quantity = entry.get("quantity");
			if (blank(quantity)) {
				if (areaElbow)
					quantity = first(row.get("engineering_quantity"), row.get("quantity"));
				else if (!unit.equals(duct(row) ? "㎡" : unit(row.get("unit"))) && !staticBoxCountEquivalent
						&& !outletCountEquivalent && !pressureReliefCountEquivalent)
					throw new RuleException("供货与安装单位不同，请确认安装工程量及单位");
				else
					quantity = row.get(duct(row) ? "engineering_quantity" : "quantity");
			}
			BigDecimal qty = amount(quantity, "安装工程量", true), price;
			if (manual)
				price = amount(entry.get("unit_price"), "未税安装销售单价", false);
			else if (project.get("power_prices") instanceof List<?> bands) {
				var selected = map(map(calculated.get("detail")).get("selected_fan"));
				Object power = first(entry.get("power"),
						selected.get("powers") instanceof List<?> powers && !powers.isEmpty()
								? String.join("/", powers.stream().map(QuoteData::str).toList()) : attr(row, "power"));
				if (blank(power)) {
					var m = Pattern
						.compile("(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?)?)\\s*(?:kW|千瓦)",
								Pattern.CASE_INSENSITIVE)
						.matcher(s(row, "spec"));
					if (m.find())
						power = m.group(1);
				}
				// 双速风机有两个功率，安装计费档位不能由程序擅自选择。
				if (str(power).contains("/"))
					throw new RuleException("双速风机请确认安装计费功率或手填安装单价");
				BigDecimal kw = amount(power, "安装计费功率kW", true);
				// 功率单位 kW；小于 1.5 为首档，1.5～17.5 含端点为中档，大于 17.5 为末档。
				int band = kw.compareTo(n("1.5")) < 0 ? 0 : kw.compareTo(n("17.5")) <= 0 ? 1 : 2;
				price = n(bands.get(band));
				out.putAll(obj("power", text(kw), "power_band", band));
			}
			else
				price = n(project.get("price"));
			BigDecimal basePrice = price, remotePrice = ZERO;
			Map<String, Object> remoteProject = null;
			// 远控附加仅用于四地区的自动设备安装价；手填价格视为已确认整行口径，不再自动加机构安装费。
			if (!manual && s(project, "kind").equals("equipment") && InstallationCatalog.FOUR_REGIONS.contains(region)
					&& explicitRemoteControl(row) && !projectId.equals("four_17")) {
				remoteProject = list(catalog.get("projects")).stream()
					.filter(p -> s(p, "id").equals("four_17") && ((List<?>) p.get("regions")).contains(region))
					.findFirst()
					.orElseThrow(() -> new RuleException("远程控制机构安装指标不存在，请先核对安装指标"));
				remotePrice = n(remoteProject.get("price"));
				price = add(price, remotePrice);
			}
			String projectName = project == null ? "人工确认安装价" : s(project, "name"), source = manual
					? "报价员手填未税销售单价" : s(project, "source"), note = str(first(entry.get("note"),
					project == null ? "" : project.getOrDefault("note", "")));
			if (remoteProject != null) {
				projectName += "＋" + s(remoteProject, "name");
				source += "；" + s(remoteProject, "source");
				note = (note.isEmpty() ? "" : note + "；") + "客户清单明确远控，叠加远程控制机构安装费";
			}
			out.putAll(obj("status", "priced", "unit", unit, "quantity", text(qty), "unit_price", text(price), "amount",
					text(mul(qty, price)), "base_unit_price", text(basePrice), "remote_control_unit_price", text(remotePrice),
					"project_id", projectId.isEmpty() ? null : projectId, "project_name", projectName, "source", source, "manual",
					manual, "product_state", state, "note", note, "auxiliary", null));
			// 风管辅材跟随本行面积另算；地区价格来源不同，无法唯一命中时仍保留为待确认。
			if (yes(map(quote.get("installation_options")).get("include_auxiliaries")) && isDuct) {
				if (!unit.equals("㎡"))
					throw new RuleException("风管安装辅材必须明确㎡工程量");
				String auxType = str(
						first(entry.get("auxiliary_type"), project == null ? null : project.get("auxiliary_type"))),
						auxSource;
				BigDecimal auxPrice;
				if (InstallationCatalog.FOUR_REGIONS.contains(region)) {
					auxPrice = n(catalog.get("four_auxiliary_price"));
					auxSource = "四地表A25";
				}
				else if (region.equals("陕西")) {
					var matches = list(catalog.get("projects")).stream()
						.filter(p -> s(p, "kind").equals("auxiliary") && ((List<?>) p.get("regions")).contains(region)
								&& s(p, "auxiliary_type").equals(auxType))
						.toList();
					if (matches.size() != 1)
						throw new RuleException("请确认陕西风管安装辅材类型（共板、角铁或复合）");
					auxPrice = n(matches.get(0).get("price"));
					auxSource = s(matches.get(0), "source");
				}
				else {
					auxPrice = amount(entry.get("auxiliary_price"), "本地区人工确认安装辅材单价", false);
					auxSource = "报价员确认安装辅材单价";
				}
				out.put("auxiliary", obj("name", "风管安装辅材", "unit", "㎡", "quantity", text(qty), "unit_price",
						text(auxPrice), "amount", text(mul(qty, auxPrice)), "source", auxSource));
			}
		}
		catch (RuleException | ArithmeticException | ClassCastException | NullPointerException e) {
			out.putAll(obj("status", "pending", "amount", null, "auxiliary", null, "reason",
					e instanceof RuleException ? e.getMessage() : "安装数据格式不正确，请核对"));
		}
		return out;
	}

	// 安装汇总结果及阻止正式报价的问题列表；逐行已计算金额可展示，但有问题时汇总金额保持空。
	public record Calculation(Map<String, Object> fee, List<String> issues) {
	}

	/** 每行先合并人工和辅材，再按安装税率计算并向上取整到整数元。 */
	private static BigDecimal priceLine(Map<String, Object> line, BigDecimal taxFactor) {
		BigDecimal untaxed = add(n(line.get("amount")),
				line.get("auxiliary") == null ? ZERO : n(map(line.get("auxiliary")).get("amount")));
		BigDecimal taxed = mul(untaxed, taxFactor), quoted = taxed.setScale(0, RoundingMode.CEILING);
		line.putAll(obj("untaxed_total", text(untaxed), "tax_amount", text(taxed.subtract(untaxed, MC)),
				"rounding_difference", text(quoted.subtract(taxed, MC)), "quoted_amount", text(quoted)));
		return quoted;
	}

	/**
	 * 汇总订单安装费，包括客户清单产品、独立安装项目和现场附加费。任一安装行待确认时，安装总额不进入正式报价。
	 */
	public static Calculation calculate(Map<String, Object> quote, List<Map<String, Object>> resources,
			List<Map<String, Object>> calculatedRows, LocalDate day) {
		var empty = obj("amount", "0", "untaxed_amount", "0", "status", "not_applicable", "reason", "本单不含安装", "lines",
				new ArrayList<>());
		if (!enabled(quote))
			return new Calculation(empty, new ArrayList<>());
		if (!quote.containsKey("installation_options"))
			return pending(empty, "旧版整单安装金额需核对并改填安装项目，不能直接作为每㎡单价", "安装费：");
		var active = InstallationCatalog.active(resources, day);
		if (active.size() != 1)
			return pending(empty, "需要一个已发布且生效的安装指标版本", "安装费：");
		var resource = active.get(0);
		var options = map(quote.get("installation_options"));
		var catalog = map(resource.get("body"));
		try {
			InstallationCatalog.validate(catalog);
		}
		catch (RuleException e) {
			return pending(empty, e.getMessage(), "安装指标无效：");
		}
		List<Map<String, Object>> lines = new ArrayList<>();
		List<String> issues = new ArrayList<>();
		var rows = list(quote.get("rows"));
		for (int i = 0; i < rows.size(); i++) {
			var line = line(rows.get(i), map(rows.get(i).get("installation")), quote, catalog,
					i < calculatedRows.size() ? calculatedRows.get(i) : obj());
			line.put("row_index", i);
			lines.add(line);
		}
		// 独立安装项目仅增加安装费用，使用 extra_index 标记，不占材料清单行号。
		var extra = list(quote.get("installation_items"));
		for (int i = 0; i < extra.size(); i++) {
			var e = extra.get(i);
			var row = obj("name", s(e, "name"), "category", s(e, "category"), "unit", s(e, "unit"), "quantity",
					s(e, "quantity"));
			var line = line(row, e, quote, catalog, obj());
			line.put("extra_index", i);
			lines.add(line);
		}
		for (var line : lines)
			if (s(line, "status").equals("pending"))
				issues.add("安装费：" + s(line, "name") + "：" + s(line, "reason"));
		var priced = lines.stream().filter(l -> s(l, "status").equals("priced")).toList();
		if (priced.isEmpty())
			issues.add("安装费：没有已确认的安装项目");
		boolean includesTax = taxIncluded(quote);
		// 安装指标是未税销售价，含税订单乘 1.01、不含税乘 1，不再除设备毛利率。
		BigDecimal taxFactor = includesTax ? n("1.01") : ONE;
		BigDecimal labor = sum(priced, "amount"), auxiliaries = priced.stream()
			.filter(l -> l.get("auxiliary") != null)
			.map(l -> n(map(l.get("auxiliary")).get("amount")))
			.reduce(ZERO, QuoteData::add), site = ZERO;
		BigDecimal lineAmount = priced.stream().map(line -> priceLine(line, taxFactor)).reduce(ZERO, QuoteData::add);
		// 交叉施工必须明确选择计取或不计取；其他工况仍由报价人员主动选择是否计取现场附加费。
		boolean crossWork = yes(options.get("cross_work"));
		boolean usesExplicitCrossWorkDecision = options.containsKey("cross_work_fee_decision");
		String crossWorkFeeDecision = s(options, "cross_work_fee_decision");
		boolean siteSelected = crossWork && usesExplicitCrossWorkDecision ? crossWorkFeeDecision.equals("charge")
				: yes(options.get("include_site_fee"))
						|| !options.containsKey("include_site_fee") && !blank(options.get("site_amount"));
		if (crossWork && usesExplicitCrossWorkDecision && !crossWorkFeeDecision.equals("charge")
				&& !crossWorkFeeDecision.equals("waive"))
			issues.add("安装费：交叉施工请确认是否计取现场附加费");
		if (crossWork && usesExplicitCrossWorkDecision && crossWorkFeeDecision.equals("waive")
				&& blank(options.get("site_waive_reason")))
			issues.add("安装费：交叉施工不计取现场附加费时请填写原因");
		try {
			if (!blank(options.get("height")))
				amount(options.get("height"), "施工标高", false);
			if (siteSelected)
				site = amount(options.get("site_amount"), "未税现场附加费", false);
			if (crossWork && crossWorkFeeDecision.equals("charge") && site.signum() <= 0)
				throw new RuleException("交叉施工计取现场附加费时金额必须大于0");
			if (site.signum() > 0 && blank(options.get("site_note")))
				throw new RuleException("现场附加费大于0时请填写附加原因");
		}
		catch (RuleException e) {
			issues.add("安装费：" + e.getMessage());
		}
		// 现场附加费为整单金额且只加一次：含税另乘 1.01 向上取整；不含税保留录入金额的小数。
		BigDecimal siteTaxed = mul(site, taxFactor), siteQuoted = includesTax
				? siteTaxed.setScale(0, RoundingMode.CEILING) : site;
		BigDecimal untaxed = add(labor, auxiliaries, site), taxed = mul(untaxed, taxFactor),
				amount = add(lineAmount, siteQuoted);
		var fee = obj("status", issues.isEmpty() ? "ready" : "pending", "amount",
				issues.isEmpty() ? text(amount) : null, "untaxed_amount", text(untaxed), "labor_amount", text(labor),
				"auxiliary_amount", text(auxiliaries), "line_amount", text(lineAmount), "site_amount", text(site),
				"site_quoted_amount", text(siteQuoted), "site_tax_amount", text(siteTaxed.subtract(site, MC)),
				"site_rounding_difference", text(siteQuoted.subtract(siteTaxed, MC)), "site_note",
				siteSelected ? options.getOrDefault("site_note", "") : "", "cross_work_fee_decision",
				crossWorkFeeDecision, "site_waive_reason", options.getOrDefault("site_waive_reason", ""), "tax_factor",
				text(taxFactor), "tax_amount",
				text(taxed.subtract(untaxed, MC)), "rounding_difference", text(amount.subtract(taxed, MC)), "lines",
				lines, "reason", includesTax
						? "每行安装人工与辅材分别×1.01后向上取整数元；现场附加费单独×1.01后向上取整数元；付款系数在整单末尾应用"
						: "每行安装人工与辅材分别向上取整数元；现场附加费按录入金额单独计入；付款系数在整单末尾应用",
				"catalog",
				obj("id", resource.get("id"), "version", resource.get("version"), "source", catalog.get("source")),
				"engine_version", VERSION);
		return new Calculation(fee, issues);
	}

	// 缺指标或旧安装数据等整单级问题统一返回待确认，保留明确原因而不视为零费用。
	private static Calculation pending(Map<String, Object> fee, String reason, String prefix) {
		fee.putAll(obj("amount", null, "status", "pending", "reason", reason));
		return new Calculation(fee, new ArrayList<>(List.of(prefix + reason)));
	}

	/**
	 * 设备运费门槛按整单试算销售额判断：风管和设备材料、按地区系数试算的设备运费、安装费先汇总，付款系数最后应用。
	 * 人工运费仅在试算销售额低于门槛时读取，并且不会反过来改变本次门槛判断。
	 */
	static void resolveEquipmentFreight(Map<String, Object> quote, Map<String, Object> result,
			Map<String, Object> installation, List<String> issues) {
		var freight = map(map(result.get("fees")).get("equipment_freight"));
		if (!s(freight, "status").equals("trial"))
			return;
		var term = map(result.get("payment"));
		boolean inputsReady = !term.isEmpty()
				&& issues.stream().allMatch(i -> i.startsWith("正式客户模板"));
		if (!inputsReady) {
			EquipmentPricingEngine.discardEquipmentFreightPlan(freight);
			String reason = term.isEmpty() ? "请选择整单付款方式后再判断8000元门槛" : "整单仍有待确认项，无法判断8000元门槛";
			freight.putAll(obj("status", "pending", "amount", null, "reason", reason, "basis", "pending",
					"included_in_rows", false));
			issues.add("设备运费：" + reason);
			return;
		}
		// 门槛按 CEILING((材料小计+地区试算运费+安装费)×付款系数) 判断，达到含等于门槛。
		BigDecimal calculated = n(freight.get("calculated_amount"));
		BigDecimal trialBeforePayment = add(n(result.get("subtotal")), calculated, n(installation.get("amount")));
		BigDecimal trialTotal = mul(trialBeforePayment, n(term.get("coefficient"))).setScale(0, RoundingMode.CEILING);
		freight.putAll(obj("trial_order_before_payment_total", text(trialBeforePayment), "trial_order_total", text(trialTotal)));
		if (trialTotal.compareTo(n(freight.get("threshold"))) >= 0) {
			EquipmentPricingEngine.applyEquipmentFreightPlan(freight);
			result.put("subtotal", text(add(n(result.get("subtotal")), calculated)));
			freight.putAll(obj("status", "ready", "amount", text(calculated), "reason",
					"整单试算销售额达到8000元，设备运费已逐行计入设备单台售价", "basis", "regional_per_unit",
					"included_in_rows", true));
			return;
		}
		EquipmentPricingEngine.discardEquipmentFreightPlan(freight);
		// 低于门槛才读取人工整单设备运费；明确填 0 有效，该金额不反向影响已经完成的门槛判断。
		Object entered = map(quote.get("equipment_options")).get("freight_amount");
		if (blank(entered)) {
			String reason = "整单试算销售额低于8000元，请填写整单设备运费（可填0）";
			freight.putAll(obj("status", "pending", "amount", null, "reason", reason, "basis", "manual_order",
					"included_in_rows", false));
			issues.add("设备运费：" + reason);
			return;
		}
		try {
			BigDecimal manual = amount(entered, "整单设备运费", false).setScale(0, RoundingMode.CEILING);
			freight.putAll(obj("status", "ready", "amount", text(manual), "reason",
					"整单试算销售额低于8000元，采用人工填写的整单设备运费并单独列示", "basis", "manual_order",
					"included_in_rows", false));
		}
		catch (RuleException e) {
			freight.putAll(obj("status", "pending", "amount", null, "reason", e.getMessage(), "basis", "manual_order",
					"included_in_rows", false));
			issues.add("设备运费：" + e.getMessage());
		}
	}

	/**
	 * 把逐行安装结果附回材料行，并完成整单最终金额。自动设备运费已经进入设备行时不重复加总；人工整单运费仍作为独立费用加入。
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> finalizeOrder(Map<String, Object> quote, Map<String, Object> result,
			List<Map<String, Object>> resources, boolean supplyOnly, LocalDate day) {
		// 混合报价子组仅返回供货结果，安装、门槛与付款等到恢复原清单顺序后再统一计算。
		if (supplyOnly)
			return result;
		var installation = calculate(quote, resources, list(result.get("rows")), day);
		map(result.get("fees")).put("installation", installation.fee());
		result.putIfAbsent("material_total", result.get("subtotal"));
		List<String> issues = (List<String>) result.get("issues");
		issues.addAll(installation.issues());
		var term = map(result.get("payment"));
		if (term.isEmpty())
			issues.add("请选择整单付款方式");
		resolveEquipmentFreight(quote, result, installation.fee(), issues);
		// 将安装费按原行号附回，供页面和导出模板展示“材料金额 + 本行安装费”。
		if (enabled(quote)) {
			Map<Integer, Map<String, Object>> installationRows = new HashMap<>();
			for (var line : list(installation.fee().get("lines")))
				if (line.get("row_index") != null)
					installationRows.put(n(line.get("row_index")).intValue(), line);
			var rows = list(result.get("rows"));
			for (int i = 0; i < rows.size(); i++) {
				var row = rows.get(i);
				var line = installationRows.get(i);
				if (line == null)
					continue;
				String status = s(line, "status");
				Object installationAmount = status.equals("excluded") ? "0"
						: status.equals("priced") ? line.get("quoted_amount") : null;
				Object lineTotal = !blank(row.get("amount")) && !blank(installationAmount)
						? text(add(n(row.get("amount")), n(installationAmount))) : null;
				row.putAll(obj("installation_status", status, "installation_reason", line.get("reason"),
						"installation_amount", installationAmount, "line_total", lineTotal));
			}
		}
		BigDecimal total = null, before = null, payment = null, difference = null;
		// 付款系数只在整单末尾应用；子引擎生成的行金额均保持付款前口径。
		if (issues.stream().allMatch(i -> i.startsWith("正式客户模板"))) {
			var equipmentFreight = map(map(result.get("fees")).get("equipment_freight"));
			BigDecimal equipmentFreightAmount = yes(equipmentFreight.get("included_in_rows"))
					|| blank(equipmentFreight.get("amount")) ? ZERO : n(equipmentFreight.get("amount"));
			before = add(n(result.get("subtotal")), equipmentFreightAmount, n(installation.fee().get("amount")));
			// 最终付款调整额与取整差额分别保存：付款调整按未取整总额计算，最终报价再向上取整到元。
			BigDecimal unrounded = mul(before, n(term.get("coefficient")));
			total = unrounded.setScale(0, RoundingMode.CEILING);
			payment = unrounded.subtract(before, MC);
			difference = total.subtract(unrounded, MC);
		}
		var equipmentFreight = map(map(result.get("fees")).get("equipment_freight"));
		result.putAll(obj("total", text(total), "before_payment_total", text(before), "payment_amount", text(payment),
				"equipment_freight_total", blank(equipmentFreight.get("amount")) ? null : equipmentFreight.get("amount"),
				"rounding_difference", text(difference), "installation_engine_version", VERSION, "formal_ready",
				issues.isEmpty()));
		return result;
	}

}
