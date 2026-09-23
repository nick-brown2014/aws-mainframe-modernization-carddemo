package com.carddemo.posttran;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class PostingJobApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PostingJobApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
