package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GenerationOperationRepositoryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    @Autowired private GenerationOperationRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private GenerationDownstreamClient downstream;

    @BeforeEach
    void clearOperations() {
        jdbc.update("DELETE FROM generation_operations");
    }

    @Test
    void migrationEnforcesOneOperationAndOneLeaseForConcurrentOwnerJob()
            throws Exception {
        UUID savedJobId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.createOrReplay(
                        "owner-1",
                        "concurrent-key-a",
                        savedJobId,
                        "a".repeat(64),
                        Duration.ofMinutes(10));
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.createOrReplay(
                        "owner-1",
                        "concurrent-key-b",
                        savedJobId,
                        "a".repeat(64),
                        Duration.ofMinutes(10));
            });
            start.countDown();
            GenerationOperation firstOperation =
                    first.get(10, TimeUnit.SECONDS);
            GenerationOperation secondOperation =
                    second.get(10, TimeUnit.SECONDS);

            assertEquals(firstOperation.id(), secondOperation.id());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM generation_operations",
                            Integer.class));

            UUID firstLease = UUID.randomUUID();
            UUID secondLease = UUID.randomUUID();
            List<Boolean> leaseResults = List.of(
                    repository.tryAcquire(
                            firstOperation.id(),
                            "owner-1",
                            firstLease,
                            Duration.ofSeconds(30)),
                    repository.tryAcquire(
                            firstOperation.id(),
                            "owner-1",
                            secondLease,
                            Duration.ofSeconds(30)));
            assertEquals(1, leaseResults.stream()
                    .filter(Boolean::booleanValue)
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }
}
