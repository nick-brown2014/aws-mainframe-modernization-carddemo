package com.carddemo.posttran;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountTest {
    @Test
    void postingRoutesSignedAmountToMatchingCycleTotal() {
        Account before = new Account("00000000001", "Y", new BigDecimal("100.00"),
                new BigDecimal("1000.00"), new BigDecimal("100.00"), "2020-01-01",
                "2030-01-01", "2020-01-01", new BigDecimal("20.25"),
                new BigDecimal("-4.50"), "12345     ", "A         ", " ".repeat(178));
        Account after = before.post(new BigDecimal("2.75")).post(new BigDecimal("-1.25"));
        assertEquals(new BigDecimal("101.50"), after.currBal());
        assertEquals(new BigDecimal("23.00"), after.currCycCredit());
        assertEquals(new BigDecimal("-5.75"), after.currCycDebit());
        assertEquals(new BigDecimal("100.00"), before.currBal());
        TranCatBalance balance = new TranCatBalance(before.id(), "01", "0001",
                new BigDecimal("10.00"), " ".repeat(22));
        assertEquals(new BigDecimal("11.50"), balance.add(new BigDecimal("1.50")).balance());
    }
}
