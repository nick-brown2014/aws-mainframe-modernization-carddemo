package com.carddemo.posttran.cobol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CobolFieldsTest {

    @Test
    void parsesUnsignedZonedWithImpliedDecimals() {
        assertThat(CobolFields.parseZoned("00000005047", 2)).isEqualByComparingTo("50.47");
        assertThat(CobolFields.parseZoned("00000005047", 2).scale()).isEqualTo(2);
    }

    @Test
    void parsesPositiveOverpunch() {
        // 'G' = +7, '{' = +0
        assertThat(CobolFields.parseZoned("0000000504G", 2)).isEqualByComparingTo("50.47");
        assertThat(CobolFields.parseZoned("00000001940{", 2)).isEqualByComparingTo("194.00");
    }

    @Test
    void parsesNegativeOverpunch() {
        // '}' = -0, 'J'..'R' = -1..-9
        assertThat(CobolFields.parseZoned("0000009190}", 2)).isEqualByComparingTo("-919.00");
        assertThat(CobolFields.parseZoned("0000000012J", 2)).isEqualByComparingTo("-1.21");
        assertThat(CobolFields.parseZoned("0000000012R", 2)).isEqualByComparingTo("-1.29");
    }

    @Test
    void parsesExplicitSignAndBlanks() {
        assertThat(CobolFields.parseZoned("-0000000100", 2)).isEqualByComparingTo("-1.00");
        assertThat(CobolFields.parseZoned("           ", 2)).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> CobolFields.parseZoned("12X4", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsZonedRoundTrip() {
        assertThat(CobolFields.formatZoned(new BigDecimal("50.47"), 11, 2, true)).isEqualTo("00000005047");
        assertThat(CobolFields.formatZoned(new BigDecimal("-919.00"), 11, 2, true)).isEqualTo("0000009190}");
        assertThat(CobolFields.formatZoned(new BigDecimal("-1.21"), 11, 2, true)).isEqualTo("0000000012J");
        assertThat(CobolFields.parseZoned(CobolFields.formatZoned(new BigDecimal("-123.45"), 11, 2, true), 2))
                .isEqualByComparingTo("-123.45");
        assertThat(CobolFields.formatUnsigned(103, 4)).isEqualTo("0103");
    }

    @Test
    void alnumPadsAndTruncatesLikeMove() {
        assertThat(CobolFields.alnum("AB", 4)).isEqualTo("AB  ");
        assertThat(CobolFields.alnum("ABCDEF", 4)).isEqualTo("ABCD");
        assertThat(CobolFields.alnum(null, 2)).isEqualTo("  ");
    }
}
