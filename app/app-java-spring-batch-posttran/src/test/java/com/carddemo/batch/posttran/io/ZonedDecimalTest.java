package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-R1, AC-R2, AC-R3 (spec 4.7 / 8.2). */
class ZonedDecimalTest {

    @Test
    @DisplayName("AC-R1: positive overpunch G -> 504.77")
    void parsesPositiveOverpunch() {
        BigDecimal v = ZonedDecimal.parse("0000005047G");
        assertThat(v).isEqualByComparingTo("504.77");
        assertThat(v.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-R2: negative overpunch } -> -123.40")
    void parsesNegativeOverpunch() {
        assertThat(ZonedDecimal.parse("0000001234}")).isEqualTo(new BigDecimal("-123.40"));
    }

    @Test
    @DisplayName("AC-R3: format round-trips both signs")
    void formatsWithOverpunch() {
        assertThat(ZonedDecimal.format(new BigDecimal("-123.40"), 11)).isEqualTo("0000001234}");
        assertThat(ZonedDecimal.format(new BigDecimal("504.77"), 11)).isEqualTo("0000005047G");
        assertThat(ZonedDecimal.format(new BigDecimal("0.00"), 11)).isEqualTo("0000000000{");
    }

    @Test
    @DisplayName("AC-R3: every amount in the 300-record sample file round-trips byte-for-byte")
    void roundTripsAllSampleAmounts() throws IOException {
        List<String> lines = Files.readAllLines(TestData.ASCII_DATA.resolve("dailytran.txt"), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(300);
        for (String line : lines) {
            String field = FixedWidth.slice(line, 133, 11);
            BigDecimal parsed = ZonedDecimal.parse(field);
            assertThat(parsed.scale()).isEqualTo(2);
            assertThat(ZonedDecimal.format(parsed, 11)).as(field).isEqualTo(field);
        }
    }

    @Test
    void parsesUnsignedDigitsAsPositive() {
        assertThat(ZonedDecimal.parse("00000012345")).isEqualTo(new BigDecimal("123.45"));
    }

    @Test
    void failsFastOnMalformedInput() {
        assertThatThrownBy(() -> ZonedDecimal.parse("00000ABCDE{")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZonedDecimal.parse("0000005047X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZonedDecimal.parse("")).isInstanceOf(IllegalArgumentException.class);
    }
}
