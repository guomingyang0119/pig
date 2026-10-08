package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.BigInteger;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * 设备售价使用的精确有理数，分子分母均为整数并自动约分。
 * 成本除以毛利分母或税系数可能产生循环小数，连续计算保持分数形式，直到最终售价向上取整。
 * 展示用 decimal() 才转为十进制，避免把展示精度误差带回结算。
 * @param numerator 分子，可表示正数、零或负数
 * @param denominator 分母，必须非零并统一为正数
 */
record EquipmentFraction(BigInteger numerator, BigInteger denominator) implements Comparable<EquipmentFraction> {
	// 规范符号并除以最大公约数，保证比较、运算和取整共用同一精确值。
	EquipmentFraction {
		if (denominator.signum() == 0)
			throw new RuleException("公式出现除以0");
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		BigInteger gcd = numerator.gcd(denominator);
		numerator = numerator.divide(gcd);
		denominator = denominator.divide(gcd);
	}

	/**
	 * 按 BigDecimal 的有效整数和小数位数转换为精确分数；负 scale 的数值也不会丢失整数位。
	 *
	 */
	static EquipmentFraction of(BigDecimal value) {
		return value.scale() >= 0 ? new EquipmentFraction(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
				: new EquipmentFraction(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())),
						BigInteger.ONE);
	}

	// 通分相加，合并售价组成项时保留精确值。
	EquipmentFraction add(EquipmentFraction b) {
		return new EquipmentFraction(numerator.multiply(b.denominator).add(b.numerator.multiply(denominator)),
				denominator.multiply(b.denominator));
	}

	// 精确乘入税系数、外形系数或设备数量。
	EquipmentFraction multiply(EquipmentFraction b) {
		return new EquipmentFraction(numerator.multiply(b.numerator), denominator.multiply(b.denominator));
	}

	// 精确除以毛利分母或税系数；零除数由构造器统一拒绝。
	EquipmentFraction divide(EquipmentFraction b) {
		return new EquipmentFraction(numerator.multiply(b.denominator), denominator.multiply(b.numerator));
	}

	// 仅供明细展示，使用报价模块统一的 48 位有效数字上下文。
	BigDecimal decimal() {
		return new BigDecimal(numerator).divide(new BigDecimal(denominator), MC);
	}

	/**
	 * 通过整数商和余数直接向上取整到元，无需先转为近似十进制。
	 * 正余数才加 1；负数商向零截断已符合数学向上取整。
	 *
	 */
	BigDecimal ceil() {
		BigInteger[] qr = numerator.divideAndRemainder(denominator);
		return new BigDecimal(qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0]);
	}

	// 交叉相乘比较精确值，避免循环小数被误判为相等或跨越价格边界。
	@Override
	public int compareTo(EquipmentFraction b) {
		return numerator.multiply(b.denominator).compareTo(b.numerator.multiply(denominator));
	}
}
