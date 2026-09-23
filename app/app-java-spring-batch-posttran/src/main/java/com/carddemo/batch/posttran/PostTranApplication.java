package com.carddemo.batch.posttran;

import com.carddemo.batch.posttran.listener.PostTranExitCodeMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Java replacement for COBOL batch program CBTRN02C (job POSTTRAN). */
@SpringBootApplication
public class PostTranApplication {

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** Runs the job and returns the process exit code (0 clean, 4 rejects, 8 failure) without exiting the JVM. */
    public static int execute(String... args) {
        try (ConfigurableApplicationContext ctx = new SpringApplication(PostTranApplication.class).run(args)) {
            return SpringApplication.exit(ctx);
        } catch (Exception e) {
            System.out.println("ERROR " + e.getMessage());
            System.out.println("ABENDING PROGRAM");
            return PostTranExitCodeMapper.FAILURE;
        }
    }
}
