package com.debugagent.inspector;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * HTTP filter that captures request metadata into an in-memory ring buffer
 * for later inspection by RequestInspector tools.
 * <p>
 * Registered only when Spring Web MVC (DispatcherServlet) is on the classpath.
 * <p>
 * Registered at a very high precedence (runs early) to capture accurate timing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCaptureFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestCaptureFilter.class);
    private static final int MAX_REQUESTS = 500;

    public static final Deque<Map<String, Object>> recentRequests = new ConcurrentLinkedDeque<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }

        // Skip agent's own endpoints
        String path = req.getRequestURI();
        if (path != null && path.startsWith("/agent")) {
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.nanoTime();
        Throwable error = null;

        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            error = e;
            throw e;
        } finally {
            try {
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestamp", Instant.now().toString());
                entry.put("method", req.getMethod());
                entry.put("path", path);
                entry.put("query", req.getQueryString());
                entry.put("status", resp.getStatus());
                entry.put("durationMs", durationMs);
                entry.put("clientIp", req.getRemoteAddr());
                entry.put("userAgent", truncate(req.getHeader("User-Agent"), 100));
                entry.put("contentType", req.getContentType());

                // Capture key headers
                Map<String, String> respHeaders = new LinkedHashMap<>();
                for (String header : List.of("Content-Type", "Location", "X-Request-Id", "X-Rate-Limit-Remaining")) {
                    String val = resp.getHeader(header);
                    if (val != null) respHeaders.put(header, val);
                }
                if (!respHeaders.isEmpty()) entry.put("responseHeaders", respHeaders);

                if (error != null) {
                    entry.put("exception", error.getClass().getSimpleName());
                    entry.put("exceptionMessage", truncate(error.getMessage(), 200));
                }

                // Mark slow requests
                if (durationMs > 1000) {
                    entry.put("slow", true);
                }

                recentRequests.addFirst(entry);
                while (recentRequests.size() > MAX_REQUESTS) {
                    recentRequests.removeLast();
                }
            } catch (Exception e) {
                log.debug("Failed to capture request info: {}", e.getMessage());
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
