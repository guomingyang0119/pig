package com.pig4cloud.pig.admin.quotation;

import java.math.BigDecimal;
import java.math.BigInteger;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

/**
 * Exact supply-price arithmetic: repeating decimals must not cross freight/ceiling
 * boundaries.
 */
record EquipmentFraction(BigInteger numerator, BigInteger denominator) implements Comparable<EquipmentFraction> {
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

	static EquipmentFraction of(BigDecimal value) {
		return value.scale() >= 0 ? new EquipmentFraction(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
				: new EquipmentFraction(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())),
						BigInteger.ONE);
	}

	EquipmentFraction add(EquipmentFraction b) {
		return new EquipmentFraction(numerator.multiply(b.denominator).add(b.numerator.multiply(denominator)),
				denominator.multiply(b.denominator));
	}

	EquipmentFraction multiply(EquipmentFraction b) {
		return new EquipmentFraction(numerator.multiply(b.numerator), denominator.multiply(b.denominator));
	}

	EquipmentFraction divide(EquipmentFraction b) {
		return new EquipmentFraction(numerator.multiply(b.denominator), denominator.multiply(b.numerator));
	}

	BigDecimal decimal() {
		return new BigDecimal(numerator).divide(new BigDecimal(denominator), MC);
	}

	BigDecimal ceil() {
		BigInteger[] qr = numerator.divideAndRemainder(denominator);
		return new BigDecimal(qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0]);
	}

	@Override
	public int compareTo(EquipmentFraction b) {
		return numerator.multiply(b.denominator).compareTo(b.numerator.multiply(denominator));
	}
}
