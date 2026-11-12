package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Immutable monetary snapshots and cumulative, residual-preserving source slices. */
public final class SourceAmounts {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private SourceAmounts() {}

    public record Snapshot(BigDecimal quantity, BigDecimal unitCost, BigDecimal subtotal, BigDecimal discount,
                           BigDecimal net, BigDecimal tax, BigDecimal gross) {}
    public record Slice(BigDecimal start, BigDecimal end, BigDecimal subtotal, BigDecimal discount,
                        BigDecimal net, BigDecimal tax, BigDecimal gross) {}

    public static Snapshot price(BigDecimal quantity, BigDecimal unitCost, BigDecimal discountPct,
                                 BigDecimal discountAmount, BigDecimal taxRatePct, int quantityScale) {
        if (quantityScale < 0 || quantityScale > 6) throw invalid("Invalid quantity scale");
        check(quantity, 18, 6, "quantity");
        check(unitCost, 18, 6, "unit cost");
        check(taxRatePct, 9, 4, "tax rate");
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > quantityScale)
            throw invalid("Quantity must be positive and match the unit precision");
        if (unitCost.signum() < 0 || taxRatePct.signum() < 0 || taxRatePct.compareTo(HUNDRED) > 0)
            throw invalid("Invalid price or tax rate");
        if (discountPct != null && discountAmount != null) throw invalid("Choose one discount method");
        if (discountPct != null) {
            check(discountPct, 9, 4, "discount rate");
            if (discountPct.signum() < 0 || discountPct.compareTo(HUNDRED) > 0) throw invalid("Invalid discount rate");
        }
        if (discountAmount != null) {
            check(discountAmount, 18, 2, "discount");
            if (discountAmount.signum() < 0) throw invalid("Invalid discount");
        }
        BigDecimal subtotal = money(quantity.multiply(unitCost));
        BigDecimal discount = discountPct != null ? money(subtotal.multiply(discountPct).divide(HUNDRED))
                : discountAmount != null ? discountAmount.setScale(2) : BigDecimal.ZERO.setScale(2);
        if (discount.compareTo(subtotal) > 0) throw invalid("Discount exceeds subtotal");
        BigDecimal net = subtotal.subtract(discount);
        BigDecimal tax = money(net.multiply(taxRatePct).divide(HUNDRED));
        BigDecimal gross = money(net.add(tax));
        return new Snapshot(quantity, unitCost, subtotal, discount, net, tax, gross);
    }

    public static Slice slice(Snapshot source, BigDecimal start, BigDecimal end) {
        if (source == null) throw invalid("Source is required");
        check(source.quantity(), 18, 6, "source quantity");
        check(source.unitCost(), 18, 6, "source unit cost");
        if (source.unitCost().signum()<0) throw invalid("Invalid source unit cost");
        check(start, 18, 6, "slice start");
        check(end, 18, 6, "slice end");
        if (source.quantity().signum() <= 0 || start.signum() < 0 || start.compareTo(end) >= 0 || end.compareTo(source.quantity()) > 0)
            throw invalid("Invalid source interval");
        check(source.discount(), 18, 2, "source discount");
        check(source.subtotal(), 18, 2, "source subtotal");
        check(source.gross(), 18, 2, "source gross");
        check(source.net(), 18, 2, "source net");
        check(source.tax(), 18, 2, "source tax");
        if (source.discount().signum()<0 || source.net().signum()<0 || source.tax().signum()<0
                || source.subtotal().compareTo(source.net().add(source.discount()))!=0
                || source.gross().compareTo(source.net().add(source.tax()))!=0)
            throw invalid("Invalid source snapshot");
        BigDecimal d = part(source.discount(), source.quantity(), start, end);
        BigDecimal n = part(source.net(), source.quantity(), start, end);
        BigDecimal t = part(source.tax(), source.quantity(), start, end);
        return new Slice(start, end, n.add(d), d, n, t, n.add(t));
    }

    private static BigDecimal part(BigDecimal amount, BigDecimal quantity, BigDecimal start, BigDecimal end) {
        return cumulative(amount, quantity, end).subtract(cumulative(amount, quantity, start));
    }
    private static BigDecimal cumulative(BigDecimal amount, BigDecimal quantity, BigDecimal point) {
        return amount.multiply(point).divide(quantity, 2, RoundingMode.HALF_UP);
    }
    private static BigDecimal money(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        check(rounded, 18, 2, "amount");
        return rounded;
    }
    private static void check(BigDecimal value, int precision, int scale, String name) {
        if (value == null || value.scale() > scale || value.scale() < -precision || (long) value.precision() - value.scale() > precision - scale)
            throw invalid("Invalid " + name);
    }
    private static DomainException invalid(String message) { return new DomainException(400, message); }
}
