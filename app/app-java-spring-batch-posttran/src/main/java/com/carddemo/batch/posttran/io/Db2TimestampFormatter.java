package com.carddemo.batch.posttran.io;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Z-GET-DB2-FORMAT-TIMESTAMP: {@code YYYY-MM-DD-HH.MM.SS.hh0000} (26 chars). COBOL CURRENT-DATE has
 * centisecond precision, so the fraction is truncated (not rounded) to 2 digits and zero-filled to 6.
 */
public final class Db2TimestampFormatter {

    public static final int LENGTH = 26;

    private static final DateTimeFormatter PREFIX = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss");

    private final Clock clock;

    public Db2TimestampFormatter(Clock clock) {
        this.clock = clock;
    }

    public static String format(LocalDateTime ts) {
        int centis = ts.getNano() / 10_000_000;
        return PREFIX.format(ts) + "." + (centis < 10 ? "0" : "") + centis + "0000";
    }

    public String now() {
        return format(LocalDateTime.now(clock));
    }
}
