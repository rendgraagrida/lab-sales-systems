package dev.labtrial.salestools;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.SourceAmounts;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceAmountsTest {
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, decimal(expected).compareTo(actual));
    }

    @Test void procurementTaxIsCalculatedFromDiscountedSourceNet() {
        var source = SourceAmounts.price(decimal("10"), decimal("60"), decimal("4"), null, decimal("7"), 0);
        amount("600.00", source.subtotal());
        amount("24.00", source.discount());
        amount("576.00", source.net());
        amount("40.32", source.tax());
        amount("616.32", source.gross());
    }

    @Test void partialSourceAmountsPreserveNetTaxAndDiscountResiduals() {
        var source = SourceAmounts.price(decimal("3"), decimal("0.50"), null, decimal("0.50"), decimal("7"), 0);
        var first = SourceAmounts.slice(source, decimal("0"), decimal("1"));
        var middle = SourceAmounts.slice(source, decimal("1"), decimal("2"));
        var last = SourceAmounts.slice(source, decimal("2"), decimal("3"));
        amount("0.33", first.net());
        amount("0.34", middle.net());
        amount("0.33", last.net());
        amount("0.17", first.discount());
        amount("0.16", middle.discount());
        amount("0.17", last.discount());
        amount("0.02", first.tax());
        amount("0.03", middle.tax());
        amount("0.02", last.tax());
        amount("0.50", middle.subtotal());
        amount("0.37", middle.gross());
        amount("1.00", first.net().add(middle.net()).add(last.net()));
        amount("0.07", first.tax().add(middle.tax()).add(last.tax()));
        amount("0.50", first.discount().add(middle.discount()).add(last.discount()));
    }

    @Test void fractionalQuantitiesFollowConfiguredUnitPrecision() {
        var source = SourceAmounts.price(decimal("1.25"), decimal("8"), null, null, decimal("7"), 2);
        amount("10.00", source.net());
        amount("0.70", source.tax());
        assertThrows(DomainException.class, () -> SourceAmounts.price(decimal("1.25"), decimal("8"), null, null, decimal("7"), 0));
    }

    @Test void conflictingDiscountMethodsCannotChargeOrDiscountTwice() {
        assertThrows(DomainException.class, () -> SourceAmounts.price(decimal("10"), decimal("60"), decimal("4"), decimal("24"), decimal("7"), 0));
        assertThrows(DomainException.class, () -> SourceAmounts.price(decimal("1"), decimal("10"), null, decimal("10.01"), decimal("7"), 0));
    }

    @Test void invalidIntervalsCannotCreateOverbillingOrNegativeEntitlement() {
        var source = SourceAmounts.price(decimal("3"), decimal("10"), null, null, decimal("7"), 0);
        assertThrows(DomainException.class, () -> SourceAmounts.slice(source, decimal("-1"), decimal("1")));
        assertThrows(DomainException.class, () -> SourceAmounts.slice(source, decimal("1"), decimal("1")));
        assertThrows(DomainException.class, () -> SourceAmounts.slice(source, decimal("2"), decimal("4")));
    }

    @Test void extremeNumbersAndOverflowAreRejectedBeforePersistence() {
        assertThrows(DomainException.class, () -> SourceAmounts.price(new BigDecimal("1e2147483647"), decimal("1"), null, null, decimal("7"), 0));
        assertThrows(DomainException.class, () -> SourceAmounts.price(decimal("999999999999"), decimal("999999999999"), null, null, decimal("7"), 0));
        assertThrows(DomainException.class, () -> SourceAmounts.price(decimal("0"), decimal("1"), null, null, decimal("7"), 0));
    }
    @Test void partialServiceFeeBillingPreservesDiscountAndTaxWithoutFractionalCoverage() {
        var first=SourceAmounts.feeSlice(decimal("0.06"),decimal("0.02"),decimal("0.01"),decimal("0.00"),decimal("0.02"));
        var middle=SourceAmounts.feeSlice(decimal("0.06"),decimal("0.02"),decimal("0.01"),decimal("0.02"),decimal("0.04"));
        var last=SourceAmounts.feeSlice(decimal("0.06"),decimal("0.02"),decimal("0.01"),decimal("0.04"),decimal("0.06"));
        amount("0.01",first.discount()); amount("0.00",middle.discount()); amount("0.01",last.discount());
        amount("0.00",first.tax()); amount("0.01",middle.tax()); amount("0.00",last.tax());
        amount("0.08",first.subtotal().add(middle.subtotal()).add(last.subtotal()));
        amount("0.07",first.gross().add(middle.gross()).add(last.gross()));
    }

    @Test void serviceFeeCoordinatesUseMoneyCapacityAndRejectUnbillableOrUnsafeRanges() {
        var lastCent=SourceAmounts.feeSlice(decimal("9999999999999999.99"),decimal("0"),decimal("0"),
                decimal("9999999999999999.98"),decimal("9999999999999999.99"));
        amount("0.01",lastCent.net());
        assertThrows(DomainException.class,()->SourceAmounts.feeSlice(decimal("0"),decimal("0"),decimal("0"),decimal("0"),decimal("0.01")));
        assertThrows(DomainException.class,()->SourceAmounts.feeSlice(decimal("1"),decimal("0"),decimal("0"),decimal("0"),decimal("1.01")));
        assertThrows(DomainException.class,()->SourceAmounts.feeSlice(decimal("1"),decimal("0"),decimal("0"),decimal("0"),decimal("0.001")));
        assertThrows(DomainException.class,()->SourceAmounts.feeSlice(decimal("1"),decimal("0"),decimal("0"),decimal("1e2147483647"),decimal("1")));
    }
}
