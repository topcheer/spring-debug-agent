package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Active API endpoint probing tools.
 * <p>
 * Can actively invoke the application's own REST endpoints from inside
 * the JVM (loopback HTTP) and batch-test all endpoints.
 * Conditional on Spring MVC being on the classpath.
 */
public class EndpointTestInspector implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(EndpointTestInspector.class);

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Actively call an application API endpoint via internal HTTP loopback. Shows status code, response body preview, and response time. Useful for quick API smoke tests.")
    public Map<String, Object> testEndpoint(
            @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH", required = true) String method,
            @ToolParam(description = "API path (e.g., /api/orders/1)", required = true) String path,
            @ToolParam(description = "Request body (for POST/PUT). Leave empty for GET/DELETE.") String body,
            @ToolParam(description = "Content-Type header (default application/json)") String contentType,
            @ToolParam(description = "Server port (default 8080)") Integer port
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        int targetPort = port != null ? port : 8080;
        String url = String.format("http://localhost:%d%s", targetPort, path);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            String httpMethod = method.toUpperCase();
            if (body != null && !body.isBlank()) {
                String ct = contentType != null ? contentType : "application/json";
                reqBuilder.header("Content-Type", ct);
                reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.ofString(body));
            } else {
                reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
            }

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            result.put("method", httpMethod);
            result.put("url", url);
            result.put("status", response.statusCode());
            result.put("durationMs", duration);

            // Response headers (selective)
            Map<String, String> respHeaders = new LinkedHashMap<>();
            response.headers().firstValue("Content-Type")
                    .ifPresent(v -> respHeaders.put("Content-Type", v));
            response.headers().firstValue("Content-Length")
                    .ifPresent(v -> respHeaders.put("Content-Length", v));
            if (!respHeaders.isEmpty()) {
                result.put("responseHeaders", respHeaders);
            }

            // Response body (truncated)
            String respBody = response.body();
            if (respBody != null && !respBody.isEmpty()) {
                result.put("bodyLength", respBody.length());
                result.put("body", respBody.length() > 2000
                        ? respBody.substring(0, 2000) + "... (truncated)"
                        : respBody);
            }

            result.put("success", response.statusCode() >= 200 && response.statusCode() < 300);

        } catch (Exception e) {
            result.put("method", method);
            result.put("url", url);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Batch-test all GET endpoints in the application. Calls each GET endpoint and reports status, duration, and whether it succeeded. Great for smoke testing after deployment.")
    public List<Map<String, Object>> testEndpointBatch(
            @ToolParam(description = "Filter endpoints by path prefix (e.g., /api). Leave empty for all.") String pathFilter,
            @ToolParam(description = "Server port (default 8080)") Integer port
    ) {
        List<Map<String, Object>> results = new ArrayList<>();
        int targetPort = port != null ? port : 8080;

        try {
            RequestMappingHandlerMapping mapping = ctx.getBean(
                    "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

            Map<RequestMappingInfo, HandlerMethod> handlerMethods = mapping.getHandlerMethods();
            Set<String> testedPaths = new TreeSet<>();

            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                RequestMappingInfo info = entry.getKey();

                // Check if GET is supported
                Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (!methods.isEmpty() && !methods.contains(org.springframework.web.bind.annotation.RequestMethod.GET)) {
                    continue;
                }

                // Get URL patterns
                Set<String> patterns = info.getPathPatternsCondition() != null
                        ? info.getPathPatternsCondition().getPatternValues()
                        : (info.getPatternsCondition() != null ? info.getPatternsCondition().getPatterns() : Set.of());

                for (String pattern : patterns) {
                    // Skip non-GET-testable patterns (contain variables)
                    if (pattern.contains("{") || pattern.contains("*")) {
                        results.add(Map.of(
                                "path", pattern,
                                "method", "GET",
                                "status", "skipped",
                                "reason", "Path contains variables - test manually"));
                        continue;
                    }

                    if (pathFilter != null && !pathFilter.isBlank() && !pattern.startsWith(pathFilter)) {
                        continue;
                    }

                    if (testedPaths.contains(pattern)) continue;
                    testedPaths.add(pattern);

                    // Call the endpoint
                    String url = String.format("http://localhost:%d%s", targetPort, pattern);
                    Map<String, Object> testResult = new LinkedHashMap<>();
                    testResult.put("path", pattern);
                    testResult.put("method", "GET");
                    testResult.put("url", url);

                    try {
                        HttpClient client = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .build();

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(Duration.ofSeconds(15))
                                .GET()
                                .build();

                        long startTime = System.currentTimeMillis();
                        HttpResponse<String> response = client.send(request,
                                HttpResponse.BodyHandlers.ofString());
                        long duration = System.currentTimeMillis() - startTime;

                        testResult.put("status", response.statusCode());
                        testResult.put("durationMs", duration);
                        testResult.put("success", response.statusCode() >= 200 && response.statusCode() < 300);

                        if (response.statusCode() >= 400) {
                            String body = response.body();
                            if (body != null && !body.isEmpty()) {
                                testResult.put("error", body.length() > 200
                                        ? body.substring(0, 200) + "..."
                                        : body);
                            }
                        }

                    } catch (Exception e) {
                        testResult.put("status", -1);
                        testResult.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    }

                    results.add(testResult);
                }
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to discover endpoints: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "Test an endpoint with custom authentication headers. Useful for verifying security configuration or testing as different users.")
    public Map<String, Object> testEndpointAuth(
            @ToolParam(description = "HTTP method (GET, POST, etc.)", required = true) String method,
            @ToolParam(description = "API path", required = true) String path,
            @ToolParam(description = "Auth header name (e.g., Authorization, X-API-Key)", required = true) String authHeader,
            @ToolParam(description = "Auth header value (e.g., 'Bearer eyJ...')", required = true) String authValue,
            @ToolParam(description = "Request body (optional)") String body,
            @ToolParam(description = "Server port (default 8080)") Integer port
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int targetPort = port != null ? port : 8080;
        String url = String.format("http://localhost:%d%s", targetPort, path);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header(authHeader, authValue);

            String httpMethod = method.toUpperCase();
            if (body != null && !body.isBlank()) {
                reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.ofString(body));
            } else {
                reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
            }

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            result.put("method", httpMethod);
            result.put("url", url);
            result.put("status", response.statusCode());
            result.put("durationMs", duration);
            result.put("success", response.statusCode() >= 200 && response.statusCode() < 300);

            String respBody = response.body();
            if (respBody != null && !respBody.isEmpty()) {
                result.put("body", respBody.length() > 1000
                        ? respBody.substring(0, 1000) + "... (truncated)"
                        : respBody);
            }

        } catch (Exception e) {
            result.put("url", url);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get endpoint coverage report: which API endpoints have been called vs. never called. Helps identify dead code or untested paths. Requires request tracking data.")
    public Map<String, Object> getEndpointCoverage() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Get all registered endpoints
        Set<String> allEndpoints = new TreeSet<>();
        try {
            RequestMappingHandlerMapping mapping = ctx.getBean(
                    "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                Set<String> patterns = entry.getKey().getPathPatternsCondition() != null
                        ? entry.getKey().getPathPatternsCondition().getPatternValues()
                        : (entry.getKey().getPatternsCondition() != null
                                ? entry.getKey().getPatternsCondition().getPatterns() : Set.of());
                allEndpoints.addAll(patterns);
            }
        } catch (Exception e) {
            result.put("error", "Failed to discover endpoints: " + e.getMessage());
            return result;
        }

        // Get called paths from request capture
        Set<String> calledPaths = new HashSet<>();
        try {
            for (Map<String, Object> req : RequestCaptureFilter.recentRequests) {
                String p = (String) req.get("path");
                if (p != null) calledPaths.add(p);
            }
        } catch (Exception ignored) {
        }

        // Classify endpoints
        List<Map<String, Object>> covered = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();

        for (String endpoint : allEndpoints) {
            // Check if any called path matches this endpoint
            boolean found = false;
            for (String called : calledPaths) {
                if (pathsMatch(endpoint, called)) {
                    found = true;
                    break;
                }
            }

            if (found) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("pattern", endpoint);
                covered.add(info);
            } else {
                uncovered.add(endpoint);
            }
        }

        result.put("totalEndpoints", allEndpoints.size());
        result.put("coveredCount", covered.size());
        result.put("uncoveredCount", uncovered.size());
        result.put("coverageRate", String.format("%.1f%%",
                allEndpoints.isEmpty() ? 0 : covered.size() * 100.0 / allEndpoints.size()));
        result.put("uncoveredEndpoints", uncovered);

        return result;
    }

    private boolean pathsMatch(String pattern, String actualPath) {
        // Normalize
        if (pattern.equals(actualPath)) return true;

        // Try prefix match for parameterized patterns
        String normalizedPattern = pattern.replaceAll("\\{[^}]+}", "[^/]+");
        return actualPath.matches(normalizedPattern.replace("/", "\\/"));
    }

    @DebugTool(description = "Compare responses from the same endpoint on two different ports or hosts. Useful for A/B testing, canary deployment verification, or staging vs. production comparison.")
    public Map<String, Object> compareEndpoints(
            @ToolParam(description = "HTTP method (GET, POST, etc.)", required = true) String method,
            @ToolParam(description = "API path (e.g., /api/orders)", required = true) String path,
            @ToolParam(description = "Base URL for instance A (e.g., http://localhost:8080)", required = true) String baseUrlA,
            @ToolParam(description = "Base URL for instance B (e.g., http://localhost:8090)", required = true) String baseUrlB
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            // Call instance A
            HttpRequest reqA = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrlA + path))
                    .timeout(Duration.ofSeconds(15))
                    .method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
                    .build();
            long startA = System.currentTimeMillis();
            HttpResponse<String> respA = client.send(reqA, HttpResponse.BodyHandlers.ofString());
            long durA = System.currentTimeMillis() - startA;

            // Call instance B
            HttpRequest reqB = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrlB + path))
                    .timeout(Duration.ofSeconds(15))
                    .method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
                    .build();
            long startB = System.currentTimeMillis();
            HttpResponse<String> respB = client.send(reqB, HttpResponse.BodyHandlers.ofString());
            long durB = System.currentTimeMillis() - startB;

            result.put("instanceA", Map.of(
                    "url", baseUrlA + path,
                    "status", respA.statusCode(),
                    "durationMs", durA,
                    "bodyLength", respA.body() != null ? respA.body().length() : 0
            ));

            result.put("instanceB", Map.of(
                    "url", baseUrlB + path,
                    "status", respB.statusCode(),
                    "durationMs", durB,
                    "bodyLength", respB.body() != null ? respB.body().length() : 0
            ));

            result.put("statusMatch", respA.statusCode() == respB.statusCode());

            // Simple body comparison
            String bodyA = respA.body() != null ? respA.body() : "";
            String bodyB = respB.body() != null ? respB.body() : "";
            result.put("bodyMatch", bodyA.equals(bodyB));

            if (!bodyA.equals(bodyB)) {
                result.put("bodyLengthA", bodyA.length());
                result.put("bodyLengthB", bodyB.length());
                // Find first difference
                int minLen = Math.min(bodyA.length(), bodyB.length());
                int firstDiff = -1;
                for (int i = 0; i < minLen; i++) {
                    if (bodyA.charAt(i) != bodyB.charAt(i)) {
                        firstDiff = i;
                        break;
                    }
                }
                if (firstDiff >= 0) {
                    result.put("firstDifferenceAt", firstDiff);
                    int start = Math.max(0, firstDiff - 20);
                    int endA = Math.min(bodyA.length(), firstDiff + 40);
                    int endB = Math.min(bodyB.length(), firstDiff + 40);
                    result.put("contextA", bodyA.substring(start, endA));
                    result.put("contextB", bodyB.substring(start, endB));
                }
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }
}
