package com.carddemo.posttran;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the POSTTRAN job (COBOL program CBTRN02C). The JVM exit code is 0 when every
 * record posted, 4 when at least one record was rejected (COBOL RETURN-CODE 4), and 1 on failure.
 */
@SpringBootApplication
public class PostTranApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(PostTranApplication.class, args)));
    }
}
