package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * A bounded arithmetic parser. No scripting engine, reflection or method/property access.
 */
public final class DecimalFormula {

	private final String input;

	private final Map<String, Object> variables;

	private final Set<String> names = new HashSet<>();

	private final boolean validate;

	private int position;

	private int nodes;

	private int depth;

	private DecimalFormula(String input, Map<String, Object> variables, boolean validate) {
		if (input == null || input.isBlank() || input.length() > 800)
			throw new RuleException("公式未配置或过长");
		this.input = input;
		this.variables = variables;
		this.validate = validate;
	}

	public static BigDecimal evaluate(String expression, Map<String, Object> variables) {
		return new DecimalFormula(expression, variables, false).parse();
	}

	public static Set<String> variables(String expression) {
		DecimalFormula parser = new DecimalFormula(expression, Map.of(), true);
		parser.parse();
		return parser.names;
	}

	private BigDecimal parse() {
		BigDecimal value = expression();
		space();
		if (position != input.length())
			throw new RuleException("公式只支持四则运算、括号和受限函数");
		return n(value);
	}

	private void space() {
		while (position < input.length() && Character.isWhitespace(input.charAt(position)))
			position++;
	}

	private boolean take(char c) {
		space();
		if (position < input.length() && input.charAt(position) == c) {
			position++;
			return true;
		}
		return false;
	}

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
		if (args.isEmpty() || (Set.of("abs", "ceil", "floor").contains(name) && args.size() != 1)
				|| (name.equals("round") && args.size() > 2))
			throw new RuleException("函数参数数量不正确");
		if (validate)
			return ONE;
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
