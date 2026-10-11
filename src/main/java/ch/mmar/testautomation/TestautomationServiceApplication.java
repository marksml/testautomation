package ch.mmar.testautomation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the testautomation-service.
 *
 * <p>Current state: bare Spring Boot skeleton (Java 21, Maven). No REST endpoints and no
 * persistence are wired up yet; those are added once the job queue design is settled.
 */
@SpringBootApplication
public class TestautomationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestautomationServiceApplication.class, args);
    }
}
