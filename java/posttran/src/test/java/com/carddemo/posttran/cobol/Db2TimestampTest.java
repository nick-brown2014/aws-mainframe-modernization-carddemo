package com.carddemo.posttran.cobol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class Db2TimestampTest {

    @Test
    void formatsDb2LayoutWithHundredthsAndLiteralZeros() {
        String ts = Db2Timestamp.format(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_789));
        assertThat(ts).isEqualTo("2022-06-10-19.27.53.120000");
        assertThat(ts).hasSize(Db2Timestamp.LENGTH);
    }

    @Test
    void usesInjectedClock() {
        Clock clock = Clock.fixed(Instant.parse("2025-01-02T03:04:05.67Z"), ZoneOffset.UTC);
        assertThat(new Db2Timestamp(clock).now()).isEqualTo("2025-01-02-03.04.05.670000");
    }
}
