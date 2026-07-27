package com.jobseekercopilot.documentgenerationgateway.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobseekercopilot.documentgenerationgateway.exception.GenerationDeadlineExceededException;
import com.jobseekercopilot.documentgenerationgateway.generation.OperationDeadlineGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class OperationDeadlineHttpTest {

    @Test
    void remainingOperationBudgetBoundsTheActualHttpRead() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
                byte[] response =
                        "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var guard = new OperationDeadlineGuard();
            var restTemplate = new DownstreamApiConfig().restTemplate(
                    new RestTemplateBuilder(),
                    guard,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(2));
            Instant started = Instant.now();

            assertThrows(
                    GenerationDeadlineExceededException.class,
                    () -> guard.call(
                            started.plusMillis(150),
                            () -> restTemplate.getForObject(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/slow",
                                    String.class)));

            assertTrue(
                    Duration.between(started, Instant.now())
                            .compareTo(Duration.ofSeconds(1)) < 0,
                    "HTTP read exceeded the bounded operation budget");
        } finally {
            server.stop(0);
        }
    }
}
