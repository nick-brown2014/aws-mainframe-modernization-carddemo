package com.carddemo.batch.posttran;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.time.ZoneId;

@ConfigurationProperties(prefix = "posttran")
public record PostTranProperties(Clock clock, Input input, Output output) {

    public PostTranProperties {
        clock = clock == null ? new Clock(null) : clock;
        input = input == null ? new Input(null) : input;
        output = output == null ? new Output(null, null) : output;
    }

    public record Clock(ZoneId zone) {
        public Clock {
            zone = zone == null ? ZoneId.of("America/Chicago") : zone;
        }
    }

    public record Input(Charset charset) {
        public Input {
            charset = charset == null ? Charset.forName("UTF-8") : charset;
        }
    }

    public record Output(Charset charset, String lineSeparator) {
        public Output {
            charset = charset == null ? Charset.forName("UTF-8") : charset;
            lineSeparator = lineSeparator == null ? "\n" : lineSeparator;
        }
    }
}
