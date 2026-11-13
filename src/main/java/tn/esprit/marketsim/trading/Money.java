package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conventions monetaires du module 2 : BigDecimal partout, prix / quantites a 6 decimales, montants a 2. */
public final class Money {

    public static final int PRICE_SCALE = 6;
    public static final int AMOUNT_SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Money() {
    }

    public static BigDecimal price(BigDecimal v) {
        return v == null ? null : v.setScale(PRICE_SCALE, ROUNDING);
    }

    public static BigDecimal amount(BigDecimal v) {
        return v == null ? null : v.setScale(AMOUNT_SCALE, ROUNDING);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }

    public static BigDecimal orZero(BigDecimal v) {
        return v == null ? zero() : v;
    }
}
