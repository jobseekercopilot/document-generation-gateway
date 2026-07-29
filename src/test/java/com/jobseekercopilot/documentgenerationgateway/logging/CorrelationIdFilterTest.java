package com.jobseekercopilot.documentgenerationgateway.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter =
            new CorrelationIdFilter("document-generation-gateway");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesABoundedSafeCorrelationId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(
                CorrelationIdFilter.HEADER_NAME,
                "browser-request_123");
        var response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                assertingChain("browser-request_123"));

        assertEquals(
                "browser-request_123",
                response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void replacesUnboundedOrUnsafeCorrelationInput() throws Exception {
        String unsafe = "candidate@example.com\r\n"
                + "X-Injected: true"
                + "x".repeat(100);
        var request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, unsafe);
        var response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (innerRequest, innerResponse) -> {
                    String normalized = MDC.get(
                            CorrelationIdFilter.MDC_KEY);
                    assertTrue(CorrelationIds.isValid(normalized));
                    assertFalse(unsafe.equals(normalized));
                });

        String returned = response.getHeader(
                CorrelationIdFilter.HEADER_NAME);
        assertTrue(CorrelationIds.isValid(returned));
        assertFalse(unsafe.equals(returned));
    }

    private MockFilterChain assertingChain(String expected) {
        return new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest request,
                    jakarta.servlet.ServletResponse response)
                    throws IOException, ServletException {
                assertEquals(
                        expected,
                        MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        };
    }
}
