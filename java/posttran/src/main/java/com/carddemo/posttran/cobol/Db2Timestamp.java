package com.carddemo.posttran.cobol;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Java equivalent of paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP}.
 *
 * <p>COBOL takes {@code FUNCTION CURRENT-DATE} ({@code YYYYMMDDHHMMSShh...}, hundredths of a
 * second) and rearranges it into the 26-byte DB2 layout {@code YYYY-MM-DD-HH.MM.SS.hh0000}: the two
 * hundredths digits followed by a literal {@code 0000}.
 */
public final class Db2Timestamp {

    public static final int LENGTH = 26;

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS");

    private final Clock clock;

    public Db2Timestamp(Clock clock) {
        this.clock = clock;
    }

    public Db2Timestamp() {
        this(Clock.systemDefaultZone());
    }

    public String now() {
        return format(LocalDateTime.now(clock));
    }

    public static String format(LocalDateTime dateTime) {
        return FORMAT.format(dateTime) + "0000";
    }
}
