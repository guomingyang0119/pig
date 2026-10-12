package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 设备供货指标的结构、成本规则及生效版本校验。
 * 参数沿用已核对源表：阀体公式、风口面积成本、风机本体/工况、电机和外购设备价格各自维护。
 * 发布前校验可计算性；报价时仍须唯一匹配具体材质、型号和工况，不能凭相近规格借价。
 */
public final class EquipmentCatalog {

	private EquipmentCatalog() {
	}

	// 毛利、税费、阀门/消声设备成本及运费门槛等受支持参数；必须全部可读取且满足各自范围。
	private static final Set<String> RATES = Set.of("gross_margin", "tax_factor", "round_factor", "valve_steel",
			"density", "split_width", "split_height", "splice_width", "splice_height", "fire_splice", "manual_splice",
			"check_splice", "remote_extra", "near_control", "remote_control", "auto_control", "panel_rate",
			"panel_edge", "silencer_rate", "plenum_rate", "double_layer_rate", "silencer_edge", "elbow_taxed_rate",
			"freight_threshold", "thickness_1_factor", "thickness_1_2_factor", "angle_factor");

	// 具有阀体组成成本的类型；余压阀在引擎中另按防火阀规格代价。
	public static final Set<String> VALVES = Set.of("fire", "electric_control", "electric_fire", "manual", "check",
			"multi_leaf", "plate_smoke");

	// 阀体各组成项公式，包括普通、多叶及板式排烟结构。
	private static final Set<String> EXPRESSIONS = Set.of("frame", "blade", "labor", "link", "corners", "shaft",
			"consumables", "brace", "multi_frame", "multi_blade", "multi_back", "multi_brace", "plate_frame",
			"plate_blade", "plate_consumables", "plate_brace");

	// 阀体公式白名单：宽高和板厚按 mm，材料价格按元/吨，组数、叶片和拉条数由引擎计算。
	private static final Set<String> VARIABLES = Set.of("width", "height", "groups", "blades", "density",
			"frame_thickness", "blade_thickness", "frame_price", "blade_price", "back_price", "brace_count",
			"labor_base");

	// 存在明确面积成本配置的风口类型；常开式百叶需另确认计价口径。
	private static final Set<String> LOUVERS = Set.of("single", "single_valve", "single_mesh", "double", "double_valve",
			"double_mesh", "rain", "rain_mesh", "gravity", "gravity_valve", "access", "hinged", "hinged_mesh",
			"diffuser", "diffuser_valve", "linear", "linear_valve", "abs_single", "abs_double", "abs_hinged_mesh");

	// 设备运费地区系数仅覆盖已确认的六省区，不能自动推算其他地区。
	public static final Set<String> REGIONS = Set.of("贵州", "四川", "广西", "云南", "陕西", "海南");

	/**
	 * 对外发布校验入口；把缺失列表、错误对象结构等异常转成可以展示给报价负责人的指标错误。
	 *
	 */
	public static void validate(Map<String, Object> body) {
		try {
			validateBody(body);
		}
		catch (ClassCastException | NullPointerException | IndexOutOfBoundsException e) {
			throw new RuleException("设备指标结构不完整，请保留字段及列表记录结构");
		}
	}

	/**
	 * 逐项检查参数、付款编码、公式变量和查价记录。
	 * 设备毛利率取 [0,1)，运费及防爆倍率不小于 1；止回阀拼接费固定为 0。
	 *
	 */
	private static void validateBody(Map<String, Object> b) {
		Map<String, Object> rates = map(b.get("rates"));
		// 两个钢号允许暂空以继续服务其他产品；旧未分钢号价格仅保留历史，不参与新报价。
		for (String grade : List.of("201", "304"))
			if (!blank(rates.get("fire_150_" + grade + "_steel"))) fire150Price(rates, grade);
		for (String key : List.of("pressure_supply_split_height", "pressure_supply_mechanism_width"))
			if (rates.containsKey(key)) positive(rates.get(key), "正压送风口指标 " + key);
		for (String key : List.of("pressure_supply_panel_rate", "pressure_supply_mechanism"))
			if (rates.containsKey(key)) nonnegative(rates.get(key), "正压送风口指标 " + key);
		for (String key : List.of("valve_remote_extra", "fire_auto_extra"))
			if (rates.containsKey(key)) nonnegative(rates.get(key), "机构附加指标 " + key);
		for (String key : RATES)
			if (n(rates.get(key)).signum() < 0)
				throw new RuleException("设备指标不能为负：" + key);
		if (n(rates.get("check_splice")).signum() != 0)
			throw new RuleException("已确认止回阀不加拼接费，此值必须为0");
		if (n(rates.get("gross_margin")).signum() < 0 || n(rates.get("gross_margin")).compareTo(ONE) >= 0)
			throw new RuleException("设备毛利率必须大于等于0且小于1");
		for (String key : List.of("tax_factor", "round_factor", "density", "split_width", "split_height",
				"splice_width", "splice_height", "thickness_1_factor", "thickness_1_2_factor", "angle_factor"))
			if (n(rates.get(key)).signum() <= 0)
				throw new RuleException("设备指标必须大于0：" + key);
		Map<String, Object> regions = map(b.get("regions"));
		if (!regions.keySet().equals(REGIONS))
			throw new RuleException("设备运费仅支持已确认六省区");
		for (Object val : regions.values())
			if (n(val).compareTo(ONE) < 0)
				throw new RuleException("运费系数不能小于1");
		Set<String> codes = new HashSet<>();
		for (Map<String, Object> term : list(b.get("payment_terms"))) {
			if (!codes.add(s(term, "code")) || !Set.of("no_install", "with_install").contains(s(term, "mode"))
					|| n(term.get("coefficient")).signum() <= 0)
				throw new RuleException("设备付款方案配置不正确");
		}
		Set<String> required = new HashSet<>();
		DuctCatalog.defaultPaymentTerms().forEach(t -> required.add(s(t, "code")));
		if (!codes.equals(required))
			throw new RuleException("设备付款方案必须与已确认风管方案保持一致");
		for (String key : EXPRESSIONS)
			if (!VARIABLES.containsAll(DecimalFormula.variables(s(map(b.get("valve_expressions")), key))))
				throw new RuleException("阀体成本公式包含未定义变量：" + key);
		if (map(b.get("valve_expressions")).containsKey("pressure_supply_link")
				&& !VARIABLES.containsAll(DecimalFormula.variables(s(map(b.get("valve_expressions")), "pressure_supply_link"))))
			throw new RuleException("正压送风口连杆公式包含未定义变量");
		for (String key : VALVES)
			for (String field : List.of("mechanism", "blade_thickness", "labor_base"))
				if (n(map(map(b.get("valves")).get(key)).get(field)).signum() < 0)
					throw new RuleException("阀体成本指标不能为负");
		// 每种风口四个值依次为面积成本、最低成本、宽高增加值和附件倍率，均不能为负。
		for (String key : LOUVERS) {
			Object entry = map(b.get("louvers")).get(key);
			if (!(entry instanceof List<?> values) || values.size() != 4
					|| values.stream().anyMatch(v -> n(v).signum() < 0))
				throw new RuleException("风口面积成本配置不正确：" + key);
		}
		// 列表编号用于选型与候选确认，同一列表内必须非空且唯一。
		for (String kind : List.of("materials", "fan_points", "fan_bodies", "motors", "purchased")) {
			if (!(b.get(kind) instanceof List<?>))
				throw new RuleException("设备指标缺少列表：" + kind);
			Set<String> ids = new HashSet<>();
			for (Map<String, Object> row : list(b.get(kind)))
				if (blank(row.get("id")) || !ids.add(s(row, "id")))
					throw new RuleException(kind + "存在空编号或重复编号");
		}
		// 不锈钢按 201/304 材质和对应板厚唯一查吨价，不允许一个厚度有多个价格。
		Set<String> materials = new HashSet<>();
		for (Map<String, Object> row : list(b.get("materials"))) {
			if (!Set.of("201", "304").contains(s(row, "material")) || n(row.get("thickness")).signum() <= 0
					|| n(row.get("ton_price")).signum() <= 0)
				throw new RuleException("不锈钢材料价格配置不正确");
			if (!materials.add(s(row, "material") + ":" + n(row.get("thickness")).stripTrailingZeros().toPlainString()))
				throw new RuleException("同材质板厚只能配置一条吨价");
		}
		// 风机工况需具备机号、风量、风压、转速、功率和极数；单双速用功率/极数列表表达。
		for (Map<String, Object> row : list(b.get("fan_points"))) {
			for (String key : List.of("size", "airflow", "pressure", "speed"))
				if (n(row.get(key)).signum() <= 0)
					throw new RuleException("风机参数不正确：" + s(row, "id") + " / " + key);
			if (!Set.of("HTFC", "HTF(A)", "SWF").contains(s(row, "family")))
				throw new RuleException("风机系列不正确");
			checkNumbers(row.get("powers"), "风机功率不正确");
			if (blank(row.get("model")))
				throw new RuleException("风机型号及极数不完整");
			checkNumbers(row.get("poles"), "风机型号及极数不完整");
		}
		// 本体公式仅允许钢材吨价、机号和板厚变量，不允许引用未确认的电机或售价。
		for (Map<String, Object> row : list(b.get("fan_bodies"))) {
			if (n(row.get("steel_price")).signum() <= 0 || n(row.get("size")).signum() <= 0)
				throw new RuleException("风机本体指标不正确");
			for (String key : List.of("material_expression", "other_expression"))
				if (!Set.of("steel_price", "size", "thickness").containsAll(DecimalFormula.variables(s(row, key))))
					throw new RuleException("风机本体公式包含未定义变量");
		}
		for (Map<String, Object> row : list(b.get("motors"))) {
			if (n(row.get("cost")).signum() < 0)
				throw new RuleException("电机成本和参数不完整");
			checkNumbers(row.get("powers"), "电机功率或极数不正确");
			checkNumbers(row.get("poles"), "电机功率或极数不正确");
		}
		for (Map<String, Object> row : list(b.get("purchased"))) {
			PurchasedEquipment.validatePrice(row);
			if (blank(row.get("model")))
				throw new RuleException("外购设备基础价不完整");
		}
		// 防爆档位按成本上限递增，末档不设上限；实际计算时上限包含本数。
		List<Map<String, Object>> tiers = list(b.get("explosion_tiers"));
		if (tiers.isEmpty() || tiers.get(tiers.size() - 1).get("limit") != null)
			throw new RuleException("防爆系数缺少最高档");
		var last = ZERO;
		for (Map<String, Object> row : tiers) {
			if (n(row.get("factor")).compareTo(ONE) < 0)
				throw new RuleException("防爆系数不正确");
			if (row.get("limit") != null) {
				if (n(row.get("limit")).compareTo(last) <= 0)
					throw new RuleException("防爆档位必须递增");
				last = n(row.get("limit"));
			}
		}
	}

	/** 报价时所选钢号必须已有有效专用价，不借用旧单价、另一钢号或通用材料价格。 */
	static BigDecimal fire150Price(Map<String, Object> rates, String grade) {
		String label = "150°防火阀" + grade + "不锈钢专用材料吨价";
		Object value = rates.get("fire_150_" + grade + "_steel");
		if (blank(value)) throw new RuleException("缺少" + label + "，请在材料与配件价格中填写并发布");
		try { return positive(value, label); }
		catch (RuleException e) { throw new RuleException(label + "必须为有效的正数，请在材料与配件价格中填写并发布"); }
	}

	// 功率和极数必须是非空正数列表，供单双速风机与电机组合精确匹配。
	private static void checkNumbers(Object value, String message) {
		if (!(value instanceof List<?> values) || values.isEmpty() || values.stream().anyMatch(v -> n(v).signum() <= 0))
			throw new RuleException(message);
	}

	/**
	 * 按指标系列取已到生效日的最新正式版本，再筛选发布且仍在有效期内的版本。
	 * 停用或过期的新版本会挡住旧价；未来版本和草稿不提前生效。
	 *
	 */
	public static List<Map<String, Object>> active(List<Map<String, Object>> resources, LocalDate date) {
		Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
		for (Map<String, Object> r : resources) {
			Map<String, Object> b = map(r.get("body"));
			if (!s(r, "kind").equals("equipment_catalog") || !"guizhou".equals(b.getOrDefault("company_id", "guizhou"))
					|| !Set.of("published", "retired").contains(s(r, "status"))
					|| s(b, "effective_from").compareTo(date.toString()) > 0)
				continue;
			String family = s(r, "family");
			if (!latest.containsKey(family) || n(r.get("version")).compareTo(n(latest.get(family).get("version"))) > 0)
				latest.put(family, r);
		}
		return latest.values().stream().filter(r -> {
			var b = map(r.get("body"));
			return s(r, "status").equals("published") && !blank(b.get("effective_from"))
					&& (blank(b.get("effective_to")) || s(b, "effective_to").compareTo(date.toString()) >= 0);
		}).toList();
	}

}
