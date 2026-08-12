package me.arthed.smartgambling.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Exact positive money value. Conversion to {@code double} only happens at the Vault boundary. */
public record Money(long unscaled, int scale) implements Comparable<Money> {
    public static final int MAX_SCALE = 8;

    public Money {
        if (unscaled <= 0L) {
            throw new IllegalArgumentException("Money must be greater than zero");
        }
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("Money scale must be between 0 and " + MAX_SCALE);
        }
    }

    public static Money of(long whole) {
        return new Money(whole, 0);
    }

    public static Money of(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("Money must be finite and greater than zero");
        }
        return of(BigDecimal.valueOf(value));
    }

    public static Money of(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("Money must be greater than zero");
        }
        int normalizedScale = Math.max(0, normalized.scale());
        if (normalizedScale > MAX_SCALE) {
            throw new IllegalArgumentException("Money has more than " + MAX_SCALE + " decimal places");
        }
        return new Money(normalized.setScale(normalizedScale).unscaledValue().longValueExact(), normalizedScale);
    }

    public BigDecimal decimal() {
        return BigDecimal.valueOf(this.unscaled, this.scale);
    }

    public double vaultAmount() {
        double value = decimal().doubleValue();
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new ArithmeticException("Money cannot be represented by Vault's double API");
        }
        return value;
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "other");
        return of(decimal().add(other.decimal()));
    }

    public Money multiply(BigDecimal factor, int resultScale) {
        Objects.requireNonNull(factor, "factor");
        if (factor.signum() <= 0 || resultScale < 0 || resultScale > MAX_SCALE) {
            throw new IllegalArgumentException("Invalid money multiplier or result scale");
        }
        return of(decimal().multiply(factor).setScale(resultScale, RoundingMode.HALF_UP));
    }

    @Override
    public int compareTo(Money other) {
        return decimal().compareTo(other.decimal());
    }
}
