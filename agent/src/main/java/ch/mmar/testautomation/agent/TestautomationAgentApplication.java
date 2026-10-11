package ch.mmar.testautomation.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the testautomation-agent.
 *
 * <p>Current state: bare Spring Boot skeleton (Java 21, Maven). The agent does not talk to the
 * service yet — the claim protocol (see {@code docs/postgres-job-queue.md}, §8) has to be settled
 * first.
 */
@SpringBootApplication
public class TestautomationAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestautomationAgentApplication.class, args);
    }
}
