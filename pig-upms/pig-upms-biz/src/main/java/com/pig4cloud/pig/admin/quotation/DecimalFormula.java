package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 报价指标和产品成本规则共用的受限十进制算式解析器。
 * 支持四则运算、括号及白名单函数，使用报价模块的 48 位有效数字上下文计算，金额舍入由各业务引擎决定。
 * 不执行脚本、反射或对象成员访问；公式长度、节点数、递归层级和函数参数数量均设有上限。
 */
public final class DecimalFormula {

	// 待解析的原始成本算式，最多 800 个字符。
	private final String input;

	// 当前产品的公式变量值；材质、尺寸、价格等取值由调用方按对应指标口径提供。
	private final Map<String, Object> variables;

	// 解析时收集的变量名，用于发布指标或规则前检查是否引用了未定义参数。
	private final Set<String> names = new HashSet<>();

	// 仅校验语法和收集变量时使用占位值 1，不要求提供实际业务参数。
	private final boolean validate;

	// 公式字符串的读取位置，解析结束必须消耗完整表达式。
	private int position;

	// 累计解析节点数，限制复杂公式的计算量。
	private int nodes;

	// 当前递归深度，限制括号、符号和函数嵌套层数。
	private int depth;

	/**
	 * 构造计算或语法校验实例；空公式和超长公式均不能作为有效报价规则。
	 *
	 */
	private DecimalFormula(String input, Map<String, Object> variables, boolean validate) {
		if (input == null || input.isBlank() || input.length() > 800)
			throw new RuleException("公式未配置或过长");
		this.input = input;
		this.variables = variables;
		this.validate = validate;
	}

	/**
	 * 以实际参数计算成本或价格表达式；缺失变量、非法函数、除以零等情况交由调用方转成待确认原因。
	 *
	 */
	public static BigDecimal evaluate(String expression, Map<String, Object> variables) {
		return new DecimalFormula(expression, variables, false).parse();
	}

	/**
	 * 校验公式语法并返回所引用的变量名。此步骤不核验实际参数的业务范围，也不执行最终金额舍入。
	 *
	 */
	public static Set<String> variables(String expression) {
		DecimalFormula parser = new DecimalFormula(expression, Map.of(), true);
		parser.parse();
		return parser.names;
	}

	/**
	 * 从完整表达式开始解析；尾部剩余字符意味着使用了不支持的语法，不能只采用前半段结果。
	 *
	 */
	private BigDecimal parse() {
		BigDecimal value = expression();
		space();
		if (position != input.length())
			throw new RuleException("公式只支持四则运算、括号和受限函数");
		return n(value);
	}

	// 忽略算式中的空白，使负责人在编辑公式时可以按习惯分隔运算符。
	private void space() {
		while (position < input.length() && Character.isWhitespace(input.charAt(position)))
			position++;
	}

	// 读取当前运算符或括号；不匹配时保留位置供后续语法分支判断。
	private boolean take(char c) {
		space();
		if (position < input.length() && input.charAt(position) == c) {
			position++;
			return true;
		}
		return false;
	}

	// 加减层先调用乘除层，保证常规四则运算优先级。
	private BigDecimal expression() {
		BigDecimal value = term();
		while (true) {
			if (take('+'))
				value = n(value.add(term(), MC));
			else if (take('-'))
				value = n(value.subtract(term(), MC));
			else
				return value;
		}
	}

	// 乘除从左向右计算；校验模式只走语法，真正计算时明确拒绝零除数。
	private BigDecimal term() {
		BigDecimal value = atom();
		while (true) {
			if (take('*'))
				value = n(value.multiply(atom(), MC));
			else if (take('/')) {
				BigDecimal divisor = atom();
				if (validate)
					value = ONE;
				else {
					if (divisor.signum() == 0)
						throw new RuleException("公式出现除以0");
					value = n(value.divide(divisor, MC));
				}
			}
			else
				return value;
		}
	}

	// 每个原子表达式计入复杂度，累计最多 160 个节点且嵌套最多 40 层。
	private BigDecimal atom() {
		if (++nodes > 160 || ++depth > 40)
			throw new RuleException("公式过于复杂");
		try {
			return primary();
		}
		finally {
			depth--;
		}
	}

	/**
	 * 解析正负号、括号、数字、变量及函数；变量名需以小写字母开头且最多 40 个字符。
	 *
	 */
	private BigDecimal primary() {
		if (take('+'))
			return atom();
		if (take('-'))
			return atom().negate();
		if (take('(')) {
			BigDecimal value = expression();
			if (!take(')'))
				throw new RuleException("公式缺少右括号");
			return value;
		}
		space();
		int start = position;
		if (position < input.length() && (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.')) {
			while (position < input.length()
					&& (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.'))
				position++;
			if (position < input.length() && "eE".indexOf(input.charAt(position)) >= 0) {
				position++;
				if (position < input.length() && "+-".indexOf(input.charAt(position)) >= 0)
					position++;
				while (position < input.length() && Character.isDigit(input.charAt(position)))
					position++;
			}
			return n(input.substring(start, position));
		}
		while (position < input.length()
				&& (Character.isLetterOrDigit(input.charAt(position)) || input.charAt(position) == '_'))
			position++;
		String name = input.substring(start, position);
		if (!name.matches("[a-z][a-z0-9_]{0,39}"))
			throw new RuleException("变量名需要小写英文字母、数字或下划线");
		if (!take('(')) {
			names.add(name);
			if (validate)
				return ONE;
			if (!variables.containsKey(name))
				throw new RuleException("缺少变量：" + name);
			return n(variables.get(name));
		}
		// 成本算式只能调用这些数学函数，函数名不会被当成普通变量收集。
		if (!Set.of("min", "max", "abs", "ceil", "floor", "round").contains(name))
			throw new RuleException("不允许调用此函数");
		List<BigDecimal> args = new ArrayList<>();
		if (!take(')')) {
			do {
				if (args.size() >= 20)
					throw new RuleException("函数参数过多");
				args.add(expression());
			}
			while (take(','));
			if (!take(')'))
				throw new RuleException("公式缺少右括号");
		}
		// 每个函数至少一个参数；abs/ceil/floor 固定一个，round 可附带小数位数，任何函数最多 20 个参数。
		if (args.isEmpty() || (Set.of("abs", "ceil", "floor").contains(name) && args.size() != 1)
				|| (name.equals("round") && args.size() > 2))
			throw new RuleException("函数参数数量不正确");
		if (validate)
			return ONE;
		// ceil/floor 取整到整数，round 按四舍五入保留 0～8 位；不会隐式套用风管或设备的最终结算规则。
		return switch (name) {
			case "min" -> Collections.min(args);
			case "max" -> Collections.max(args);
			case "abs" -> args.get(0).abs();
			case "ceil" -> args.get(0).setScale(0, RoundingMode.CEILING);
			case "floor" -> args.get(0).setScale(0, RoundingMode.FLOOR);
			default -> {
				int digits;
				try {
					digits = args.size() == 1 ? 0 : args.get(1).intValueExact();
				}
				catch (ArithmeticException e) {
					throw new RuleException("round小数位需为0到8的整数");
				}
				if (digits < 0 || digits > 8)
					throw new RuleException("round小数位需为0到8的整数");
				yield args.get(0).setScale(digits, RoundingMode.HALF_UP);
			}
		};
	}

}
