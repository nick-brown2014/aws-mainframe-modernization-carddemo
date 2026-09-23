package com.carddemo.batch.posttran.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-T1 (spec 4.8). */
class Db2TimestampFormatterTest {

    @Test
    @DisplayName("AC-T1: centiseconds truncated (not rounded), zero-filled to microseconds")
    void formatsDb2StyleTimestamp() {
        String ts = Db2TimestampFormatter.format(LocalDateTime.of(2026, 9, 23, 18, 15, 42, 376_000_000));
        assertThat(ts).isEqualTo("2026-09-23-18.15.42.370000").hasSize(26);
    }

    @Test
    void truncatesNotRounds() {
        assertThat(Db2TimestampFormatter.format(LocalDateTime.of(2026, 1, 1, 0, 0, 0, 999_999_999)))
                .isEqualTo("2026-01-01-00.00.00.990000");
        assertThat(Db2TimestampFormatter.format(LocalDateTime.of(2026, 1, 1, 0, 0, 0, 9_000_000)))
                .isEqualTo("2026-01-01-00.00.00.000000");
    }

    @Test
    void usesInjectedClockZone() {
        Clock utcMidnight = Clock.fixed(Instant.parse("2026-09-24T03:15:42.376Z"), ZoneId.of("America/Chicago"));
        assertThat(new Db2TimestampFormatter(utcMidnight).now()).isEqualTo("2026-09-23-22.15.42.370000");
    }
}
