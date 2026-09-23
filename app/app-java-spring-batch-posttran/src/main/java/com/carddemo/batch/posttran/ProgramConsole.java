package com.carddemo.batch.posttran;

import org.springframework.stereotype.Component;

import java.io.PrintStream;

/** Mirrors the COBOL {@code DISPLAY} statements (spec section 7.4) so existing log scrapers keep working. */
@Component
public class ProgramConsole {

    private final PrintStream out;

    public ProgramConsole() {
        this(System.out);
    }

    public ProgramConsole(PrintStream out) {
        this.out = out;
    }

    public void display(String line) {
        out.println(line);
        out.flush();
    }
}
